package me.rerere.ai.provider.providers.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.EndpointRacer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端到端验证"多入口赛跑"在真实 provider 链路上生效：网关的三个入口里只有最快的那个收到
 * 生成请求，其余入口连生成请求都不会收到。
 */
class OpenAIProviderEndpointRaceTest {
    private val servers = mutableListOf<HttpServer>()
    private val client = OkHttpClient()

    private val fastGenerationCalls = AtomicInteger()
    private val slowGenerationCalls = AtomicInteger()

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

    private fun chatCompletionJson(content: String): String =
        """{"id":"chatcmpl-1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""

    private fun startGateway(probeDelayMs: Long, generationCalls: AtomicInteger): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            when {
                exchange.requestURI.path.endsWith("/models") -> {
                    if (probeDelayMs > 0) Thread.sleep(probeDelayMs)
                    respond(exchange, 200, """{"data":[]}""")
                }

                exchange.requestURI.path.endsWith("/chat/completions") -> {
                    generationCalls.incrementAndGet()
                    respond(exchange, 200, chatCompletionJson("from-${server.address.port}"))
                }

                else -> respond(exchange, 404, "")
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/v1"
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun deadUrl(): String {
        val port = java.net.ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port/v1"
    }

    private suspend fun generate(baseUrl: String): String {
        val provider = OpenAIProvider(client)
        val setting = ProviderSetting.OpenAI(
            name = "test",
            baseUrl = baseUrl,
            apiKey = "sk-test",
        )
        val result = provider.generateText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "test-model", displayName = "test")),
        )
        return result.message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    @Test
    fun `only the winning endpoint receives the generation request`() = runBlocking {
        val slow = startGateway(probeDelayMs = 700, generationCalls = slowGenerationCalls)
        val fast = startGateway(probeDelayMs = 0, generationCalls = fastGenerationCalls)

        val text = generate("$slow\n$fast")

        assertTrue("winner should answer", text.contains("from-"))
        assertEquals(1, fastGenerationCalls.get())
        assertEquals(0, slowGenerationCalls.get())
    }

    @Test
    fun `unreachable endpoint is dropped before the request`() = runBlocking {
        val alive = startGateway(probeDelayMs = 0, generationCalls = fastGenerationCalls)

        val text = generate("${deadUrl()}\n$alive")

        assertTrue("winner should answer", text.contains("from-"))
        assertEquals(1, fastGenerationCalls.get())
    }

    @Test
    fun `single endpoint skips probing entirely`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val probeCalls = AtomicInteger()
        server.createContext("/") { exchange ->
            when {
                exchange.requestURI.path.endsWith("/models") -> {
                    probeCalls.incrementAndGet()
                    respond(exchange, 200, """{"data":[]}""")
                }

                exchange.requestURI.path.endsWith("/chat/completions") ->
                    respond(exchange, 200, chatCompletionJson("single"))

                else -> respond(exchange, 404, "")
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        servers += server
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        val text = generate(baseUrl)

        assertTrue(text.contains("single"))
        assertEquals("a single address must not add a probe request", 0, probeCalls.get())
    }
}
