package me.rerere.myagent.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.dap.DapAdapterSpec
import me.rerere.myagent.data.dap.DapAdapters
import me.rerere.myagent.data.dap.DapRunOutcome
import me.rerere.myagent.data.dap.RemoteDapClient
import me.rerere.myagent.data.dap.RemoteDapHandle
import me.rerere.myagent.data.dap.RemoteDapRegistry
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.RemoteFileClient
import me.rerere.myagent.data.ssh.probeRemote
import me.rerere.myagent.data.ssh.remoteParent

/**
 * `remote_debug` — drive a debug adapter running on the SSH host.
 *
 * Why this exists: the agent can already run `ssh_exec "go build ./..."`, but when a build fails
 * at runtime it has no way to stop on a line, inspect a variable, or see why a goroutine is stuck.
 * DAP over an SSH exec channel gives exactly that: `dlv dap`, `python -m debugpy.adapter`,
 * `lldb-dap` and `gdb -i dap` all speak the protocol on stdin/stdout, which is the same transport
 * the LSP side already uses.
 *
 * The handshake is stateful by design:
 *   launch/attach → set_breakpoint(s) → continue/step_* → stack_trace/scopes/variables/evaluate
 *   → terminate. Every response includes the current stop state so the model does not have to
 *   remember thread ids: they are cached per session and defaulted.
 */
fun remoteDebugTool(
    context: Context,
    repo: SshHostRepository,
): Tool = Tool(
    name = "remote_debug",
    description = """
        Interactive debugging on a saved SSH host via DAP (debug adapter protocol). Starts a
        debug adapter over SSH (dlv for Go, python -m debugpy.adapter for Python, lldb-dap/gdb for
        native code) and lets you set breakpoints, run/step, and inspect the stopped program.
        Typical flow: launch(program=…, adapter optional) → set_breakpoint(file, line or function)
        → continue → stack_trace / scopes / variables / evaluate → terminate.
        Actions that resume execution return whether the program stopped (with location) or is
        still running after the timeout, so a long-running program never hangs the call.
        All paths are remote paths; `~` is expanded on the host. Requires the adapter installed
        on the host (dlv, debugpy, lldb-dap or gdb 14+).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "Saved SSH host name")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "launch | attach | set_breakpoint | remove_breakpoint | continue | step_over | step_in | step_out | pause | evaluate | stack_trace | threads | scopes | variables | output | custom_request | terminate | sessions")
                })
                put("program", buildJsonObject {
                    put("type", "string")
                    put("description", "launch: remote path to the source file/package dir/compiled binary")
                })
                put("args", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Arguments passed to the debuggee")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory on the remote host (default: the program's directory)")
                })
                put("adapter", buildJsonObject {
                    put("type", "string")
                    put("description", "Force an adapter: dlv, debugpy, lldb-dap, gdb, or a full remote command line")
                })
                put("file", buildJsonObject {
                    put("type", "string")
                    put("description", "Source file for a breakpoint (remote path)")
                })
                put("line", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line for a source breakpoint")
                })
                put("function", buildJsonObject {
                    put("type", "string")
                    put("description", "Function breakpoint name (instead of file+line)")
                })
                put("condition", buildJsonObject {
                    put("type", "string")
                    put("description", "Conditional breakpoint expression")
                })
                put("expression", buildJsonObject {
                    put("type", "string")
                    put("description", "Expression to evaluate (evaluate action)")
                })
                put("context", buildJsonObject {
                    put("type", "string")
                    put("description", "Evaluate context: repl (default), watch, hover, variables")
                })
                put("frame_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Stack frame id (defaults to the top frame from the last stack_trace)")
                })
                put("thread_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Thread id (defaults to the stopped thread)")
                })
                put("levels", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max stack frames to fetch (default 20)")
                })
                put("variable_ref", buildJsonObject {
                    put("type", "integer")
                    put("description", "Variables reference from scopes/variables (for variables action)")
                })
                put("scope_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Alias for variable_ref")
                })
                put("pid", buildJsonObject {
                    put("type", "integer")
                    put("description", "attach: remote process id")
                })
                put("port", buildJsonObject {
                    put("type", "integer")
                    put("description", "attach: remote debug port (use with debugpy)")
                })
                put("stop_on_entry", buildJsonObject {
                    put("type", "boolean")
                    put("description", "launch: stop at the entry point")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "custom_request: DAP command name")
                })
                put("payload", buildJsonObject {
                    put("type", "string")
                    put("description", "custom_request: JSON arguments object")
                })
                put("session", buildJsonObject {
                    put("type", "string")
                    put("description", "Session key (defaults to the active session; see action=sessions)")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Seconds to wait for a stop after continue/step, default 20, max 180")
                })
            },
            required = listOf("host", "action"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val host = params.text("host")
        val action = params.text("action")
        if (host == null || action == null) {
            return@Tool listOf(UIMessagePart.Text(dapError("host and action are required")))
        }
        val call = DapCall(
            host = host,
            action = action,
            program = params.text("program"),
            args = (params["args"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            cwd = params.text("cwd"),
            adapter = params.text("adapter"),
            file = params.text("file"),
            line = params["line"]?.jsonPrimitive?.intOrNull,
            function = params.text("function"),
            condition = params.text("condition"),
            expression = params.text("expression"),
            context = params.text("context") ?: "repl",
            frameId = params["frame_id"]?.jsonPrimitive?.intOrNull,
            threadId = params["thread_id"]?.jsonPrimitive?.intOrNull,
            levels = (params["levels"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200),
            variableRef = params["variable_ref"]?.jsonPrimitive?.intOrNull
                ?: params["scope_id"]?.jsonPrimitive?.intOrNull,
            pid = params["pid"]?.jsonPrimitive?.intOrNull,
            port = params["port"]?.jsonPrimitive?.intOrNull,
            stopOnEntry = params["stop_on_entry"]?.jsonPrimitive?.booleanOrNull ?: false,
            query = params.text("query"),
            payload = params.text("payload"),
            session = params.text("session"),
            timeoutSec = (params["timeout"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(5, 180),
        )
        val output = withContext(Dispatchers.IO) {
            runCatching { runRemoteDebug(context, repo, call) }
        }
        listOf(UIMessagePart.Text(output.getOrElse { dapError(it) }))
    },
)

private data class DapCall(
    val host: String,
    val action: String,
    val program: String?,
    val args: List<String>,
    val cwd: String?,
    val adapter: String?,
    val file: String?,
    val line: Int?,
    val function: String?,
    val condition: String?,
    val expression: String?,
    val context: String,
    val frameId: Int?,
    val threadId: Int?,
    val levels: Int,
    val variableRef: Int?,
    val pid: Int?,
    val port: Int?,
    val stopOnEntry: Boolean,
    val query: String?,
    val payload: String?,
    val session: String?,
    val timeoutSec: Int,
)

private val DAP_ACTIONS = setOf(
    "launch", "attach", "set_breakpoint", "remove_breakpoint", "continue", "step_over", "step_in",
    "step_out", "pause", "evaluate", "stack_trace", "threads", "scopes", "variables", "output",
    "custom_request", "terminate", "sessions",
)

private suspend fun runRemoteDebug(
    context: Context,
    repo: SshHostRepository,
    call: DapCall,
): String {
    RemoteDapRegistry.closeStale()
    val host = repo.getByName(call.host) ?: return dapError("no saved host '${call.host}'")
    if (call.action !in DAP_ACTIONS) return dapError("unknown action '${call.action}'")

    when (call.action) {
        "sessions" -> return describeSessions(host)
        "terminate" -> return terminateSession(host, call)
    }

    if (call.action == "launch" || call.action == "attach") {
        // A second launch on the same connection would leave a debuggee running with no owner.
        RemoteDapRegistry.snapshot().filter { it.hostName == host.name }.forEach { stale ->
            RemoteDapRegistry.remove(stale.key)?.close()
        }
        return startSession(context, host, call)
    }

    val handle = resolveSession(host, call)
        ?: return dapError("no live debug session for '${host.name}'; call launch or attach first (action=sessions lists them)")
    handle.lastUsedAtMs = System.currentTimeMillis()
    return try {
        executeDapAction(handle, call)
    } catch (t: Throwable) {
        if (!handle.alive) RemoteDapRegistry.remove(handle.key)?.close()
        dapError(t)
    }
}

private fun resolveSession(host: SshHostEntity, call: DapCall): RemoteDapHandle? {
    call.session?.let { key ->
        return RemoteDapRegistry.get(key)?.takeIf { it.alive }
    }
    val active = RemoteDapRegistry.active()
    if (active != null && active.alive && active.hostName == host.name) return active
    return RemoteDapRegistry.snapshot().lastOrNull { it.hostName == host.name && it.alive }
}

private suspend fun startSession(
    context: Context,
    host: SshHostEntity,
    call: DapCall,
): String {
    val session = try {
        openRemoteSession(context, host)
    } catch (t: Throwable) {
        return dapError(t)
    }
    val files = RemoteFileClient(session)
    var client: RemoteDapClient? = null
    try {
        val available = DapAdapters.parseProbeOutput(probeRemote(session, DapAdapters.probeScript()))
        val program = call.program?.let { resolveRemotePath(session, it) }
        val cwd = call.cwd?.let { resolveRemotePath(session, it) }

        if (call.action == "launch") {
            if (program == null) return dapError("launch needs `program` (remote path)")
            val adapter = DapAdapters.select(program, call.adapter, available)
                ?: return dapError(missingAdapterMessage(program, available))
            val launchExtra = adapter.launchDefaults.toMutableMap()
            if (adapter.name == "dlv") {
                val mode = when {
                    program.endsWith(".go") -> "debug"
                    files.isDirectory(program) -> "debug"
                    else -> "exec"
                }
                launchExtra["mode"] = JsonPrimitive(mode)
            }
            val workingDir = cwd ?: if (files.isDirectory(program)) program else remoteParent(program)
            val dap = RemoteDapClient.start(session, workingDir, adapter)
            client = dap
            dap.initialize(adapter.name)
            dap.launch(
                program = program,
                args = call.args,
                cwd = workingDir,
                stopOnEntry = call.stopOnEntry,
                extra = launchExtra,
                timeoutMs = 60_000,
            )
            val handle = RemoteDapHandle(
                key = dapKey(host, adapter, program),
                hostName = host.name,
                adapter = adapter,
                session = session,
                files = files,
                client = dap,
            ).also { it.target = program }
            RemoteDapRegistry.put(handle)
            return buildString {
                appendLine("launched ${adapter.name} on '${host.name}' for $program")
                append(describeSession(handle))
                append(describeStop(dap))
            }
        }

        if (call.pid == null && call.port == null) {
            return dapError("attach needs `pid` or `port`")
        }
        val hintTarget = program ?: call.adapter ?: "attach"
        val adapter = DapAdapters.select(program, call.adapter, available)
            ?: return dapError(missingAdapterMessage(hintTarget, available))
        val dap = RemoteDapClient.start(session, cwd, adapter)
        client = dap
        dap.initialize(adapter.name)
        dap.attach(
            pid = call.pid,
            port = call.port,
            host = null,
            cwd = cwd,
            timeoutMs = 45_000,
        )
        val target = "pid=${call.pid ?: "-"} port=${call.port ?: "-"}"
        val handle = RemoteDapHandle(
            key = dapKey(host, adapter, target),
            hostName = host.name,
            adapter = adapter,
            session = session,
            files = files,
            client = dap,
        ).also { it.target = target }
        RemoteDapRegistry.put(handle)
        return buildString {
            appendLine("attached ${adapter.name} on '${host.name}' to $target")
            append(describeSession(handle))
            append(describeStop(dap))
        }
    } catch (t: Throwable) {
        runCatching { client?.close() }
        runCatching { files.close() }
        runCatching { session.disconnect() }
        return dapError(t)
    }
}

private fun dapKey(host: SshHostEntity, adapter: DapAdapterSpec, target: String): String =
    "${host.name}|${adapter.name}|$target"

private fun missingAdapterMessage(program: String, available: Set<String>): String =
    "no debug adapter for '$program' found on the host. Available: " +
        available.joinToString(", ").ifEmpty { "none" } +
        ". Install one: dlv (go install github.com/go-delve/delve/cmd/dlv@latest), " +
        "debugpy (pip install debugpy), lldb-dap, or gdb 14+. " +
        "Or pass `adapter` with a full remote command line."

private suspend fun executeDapAction(handle: RemoteDapHandle, call: DapCall): String {
    val client = handle.client
    val timeoutMs = call.timeoutSec * 1000L
    val file = call.file?.let { resolveRemotePath(handle.session, it) }
    return try {
        when (call.action) {
            "set_breakpoint" -> {
                val body = client.setBreakpoint(file, call.line, call.function, call.condition)
                formatBreakpoints(body)
            }

            "remove_breakpoint" -> {
                val body = client.removeBreakpoint(file, call.line, call.function)
                formatBreakpoints(body)
            }

            "continue", "step_over", "step_in", "step_out" -> {
                val command = when (call.action) {
                    "continue" -> "continue"
                    "step_over" -> "next"
                    "step_in" -> "stepIn"
                    else -> "stepOut"
                }
                when (client.resume(command, call.threadId, timeoutMs)) {
                    is DapRunOutcome.Stopped -> describeStop(client, call.levels)
                    DapRunOutcome.Terminated -> "program terminated"
                    DapRunOutcome.StillRunning -> "still running after ${call.timeoutSec}s (no breakpoint hit yet)"
                }
            }

            "pause" -> {
                val threadId = call.threadId ?: client.currentThreadId() ?: firstThreadId(client)
                if (threadId == null) return dapError("no thread to pause")
                client.customRequest("pause", buildJsonObject { put("threadId", threadId) }, 15_000)
                delay(150)
                describeStop(client, call.levels)
            }

            "threads" -> formatThreads(client.threads(timeoutMs))

            "stack_trace" -> {
                val threadId = call.threadId ?: client.currentThreadId() ?: firstThreadId(client)
                    ?: return dapError("no stopped thread; pass thread_id or run until a breakpoint")
                val body = client.stackTrace(threadId, call.levels, timeoutMs)
                val frames = body["stackFrames"] as? JsonArray
                (frames?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
                    ?.let { client.rememberFrame(it) }
                formatFrames(body)
            }

            "scopes" -> {
                val frameId = call.frameId ?: client.currentFrameId()
                    ?: return dapError("no frame selected; call stack_trace first or pass frame_id")
                client.rememberFrame(frameId)
                formatScopes(client.scopes(frameId, timeoutMs))
            }

            "variables" -> {
                val reference = call.variableRef
                    ?: return dapError("variables needs variable_ref (from scopes or a variables response)")
                formatVariables(client.variables(reference, timeoutMs))
            }

            "evaluate" -> {
                val expression = call.expression ?: return dapError("evaluate needs `expression`")
                val frameId = call.frameId ?: client.currentFrameId()
                val body = client.evaluate(expression, frameId, call.context, timeoutMs)
                buildString {
                    append(body["result"]?.jsonPrimitive?.contentOrNull ?: "(no result)")
                    body["type"]?.jsonPrimitive?.contentOrNull?.let { append("  [").append(it).append(']') }
                }
            }

            "output" -> client.outputText().ifBlank { "no debug output captured yet" }

            "custom_request" -> {
                val command = call.query ?: return dapError("custom_request needs `query` (DAP command)")
                val args = call.payload?.let { payload ->
                    (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload) }.getOrNull()
                        as? JsonObject) ?: return dapError("payload must be a JSON object")
                }
                prettyJson(client.customRequest(command, args, timeoutMs))
            }

            else -> dapError("unsupported action '${call.action}'")
        }
    } catch (t: Throwable) {
        dapError(t)
    }
}

private suspend fun firstThreadId(client: RemoteDapClient): Int? {
    val body = runCatching { client.threads(10_000) }.getOrNull() ?: return null
    val threads = body["threads"] as? JsonArray ?: return null
    return (threads.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
}

private fun describeSessions(host: SshHostEntity): String {
    val all = RemoteDapRegistry.snapshot().filter { it.hostName == host.name }
    if (all.isEmpty()) return "no debug sessions on '${host.name}'"
    val activeKey = RemoteDapRegistry.active()?.key
    return buildString {
        appendLine("debug sessions on '${host.name}':")
        for (handle in all) {
            val idleSec = (System.currentTimeMillis() - handle.lastUsedAtMs) / 1000
            val marker = if (handle.key == activeKey) "*" else " "
            appendLine(
                "$marker ${handle.key} — ${handle.adapter.name} target=${handle.target ?: "?"} " +
                    "alive=${handle.alive} terminated=${handle.client.terminated} idle=${idleSec}s",
            )
        }
    }.trimEnd()
}

private suspend fun terminateSession(host: SshHostEntity, call: DapCall): String {
    val handle = resolveSession(host, call) ?: return "no debug session to terminate"
    RemoteDapRegistry.remove(handle.key)
    runCatching { handle.client.disconnect(terminateDebuggee = true, timeoutMs = 5_000) }
    handle.close()
    return "terminated ${handle.adapter.name} session ${handle.key}"
}

private fun describeSession(handle: RemoteDapHandle): String = buildString {
    appendLine("session: ${handle.key}")
    appendLine("adapter: ${handle.adapter.name}")
    val caps = handle.client.capabilities.keys
    if (caps.isNotEmpty()) appendLine("capabilities: " + caps.sorted().joinToString(", "))
}

private suspend fun describeStop(client: RemoteDapClient, levels: Int = 5): String {
    val event = client.lastStop ?: return if (client.terminated) "program terminated" else "status: running"
    val reason = event["reason"]?.jsonPrimitive?.contentOrNull ?: "stopped"
    val threadId = event["threadId"]?.jsonPrimitive?.intOrNull
    val builder = StringBuilder()
    builder.appendLine("stopped: reason=$reason thread=${threadId ?: "?"}")
    if (threadId != null) {
        val body = runCatching { client.stackTrace(threadId, levels, 15_000) }.getOrNull()
        val frames = body?.get("stackFrames") as? JsonArray
        if (!frames.isNullOrEmpty()) {
            val top = frames.firstOrNull() as? JsonObject
            top?.get("id")?.jsonPrimitive?.intOrNull?.let { client.rememberFrame(it) }
            builder.append(formatFrames(body))
        }
    }
    return builder.toString().trimEnd()
}

private fun formatFrames(body: JsonObject): String {
    val frames = body["stackFrames"] as? JsonArray ?: return "no stack frames"
    if (frames.isEmpty()) return "no stack frames"
    val out = StringBuilder()
    for ((index, element) in frames.withIndex()) {
        if (index >= 40) {
            out.append("… more frames not shown\n")
            break
        }
        val frame = element as? JsonObject ?: continue
        val id = frame["id"]?.jsonPrimitive?.intOrNull ?: -1
        val name = frame["name"]?.jsonPrimitive?.contentOrNull ?: "?"
        val source = frame["source"] as? JsonObject
        val path = source?.get("path")?.jsonPrimitive?.contentOrNull
            ?: source?.get("name")?.jsonPrimitive?.contentOrNull
        val line = (frame["line"]?.jsonPrimitive?.intOrNull ?: 0)
        val column = (frame["column"]?.jsonPrimitive?.intOrNull ?: 0)
        out.append("[$id] $name")
        if (path != null) out.append(" @ $path:$line:$column")
        else if (line > 0) out.append(" @ line $line")
        out.append('\n')
    }
    return out.toString().trimEnd()
}

private fun formatBreakpoints(body: JsonObject): String {
    val breakpoints = body["breakpoints"] as? JsonArray ?: return "no breakpoints"
    if (breakpoints.isEmpty()) return "0 breakpoints (all removed for this target)"
    val out = StringBuilder("${breakpoints.size} breakpoint(s):\n")
    breakpoints.forEach { element ->
        val bp = element as? JsonObject ?: return@forEach
        val verified = bp["verified"]?.jsonPrimitive?.booleanOrNull == true
        val line = bp["line"]?.jsonPrimitive?.intOrNull
        val message = bp["message"]?.jsonPrimitive?.contentOrNull
        out.append(if (verified) "verified" else "PENDING")
        if (line != null) out.append(" line ").append(line)
        if (!message.isNullOrBlank()) out.append(" — ").append(message)
        out.append('\n')
    }
    return out.toString().trimEnd()
}

private fun formatThreads(body: JsonObject): String {
    val threads = body["threads"] as? JsonArray ?: return "no threads"
    if (threads.isEmpty()) return "no threads"
    val out = StringBuilder("${threads.size} thread(s):\n")
    threads.forEach { element ->
        val thread = element as? JsonObject ?: return@forEach
        out.append(thread["id"]?.jsonPrimitive?.intOrNull)
            .append(": ")
            .append(thread["name"]?.jsonPrimitive?.contentOrNull ?: "?")
            .append('\n')
    }
    return out.toString().trimEnd()
}

private fun formatScopes(body: JsonObject): String {
    val scopes = body["scopes"] as? JsonArray ?: return "no scopes"
    if (scopes.isEmpty()) return "no scopes"
    val out = StringBuilder("${scopes.size} scope(s):\n")
    scopes.forEach { element ->
        val scope = element as? JsonObject ?: return@forEach
        out.append(scope["name"]?.jsonPrimitive?.contentOrNull ?: "?")
            .append("  variablesReference=")
            .append(scope["variablesReference"]?.jsonPrimitive?.intOrNull)
            .append('\n')
    }
    return out.toString().trimEnd() +
        "\n(use variables with variable_ref to read a scope's contents)"
}

private fun formatVariables(body: JsonObject): String {
    val variables = body["variables"] as? JsonArray ?: return "no variables"
    if (variables.isEmpty()) return "no variables"
    val out = StringBuilder("${variables.size} variable(s):\n")
    variables.forEach { element ->
        val variable = element as? JsonObject ?: return@forEach
        val name = variable["name"]?.jsonPrimitive?.contentOrNull ?: "?"
        val value = variable["value"]?.jsonPrimitive?.contentOrNull ?: ""
        val type = variable["type"]?.jsonPrimitive?.contentOrNull
        val ref = variable["variablesReference"]?.jsonPrimitive?.intOrNull
        out.append(name)
        if (!type.isNullOrBlank()) out.append(": ").append(type)
        out.append(" = ").append(value)
        if (ref != null && ref > 0) out.append("  (ref=").append(ref).append(')')
        out.append('\n')
    }
    return out.toString().trimEnd()
}

private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }

private fun prettyJson(element: JsonElement): String =
    PRETTY_JSON.encodeToString(JsonElement.serializer(), element)

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun dapError(message: String): String = buildJsonObject {
    put("error", message)
    put("tool", "remote_debug")
}.toString()

private fun dapError(t: Throwable): String = buildJsonObject {
    put("error", t.message ?: t.javaClass.simpleName)
    put("error_type", t.javaClass.simpleName)
    put("tool", "remote_debug")
}.toString()
