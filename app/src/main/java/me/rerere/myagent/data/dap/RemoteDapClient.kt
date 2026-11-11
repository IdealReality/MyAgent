package me.rerere.myagent.data.dap

import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.myagent.data.ssh.FramedPeer
import me.rerere.myagent.data.ssh.FramingException
import me.rerere.myagent.data.ssh.RemoteExec
import me.rerere.myagent.data.ssh.RemoteExecChannel
import me.rerere.myagent.data.ssh.posixShellQuote
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class DapException(message: String) : Exception(message)

internal class DapTimeoutException(val command: String, val timeoutMs: Long) :
    Exception("debug adapter did not answer $command within ${timeoutMs}ms")

/** How a `continue`/step call ended. */
internal sealed interface DapRunOutcome {
    data class Stopped(val event: JsonObject) : DapRunOutcome
    data object Terminated : DapRunOutcome
    data object StillRunning : DapRunOutcome
}

/**
 * A debug adapter running on the far side of SSH, spoken to over DAP.
 *
 * The session state that matters is small and explicit: capabilities from `initialize`, the last
 * `stopped` event (thread + reason), a bounded output ring, and the breakpoint sets we have told
 * the adapter about. Everything else is a request/response round trip.
 *
 * Deadlines are mandatory for the same reason as in the LSP client, and more so: `continue` on a
 * program that never hits a breakpoint has no natural end, so the caller decides how long to
 * wait and gets "still running" — with its `threadId` — instead of a hang.
 */
internal class RemoteDapClient private constructor(
    private val session: Session,
    private val exec: RemoteExecChannel,
    val adapterName: String,
) : Closeable {

    private val peer = FramedPeer(exec.stdout, exec.stdin, "dap-$adapterName")
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val nextSeq = AtomicInteger(1)
    private val reverseRequestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "myagent-dap-$adapterName-reverse").apply { isDaemon = true }
    }

    private val eventLock = Any()
    private val events = ArrayDeque<Pair<Int, JsonObject>>()

    private val outputLock = Any()
    private val output = StringBuilder()

    private val breakpointLock = Any()
    private val breakpointsByPath = LinkedHashMap<String, MutableList<JsonObject>>()
    private var functionBreakpoints: MutableList<JsonObject> = ArrayList()

    @Volatile
    var capabilities: JsonObject = JsonObject(emptyMap())
        private set

    @Volatile
    var lastStop: JsonObject? = null
        private set

    @Volatile
    var terminated: Boolean = false
        private set

    @Volatile
    var lastExit: JsonObject? = null
        private set

    @Volatile
    private var closedReason: Throwable? = null

    private val stopEpoch = AtomicInteger(0)

    val isAlive: Boolean get() = exec.isAlive && closedReason == null

    fun stderrTail(): String = exec.stderrSnapshot(1_200)

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        peer.onMessage = ::handleMessage
        peer.onClosed = { cause ->
            val reason = cause ?: FramingException(
                "debug adapter $adapterName exited (status ${exec.exitStatus ?: -1})",
            )
            closedReason = reason
            for (key in pending.keys) pending.remove(key)?.completeExceptionally(reason)
        }
        peer.start()
    }

    /** DAP `initialize`; the response body *is* the capability set. */
    suspend fun initialize(adapterId: String, timeoutMs: Long = 20_000): JsonObject {
        val body = try {
            sendRequest(
                "initialize",
                buildJsonObject {
                    put("clientID", "myagent")
                    put("clientName", "MyAgent")
                    put("adapterID", adapterId)
                    put("pathFormat", "path")
                    put("linesStartAt1", true)
                    put("columnsStartAt1", true)
                    put("supportsRunInTerminalRequest", true)
                    put("supportsVariableType", true)
                    put("supportsVariablePaging", false)
                    put("supportsProgressReporting", false)
                    put("locale", "en-US")
                },
                timeoutMs,
            )
        } catch (t: Throwable) {
            val tail = stderrTail().trim()
            throw DapException(
                (t.message ?: "initialize failed") + if (tail.isEmpty()) "" else ": $tail",
            )
        }
        capabilities = body
        return body
    }

    /**
     * `launch` + `configurationDone` handshake.
     *
     * The wire order DAP mandates is initialize → launch → (adapter sends `initialized`) →
     * setBreakpoints → configurationDone → launch response. Sending `configurationDone` before
     * the adapter announced `initialized` is rejected by Delve, and awaiting the launch response
     * before `configurationDone` deadlocks it — both directions have a failure mode, which is
     * why this is a single method rather than three the model could call out of order.
     */
    suspend fun launch(
        program: String,
        args: List<String>,
        cwd: String?,
        stopOnEntry: Boolean,
        extra: Map<String, JsonPrimitive> = emptyMap(),
        timeoutMs: Long = 45_000,
    ): JsonObject {
        val arguments = buildJsonObject {
            put("request", "launch")
            put("program", program)
            if (cwd != null) put("cwd", cwd)
            if (args.isNotEmpty()) {
                put("args", JsonArray(args.map { JsonPrimitive(it) }))
            }
            put("stopOnEntry", stopOnEntry)
            for ((key, value) in extra) put(key, value)
        }
        return runConfigurationHandshake("launch", arguments, timeoutMs)
    }

    suspend fun attach(
        pid: Int?,
        port: Int?,
        host: String?,
        cwd: String?,
        timeoutMs: Long = 30_000,
    ): JsonObject {
        val arguments = buildJsonObject {
            put("request", "attach")
            if (cwd != null) put("cwd", cwd)
            if (pid != null) {
                put("processId", pid)
                put("pid", pid)
            }
            if (port != null) {
                put("port", port)
                put("connect", JsonObject(emptyMap()))
            }
            if (host != null) put("host", host)
        }
        return runConfigurationHandshake("attach", arguments, timeoutMs)
    }

    private suspend fun runConfigurationHandshake(
        command: String,
        arguments: JsonObject,
        timeoutMs: Long,
    ): JsonObject {
        val beforeLaunch = lastEventSeq()
        val (launchSeq, deferred) = sendRequestAsync(command, arguments)
        // Some adapters (debugpy) emit `initialized` before the launch request is read; the
        // event ring makes the ordering irrelevant.
        val initialized = awaitEvent("initialized", afterSeq = beforeLaunch, timeoutMs = 15_000)
            ?: awaitEvent("initialized", afterSeq = 0, timeoutMs = 2_000)
        if (initialized == null && closedReason != null) throw closedReason!!
        applyAllBreakpoints()
        runCatching { sendRequest("configurationDone", buildJsonObject {}, 10_000) }
        try {
            boundedAwait(command, deferred, timeoutMs)
            return deferred.getCompleted()
        } finally {
            pending.remove(launchSeq)
        }
    }

    // ---------------------------------------------------------------- requests

    private suspend fun sendRequest(command: String, args: JsonObject?, timeoutMs: Long): JsonObject {
        val (seq, deferred) = sendRequestAsync(command, args)
        try {
            boundedAwait(command, deferred, timeoutMs)
            return deferred.getCompleted()
        } finally {
            // A timed-out request must not stay in the map: the adapter may answer an hour
            // later, and routing that answer to a fresh call's deferred would be a lie.
            pending.remove(seq)
        }
    }

    private fun sendRequestAsync(command: String, args: JsonObject?): Pair<Int, CompletableDeferred<JsonObject>> {
        val seq = nextSeq.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[seq] = deferred
        val payload = buildJsonObject {
            put("seq", seq)
            put("type", "request")
            put("command", command)
            if (args != null) put("arguments", args)
        }
        try {
            peer.send(payload)
        } catch (t: Throwable) {
            pending.remove(seq)
            throw t
        }
        return seq to deferred
    }

    private suspend fun boundedAwait(
        command: String,
        deferred: CompletableDeferred<JsonObject>,
        timeoutMs: Long,
    ) {
        val body = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (body == null) throw DapTimeoutException(command, timeoutMs)
    }

    private fun respondToReverseRequest(seq: Int, command: String, body: JsonObject?, error: String?) {
        val payload = buildJsonObject {
            put("seq", nextSeq.getAndIncrement())
            put("type", "response")
            put("request_seq", seq)
            put("success", error == null)
            put("command", command)
            if (body != null) put("body", body)
            if (error != null) put("message", error)
        }
        peer.send(payload)
    }

    // ---------------------------------------------------------------- messages

    private fun handleMessage(message: JsonObject) {
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "response" -> {
                val requestSeq = message["request_seq"]?.jsonPrimitive?.intOrNull ?: return
                val deferred = pending.remove(requestSeq) ?: return
                if (message["success"]?.jsonPrimitive?.booleanOrNull == false) {
                    deferred.completeExceptionally(
                        DapException(
                            message["message"]?.jsonPrimitive?.contentOrNull
                                ?: "DAP request failed",
                        ),
                    )
                } else {
                    deferred.complete(message["body"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            }
            "event" -> handleEvent(message)
            "request" -> {
                val seq = message["seq"]?.jsonPrimitive?.intOrNull ?: return
                val command = message["command"]?.jsonPrimitive?.contentOrNull ?: return
                val args = message["arguments"]
                reverseRequestExecutor.execute {
                    runCatching { handleReverseRequest(seq, command, args) }
                }
            }
        }
    }

    private fun handleEvent(message: JsonObject) {
        val seq = message["seq"]?.jsonPrimitive?.intOrNull ?: 0
        synchronized(eventLock) {
            events.addLast(seq to message)
            while (events.size > 200) events.removeFirst()
        }
        when (message["event"]?.jsonPrimitive?.contentOrNull) {
            "stopped" -> {
                lastStop = message["body"] as? JsonObject
                stopEpoch.incrementAndGet()
            }
            "continued" -> {
                lastStop = null
            }
            "terminated" -> {
                terminated = true
                stopEpoch.incrementAndGet()
            }
            "exited" -> {
                lastExit = message["body"] as? JsonObject
                stopEpoch.incrementAndGet()
            }
            "output" -> {
                val body = message["body"] as? JsonObject ?: return
                val text = body["output"]?.jsonPrimitive?.contentOrNull ?: return
                synchronized(outputLock) {
                    output.append(text)
                    if (output.length > OUTPUT_RING_CHARS) {
                        output.delete(0, output.length - OUTPUT_RING_CHARS)
                    }
                }
            }
        }
    }

    private fun handleReverseRequest(seq: Int, command: String, args: JsonElement?) {
        when (command) {
            "runInTerminal" -> {
                val payload = args as? JsonObject
                val argv = (payload?.get("args") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                if (argv.isEmpty()) {
                    respondToReverseRequest(seq, command, null, "runInTerminal without args")
                    return
                }
                val cwd = payload?.get("cwd")?.jsonPrimitive?.contentOrNull
                val pid = startDetached(argv, cwd)
                respondToReverseRequest(seq, command, buildJsonObject { put("processId", pid) }, null)
            }
            // Child debug sessions (js-debug, some JVM adapters) need a shared TCP server that
            // this client does not implement; say so instead of pretending to support it.
            "startDebugging" -> respondToReverseRequest(
                seq, command, null, "child debug sessions are not supported by remote_debug",
            )
            else -> respondToReverseRequest(seq, command, null, "unsupported reverse request: $command")
        }
    }

    /**
     * Start a program the adapter asked us to run (usually the debuggee for `runInTerminal`).
     * Detached with all three streams redirected: an exec channel only reports EOF once every
     * holder of its stdout closes, so a plain `cmd &` would keep this request pending for the
     * entire life of the debuggee.
     */
    private fun startDetached(argv: List<String>, cwd: String?): Int {
        val commandLine = argv.joinToString(" ") { posixShellQuote(it) }
        val script = buildString {
            if (cwd != null) append("cd ").append(posixShellQuote(cwd)).append(" && ")
            append("nohup ")
            append(commandLine)
            append(" >/tmp/myagent-dap-terminal.log 2>&1 </dev/null & echo $!")
        }
        val result = RemoteExec.run(session, script, timeoutMs = 10_000)
        return result.stdout.trim().lineSequence().lastOrNull()?.toIntOrNull() ?: 0
    }

    // ---------------------------------------------------------------- state helpers

    fun outputText(): String = synchronized(outputLock) { output.toString() }

    private fun lastEventSeq(): Int = synchronized(eventLock) { events.lastOrNull()?.first ?: 0 }

    private suspend fun awaitEvent(name: String, afterSeq: Int, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val found = synchronized(eventLock) {
                events.firstOrNull {
                    it.first > afterSeq && it.second["event"]?.jsonPrimitive?.contentOrNull == name
                }?.second
            }
            if (found != null) return found
            if (System.currentTimeMillis() >= deadline) return null
            if (closedReason != null) return null
            delay(20)
        }
    }

    /** Current stop state, if any, plus its epoch — lets a caller detect a *new* stop. */
    fun stopSnapshot(): Pair<Int, JsonObject?> = stopEpoch.get() to lastStop

    // ---------------------------------------------------------------- breakpoints

    suspend fun setBreakpoint(
        path: String?,
        line: Int?,
        function: String?,
        condition: String?,
        timeoutMs: Long = 15_000,
    ): JsonObject {
        if (function != null) {
            val entry = buildJsonObject {
                put("name", function)
                if (condition != null) put("condition", condition)
            }
            synchronized(breakpointLock) {
                functionBreakpoints.removeAll { it["name"]?.jsonPrimitive?.contentOrNull == function }
                functionBreakpoints.add(entry)
            }
            val body = sendRequest(
                "setFunctionBreakpoints",
                buildJsonObject {
                    put("breakpoints", JsonArray(synchronized(breakpointLock) { functionBreakpoints.toList() }))
                },
                timeoutMs,
            )
            return body
        }
        requireNotNull(path) { "set_breakpoint needs either file+line or function" }
        val sourceLine = requireNotNull(line) { "set_breakpoint needs line with file" }
        val entry = buildJsonObject {
            put("line", sourceLine)
            if (condition != null) put("condition", condition)
        }
        val pendingLines = synchronized(breakpointLock) {
            val list = breakpointsByPath.getOrPut(path) { ArrayList() }
            list.removeAll { it["line"]?.jsonPrimitive?.intOrNull == sourceLine }
            list.add(entry)
            list.toList()
        }
        return pushBreakpoints(path, pendingLines, timeoutMs)
    }

    suspend fun removeBreakpoint(
        path: String?,
        line: Int?,
        function: String?,
        timeoutMs: Long = 15_000,
    ): JsonObject {
        if (function != null) {
            synchronized(breakpointLock) {
                functionBreakpoints.removeAll { it["name"]?.jsonPrimitive?.contentOrNull == function }
            }
            return sendRequest(
                "setFunctionBreakpoints",
                buildJsonObject {
                    put("breakpoints", JsonArray(synchronized(breakpointLock) { functionBreakpoints.toList() }))
                },
                timeoutMs,
            )
        }
        requireNotNull(path) { "remove_breakpoint needs either file+line or function" }
        val sourceLine = requireNotNull(line) { "remove_breakpoint needs line with file" }
        val remaining = synchronized(breakpointLock) {
            val list = breakpointsByPath[path] ?: ArrayList()
            list.removeAll { it["line"]?.jsonPrimitive?.intOrNull == sourceLine }
            list.toList()
        }
        return pushBreakpoints(path, remaining, timeoutMs)
    }

    private suspend fun pushBreakpoints(
        path: String,
        lines: List<JsonObject>,
        timeoutMs: Long,
    ): JsonObject = sendRequest(
        "setBreakpoints",
        buildJsonObject {
            put("source", buildJsonObject { put("path", path) })
            put("breakpoints", JsonArray(lines))
        },
        timeoutMs,
    )

    private suspend fun applyAllBreakpoints() {
        val snapshot = synchronized(breakpointLock) { breakpointsByPath.mapValues { it.value.toList() } }
        for ((path, lines) in snapshot) {
            runCatching { pushBreakpoints(path, lines, 15_000) }
        }
        val functions = synchronized(breakpointLock) { functionBreakpoints.toList() }
        if (functions.isNotEmpty()) {
            runCatching {
                sendRequest(
                    "setFunctionBreakpoints",
                    buildJsonObject { put("breakpoints", JsonArray(functions)) },
                    15_000,
                )
            }
        }
    }

    // ---------------------------------------------------------------- execution

    suspend fun threads(timeoutMs: Long = 15_000): JsonObject =
        sendRequest("threads", null, timeoutMs)

    suspend fun stackTrace(threadId: Int, levels: Int, timeoutMs: Long = 20_000): JsonObject =
        sendRequest(
            "stackTrace",
            buildJsonObject {
                put("threadId", threadId)
                put("startFrame", 0)
                put("levels", levels)
            },
            timeoutMs,
        )

    suspend fun scopes(frameId: Int, timeoutMs: Long = 15_000): JsonObject =
        sendRequest("scopes", buildJsonObject { put("frameId", frameId) }, timeoutMs)

    suspend fun variables(reference: Int, timeoutMs: Long = 20_000): JsonObject =
        sendRequest("variables", buildJsonObject { put("variablesReference", reference) }, timeoutMs)

    suspend fun evaluate(
        expression: String,
        frameId: Int?,
        context: String,
        timeoutMs: Long = 20_000,
    ): JsonObject = sendRequest(
        "evaluate",
        buildJsonObject {
            put("expression", expression)
            if (frameId != null) put("frameId", frameId)
            put("context", context)
        },
        timeoutMs,
    )

    suspend fun resume(kind: String, threadId: Int?, timeoutMs: Long): DapRunOutcome {
        val epochBefore = stopEpoch.get()
        val resolvedThread = threadId ?: currentThreadId()
        if (kind == "continue" && resolvedThread == null) {
            throw DapException("no stopped thread to continue; call stack_trace or threads first")
        }
        lastStop = null
        val args = buildJsonObject { resolvedThread?.let { put("threadId", it) } }
        sendRequest(kind, args, 20_000)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (terminated) return DapRunOutcome.Terminated
            val epoch = stopEpoch.get()
            if (epoch > epochBefore && lastStop != null) return DapRunOutcome.Stopped(lastStop!!)
            delay(30)
        }
        return if (terminated) DapRunOutcome.Terminated else DapRunOutcome.StillRunning
    }

    fun currentThreadId(): Int? =
        lastStop?.get("threadId")?.jsonPrimitive?.intOrNull

    fun currentFrameId(): Int? = lastFrameId

    /** Remembered from the most recent stack_trace so evaluate/scopes can default to it. */
    @Volatile
    private var lastFrameId: Int? = null

    fun rememberFrame(frameId: Int?) {
        if (frameId != null) lastFrameId = frameId
    }

    suspend fun customRequest(command: String, args: JsonObject?, timeoutMs: Long): JsonObject =
        sendRequest(command, args, timeoutMs)

    suspend fun disconnect(terminateDebuggee: Boolean, timeoutMs: Long = 10_000) {
        runCatching {
            sendRequest(
                "disconnect",
                buildJsonObject { put("terminateDebuggee", terminateDebuggee) },
                timeoutMs,
            )
        }
    }

    override fun close() {
        runCatching { peer.close() }
        runCatching { exec.close() }
        reverseRequestExecutor.shutdownNow()
    }

    companion object {
        private const val OUTPUT_RING_CHARS = 64 * 1024

        fun start(session: Session, cwd: String?, adapter: DapAdapterSpec): RemoteDapClient {
            val exec = RemoteExecChannel.start(session, cwd, adapter.command, connectTimeoutMs = 20_000)
            val client = RemoteDapClient(session, exec, adapter.name)
            client.start()
            return client
        }
    }
}
