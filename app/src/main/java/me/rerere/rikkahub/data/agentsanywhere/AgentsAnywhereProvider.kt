package me.rerere.rikkahub.data.agentsanywhere

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AA 侧一次请求失败的原因，带 HTTP 码与截断后的响应体，便于在聊天里直接读懂。
 */
class AgentsAnywhereException(
    message: String,
    val code: Int? = null,
) : Exception(message)

/**
 * Agents Anywhere 作为 **provider**：远端 agent 直接成为一个「模型」。
 *
 * ## 为什么不是工具
 *
 * 之前的形态是给本地模型三个 `agents_anywhere_*` 工具，本地模型调用它们、拿到远端答复、
 * 再自己转述一遍给用户。那是「本地助手 + 中间转述」，不是让本地直接和其他 agent 对话：
 * 多一轮本地模型开销、远端的流式输出被压成一次性文本、远端的 reasoning 与工具调用全部丢失。
 *
 * ## 映射
 *
 * 一次 `streamText` = 一轮远端 agent 对话：
 *
 * - **会话映射**：AA 的会话 id 由客户端持有。这里按「本地 conversation id → 远端 session id」
 *   在进程内缓存，并在 `session` 参数上做保底——缓存丢失时会话 id 本身编码在
 *   [REMOTE_SESSION_PREFIX] 前缀里，可以从消息历史里恢复。
 * - **增量**：AA 没有 SSE，real-time 只能轮询 `timeline?mode=changes&afterSeq=`。timeline
 *   条目是**累积全文 + revision 递增**，所以每条 assistant/reasoning 条目按「已发送长度」
 *   裁出新尾巴，作为 `TextDelta` / `ReasoningDelta` 推出去。
 * - **工具**：远端 `type=tool` 的条目映射成 [UIMessagePart.ServerTool]（server tool 的语义就是
 *   「由服务端（远端 agent）执行的工具」），不占用本地工具循环。
 * - **权限**：建会话时把 `selections.permission` 设成配置的档位（默认 `full-auto`），同时仍在
 *   轮询中兜底自动应答 notices——否则远端换成 `ask-all` 的部署会让这一轮永远卡住。
 */
class AgentsAnywhereProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val sessions: AgentsAnywhereSessionStore,
) : Provider<ProviderSetting.AgentsAnywhere> {

    override suspend fun listModels(providerSetting: ProviderSetting.AgentsAnywhere): List<Model> =
        withContext(Dispatchers.IO) {
            fetchCatalog(providerSetting) ?: providerSetting.models
        }

    /**
     * 拉远端模型目录；失败返回 null（而不是空表），让设置页能把「没连上」和「目录是空的」
     * 区分开——`listModels` 的契约要求返回可用模型，所以它在失败时才退回已存的旧列表。
     */
    suspend fun fetchCatalog(
        providerSetting: ProviderSetting.AgentsAnywhere,
    ): List<Model>? = withContext(Dispatchers.IO) {
        val origin = providerSetting.normalizedOrigin()
        if (origin.isEmpty() || providerSetting.connectorId.isBlank() ||
            providerSetting.runtimeId.isBlank()
        ) {
            return@withContext null
        }
        val url = "$origin/api/v2/connectors/${providerSetting.connectorId}" +
            "/runtimes/${providerSetting.runtimeId}/catalogs/model"
        val root = runCatching { getJson(providerSetting, url) }.getOrNull()
            ?: return@withContext null
        val catalog = root["catalog"]?.jsonObject ?: return@withContext null
        val items = catalog["models"]?.jsonArray ?: return@withContext null
        items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val selectionId = item["selectionId"]?.jsonPrimitive?.contentOrNull
                ?: item["id"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            Model(
                // modelId 就是 AA 的 model selectionId：建会话时原样回填。
                modelId = selectionId,
                displayName = item["displayName"]?.jsonPrimitive?.contentOrNull ?: selectionId,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                // 远端 agent 自己跑工具循环，本地不再叠加工具面；标 TOOL 让 UI 把小扳手点亮。
                abilities = listOf(ModelAbility.TOOL),
            )
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.AgentsAnywhere,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val parts = mutableListOf<UIMessagePart>()
        var text = ""
        collectTurn(providerSetting, messages, params) { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text += chunk.text
                is StreamChunk.ReasoningDelta -> parts += UIMessagePart.Reasoning(reasoning = chunk.text)
                is StreamChunk.ServerToolEnd -> parts += UIMessagePart.ServerTool(
                    toolCallId = chunk.id,
                    toolName = "",
                    input = chunk.input,
                    output = chunk.output,
                    status = chunk.status,
                )
                else -> Unit
            }
        }
        if (text.isNotEmpty()) parts += UIMessagePart.Text(text)
        return TextGenerationResult(
            id = params.sessionId.orEmpty(),
            model = params.model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.AgentsAnywhere,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        collectTurn(providerSetting, messages, params) { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("Agents Anywhere 不支持生成图片")

    // ---------------------------------------------------------------------------------------

    /**
     * 跑一轮：必要时先创建远端会话，再轮询 timeline 把增量喂给 [emit]。
     *
     * 结束条件是 runtime 回到 idle/error，而不是「timeline 里出现 assistant 条目」——
     * assistant 条目在流式过程中就已经在 timeline 里（status=running，text 不断变长），
     * 用它判结束会截断输出。
     */
    private suspend fun collectTurn(
        setting: ProviderSetting.AgentsAnywhere,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        emit: suspend (StreamChunk) -> Unit,
    ) {
        val prompt = messages.lastUserPrompt()
        if (prompt.isBlank()) error("Agents Anywhere: 本轮没有用户输入")
        if (!setting.isReady()) {
            error(
                "Agents Anywhere 未配置完整：请在 设置 → 服务商 → ${setting.name} 填服务器地址与 " +
                    "connector / project / runtime"
            )
        }

        val conversationKey = params.sessionId.orEmpty()
        val knownSession = sessions.remoteSessionId(conversationKey)
        val started = System.currentTimeMillis()
        val deadline = started + setting.turnTimeoutMillis.coerceAtLeast(60_000L)

        // 续聊必须先取「发送前的 seq 基线」再发消息。AA 的 timeline 是整段会话的历史，
        // `mode=changes&afterSeq=0` 会把上一轮的 assistant / reasoning / tool 条目全部重放一遍，
        // 直接表现为「这一轮的回复里混进了上一轮的文本」。以发送前的游标为起点，只有本轮新增
        // 或本轮被更新的条目会被读到。新建会话没有历史，基线自然是 0。
        val sessionId: String
        var baselineSeq = 0L
        if (knownSession != null) {
            baselineSeq = currentSeq(setting, knownSession)
            sendMessage(setting, knownSession, prompt)
            sessionId = knownSession
        } else {
            sessionId = createSession(setting, params.model.modelId, prompt)
                .also { sessions.remember(conversationKey, it) }
        }

        val seenText = HashMap<String, Int>()      // timeline item id -> 已发送字符数
        val seenReasoning = HashMap<String, Int>()
        val closedTools = HashSet<String>()
        val openedText = HashSet<String>()
        val openedReasoning = HashSet<String>()
        var afterSeq = baselineSeq
        var nextSeq = baselineSeq
        var sawRunning = false
        var failure: String? = null

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val page = pollTimeline(setting, sessionId, afterSeq)
            afterSeq = page.nextSeq
            nextSeq = page.nextSeq

            for (item in page.items) {
                val id = item.id
                when (item.kind) {
                    TimelineKind.ASSISTANT -> {
                        val full = item.text
                        val already = seenText[id] ?: 0
                        if (full.length > already) {
                            if (id !in openedText) {
                                openedText += id
                                emit(StreamChunk.TextStart(id))
                            }
                            emit(StreamChunk.TextDelta(id, full.substring(already)))
                            seenText[id] = full.length
                        }
                        if (item.finished && id in openedText) {
                            openedText -= id
                            emit(StreamChunk.TextEnd(id))
                        }
                    }

                    TimelineKind.REASONING -> {
                        val full = item.text
                        val already = seenReasoning[id] ?: 0
                        if (full.length > already) {
                            if (id !in openedReasoning) {
                                openedReasoning += id
                                emit(StreamChunk.ReasoningStart(id))
                            }
                            emit(StreamChunk.ReasoningDelta(id, full.substring(already)))
                            seenReasoning[id] = full.length
                        }
                        if (item.finished && id in openedReasoning) {
                            openedReasoning -= id
                            emit(StreamChunk.ReasoningEnd(id))
                        }
                    }

                    TimelineKind.TOOL -> {
                        if (id in closedTools) continue
                        val status = when (item.status) {
                            "running", "pending", "waiting_approval" -> ServerToolStatus.IN_PROGRESS
                            "failed", "cancelled", "interrupted" -> ServerToolStatus.FAILED
                            else -> ServerToolStatus.COMPLETED
                        }
                        emit(
                            StreamChunk.ServerToolEnd(
                                id = id,
                                input = item.toolInput,
                                output = item.toolOutput,
                                status = status,
                                metadata = buildJsonObject {
                                    item.title?.let { put("title", it) }
                                    put("source", "agents_anywhere")
                                },
                            )
                        )
                        if (status != ServerToolStatus.IN_PROGRESS) closedTools += id
                    }

                    TimelineKind.USER, TimelineKind.OTHER -> Unit
                }
            }

            val state = runtimeState(setting, sessionId)
            val status = state["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status == "running" || status == "waiting_approval" || status == "pending" ||
                status == "waiting" || status == "stopping"
            ) {
                sawRunning = true
            }
            if (status == "waiting_approval") approveFirstNotice(setting, sessionId)
            if (status == "error") {
                failure = state["error"]?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                break
            }
            // 首轮刚建会话时状态可能还没翻到 running；只有在看到过一次 running，或已经拿到
            // 任何内容之后，idle 才算「这一轮结束」，否则会在第一个 poll 就提前退出。
            val produced = seenText.isNotEmpty() || seenReasoning.isNotEmpty()
            if (status == "idle" && (sawRunning || produced)) break
            if (status.isEmpty() && produced) break
            delay(POLL_INTERVAL_MILLIS)
        }

        for (id in openedText) emit(StreamChunk.TextEnd(id))
        for (id in openedReasoning) emit(StreamChunk.ReasoningEnd(id))

        failure?.let { error("远端 agent 报错：$it") }
        if (seenText.isEmpty() && seenReasoning.isEmpty() && closedTools.isEmpty()) {
            error(
                "远端 agent 在 ${(System.currentTimeMillis() - started) / 1000}s 内没有产出。" +
                    "检查 connector 是否 online、runtime 是否 active" +
                    if (nextSeq > 0) "（已读到 seq $nextSeq）" else ""
            )
        }
        emit(StreamChunk.Finish(finishReason = "stop", model = params.model.modelId))
    }

    // ---- 会话管理 -------------------------------------------------------------------------

    private suspend fun createSession(
        setting: ProviderSetting.AgentsAnywhere,
        modelSelectionId: String,
        prompt: String,
    ): String {
        val selections = buildJsonObject {
            if (modelSelectionId.isNotBlank()) put("model", modelSelectionId)
            val permission = setting.permissionSelectionId.trim()
            if (permission.isNotEmpty()) put("permission", permission)
        }
        val body = buildJsonObject {
            put("connectorId", setting.connectorId.trim())
            put("projectId", setting.projectId.trim())
            put("runtime", RUNTIME_ACP)
            put("runtimeId", setting.runtimeId.trim())
            put("content", prompt)
            val cwd = setting.cwd.trim()
            if (cwd.isNotEmpty()) put("cwd", cwd)
            if (selections.isNotEmpty()) put("selections", selections)
        }
        val response = postJson(setting, "/api/v2/sessions/create-and-start", body)
        return response["session"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw AgentsAnywhereException("create-and-start 未返回 session id")
    }

    private suspend fun sendMessage(
        setting: ProviderSetting.AgentsAnywhere,
        sessionId: String,
        prompt: String,
    ) {
        postJson(
            setting,
            "/api/v2/sessions/$sessionId/runtime/messages",
            buildJsonObject { put("content", prompt) },
        )
    }

    // ---- timeline 轮询 ---------------------------------------------------------------------

    internal class TimelineEntry(
        val id: String,
        val kind: TimelineKind,
        val text: String,
        val status: String,
        val finished: Boolean,
        val toolInput: kotlinx.serialization.json.JsonElement?,
        val toolOutput: kotlinx.serialization.json.JsonElement?,
        val title: String?,
    )

    internal enum class TimelineKind { ASSISTANT, REASONING, TOOL, USER, OTHER }

    private class TimelinePage(val items: List<TimelineEntry>, val nextSeq: Long)

    private suspend fun pollTimeline(
        setting: ProviderSetting.AgentsAnywhere,
        sessionId: String,
        afterSeq: Long,
    ): TimelinePage {
        val url = "${setting.normalizedOrigin()}/api/v2/sessions/$sessionId/timeline" +
            "?mode=changes&afterSeq=$afterSeq&limit=200"
        val root = getJson(setting, url)
        val rawNext = root["nextSeq"]?.jsonPrimitive?.longOrNull ?: afterSeq
        val items = root["items"]?.jsonArray ?: return TimelinePage(emptyList(), afterSeq)
        val parsed = items.mapNotNull { parseTimelineItem(it) }
        return TimelinePage(parsed, maxOf(rawNext, afterSeq))
    }

    /**
     * 会话当前的 timeline 游标（`nextSeq`）。
     *
     * 只为了把「发送前就已存在的历史条目」排除在本轮增量之外，所以 `limit=1` 让返回体最小。
     */
    private suspend fun currentSeq(
        setting: ProviderSetting.AgentsAnywhere,
        sessionId: String,
    ): Long {
        val root = runCatching {
            getJson(
                setting,
                "${setting.normalizedOrigin()}/api/v2/sessions/$sessionId/timeline" +
                    "?mode=latest&limit=1",
            )
        }.getOrNull() ?: return 0L
        return root["nextSeq"]?.jsonPrimitive?.longOrNull ?: 0L
    }


    private suspend fun runtimeState(
        setting: ProviderSetting.AgentsAnywhere,
        sessionId: String,
    ): JsonObject {
        val root = getJson(
            setting,
            "${setting.normalizedOrigin()}/api/v2/sessions/$sessionId/runtime/state",
        )
        return root["state"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** 兜底：远端弹审批时选第一个 allow 语义的动作，避免这一轮永远挂在 waiting_approval。 */
    private suspend fun approveFirstNotice(
        setting: ProviderSetting.AgentsAnywhere,
        sessionId: String,
    ) {
        val root = runCatching {
            getJson(setting, "${setting.normalizedOrigin()}/api/v2/sessions/$sessionId/runtime/notices")
        }.getOrNull() ?: return
        val notices = root["notices"]?.jsonArray ?: root["items"]?.jsonArray ?: return
        for (notice in notices) {
            val obj = notice as? JsonObject ?: continue
            val noticeId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val actions = obj["actions"]?.jsonArray ?: continue
            val action = actions.firstOrNull {
                val actionId = (it as? JsonObject)?.get("actionId")
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                actionId.startsWith("allow")
            } ?: actions.firstOrNull()
            val actionId = (action as? JsonObject)?.get("actionId")
                ?.jsonPrimitive?.contentOrNull ?: continue
            runCatching {
                postJson(
                    setting,
                    "/api/v2/sessions/$sessionId/runtime/notices/$noticeId/respond",
                    buildJsonObject { put("actionId", actionId) },
                )
            }
            return
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------

    private suspend fun getJson(
        setting: ProviderSetting.AgentsAnywhere,
        url: String,
    ): JsonObject = execute(setting, Request.Builder().url(url).get())

    private suspend fun postJson(
        setting: ProviderSetting.AgentsAnywhere,
        path: String,
        body: JsonObject,
    ): JsonObject = execute(
        setting,
        Request.Builder()
            .url("${setting.normalizedOrigin()}$path")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA)),
    )

    private suspend fun execute(
        setting: ProviderSetting.AgentsAnywhere,
        builder: Request.Builder,
    ): JsonObject {
        val token = setting.accessToken.trim()
        if (token.isNotEmpty()) builder.header("authorization", "Bearer $token")
        val response = client.newCall(builder.build()).await()
        val raw = response.body.string()
        if (!response.isSuccessful) {
            val hint = when (response.code) {
                401, 403 -> "token 无效或过期，重新登录 AA 后更新 access token"
                404 -> "会话/项目不存在（可能已在服务端归档）"
                422 -> "参数不合法：$raw"
                else -> raw.take(400)
            }
            throw AgentsAnywhereException("Agents Anywhere ${response.code}: $hint", response.code)
        }
        return runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw AgentsAnywhereException("响应不是 JSON：${raw.take(200)}") }
    }

    companion object {
        private const val TAG = "AgentsAnywhereProvider"
        private const val RUNTIME_ACP = "acp"
        private const val POLL_INTERVAL_MILLIS = 700L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 把一条 AA timeline 条目解析成 [TimelineEntry]。
         *
         * 抽成纯函数是为了可单测：条目形状（`type` + `role` + `content.kind` 三者共同决定语义、
         * 累积全文与 revision 语义、tool 条目把命令塞在 `content.command`）都是实测出来的惯例，
         * 出错会直接表现为「聊天里句子断掉或重复」，必须有回归。
         */
        internal fun parseTimelineItem(element: kotlinx.serialization.json.JsonElement): TimelineEntry? {
            val obj = element as? JsonObject ?: return null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val role = obj["role"]?.jsonPrimitive?.contentOrNull
            val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = obj["content"]?.jsonObject
            val kind = when {
                type == "message" && role == "assistant" -> TimelineKind.ASSISTANT
                type == "message" && role == "user" -> TimelineKind.USER
                type == "system" &&
                    content?.get("kind")?.jsonPrimitive?.contentOrNull == "reasoning" -> TimelineKind.REASONING
                type == "tool" -> TimelineKind.TOOL
                else -> TimelineKind.OTHER
            }
            return TimelineEntry(
                id = id,
                kind = kind,
                text = content?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty(),
                status = status,
                finished = obj["completedAt"]?.jsonPrimitive?.contentOrNull != null ||
                    status == "done" || status == "failed" || status == "cancelled" ||
                    status == "interrupted",
                toolInput = content?.let { c ->
                    c["input"] ?: buildJsonObject {
                        c["command"]?.let { put("command", it) }
                        c["title"]?.let { put("title", it) }
                    }.takeIf { it.isNotEmpty() }
                },
                toolOutput = content?.let { c -> c["output"] ?: c["text"] ?: c["result"] },
                title = content?.get("title")?.jsonPrimitive?.contentOrNull,
            )
        }

        /**
         * 把一条消息数组折叠成「本轮要发给远端的 prompt」。
         *
         * 只取最后一条 user 文本：远端 agent 自己维护上下文（真正多轮靠 AA 的同一个 session），
         * 本地历史不需要重放。系统提示词与历史消息都不发——远端 agent 用的是它自己的系统提示，
         * 本地再叠一层只会让远端的两套指令打架。
         */
        internal fun List<UIMessage>.lastUserPrompt(): String =
            asReversed()
                .firstOrNull { it.role == MessageRole.USER }
                ?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                ?.trim()
                .orEmpty()
    }
}
