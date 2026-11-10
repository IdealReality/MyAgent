package me.rerere.myagent.data.ai.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

/**
 * 阻塞的 [Call.execute] 换成可取消的版本，整段（请求 + 读完 body）都在取消的覆盖范围内。
 *
 * 为什么需要：`withTimeoutOrNull { call.execute() }` 并不真正限制调用本身。超时只会取消外层
 * 协程，而阻塞的 socket 读取不是挂起点 —— 在 read 返回之前没有任何代码观察取消，于是挂住的
 * 主机会把这次工具调用（以及整轮生成、用户按下的“停止”）一直拖到 socket 超时。
 *
 * 做法：先建好 CancellableContinuation，再同步执行请求并在同一个续体里消费 body。协程一被
 * 取消，[Call.cancel] 关闭底层 socket，阻塞读抛出 IOException，线程立刻释放。已取消的续体上
 * 的 resume 会被忽略（CancellableContinuation 的语义），因此用 isActive 兜底即可。
 */
internal suspend fun <T> Call.executeCancellable(consume: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        try {
            val value = execute().use(consume)
            if (continuation.isActive) continuation.resumeWith(Result.success(value))
        } catch (t: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(t))
        }
    }
