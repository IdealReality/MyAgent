package me.rerere.myagent.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.lsp.LspEdits
import me.rerere.myagent.data.lsp.LspPosition
import me.rerere.myagent.data.lsp.LspRange
import me.rerere.myagent.data.lsp.LspServerSpec
import me.rerere.myagent.data.lsp.LspSpecs
import me.rerere.myagent.data.lsp.RemoteLspClient
import me.rerere.myagent.data.lsp.RemoteLspHandle
import me.rerere.myagent.data.lsp.RemoteLspRegistry
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.RemoteExec
import me.rerere.myagent.data.ssh.RemoteFileClient
import me.rerere.myagent.data.ssh.locateRemoteRoot
import me.rerere.myagent.data.ssh.probeRemote

/**
 * `remote_lsp` — code intelligence against a project that lives on another machine.
 *
 * The phone has no `clangd`, no `gopls`, no `rust-analyzer`, and installing any of them on Android
 * is not a real option. But the code this agent actually edits lives on the user's servers, and
 * `ssh_exec` + `grep` can only answer text questions. A language server, spawned over SSH and
 * spoken to over stdio, answers the semantic ones: real diagnostics, definition, references, safe
 * renames that touch every callsite, and code actions with the compiler's own knowledge.
 *
 * Design notes that are not obvious from the schema:
 *  - One server per (host, project root, server). Sessions outlive a single call because
 *    clangd/gopls take seconds to index; reaping happens on idle, not per call.
 *  - `file` is a path on the **remote** host, not on the phone. `~` is expanded remotely.
 *  - Rename/code-action edits are written back over SFTP, so a rename is atomic per file
 *    (temp file + rename) and every touched callsite is reported.
 *  - `apply: false` previews a rename without writing anything.
 */
fun remoteLspTool(
    context: Context,
    repo: SshHostRepository,
): Tool = Tool(
    name = "remote_lsp",
    description = """
        Semantic code intelligence on a saved SSH host: the same operations an editor's language
        server provides, executed on the remote machine where the project actually lives.
        Use this INSTEAD of grep/read when you need real diagnostics, the definition or all
        references of a symbol, hover types, document/workspace symbols, a project-wide rename, or
        code actions (quick fixes, import fixes, organize-imports). Text search cannot follow
        shadowing, re-exports or across-file call sites; this can.
        The server is started over SSH on first use (clangd/gopls/rust-analyzer/pyright/… are
        auto-detected on the remote PATH and in node_modules/.bin, .venv/bin, bin/); it then stays
        warm for 15 minutes of idle time. Every path (file, root) is a path ON THE REMOTE HOST.
        rename and code_actions write to the remote filesystem through SFTP — pass apply=false to
        preview a rename. Requires a saved host (save_ssh_host) and the language server installed
        remotely; if nothing is found, the error lists the candidates to install.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "Saved SSH host name (from save_ssh_host / list_ssh_hosts)")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "diagnostics | definition | type_definition | implementation | references | hover | symbols | rename | code_actions | status | reload | capabilities | request")
                })
                put("file", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote absolute path of the file ('*' = workspace symbols / all sessions). '~/…' is expanded on the host.")
                })
                put("root", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote project root. Omit to auto-detect from marker files (go.mod, Cargo.toml, compile_commands.json, .git, …) near `file`.")
                })
                put("line", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line for position actions. Default 1.")
                })
                put("symbol", buildJsonObject {
                    put("type", "string")
                    put("description", "Identifier on that line whose column to use (required for definition/references/rename when line is given); `name#2` picks the second occurrence.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace symbol query (symbols with file='*'), code-action selector/index when apply=true, kind filter when apply=false, or the LSP method for action=request.")
                })
                put("new_name", buildJsonObject {
                    put("type", "string")
                    put("description", "New name for action=rename")
                })
                put("apply", buildJsonObject {
                    put("type", "boolean")
                    put("description", "rename: apply (default true) or preview with false. code_actions: list (default) or apply with true.")
                })
                put("server", buildJsonObject {
                    put("type", "string")
                    put("description", "Force a specific server: a known name (clangd, gopls, …) or a full remote command line")
                })
                put("payload", buildJsonObject {
                    put("type", "string")
                    put("description", "JSON params for action=request (overrides auto-built params)")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Seconds, default 30, max 180")
                })
            },
            required = listOf("host", "action"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val host = params.stringOrNull("host")
        val action = params.stringOrNull("action")
        if (host == null || action == null) {
            return@Tool listOf(UIMessagePart.Text(errorText("host and action are required")))
        }
        val call = LspCall(
            host = host,
            action = action,
            root = params.stringOrNull("root"),
            file = params.stringOrNull("file"),
            line = params.get("line")?.jsonPrimitive?.intOrNull,
            symbol = params.stringOrNull("symbol"),
            query = params.stringOrNull("query"),
            newName = params.stringOrNull("new_name"),
            apply = params.get("apply")?.jsonPrimitive?.booleanOrNull,
            server = params.stringOrNull("server"),
            payload = params.stringOrNull("payload"),
            timeoutSec = (params.get("timeout")?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 180),
        )
        val output = withContext(Dispatchers.IO) {
            runCatching { runRemoteLsp(context, repo, call) }
        }
        listOf(UIMessagePart.Text(output.getOrElse { errorText(it) }))
    },
)

private data class LspCall(
    val host: String,
    val action: String,
    val root: String?,
    val file: String?,
    val line: Int?,
    val symbol: String?,
    val query: String?,
    val newName: String?,
    val apply: Boolean?,
    val server: String?,
    val payload: String?,
    val timeoutSec: Int,
)

private val POSITION_ACTIONS = setOf(
    "definition", "type_definition", "implementation", "references", "hover", "rename", "code_actions",
)

private suspend fun runRemoteLsp(
    context: Context,
    repo: SshHostRepository,
    call: LspCall,
): String {
    RemoteLspRegistry.closeStale()
    val host = repo.getByName(call.host)
        ?: return errorText("no saved host '${call.host}' — call save_ssh_host first or list_ssh_hosts")

    when (call.action) {
        "status" -> return describeStatus(host)
        "reload" -> return reloadSessions(host, call)
    }
    if (call.action !in POSITION_ACTIONS && call.action !in setOf("diagnostics", "symbols", "capabilities", "request")) {
        return errorText("unknown action '${call.action}'")
    }

    val fileHint = call.file?.takeUnless { it == "*" }
    val candidates = if (call.server != null) {
        listOfNotNull(explicitServerSpec(call.server))
    } else {
        LspSpecs.candidatesFor(fileHint ?: call.root ?: "")
    }

    // Warm path: the same server for this host/root is already running. `root` may be given as a
    // `~/…` path while stored handles hold the expanded absolute path, so expand it once through
    // any live session for this host before comparing.
    val handles = RemoteLspRegistry.snapshot()
    val requestedRoot = call.root?.let { raw ->
        if (raw.startsWith("~")) {
            handles.firstOrNull { it.hostName == host.name }
                ?.let { sessionHandle -> runCatching { resolveRemotePath(sessionHandle.session, raw) }.getOrNull() }
                ?: raw
        } else {
            raw
        }
    }
    val live = handles.firstOrNull { handle ->
        handle.hostName == host.name && handle.alive &&
            (requestedRoot == null || handle.rootPath == requestedRoot) &&
            (candidates.isEmpty() || candidates.any { it.name == handle.spec.name })
    }
    if (live != null) {
        live.lastUsedAtMs = System.currentTimeMillis()
        return try {
            executeLspAction(live, call)
        } catch (t: Throwable) {
            // A server that died mid-call must not be handed out again as "warm".
            if (!live.alive) RemoteLspRegistry.remove(live.key)?.close()
            errorText(t)
        }
    }

    if (fileHint == null && call.root == null) {
        return errorText("pass `file` (concrete remote path) or `root` so a project can be identified")
    }

    val session = try {
        openRemoteSession(context, host)
    } catch (t: Throwable) {
        return errorText(t)
    }
    var files: RemoteFileClient? = null
    var client: RemoteLspClient? = null
    try {
        files = RemoteFileClient(session)
        val filePath = fileHint?.let { resolveRemotePath(session, it) }
        val explicitRoot = call.root?.let { resolveRemotePath(session, it) }
        val markers = candidates.flatMap { it.rootMarkers }.distinct().ifEmpty { listOf(".git") }
        val rootPath = explicitRoot ?: locateRemoteRoot(files, filePath!!, markers)

        val probePool = if (candidates.isEmpty()) LspSpecs.builtIn else candidates
        val probe = LspSpecs.parseProbeOutput(probeRemote(session, LspSpecs.probeScript(rootPath, probePool)))
        val selection = selectServer(call.server, candidates, probe)
            ?: return errorText(missingServerMessage(host, probePool, probe, fileHint))

        client = RemoteLspClient(session, rootPath, selection.first, selection.second, files)
        client.initialize()
        val handle = RemoteLspHandle(
            key = RemoteLspRegistry.key(host.name, rootPath, selection.first),
            hostName = host.name,
            rootPath = rootPath,
            spec = selection.first,
            resolvedCommand = selection.second,
            session = session,
            files = files,
            client = client,
        )
        RemoteLspRegistry.put(handle)
        return executeLspAction(handle, call)
    } catch (t: Throwable) {
        runCatching { client?.close() }
        runCatching { files?.close() }
        runCatching { session.disconnect() }
        return errorText(t)
    }
}

private fun explicitServerSpec(raw: String): LspServerSpec? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return LspSpecs.builtIn.firstOrNull { it.name == value || it.command == value }
        ?: LspServerSpec(name = value, command = value, fileTypes = emptyList(), rootMarkers = emptyList())
}

private fun selectServer(
    explicit: String?,
    candidates: List<LspServerSpec>,
    probe: Map<String, String>,
): Pair<LspServerSpec, String>? {
    if (explicit != null) {
        val spec = explicitServerSpec(explicit) ?: return null
        return spec to (probe[spec.command] ?: spec.command)
    }
    for (spec in candidates) {
        probe[spec.command]?.let { return spec to it }
    }
    return null
}

private fun missingServerMessage(
    host: SshHostEntity,
    pool: List<LspServerSpec>,
    probe: Map<String, String>,
    file: String?,
): String {
    val wanted = pool.joinToString(", ") { it.name }
    val found = probe.keys.joinToString(", ").ifEmpty { "none" }
    return "no language server for ${file ?: "this project"} is installed on '${host.name}'. " +
        "Tried: $wanted. Found on PATH: $found. " +
        "Install one on the host (e.g. `pacman -S clang`, `go install golang.org/x/tools/gopls@latest`, " +
        "`pip install pyright`) or pass `server` with a full remote command line."
}

private fun describeStatus(host: SshHostEntity): String = buildString {
    appendLine("host: ${host.name} — ${host.user}@${host.host}:${host.port}")
    val live = RemoteLspRegistry.snapshot().filter { it.hostName == host.name }
    if (live.isEmpty()) {
        appendLine("language servers: none started for this host")
        appendLine("known servers: " + LspSpecs.builtIn.joinToString(", ") { it.name })
    } else {
        appendLine("language servers:")
        for (handle in live) {
            val idleSec = (System.currentTimeMillis() - handle.lastUsedAtMs) / 1000
            appendLine(
                "- ${handle.spec.name} @ ${handle.rootPath} [${handle.resolvedCommand}] " +
                    "alive=${handle.alive} idle=${idleSec}s",
            )
        }
    }
}.trimEnd()

private fun reloadSessions(host: SshHostEntity, call: LspCall): String {
    val matching = RemoteLspRegistry.snapshot().filter { handle ->
        handle.hostName == host.name &&
            (call.root == null || handle.rootPath == call.root) &&
            (call.server == null || handle.spec.name == call.server || handle.resolvedCommand == call.server)
    }
    if (matching.isEmpty()) return "no running language server matched; nothing to reload"
    matching.forEach { RemoteLspRegistry.remove(it.key)?.close() }
    return "closed ${matching.size} session(s) on '${host.name}'; the next call cold-starts the server"
}

internal fun resolveRemotePath(session: com.jcraft.jsch.Session, raw: String): String {
    val path = raw.trim()
    if (path == "~" || path.startsWith("~/")) {
        val home = RemoteExec.run(session, "printf %s \"\$HOME\"", 10_000).stdout.trim()
        if (home.isEmpty()) throw IllegalArgumentException("could not resolve ~ on the remote host")
        return if (path == "~") home else home + path.removePrefix("~")
    }
    require(path.startsWith("/")) { "'$raw' is not an absolute remote path; use /srv/... or ~/..." }
    return path
}

private suspend fun executeLspAction(handle: RemoteLspHandle, call: LspCall): String {
    val client = handle.client
    val files = handle.files
    val timeoutMs = call.timeoutSec * 1000L
    val file = call.file?.takeUnless { it == "*" }?.let { resolveRemotePath(handle.session, it) }

    return try {
        when (call.action) {
            "diagnostics" -> {
                val path = file ?: return errorText("diagnostics needs a concrete `file`")
                if (!files.exists(path)) return errorText("no such file on '${handle.hostName}': $path")
                client.syncDocument(path)
                val waitMs = minOf(timeoutMs, 15_000)
                val snapshot = client.waitForDiagnostics(path, waitMs)
                when {
                    snapshot == null ->
                        "no diagnostics were pushed by ${handle.spec.name} within ${waitMs}ms for $path " +
                            "(the server may still be indexing, or does not publish diagnostics for this file)"
                    snapshot.items.isEmpty() -> "OK: no diagnostics for $path (${handle.spec.name})"
                    else -> formatDiagnostics(path, snapshot.items)
                }
            }

            "definition", "type_definition", "implementation", "references" -> {
                val path = file ?: return errorText("${call.action} needs a concrete `file`")
                if (!files.exists(path)) return errorText("no such file on '${handle.hostName}': $path")
                val requireSymbol = call.action in setOf("definition", "references")
                val position = positionFor(handle, path, call, requireSymbol)
                val label = when (call.action) {
                    "definition" -> "definition(s)"
                    "type_definition" -> "type definition(s)"
                    "implementation" -> "implementation(s)"
                    else -> "reference(s)"
                }
                val locations = when (call.action) {
                    "definition" -> client.definition(path, position, timeoutMs)
                    "type_definition" -> client.typeDefinition(path, position, timeoutMs)
                    "implementation" -> client.implementation(path, position, timeoutMs)
                    else -> client.references(path, position, timeoutMs)
                }
                formatLocations(files, locations, label)
            }

            "hover" -> {
                val path = file ?: return errorText("hover needs a concrete `file`")
                if (!files.exists(path)) return errorText("no such file on '${handle.hostName}': $path")
                val position = positionFor(handle, path, call, requireSymbol = false)
                client.hover(path, position, timeoutMs).ifBlank { "no hover information" }
            }

            "symbols" -> {
                if (file == null) {
                    val query = call.query ?: return errorText("workspace symbols need `query` (and file='*')")
                    formatSymbolList(client.workspaceSymbols(query, timeoutMs), workspace = true)
                } else {
                    formatSymbolList(client.documentSymbols(file, timeoutMs), workspace = false)
                }
            }

            "rename" -> {
                val path = file ?: return errorText("rename needs a concrete `file`")
                val newName = call.newName ?: return errorText("rename needs `new_name`")
                val position = positionFor(handle, path, call, requireSymbol = true)
                val edit = client.rename(path, position, newName, timeoutMs)
                if (edit.isEmpty()) return "rename returned no edits (unchanged or not renameable)"
                val preview = call.apply == false
                if (preview) {
                    "rename preview (nothing written):\n" + client.previewWorkspaceEdit(edit).joinToString("\n")
                } else {
                    val applied = client.applyWorkspaceEdit(edit)
                    if (applied.isEmpty()) "rename produced no changes" else "applied rename:\n" + applied.joinToString("\n")
                }
            }

            "code_actions" -> {
                val path = file ?: return errorText("code_actions needs a concrete `file`")
                val position = positionFor(handle, path, call, requireSymbol = false)
                val only = if (call.apply == true) null else call.query
                val result = client.codeActions(path, LspRange(position, position), only, timeoutMs)
                val actions = result as? JsonArray ?: JsonArray(emptyList())
                if (call.apply != true) {
                    if (actions.isEmpty()) "no code actions at $path:${position.line + 1}:${position.character + 1}"
                    else formatCodeActions(actions)
                } else {
                    val chosen = selectCodeAction(actions, call.query)
                        ?: return "no code action matched '${call.query}'. Available:\n" + formatCodeActions(actions)
                    applyCodeAction(client, chosen, timeoutMs)
                }
            }

            "capabilities" -> {
                val caps = client.serverCapabilities ?: return "server reported no capabilities"
                truncate(prettyJson(caps), 6_000)
            }

            "request" -> {
                val method = call.query ?: return errorText("request needs `query` (the LSP method name)")
                val params = buildRequestParams(handle, call, file)
                val result = client.rawRequest(method, params, timeoutMs)
                "${handle.spec.name} <- $method:\n" + truncate(prettyJson(result), 8_000)
            }

            else -> errorText("unknown action '${call.action}'")
        }
    } catch (t: Throwable) {
        errorText(t)
    }
}

private suspend fun buildRequestParams(
    handle: RemoteLspHandle,
    call: LspCall,
    file: String?,
): JsonElement {
    call.payload?.let { payload ->
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload) }
            .onSuccess { return it }
            .onFailure { throw IllegalArgumentException("payload is not valid JSON: ${it.message}") }
    }
    if (file == null) return JsonObject(emptyMap())
    val uri = LspSpecs.pathToUri(file)
    if (call.line == null) {
        return buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) }
    }
    val position = positionFor(handle, file, call, requireSymbol = false)
    handle.client.syncDocument(file)
    return buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", uri) })
        put("position", buildJsonObject { put("line", position.line); put("character", position.character) })
    }
}

/**
 * Resolve a 1-based line + symbol into an LSP position.
 *
 * `definition`/`references`/`rename` demand an explicit symbol. Without one the only available
 * answer is "first non-whitespace column", which for a call like `foo(bar)` points at `foo` but
 * for `let x = bar()` points at `let` — and a wrong column turns a rename into a rename of the
 * wrong symbol. Refusing, with the line echoed back, costs one round trip and saves a mangled file.
 */
private fun positionFor(
    handle: RemoteLspHandle,
    path: String,
    call: LspCall,
    requireSymbol: Boolean,
): LspPosition {
    val text = handle.files.readText(path)
    val lineIndex = ((call.line ?: 1) - 1).coerceAtLeast(0)
    var lineStart = 0
    var current = 0
    while (current < lineIndex) {
        val newline = text.indexOf('\n', lineStart)
        if (newline < 0) {
            throw IllegalArgumentException("line ${lineIndex + 1} is past the end of $path")
        }
        lineStart = newline + 1
        current++
    }
    val newline = text.indexOf('\n', lineStart)
    val lineEnd = if (newline < 0) text.length else newline
    val lineText = text.substring(lineStart, lineEnd).removeSuffix("\r")

    val column = when {
        !call.symbol.isNullOrBlank() -> {
            val (name, occurrence) = LspEdits.splitOccurrence(call.symbol)
            LspEdits.resolveSymbolColumn(lineText, name, occurrence)
                ?: throw IllegalArgumentException(
                    "symbol '${call.symbol}' does not occur on line ${lineIndex + 1}: ${lineText.trim()}",
                )
        }
        requireSymbol -> throw IllegalArgumentException(
            "pass `symbol` (the identifier to act on) so the column is unambiguous. " +
                "Line ${lineIndex + 1} is: ${lineText.trim()}",
        )
        else -> lineText.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    }
    return LspPosition(lineIndex, column)
}

private fun formatLocations(
    files: RemoteFileClient,
    locations: List<JsonObject>,
    label: String,
    limit: Int = 40,
): String {
    if (locations.isEmpty()) return "no $label found"
    val cache = HashMap<String, List<String>>()
    val out = StringBuilder("found ${locations.size} $label:\n")
    for ((index, location) in locations.withIndex()) {
        if (index >= limit) {
            out.append("… ${locations.size - limit} more not shown\n")
            break
        }
        val uri = location["uri"]?.jsonPrimitive?.contentOrNull ?: continue
        val path = runCatching { LspSpecs.uriToPath(uri) }.getOrNull() ?: continue
        val start = ((location["range"] as? JsonObject)?.get("start") as? JsonObject) ?: continue
        val line = start["line"]?.jsonPrimitive?.intOrNull ?: 0
        val character = start["character"]?.jsonPrimitive?.intOrNull ?: 0
        out.append("$path:${line + 1}:${character + 1}")
        val lines = cache.getOrPut(path) {
            runCatching { files.readText(path).split('\n') }.getOrDefault(emptyList())
        }
        val snippet = lines.getOrNull(line)?.removeSuffix("\r")?.trim()
        if (!snippet.isNullOrEmpty()) out.append("\n    ").append(truncate(snippet, 200))
        out.append('\n')
    }
    return out.toString().trimEnd()
}

private fun formatDiagnostics(path: String, items: List<JsonObject>, limit: Int = 60): String {
    val out = StringBuilder("${items.size} diagnostic(s):\n")
    val sorted = items.sortedBy { it["severity"]?.jsonPrimitive?.intOrNull ?: 4 }
    for (item in sorted.take(limit)) {
        val start = ((item["range"] as? JsonObject)?.get("start") as? JsonObject)
        val lineNumber = (start?.get("line")?.jsonPrimitive?.intOrNull ?: 0) + 1
        val character = (start?.get("character")?.jsonPrimitive?.intOrNull ?: 0) + 1
        val severity = when (item["severity"]?.jsonPrimitive?.intOrNull) {
            1 -> "error"
            2 -> "warning"
            3 -> "info"
            4 -> "hint"
            else -> "diagnostic"
        }
        val message = item["message"]?.jsonPrimitive?.contentOrNull?.replace('\n', ' ') ?: ""
        val code = item["code"]?.jsonPrimitive?.contentOrNull
        out.append("$path:$lineNumber:$character: $severity: ${truncate(message, 300)}")
        if (!code.isNullOrEmpty()) out.append(" [$code]")
        out.append('\n')
    }
    if (sorted.size > limit) out.append("… ${sorted.size - limit} more not shown\n")
    return out.toString().trimEnd()
}

private fun formatCodeActions(actions: JsonArray): String {
    if (actions.isEmpty()) return "no code actions"
    val out = StringBuilder("${actions.size} code action(s):\n")
    actions.forEachIndexed { index, element ->
        val action = element as? JsonObject ?: return@forEachIndexed
        val title = action["title"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
        val kind = action["kind"]?.jsonPrimitive?.contentOrNull
        out.append("$index: ")
        if (!kind.isNullOrEmpty()) out.append("[$kind] ")
        out.append(truncate(title, 200)).append('\n')
    }
    return out.toString().trimEnd()
}

private fun selectCodeAction(actions: JsonArray, selector: String?): JsonObject? {
    if (actions.isEmpty()) return null
    if (selector.isNullOrBlank()) return actions.firstOrNull() as? JsonObject
    selector.trim().toIntOrNull()?.let { index ->
        return actions.getOrNull(index) as? JsonObject ?: return null
    }
    return actions.mapNotNull { it as? JsonObject }.firstOrNull { action ->
        action["title"]?.jsonPrimitive?.contentOrNull?.contains(selector, ignoreCase = true) == true
    }
}

private suspend fun applyCodeAction(
    client: RemoteLspClient,
    action: JsonObject,
    timeoutMs: Long,
): String {
    val title = action["title"]?.jsonPrimitive?.contentOrNull ?: "(untitled)"
    val lines = ArrayList<String>()
    (action["edit"] as? JsonObject)?.let { lines.addAll(client.applyWorkspaceEdit(it)) }
    (action["command"] as? JsonObject)?.let { command ->
        val name = command["command"]?.jsonPrimitive?.contentOrNull
        if (!name.isNullOrEmpty()) {
            client.executeCommand(command, timeoutMs)
            lines.add("executed server command $name")
        }
    }
    if (lines.isEmpty()) return "action '$title' had no workspace edit or command to apply"
    return "applied '$title':\n" + lines.joinToString("\n")
}

private fun formatSymbolList(value: JsonElement, workspace: Boolean): String {
    val array = value as? JsonArray ?: return if (workspace) "no symbols found" else "no symbols"
    if (array.isEmpty()) return if (workspace) "no symbols found" else "no symbols"
    val out = StringBuilder(if (workspace) "${array.size} symbol(s):\n" else "symbols:\n")
    var shown = 0
    fun walk(element: JsonElement, depth: Int) {
        if (depth > 8 || shown >= 120) return
        val symbol = element as? JsonObject ?: return
        val name = symbol["name"]?.jsonPrimitive?.contentOrNull ?: return
        val kind = symbolKindName(symbol["kind"]?.jsonPrimitive?.intOrNull)
        val location = symbol["location"] as? JsonObject
        if (location != null) {
            val uri = location["uri"]?.jsonPrimitive?.contentOrNull
            val line = (((location["range"] as? JsonObject)?.get("start") as? JsonObject)?.get("line")
                ?.jsonPrimitive?.intOrNull ?: 0) + 1
            val path = uri?.let { runCatching { LspSpecs.uriToPath(it) }.getOrNull() }?.let { LspSpecs.baseName(it) } ?: "?"
            out.append("  ".repeat(depth)).append("$name ($kind) @ $path:$line\n")
        } else {
            val line = (((symbol["selectionRange"] as? JsonObject)?.get("start") as? JsonObject)?.get("line")
                ?.jsonPrimitive?.intOrNull ?: 0) + 1
            out.append("  ".repeat(depth)).append("$name ($kind) @ line $line\n")
        }
        shown++
        (symbol["children"] as? JsonArray)?.forEach { walk(it, depth + 1) }
    }
    array.forEach { walk(it, 0) }
    if (shown >= 120) out.append("… truncated at 120 symbols\n")
    return out.toString().trimEnd()
}

private fun symbolKindName(kind: Int?): String = when (kind) {
    1 -> "file"; 2 -> "module"; 3 -> "namespace"; 4 -> "package"; 5 -> "class"
    6 -> "method"; 7 -> "property"; 8 -> "field"; 9 -> "constructor"; 10 -> "enum"
    11 -> "interface"; 12 -> "function"; 13 -> "variable"; 14 -> "constant"; 15 -> "string"
    16 -> "number"; 17 -> "boolean"; 18 -> "array"; 19 -> "object"; 20 -> "key"
    21 -> "null"; 22 -> "enum member"; 23 -> "struct"; 24 -> "event"; 25 -> "operator"
    26 -> "type parameter"; else -> "symbol"
}

private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }

private fun prettyJson(element: JsonElement): String =
    PRETTY_JSON.encodeToString(JsonElement.serializer(), element)

private fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "…"

private fun JsonObject.stringOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun errorText(message: String): String =
    buildJsonObject {
        put("error", message)
        put("tool", "remote_lsp")
    }.toString()

private fun errorText(t: Throwable): String =
    buildJsonObject {
        put("error", t.message ?: t.javaClass.simpleName)
        put("error_type", t.javaClass.simpleName)
        put("tool", "remote_lsp")
    }.toString()
