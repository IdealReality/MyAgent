package me.rerere.myagent.data.ai.net

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：`withTimeoutOrNull` 必须真的能中断阻塞的 HTTP 调用。
 *
 * 没有 [executeCancellable] 时，`withTimeoutOrNull { call.execute() }` 只是取消了外层协程，
 * 阻塞读不观察取消 —— 超时形同虚设，一次挂住的 web_fetch 会把整轮生成和用户的“停止”
 * 一起拖到 socket 超时。
 */
class CancellableHttpTest {

    @Test
    fun `timeout actually bounds a blocked response read`() {
        // 本地服务：故意拖 3 秒才响应，模拟“连上了但一直不回”的主机。
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            runCatching {
                Thread.sleep(3_000)
                val body = "late".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            val started = System.currentTimeMillis()
            val result = runBlocking {
                withTimeoutOrNull(300L) {
                    client.newCall(
                        Request.Builder().url("http://127.0.0.1:${server.address.port}/slow").build()
                    ).executeCancellable { response -> response.body.string() }
                }
            }
            val elapsed = System.currentTimeMillis() - started

            assertNull("the 300ms timeout must cut the call", result)
            assertTrue(
                "cancellation must release the read immediately, took ${elapsed}ms",
                elapsed < 1_500L,
            )
        } finally {
            server.stop(0)
        }
    }
}
