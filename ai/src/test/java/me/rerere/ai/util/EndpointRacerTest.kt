package me.rerere.ai.util

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * 多地址选路的纯 JVM 测试：用 JDK 自带的 HttpServer 起本地入口，验证"先到先得 + 其余放弃"，
 * 不依赖 MockWebServer，也不碰真实网络。
 */
class EndpointRacerTest {
    private val servers = mutableListOf<HttpServer>()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        EndpointRacer.resetState()
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
        EndpointRacer.resetState()
    }

    private fun startServer(
        delayMs: Long = 0,
        status: Int = 200,
        contentType: String = "application/json",
    ): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (delayMs > 0) Thread.sleep(delayMs)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/v1"
    }

    private fun deadUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port/v1"
    }

    @Test
    fun `splitValues handles newlines commas and duplicates`() {
        val raw = """
            https://a.example.com/v1
            https://b.example.com/v1, https://c.example.com/v1   https://a.example.com/v1
        """.trimIndent()

        assertEquals(
            listOf(
                "https://a.example.com/v1",
                "https://b.example.com/v1",
                "https://c.example.com/v1",
            ),
            EndpointRacer.splitValues(raw),
        )
    }

    @Test
    fun `single value keeps its raw form`() {
        val endpoints = EndpointRacer.endpoints("https://only.example.com/v1", "sk-one")
        assertEquals(1, endpoints.size)
        assertEquals("https://only.example.com/v1", endpoints[0].baseUrl)
        assertNull(endpoints[0].apiKey)
    }

    @Test
    fun `equal counts pair each address with its own key`() {
        val endpoints = EndpointRacer.endpoints(
            "https://a.example.com/v1\nhttps://b.example.com/v1",
            "sk-a,sk-b",
        )

        assertEquals(listOf("sk-a", "sk-b"), endpoints.map { it.apiKey })
    }

    @Test
    fun `unequal counts leave keys to the key roulette`() {
        val endpoints = EndpointRacer.endpoints(
            "https://a.example.com/v1\nhttps://b.example.com/v1",
            "sk-only",
        )

        assertTrue(endpoints.all { it.apiKey == null })
    }

    @Test
    fun `firstEndpointUrl picks the first address`() {
        assertEquals(
            "https://a.example.com/v1",
            "https://a.example.com/v1, https://b.example.com/v1".firstEndpointUrl(),
        )
        assertEquals("https://only.example.com/v1", "https://only.example.com/v1".firstEndpointUrl())
    }

    @Test
    fun `trailing newline left in the editor never leaks into addresses`() {
        val raw = "https://a.example.com/v1\nhttps://b.example.com/v1\n\n"

        assertEquals(
            listOf("https://a.example.com/v1", "https://b.example.com/v1"),
            EndpointRacer.splitValues(raw),
        )
        // 单地址末尾多按了回车也要落成干净的单端点，否则它会进到请求 URL / 去重键里。
        assertEquals(
            listOf("https://only.example.com/v1"),
            EndpointRacer.endpoints("https://only.example.com/v1\n").map { it.baseUrl },
        )
        assertEquals("https://a.example.com/v1", raw.firstEndpointUrl())
    }

    @Test
    fun `fastest reachable endpoint wins and the rest is dropped`() = runBlocking {
        val slow = startServer(delayMs = 900)
        val fast = startServer()

        val ordered = EndpointRacer.orderedCandidates(client, "$slow\n$fast", "", "race")
        assertEquals(fast, ordered.first().baseUrl)
        assertEquals(2, ordered.size)
    }

    @Test
    fun `winner is reused inside the ttl window`() = runBlocking {
        val slow = startServer(delayMs = 900)
        val fast = startServer()

        EndpointRacer.orderedCandidates(client, "$slow\n$fast", "", "cache")
        val again = EndpointRacer.orderedCandidates(client, "$slow\n$fast", "", "cache")
        assertEquals(fast, again.first().baseUrl)

        EndpointRacer.invalidate("cache")
        val afterInvalidate = EndpointRacer.orderedCandidates(client, "$slow\n$fast", "", "cache")
        assertEquals(fast, afterInvalidate.first().baseUrl)
    }

    @Test
    fun `unreachable endpoint is skipped`() = runBlocking {
        val alive = startServer()

        val ordered = EndpointRacer.orderedCandidates(client, "${deadUrl()}\n$alive", "", "dead")
        assertEquals(alive, ordered.first().baseUrl)
    }

    @Test
    fun `all unreachable keeps the configured order`() = runBlocking {
        val first = deadUrl()
        val second = deadUrl()

        val ordered = EndpointRacer.orderedCandidates(client, "$first\n$second", "", "none")
        assertEquals(listOf(first, second), ordered.map { it.baseUrl })
    }

    @Test
    fun `tiers separate a real gateway from a landing page`() {
        assertEquals(EndpointRacer.TIER_TRUSTED, EndpointRacer.tierOf(200, "application/json"))
        assertEquals(EndpointRacer.TIER_TRUSTED, EndpointRacer.tierOf(200, "application/json; charset=utf-8"))
        assertEquals(EndpointRacer.TIER_TRUSTED, EndpointRacer.tierOf(200, null))
        assertEquals(EndpointRacer.TIER_TRUSTED, EndpointRacer.tierOf(401, "application/json"))
        assertEquals(EndpointRacer.TIER_TRUSTED, EndpointRacer.tierOf(429, null))
        assertEquals(EndpointRacer.TIER_SUSPICIOUS, EndpointRacer.tierOf(200, "text/html"))
        assertEquals(EndpointRacer.TIER_SUSPICIOUS, EndpointRacer.tierOf(404, "application/json"))
        assertEquals(EndpointRacer.TIER_SUSPICIOUS, EndpointRacer.tierOf(302, null))
        assertEquals(EndpointRacer.TIER_UNREACHABLE, EndpointRacer.tierOf(502, "text/html"))
        assertEquals(EndpointRacer.TIER_UNREACHABLE, EndpointRacer.tierOf(null, null))
    }

    @Test
    fun `html landing page does not beat a real api endpoint`() = runBlocking {
        // 反向代理根路径回 200 的欢迎页，比真网关的 /models 快 250ms。
        val landing = startServer(contentType = "text/html")
        val api = startServer(delayMs = 250)

        val ordered = EndpointRacer.orderedCandidates(client, "$landing\n$api", "", "landing")
        assertEquals(api, ordered.first().baseUrl)
    }

    @Test
    fun `when every endpoint is suspicious the fastest one wins`() = runBlocking {
        val slow = startServer(delayMs = 400, status = 404)
        val fast = startServer(delayMs = 20, status = 404)

        val ordered = EndpointRacer.orderedCandidates(client, "$slow\n$fast", "", "suspicious")
        assertEquals(fast, ordered.first().baseUrl)
    }

    @Test
    fun `losing probes are cancelled instead of running to their timeout`() = runBlocking {
        // 慢但健康的入口：若取消没真正生效，coroutineScope 会一直等它 3 秒。
        val slowButHealthy = startServer(delayMs = 3_000)
        val fast = startServer()

        val startedAt = System.nanoTime()
        val ordered = EndpointRacer.orderedCandidates(client, "$slowButHealthy\n$fast", "", "cancel")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(fast, ordered.first().baseUrl)
        assertTrue("race took ${elapsedMs}ms, losing probe was not cancelled", elapsedMs < 1_500)
    }

    @Test
    fun `probeAll reports every endpoint in configured order`() = runBlocking {
        val alive = startServer()
        val dead = deadUrl()

        val outcomes = EndpointRacer.probeAll(client, "$alive\n$dead")

        assertEquals(2, outcomes.size)
        assertEquals(alive, outcomes[0].baseUrl)
        assertEquals(EndpointRacer.TIER_TRUSTED, outcomes[0].tier)
        assertTrue(outcomes[0].elapsedMs >= 0)
        assertEquals(dead, outcomes[1].baseUrl)
        assertEquals(EndpointRacer.TIER_UNREACHABLE, outcomes[1].tier)
    }

    @Test
    fun `currentWinner exposes the cached winner and clears on invalidate`() = runBlocking {
        val slow = startServer(delayMs = 600)
        val fast = startServer()
        val both = "$slow\n$fast"

        assertNull(EndpointRacer.currentWinner("winner", both))
        EndpointRacer.orderedCandidates(client, both, "", "winner")
        assertEquals(fast, EndpointRacer.currentWinner("winner", both))

        EndpointRacer.invalidate("winner")
        assertNull(EndpointRacer.currentWinner("winner", both))
    }

    @Test
    fun `unreachable endpoints are pushed behind unknown ones`() {
        val endpoints = listOf(
            ProviderEndpoint("https://a.example.com/v1"),
            ProviderEndpoint("https://b.example.com/v1"),
            ProviderEndpoint("https://c.example.com/v1"),
        )

        val ordered = EndpointRacer.orderCandidates(
            endpoints = endpoints,
            winner = endpoints[1],
            unreachable = setOf("https://a.example.com/v1"),
        )

        assertEquals(
            listOf("https://b.example.com/v1", "https://c.example.com/v1", "https://a.example.com/v1"),
            ordered.map { it.baseUrl },
        )
    }

    @Test
    fun `order keeper when nothing is known`() {
        val endpoints = listOf(
            ProviderEndpoint("https://a.example.com/v1"),
            ProviderEndpoint("https://b.example.com/v1"),
        )

        assertEquals(endpoints, EndpointRacer.orderCandidates(endpoints, null))
        assertEquals(endpoints, EndpointRacer.orderCandidates(endpoints, endpoints[0]))
    }

    @Test
    fun `probe failures are classified`() = runBlocking {
        val dead = deadUrl()
        val slow = startServer(delayMs = 2_000)

        val outcomes = EndpointRacer.probeAll(client, "$dead\nnot-a-url\n$slow", timeoutMs = 300)

        assertEquals(3, outcomes.size)
        assertEquals("refused", outcomes[0].error)
        assertEquals("bad url", outcomes[1].error)
        assertEquals("timeout", outcomes[2].error)
        assertTrue(outcomes.none { it.reachable })
    }

    @Test
    fun `single endpoint never updates the winner cache`() = runBlocking {
        val only = startServer()

        assertEquals(only, EndpointRacer.currentWinner("solo", only))
        val ordered = EndpointRacer.orderedCandidates(client, only, "", "solo")
        assertEquals(listOf(only), ordered.map { it.baseUrl })
    }
}
