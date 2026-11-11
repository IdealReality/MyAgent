package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.util.json

/**
 * 一个自建 / 聚合网关（new-api、agent2api、one-api 等）在同一个 base URL 下同时暴露
 * `chat/completions`、`images/generations`、`videos` 三套表面。`/models` 只回模型 id，
 * 不带类型，于是所有模型都被当成 CHAT：生图/生视频选择器里看不到它们，用户只能"直走 chat"。
 *
 * 这个文件做两件事，都不发网络请求：
 *  1. 从模型 id 推断它属于哪套表面（[inferMediaModelKind]）；
 *  2. 构造 / 解析 OpenAI 官方的异步视频协议（`POST /videos` → `GET /videos/{id}` →
 *     `GET /videos/{id}/content`），new-api 的 `task_plugin_protocol` 路由就是这套。
 */
enum class MediaModelKind { CHAT, IMAGE, VIDEO }

/** 明确的视频模型词根。先于图片判断，因为 `wan2.2-t2v` 同时含图片系的 `wan`。 */
private val VIDEO_MARKERS = listOf(
    "sora",
    "veo-", "veo_", "veo2", "veo3",
    "kling",
    "seedance",
    "hailuo",
    "cogvideo",
    "hunyuanvideo", "hunyuan-video",
    "pika",
    "runway",
    "luma-", "luma_",
    "vidu",
    "mochi",
    "ltx-video", "ltxv",
    "genmo",
    "t2v", "i2v", "kf2v",
    "-video", "_video", "/video",
    "text2video", "image2video", "video-generation",
)

/** 明确的图片生成模型词根。 */
private val IMAGE_MARKERS = listOf(
    "gpt-image",
    "dall-e", "dalle",
    "flux",
    "stable-diffusion", "stable_diffusion", "sdxl", "sd3", "sd-3", "sd-turbo",
    "seedream",
    "imagen",
    "qwen-image",
    "wanx",
    "midjourney", "mj_",
    "recraft",
    "ideogram",
    "kolors",
    "hidream",
    "grok-image", "grok-2-image", "grok2-image",
    "nano-banana",
    "jimeng",
    "z-image",
    "playground-v",
    "kandinsky",
    "janus",
    "image-gen", "imagegen",
    "-image", "_image",
)

/**
 * 从模型 id 推断媒体能力。只认**明确**的媒体词根：未知 id 一律 CHAT，
 * 避免把普通对话模型误塞进生图/生视频选择器。
 *
 * `-image` / `_image` 这类通配后缀放在最后，因为 `gpt-4o-image`、`gemini-3-pro-image`
 * 之类的确会命中；而普通聊天模型几乎不会把 image 放进 id。
 */
fun inferMediaModelKind(modelId: String): MediaModelKind {
    val id = modelId.lowercase().trim()
    if (id.isEmpty()) return MediaModelKind.CHAT
    if (VIDEO_MARKERS.any { id.contains(it) }) return MediaModelKind.VIDEO
    if (IMAGE_MARKERS.any { id.contains(it) }) return MediaModelKind.IMAGE
    return MediaModelKind.CHAT
}

/**
 * `/models` 里的一条记录 → 带类型的 [Model]。
 *
 * 图片模型的输出模态是 `[TEXT, IMAGE]`，视频模型是 `[TEXT, VIDEO]`；这正是
 * `Model.matchesPickerType` / 生图、生视频选择器用来筛选的字段，所以两处都设，
 * 不依赖用户再手动改一次类型。
 */
fun gatewayModelFromId(modelId: String): Model = when (inferMediaModelKind(modelId)) {
    MediaModelKind.IMAGE -> Model(
        modelId = modelId,
        displayName = modelId,
        type = ModelType.IMAGE,
        outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )

    MediaModelKind.VIDEO -> Model(
        modelId = modelId,
        displayName = modelId,
        type = ModelType.VIDEO,
        outputModalities = listOf(Modality.TEXT, Modality.VIDEO),
    )

    MediaModelKind.CHAT -> Model(modelId = modelId, displayName = modelId)
}

/**
 * 把一条已存模型升级到推断出的媒体类型。
 *
 * 只升不降：CHAT → IMAGE / VIDEO 会被改写，已经是 IMAGE / VIDEO 的原样返回，
 * 这样用户手动改过的类型不会被一次刷新反向覆盖。
 */
fun Model.upgradedMediaKind(): Model = when (inferMediaModelKind(modelId)) {
    MediaModelKind.IMAGE -> if (type == ModelType.IMAGE) this else copy(
        type = ModelType.IMAGE,
        outputModalities = (outputModalities + Modality.IMAGE).distinct(),
    )

    MediaModelKind.VIDEO -> if (type == ModelType.VIDEO) this else copy(
        type = ModelType.VIDEO,
        outputModalities = (outputModalities + Modality.VIDEO).distinct(),
    )

    MediaModelKind.CHAT -> this
}

// —— OpenAI 异步视频协议（Sora / new-api task plugin 共用） ——

const val OPENAI_VIDEO_SUBMIT_PATH = "/videos"

/** 与厂商无关的轮询预算：100 次 × 6 s ≈ 10 分钟，和 SiliconFlow/MiniMax 分支一致。 */
const val OPENAI_VIDEO_POLL_ATTEMPTS = 100
const val OPENAI_VIDEO_POLL_INTERVAL_MS = 6_000L

fun openAiVideoStatusPath(taskId: String): String = "/videos/${encodePathSegment(taskId)}"

fun openAiVideoContentPath(taskId: String): String = "${openAiVideoStatusPath(taskId)}/content"

private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * 视频提交体的两种方言。
 *
 * - [GATEWAY]：new-api / agent2api 的 `dto.VideoRequest`（`duration`、`width`/`height`、`image`）。
 * - [OPENAI]：OpenAI 官方 Sora（`seconds` 字符串、`size`），对多余字段会报未知参数。
 *
 * 先发 [GATEWAY]，被 400 拒绝且错误指向未知参数时再发 [OPENAI]；两边的字段都是可选，
 * 所以这个重试不会产生第二次计费（第一次请求在参数校验阶段就被拒了）。
 */
enum class VideoSubmitFlavor { GATEWAY, OPENAI }

fun buildGatewayVideoCreateBody(
    modelId: String,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    firstFrameDataUri: String?,
    flavor: VideoSubmitFlavor,
): JsonObject = buildJsonObject {
    put("model", modelId)
    put("prompt", prompt)
    val duration = durationSeconds?.coerceAtLeast(1)
    when (flavor) {
        VideoSubmitFlavor.GATEWAY -> {
            if (duration != null) put("duration", duration)
            val size = gatewayVideoSize(aspectRatio)
            if (size != null) put("size", size)
            firstFrameDataUri?.let { put("image", it) }
        }

        VideoSubmitFlavor.OPENAI -> {
            if (duration != null) put("seconds", duration.toString())
            val size = gatewayVideoSize(aspectRatio)
            if (size != null) put("size", size)
        }
    }
}

/** 只表达比例，不表达分辨率档位：网关自己按模型挑合法档位，写死 720p 会在只支持 1080p 的模型上被拒。 */
private fun gatewayVideoSize(aspectRatio: ImageAspectRatio): String? = when (aspectRatio) {
    ImageAspectRatio.SQUARE -> "1024x1024"
    ImageAspectRatio.LANDSCAPE -> "1280x720"
    ImageAspectRatio.PORTRAIT -> "720x1280"
}

/**
 * 400 是否在说"我不认识这个字段"，而不是"这个请求本身不合法"。
 *
 * OpenAI 官方：`Unknown parameter: 'duration'.` / `Unrecognized request argument`；
 * 各种兼容网关：`additional properties are not allowed` / `extra fields not permitted`。
 */
fun isUnknownParameterRejection(statusCode: Int, body: String): Boolean {
    if (statusCode != 400 && statusCode != 422) return false
    val lower = body.lowercase()
    return lower.contains("unknown parameter") ||
        lower.contains("unrecognized") ||
        lower.contains("additional propert") ||
        lower.contains("extra field") ||
        lower.contains("extra inputs") ||
        lower.contains("not permitted") ||
        lower.contains("invalid field") ||
        lower.contains("unexpected field")
}

/** 一段任务响应：提交（`id`/`task_id`）、查询（同样字段）、失败（`error`）。 */
data class VideoTaskEnvelope(
    val id: String?,
    val status: String?,
    /** 网关直接给出成品 URL（xai 风格、部分聚合网关）。OpenAI 官方没有这个字段。 */
    val url: String?,
    val error: String?,
)

/**
 * 解析视频任务响应。容忍两类 id（`id` / `task_id`）和三类 URL 位置
 * （顶层 `url` / `video_url` / `output.url`），因为这套协议在聚合网关里已被各写各的。
 */
fun parseVideoTaskEnvelope(bodyStr: String): VideoTaskEnvelope {
    val root = runCatching { json.parseToJsonElement(bodyStr).jsonObject }
        .getOrElse { return VideoTaskEnvelope(null, null, null, bodyStr.take(300).ifBlank { "unparseable response" }) }

    val id = root.stringOrNull("id") ?: root.stringOrNull("task_id")
    val status = root.stringOrNull("status") ?: root.stringOrNull("state")
    val url = root.stringOrNull("url")
        ?: root.stringOrNull("video_url")
        ?: (root["output"] as? JsonObject)?.stringOrNull("url")
        ?: ((root["output"] as? JsonObject)?.get("videos") as? JsonArray)
            ?.firstOrNull()?.let { item ->
                (item as? JsonObject)?.stringOrNull("url")
            }
        ?: (root["data"] as? JsonObject)?.stringOrNull("url")
    val error = (root["error"] as? JsonObject)?.stringOrNull("message")
        ?: (root["error"] as? JsonPrimitive)?.contentOrNull
        ?: (root["fail_reason"] as? JsonPrimitive)?.contentOrNull

    return VideoTaskEnvelope(id = id, status = status, url = url, error = error)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * 非 2xx 是否意味着「这个网关没有 `/images/generations` 这条路径」，该改走
 * `chat/completions` 的 `modalities:["image","text"]` 生图。
 *
 * 聚合网关（new-api 等）对不存在的路径回 404 + JSON `{"error":{...}}`，而 OpenRouter 对
 * 「模型不存在」也是 404 + JSON —— 两者只能靠 body 区分：body 明确提到模型找不到时，
 * 换条路径同样会失败，必须把真实原因抛给用户。
 */
fun shouldFallbackToChatImageEndpoint(statusCode: Int, body: String): Boolean {
    if (statusCode == 405) return true
    if (statusCode != 404) return false
    val lower = body.lowercase()
    val looksLikeMissingModel = lower.contains("model") &&
        (lower.contains("not found") || lower.contains("not exist") ||
            lower.contains("unknown") || lower.contains("invalid") || lower.contains("no such"))
    return !looksLikeMissingModel
}
