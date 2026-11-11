package me.rerere.ai.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一个供应商条目里的多个接入地址（同一个网关的公网 / 局域网 / tailnet 入口）。
 *
 * 语法与 [KeyRoulette] 的多密钥一致：`baseUrl`（和 `apiKey`）用空格 / 换行 / 逗号分隔，
 * 单值仍是原来的路径，不需要迁移旧数据。
 *
 * 选路是**并发赛跑**而不是顺序兜底：每次真正发请求前，对所有地址同时发一个轻量探测，
 * 第一个可信入口胜出，其余探测立刻取消（连底层连接一起 cancel）；之后整条请求只用胜者。
 * 胜者缓存 [WINNER_TTL_MS]，期间不再重复探测；真实请求失败会清缓存，下次重新赛跑。
 *
 * `apiKey` 的个数与地址个数相同（且 > 1）时按索引配对（第 N 个地址用第 N 个 key），
 * 否则 key 继续交给 [KeyRoulette] 轮换——适合"一把钥匙配多个入口"。
 */
data class ProviderEndpoint(
    val baseUrl: String,
    /** 与地址按索引配对的 key；null 表示交给 KeyRoulette 轮换。 */
    val apiKey: String? = null,
)

/** 单次探测的结果。UI 直接用它渲染"哪条入口通、多快"。 */
data class ProbeOutcome(
    val baseUrl: String,
    /** [EndpointRacer.TIER_TRUSTED] / [EndpointRacer.TIER_SUSPICIOUS] / [EndpointRacer.TIER_UNREACHABLE] */
    val tier: Int,
    val statusCode: Int? = null,
    val elapsedMs: Long = 0,
    val error: String? = null,
) {
    val reachable: Boolean get() = tier != EndpointRacer.TIER_UNREACHABLE
}

object EndpointRacer {
    private const val TAG = "EndpointRacer"

    /** 单个入口的探测预算。局域网 / tailnet 断网时最多拖这么久，且与其他入口并行，不叠加。 */
    const val PROBE_TIMEOUT_MS = 2_500L

    /**
     * 收到"可疑响应"（404、反代默认页）后，再多等这么久的可信入口。
     *
     * 只影响"有地址没挂 API"的配置；全部入口都正常时第一个可信响应立即胜出，不会多等。
     */
    const val PROBE_GRACE_MS = 800L

    /** 胜者复用时长。太短会每次请求都探测，太长则网络切换后迟迟换不回来。 */
    const val WINNER_TTL_MS = 60_000L

    /** 2xx 且返回 API 载荷，或网关自己在报错（401/403/…）：这条路径确实连到了 API。 */
    const val TIER_TRUSTED = 0

    /** 路径通但不像 API（404、CF 错误页、反代欢迎页）：能用，但优先级低于可信入口。 */
    const val TIER_SUSPICIOUS = 1

    /** 连接失败、超时、5xx：这条路径现在不可用。 */
    const val TIER_UNREACHABLE = 2

    private val GATEWAY_SPEAKING_CODES = setOf(400, 401, 403, 405, 406, 422, 429)

    private data class Winner(val baseUrl: String, val chosenAt: Long)

    private val winners = ConcurrentHashMap<String, Winner>()

    private val SPLIT_REGEX = "[\\s,]+".toRegex()

    fun splitValues(raw: String): List<String> = raw
        .split(SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    /**
     * 解析出全部端点，保持填写顺序。
     *
     * 空字符串返回一个 `baseUrl` 为空的端点，让调用方沿用原来的报错（"baseUrl empty"），
     * 而不是在这里抛一个用户看不懂的异常。
     */
    fun endpoints(baseUrl: String, apiKey: String = ""): List<ProviderEndpoint> {
        val urls = splitValues(baseUrl)
        if (urls.isEmpty()) return listOf(ProviderEndpoint(baseUrl, null))
        val keys = splitValues(apiKey)
        val paired = keys.size == urls.size && urls.size > 1
        return urls.mapIndexed { index, url ->
            ProviderEndpoint(baseUrl = url, apiKey = if (paired) keys[index] else null)
        }
    }

    /**
     * 本次请求的候选顺序：缓存里的胜者（或探测最先到达的可信入口）在前，其余按原顺序兜底。
     *
     * 只有一个地址时不探测——那等于原来每次多打一次网络。
     */
    suspend fun orderedCandidates(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String = "",
        scopeId: String = "",
    ): List<ProviderEndpoint> {
        val all = endpoints(baseUrl, apiKey)
        if (all.size <= 1) return all

        val cacheKey = stateKey(scopeId, all)
        val cached = winners[cacheKey]
        if (cached != null) {
            val fresh = System.currentTimeMillis() - cached.chosenAt < WINNER_TTL_MS
            val index = all.indexOfFirst { it.baseUrl == cached.baseUrl }
            if (fresh && index >= 0) return listOf(all[index]) + all.filterIndexed { i, _ -> i != index }
            winners.remove(cacheKey)
        }

        val ordered = race(client, all)
        ordered.firstOrNull()?.let {
            winners[cacheKey] = Winner(it.baseUrl, System.currentTimeMillis())
            Log.i(TAG, "raced ${all.size} endpoints for $scopeId, winner=${it.baseUrl}")
        }
        return ordered
    }

    /**
     * 配置页用：并发探测全部地址，等齐后按**填写顺序**返回每条的状态（不取消任何一条，
     * 因为用户要看的是完整体检结果）。
     */
    suspend fun probeAll(
        client: OkHttpClient,
        baseUrl: String,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): List<ProbeOutcome> {
        val all = endpoints(baseUrl)
        if (all.isEmpty()) return emptyList()
        Log.d(TAG, "probing ${all.size} endpoints for the config page")
        val probeClient = probeClient(client, timeoutMs)
        return coroutineScope {
            all.map { endpoint -> async(Dispatchers.IO) { probe(probeClient, endpoint) } }.awaitAll()
        }
    }

    /** 当前缓存里对这条 baseUrl 生效的地址；没有缓存（或已过期）返回 null。 */
    fun currentWinner(scopeId: String, baseUrl: String): String? {
        val all = endpoints(baseUrl)
        if (all.size <= 1) return all.firstOrNull()?.baseUrl
        val cached = winners[stateKey(scopeId, all)] ?: return null
        if (System.currentTimeMillis() - cached.chosenAt >= WINNER_TTL_MS) return null
        return cached.baseUrl
    }

    /** 真实请求失败后调用：丢掉缓存，下一次重新赛跑。 */
    fun invalidate(scopeId: String) {
        if (scopeId.isEmpty()) {
            winners.clear()
            return
        }
        winners.keys.removeIf { it.startsWith("$scopeId|") }
    }

    /** 测试用。 */
    fun resetState() {
        winners.clear()
    }

    /**
     * 探测分级：只看"这条网络路径是不是真的通到某个 API 服务"。
     *
     * - 2xx + JSON/纯文本载荷：真网关。
     * - 2xx + HTML：多半是反代 / CF 的默认页，路径没挂到 API，降级为可疑。
     * - 400/401/403/405/406/422/429：网关自己在报错，说明它活着，可信。
     * - 404 及其它 3xx/4xx：路径不对，可疑。
     * - 5xx / 连接失败 / 超时：不可达。
     */
    fun tierOf(statusCode: Int?, contentType: String?): Int = when {
        statusCode == null -> TIER_UNREACHABLE
        statusCode >= 500 -> TIER_UNREACHABLE
        statusCode in 200..299 -> if (looksLikeApiPayload(contentType)) TIER_TRUSTED else TIER_SUSPICIOUS
        statusCode in GATEWAY_SPEAKING_CODES -> TIER_TRUSTED
        else -> TIER_SUSPICIOUS
    }

    private fun looksLikeApiPayload(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return "json" in type || type == "text/plain"
    }

    private fun probeClient(client: OkHttpClient, timeoutMs: Long): OkHttpClient = client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        // 探测要的是"当下通不通"，重试只会把预算翻倍。
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 并发探测全部地址：第一个**可信**入口立即胜出，其余探测连底层 call 一起取消。
     *
     * 若先到的是可疑入口（404 / 反代默认页），不立刻收工：给可信入口一个
     * [PROBE_GRACE_MS] 的宽限期，避免被"主机在线但没挂 API"的地址骗走。
     */
    private suspend fun race(
        client: OkHttpClient,
        endpoints: List<ProviderEndpoint>,
    ): List<ProviderEndpoint> = coroutineScope {
        val probeClient = probeClient(client, PROBE_TIMEOUT_MS)
        val pending = endpoints
            .map { endpoint -> endpoint to async(Dispatchers.IO) { probe(probeClient, endpoint) } }
            .toMutableList()

        var best: ProbeOutcome? = null
        var bestEndpoint: ProviderEndpoint? = null
        var graceDeadline = 0L
        val unreachable = mutableSetOf<String>()

        while (pending.isNotEmpty()) {
            val taken = select<Pair<ProviderEndpoint, ProbeOutcome>?> {
                pending.forEach { (candidate, deferred) ->
                    deferred.onAwait { outcome -> candidate to outcome }
                }
                if (bestEndpoint != null) {
                    onTimeout((graceDeadline - System.currentTimeMillis()).coerceAtLeast(1L)) { null }
                }
            } ?: break

            val (endpoint, outcome) = taken
            pending.removeAll { (candidate, _) -> candidate == endpoint }

            if (outcome.tier == TIER_TRUSTED) {
                // 先到先得：其余探测直接放弃，不再等它们的超时。
                pending.forEach { (_, deferred) -> deferred.cancel() }
                bestEndpoint = endpoint
                break
            }

            if (outcome.tier == TIER_SUSPICIOUS) {
                val current = best
                if (current == null || outcome.elapsedMs < current.elapsedMs) {
                    best = outcome
                    bestEndpoint = endpoint
                    graceDeadline = System.currentTimeMillis() + PROBE_GRACE_MS
                }
            } else {
                // 明确拒连 / 超时的入口：留到最后再试，别让失败切换一头撞上去。
                unreachable += endpoint.baseUrl
            }
        }

        orderCandidates(endpoints, bestEndpoint, unreachable)
    }

    /**
     * 候选顺序：胜者第一，其余**按用户填写的顺序**兜底，但已经把这次探测确认不可达的
     * 入口挪到最后——真实请求失败切换时不会再撞一遍死路。
     */
    fun orderCandidates(
        endpoints: List<ProviderEndpoint>,
        winner: ProviderEndpoint?,
        unreachable: Set<String> = emptySet(),
    ): List<ProviderEndpoint> = listOfNotNull(winner) + endpoints
        .filter { it != winner }
        .sortedBy { if (it.baseUrl in unreachable) 1 else 0 }

    /**
     * 探测的是 `<baseUrl>/models`，不是裸 baseUrl：CF / 反代对未知路径可能挂起或直接返回
     * 默认页，而 /models 是这三种协议都真实挂载的端点，能真正反映"这条网络路径通不通到
     * 这个网关"。返回码本身不判定成败，交给 [tierOf] 分级。
     */
    private suspend fun probe(client: OkHttpClient, endpoint: ProviderEndpoint): ProbeOutcome {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000

        val base = endpoint.baseUrl.trimEnd('/')
        if (base.isBlank()) {
            return ProbeOutcome(endpoint.baseUrl, TIER_UNREACHABLE, error = "empty url")
        }
        return try {
            val call = client.newCall(Request.Builder().url("$base/models").get().build())
            val (code, contentType) = call.awaitStatus()
            ProbeOutcome(
                baseUrl = endpoint.baseUrl,
                tier = tierOf(code, contentType),
                statusCode = code,
                elapsedMs = elapsedMs(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ProbeOutcome(
                baseUrl = endpoint.baseUrl,
                tier = TIER_UNREACHABLE,
                elapsedMs = elapsedMs(),
                error = failureOf(e),
            )
        }
    }

    /** 把探测异常压成一个人能读懂的原因，配置页直接显示。 */
    private fun failureOf(e: Throwable): String = when (e) {
        is InterruptedIOException -> "timeout"
        is UnknownHostException -> "dns"
        is ConnectException -> "refused"
        is SSLException -> "tls"
        is IllegalArgumentException -> "bad url"
        else -> e.javaClass.simpleName
    }

    /**
     * 阻塞式 `execute()` 在协程被取消后不会自己断开连接——必须 enqueue + `invokeOnCancellation`
     * 才能让"其余立即放弃"名副其实。
     */
    private suspend fun Call.awaitStatus(): Pair<Int, String?> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { it.code to it.header("Content-Type") }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    private fun stateKey(scopeId: String, endpoints: List<ProviderEndpoint>): String =
        "$scopeId|${endpoints.joinToString(",") { it.baseUrl }}"
}

/** `baseUrl` 里的第一个地址。给只能接受单个 host 的代码（图标、host 特判）用。 */
fun String.firstEndpointUrl(): String =
    EndpointRacer.splitValues(this).firstOrNull() ?: this

/** 非流式调用：按候选顺序尝试，全部失败时抛出最后一次的异常。 */
suspend fun <T> withEndpointFailover(
    candidates: List<ProviderEndpoint>,
    scopeId: String = "",
    block: suspend (ProviderEndpoint) -> T,
): T {
    var lastFailure: Throwable? = null
    for (endpoint in candidates) {
        try {
            return block(endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            EndpointRacer.invalidate(scopeId)
            lastFailure = e
        }
    }
    throw lastFailure ?: IllegalStateException("No endpoint configured")
}

/**
 * 流式调用：只有在**还没有产出任何元素**时才会换下一个地址。
 *
 * 已经吐出一段内容后再重放会把同一段话生成两遍（而且用户已经看见了），所以那种情况原样抛出，
 * 交给上层 GenerationLoop 的"是否允许重试"策略判断。
 */
fun <T> flowWithEndpointFailover(
    candidates: List<ProviderEndpoint>,
    scopeId: String = "",
    block: suspend (ProviderEndpoint) -> Flow<T>,
): Flow<T> = flow {
    var lastFailure: Throwable? = null
    for (endpoint in candidates) {
        var emitted = false
        try {
            block(endpoint).collect { item ->
                emitted = true
                emit(item)
            }
            return@flow
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            EndpointRacer.invalidate(scopeId)
            lastFailure = e
            if (emitted) throw e
        }
    }
    throw lastFailure ?: IllegalStateException("No endpoint configured")
}

// —— 各 provider 的便捷包装：赛跑出胜者后，把地址落回一个只含单地址的 setting。 ——

suspend fun ProviderSetting.OpenAI.endpointCandidates(client: OkHttpClient): List<ProviderEndpoint> =
    EndpointRacer.orderedCandidates(client, baseUrl, apiKey, id.toString())

fun ProviderSetting.OpenAI.atEndpoint(endpoint: ProviderEndpoint): ProviderSetting.OpenAI =
    copy(baseUrl = endpoint.baseUrl, apiKey = endpoint.apiKey ?: apiKey)

suspend fun ProviderSetting.Claude.endpointCandidates(client: OkHttpClient): List<ProviderEndpoint> =
    EndpointRacer.orderedCandidates(client, baseUrl, apiKey, id.toString())

fun ProviderSetting.Claude.atEndpoint(endpoint: ProviderEndpoint): ProviderSetting.Claude =
    copy(baseUrl = endpoint.baseUrl, apiKey = endpoint.apiKey ?: apiKey)

suspend fun ProviderSetting.Google.endpointCandidates(client: OkHttpClient): List<ProviderEndpoint> =
    EndpointRacer.orderedCandidates(client, baseUrl, apiKey, id.toString())

fun ProviderSetting.Google.atEndpoint(endpoint: ProviderEndpoint): ProviderSetting.Google =
    copy(baseUrl = endpoint.baseUrl, apiKey = endpoint.apiKey ?: apiKey)
