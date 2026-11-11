package me.rerere.myagent.data.lsp

import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.myagent.data.ssh.FramedPeer
import me.rerere.myagent.data.ssh.FramingException
import me.rerere.myagent.data.ssh.RemoteExecChannel
import me.rerere.myagent.data.ssh.RemoteFileClient
import me.rerere.myagent.data.ssh.posixShellQuote
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** How many extra attempts `references` makes while a background index catches up. */
private const val REFERENCES_RETRIES = 4

/** Delay between those attempts; long enough for a warm index, short enough to stay invisible. */
private const val REFERENCES_RETRY_DELAY_MS = 700L

internal class LspException(message: String, val code: Int? = null) : Exception(message)

internal class LspTimeoutException(val method: String, val timeoutMs: Long) :
    Exception("language server did not answer $method within ${timeoutMs}ms")

internal data class OpenDocument(val version: Int, val text: String)

internal data class DiagnosticsSnapshot(val version: Int?, val items: List<JsonObject>)

/**
 * A language server running on the far side of SSH, spoken to over LSP.
 *
 * Scope is intentionally "one project, one server, one connection": the tool layer decides when
 * to create one and when to reap it. What matters here is that the protocol state is correct —
 * initialization, document sync, push-diagnostic caching, and answering the server's own
 * requests (a server that never gets `workspace/configuration` answered stalls on some versions
 * of pyright, and clangd asks for `workspace/applyEdit` when it wants a fix applied).
 *
 * Every request is deadline-bound. A hung language server (rust-analyzer during a cold
 * `cargo check`, clangd on a huge translation unit) otherwise parks the model's tool call
 * forever, which reads as "the agent froze".
 */
internal class RemoteLspClient(
    private val session: Session,
    val rootPath: String,
    val spec: LspServerSpec,
    val resolvedCommand: String,
    private val files: RemoteFileClient,
    /**
     * Remote path of the document this server is being started for. clangd needs compilation
     * flags for that file before it treats it as part of a project — see [CompileDatabase] for
     * the measurements behind the bootstrap database this triggers.
     */
    bootstrapFile: String? = null,
) : Closeable {

    private val compileDbDir: String? =
        if (spec.name == "clangd" && bootstrapFile != null && !CompileDatabase.alreadyPresent(files, rootPath)) {
            CompileDatabase.create(session, files, rootPath, bootstrapFile)
        } else {
            null
        }

    private val exec = RemoteExecChannel.start(session, rootPath, commandLine(), connectTimeoutMs = 20_000)
    private val peer = FramedPeer(exec.stdout, exec.stdin, "lsp-${spec.name}")
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)
    private val serverRequestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "myagent-lsp-${spec.name}-server-request").apply { isDaemon = true }
    }

    private val documentLock = Any()
    private val documents = LinkedHashMap<String, OpenDocument>()

    private val diagnosticsLock = Any()
    private val diagnostics = HashMap<String, DiagnosticsSnapshot>()

    @Volatile
    var serverCapabilities: JsonObject? = null
        private set

    @Volatile
    private var closedReason: Throwable? = null

    private fun commandLine(): String =
        (listOf(resolvedCommand) + spec.args).joinToString(" ") { posixShellQuote(it) }

    /** Last stderr text from the remote process; the only clue when it dies during startup. */
    fun stderrTail(): String = exec.stderrSnapshot()

    val processAlive: Boolean get() = exec.isAlive

    suspend fun initialize(timeoutMs: Long = 25_000): JsonObject {
        peer.onMessage = ::handleMessage
        peer.onClosed = { cause ->
            val reason = cause ?: FramingException(
                "language server ${spec.name} exited (status ${exec.exitStatus ?: -1})${stderrSuffix()}",
            )
            closedReason = reason
            failAllPending(reason)
        }
        peer.start()

        val rootUri = LspSpecs.pathToUri(rootPath)
        val params = buildJsonObject {
            put("processId", JsonNull)
            putJsonObject("clientInfo") {
                put("name", "MyAgent")
                put("version", "1")
            }
            put("rootUri", rootUri)
            put("rootPath", rootPath)
            putJsonArray("workspaceFolders") {
                addJsonObject {
                    put("uri", rootUri)
                    put("name", LspSpecs.baseName(rootPath).ifEmpty { rootPath })
                }
            }
            put("capabilities", clientCapabilities())
        }
        val result = try {
            request("initialize", params, timeoutMs).jsonObject
        } catch (t: Throwable) {
            // A server that died during the handshake leaves its reason on stderr only; without
            // that tail the user sees "transport already closed" and has nothing to act on.
            throw LspException((t.message ?: "initialize failed") + stderrSuffix(), null)
        }
        serverCapabilities = result["capabilities"] as? JsonObject
        notify("initialized", JsonObject(emptyMap()))
        return result
    }

    // ---------------------------------------------------------------- requests

    private suspend fun request(method: String, params: JsonElement?, timeoutMs: Long): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            peer.send(payload)
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                runCatching { notify("$/cancelRequest", buildJsonObject { put("id", id) }) }
                throw LspTimeoutException(method, timeoutMs)
            }
            return result
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonElement?) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        peer.send(payload)
    }

    private fun respond(id: JsonElement, result: JsonElement) {
        peer.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    private fun respondError(id: JsonElement, code: Int, message: String) {
        peer.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )
    }

    private fun failAllPending(cause: Throwable) {
        for (key in pending.keys) {
            pending.remove(key)?.completeExceptionally(cause)
        }
    }

    private fun stderrSuffix(): String {
        val tail = exec.stderrSnapshot(600).trim()
        return if (tail.isEmpty()) "" else ": $tail"
    }

    // ---------------------------------------------------------------- messages

    private fun handleMessage(message: JsonObject) {
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        when {
            method != null && id != null -> {
                val requestId = id
                serverRequestExecutor.execute {
                    runCatching { handleServerRequest(requestId, method, message["params"]) }
                }
            }
            method != null -> handleNotification(method, message["params"])
            id != null -> {
                val key = id.jsonPrimitive.longOrNull ?: return
                val deferred = pending.remove(key) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        LspException(
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "LSP error",
                            error["code"]?.jsonPrimitive?.intOrNull,
                        ),
                    )
                } else {
                    deferred.complete(message["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        when (method) {
            "textDocument/publishDiagnostics" -> {
                val payload = params as? JsonObject ?: return
                val uri = payload["uri"]?.jsonPrimitive?.contentOrNull ?: return
                val version = payload["version"]?.jsonPrimitive?.intOrNull
                val items = (payload["diagnostics"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                synchronized(diagnosticsLock) { diagnostics[uri] = DiagnosticsSnapshot(version, items) }
            }
            // Nothing to do, but documented so a reader knows the silence is deliberate:
            // progress/log/message notifications are noise for a one-shot tool call.
            "$/progress", "window/logMessage", "window/showMessage", "telemetry/event" -> Unit
        }
    }

    private fun handleServerRequest(id: JsonElement, method: String, params: JsonElement?) {
        when (method) {
            // Servers ask for per-section configuration; answering null means "use your
            // defaults", which is exactly right for a client with no settings surface.
            "workspace/configuration" -> {
                val items = (params as? JsonObject)?.get("items") as? JsonArray
                respond(id, JsonArray(items?.map { JsonNull } ?: emptyList()))
            }
            "client/registerCapability",
            "client/unregisterCapability",
            "window/workDoneProgress/create",
            "window/showMessageRequest",
            "workspace/workspaceFolders" -> respond(id, JsonNull)

            "workspace/applyEdit" -> {
                val edit = (params as? JsonObject)?.get("edit") as? JsonObject
                if (edit == null) {
                    respond(id, buildJsonObject { put("applied", false) })
                } else {
                    val applied = runCatching { applyWorkspaceEdit(edit) }
                    respond(
                        id,
                        if (applied.isSuccess) {
                            buildJsonObject { put("applied", true) }
                        } else {
                            buildJsonObject {
                                put("applied", false)
                                put("failureReason", applied.exceptionOrNull()?.message ?: "apply failed")
                            }
                        },
                    )
                }
            }
            else -> respondError(id, -32601, "client does not implement $method")
        }
    }

    // ---------------------------------------------------------------- documents

    /**
     * Make the server's copy of [path] match the remote disk.
     *
     * Full-text `didChange` instead of incremental ranges: the client is stateless between tool
     * calls (the agent may have run `sed` over SSH), so any incremental range would be computed
     * against a version the client cannot prove. Full sync is the only honest option.
     */
    fun syncDocument(path: String) {
        val uri = LspSpecs.pathToUri(path)
        val text = files.readText(path)
        synchronized(documentLock) {
            val existing = documents[uri]
            if (existing == null) {
                documents[uri] = OpenDocument(version = 1, text = text)
                synchronized(diagnosticsLock) { diagnostics.remove(uri) }
                notify(
                    "textDocument/didOpen",
                    buildJsonObject {
                        putJsonObject("textDocument") {
                            put("uri", uri)
                            put("languageId", LspSpecs.languageIdFor(path))
                            put("version", 1)
                            put("text", text)
                        }
                    },
                )
            } else if (existing.text != text) {
                val version = existing.version + 1
                documents[uri] = OpenDocument(version, text)
                synchronized(diagnosticsLock) { diagnostics.remove(uri) }
                notify(
                    "textDocument/didChange",
                    buildJsonObject {
                        putJsonObject("textDocument") {
                            put("uri", uri)
                            put("version", version)
                        }
                        putJsonArray("contentChanges") {
                            addJsonObject { put("text", text) }
                        }
                    },
                )
            }
        }
    }

    /**
     * After we wrote [text] to [path] ourselves, tell the server without re-reading it (the file
     * on disk is already the new content by then).
     */
    private fun syncAfterWrite(path: String, text: String) {
        val uri = LspSpecs.pathToUri(path)
        synchronized(documentLock) {
            val existing = documents[uri] ?: return
            val version = existing.version + 1
            documents[uri] = OpenDocument(version, text)
            synchronized(diagnosticsLock) { diagnostics.remove(uri) }
            notify(
                "textDocument/didChange",
                buildJsonObject {
                    putJsonObject("textDocument") {
                        put("uri", uri)
                        put("version", version)
                    }
                    putJsonArray("contentChanges") { addJsonObject { put("text", text) } }
                },
            )
        }
    }

    fun documentVersion(path: String): Int? = synchronized(documentLock) {
        documents[LspSpecs.pathToUri(path)]?.version
    }

    /**
     * Wait for a diagnostics push for [path]. Returns null on timeout: "the server never said
     * anything" and "the server said there are no problems" are different outcomes, and the
     * caller needs to distinguish them.
     */
    suspend fun waitForDiagnostics(path: String, timeoutMs: Long): DiagnosticsSnapshot? {
        val uri = LspSpecs.pathToUri(path)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val snapshot = synchronized(diagnosticsLock) { diagnostics[uri] }
            if (snapshot != null) return snapshot
            if (System.currentTimeMillis() >= deadline) return null
            delay(50)
        }
    }

    // ---------------------------------------------------------------- actions

    suspend fun definition(path: String, position: LspPosition, timeoutMs: Long): List<JsonObject> {
        syncDocument(path)
        return locationObjects(
            request("textDocument/definition", textDocumentPosition(path, position), timeoutMs),
        )
    }

    suspend fun typeDefinition(path: String, position: LspPosition, timeoutMs: Long): List<JsonObject> =
        locationObjects(request("textDocument/typeDefinition", textDocumentPosition(path, position), timeoutMs))

    suspend fun implementation(path: String, position: LspPosition, timeoutMs: Long): List<JsonObject> =
        locationObjects(request("textDocument/implementation", textDocumentPosition(path, position), timeoutMs))

    /**
     * `textDocument/references`, retried while the answer looks like "the index has not caught up
     * yet" (empty, or only the declaration we asked about).
     *
     * clangd and rust-analyzer answer references from a background index. Right after a file is
     * opened, the declaration is known but the call sites are not indexed, so the first answer is
     * a confident half-truth. Waiting a beat and asking again is what editors do; without it the
     * model concludes "no call sites" and edits only half the project.
     */
    suspend fun references(path: String, position: LspPosition, timeoutMs: Long): List<JsonObject> {
        syncDocument(path)
        val queriedUri = LspSpecs.pathToUri(path)
        val params = buildJsonObject {
            putJsonObject("textDocument") { put("uri", queriedUri) }
            put("position", positionObject(position))
            putJsonObject("context") { put("includeDeclaration", true) }
        }
        var last: List<JsonObject> = emptyList()
        for (attempt in 0..REFERENCES_RETRIES) {
            val result = locationObjects(request("textDocument/references", params, timeoutMs))
            last = result
            val onlyDeclaration = result.size <= 1 && result.all {
                (it["uri"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == queriedUri
            }
            if (result.isNotEmpty() && !onlyDeclaration) return result
            if (attempt < REFERENCES_RETRIES) delay(REFERENCES_RETRY_DELAY_MS)
        }
        return last
    }

    suspend fun hover(path: String, position: LspPosition, timeoutMs: Long): String {
        syncDocument(path)
        val result = request("textDocument/hover", textDocumentPosition(path, position), timeoutMs)
        val contents = (result as? JsonObject)?.get("contents") ?: return ""
        return flattenMarkup(contents)
    }

    suspend fun documentSymbols(path: String, timeoutMs: Long): JsonElement {
        syncDocument(path)
        return request(
            "textDocument/documentSymbol",
            buildJsonObject { putJsonObject("textDocument") { put("uri", LspSpecs.pathToUri(path)) } },
            timeoutMs,
        )
    }

    suspend fun workspaceSymbols(query: String, timeoutMs: Long): JsonElement =
        request("workspace/symbol", buildJsonObject { put("query", query) }, timeoutMs)

    /** Returns the raw `WorkspaceEdit`; applying is the caller's decision (apply vs preview). */
    suspend fun rename(
        path: String,
        position: LspPosition,
        newName: String,
        timeoutMs: Long,
    ): JsonObject {
        syncDocument(path)
        val params = buildJsonObject {
            putJsonObject("textDocument") { put("uri", LspSpecs.pathToUri(path)) }
            put("position", positionObject(position))
            put("newName", newName)
        }
        return request("textDocument/rename", params, timeoutMs) as? JsonObject ?: JsonObject(emptyMap())
    }

    suspend fun codeActions(
        path: String,
        range: LspRange,
        only: String?,
        timeoutMs: Long,
    ): JsonElement {
        syncDocument(path)
        val params = buildJsonObject {
            putJsonObject("textDocument") { put("uri", LspSpecs.pathToUri(path)) }
            putJsonObject("range") {
                put("start", positionObject(range.start))
                put("end", positionObject(range.end))
            }
            putJsonObject("context") {
                if (only != null) putJsonArray("only") { add(kotlinx.serialization.json.JsonPrimitive(only)) }
                val uri = LspSpecs.pathToUri(path)
                val cached = synchronized(diagnosticsLock) { diagnostics[uri] }?.items ?: emptyList()
                put("diagnostics", JsonArray(cached))
            }
        }
        return request("textDocument/codeAction", params, timeoutMs)
    }

    suspend fun executeCommand(command: JsonObject, timeoutMs: Long): JsonElement {
        val params = buildJsonObject {
            put("command", command["command"]?.jsonPrimitive?.contentOrNull ?: return JsonNull)
            put("arguments", command["arguments"] ?: JsonArray(emptyList()))
        }
        return request("workspace/executeCommand", params, timeoutMs)
    }

    /**
     * Render a `WorkspaceEdit` without touching the filesystem. Used by `apply: false` so the
     * model can show the user what a rename would do before anything on their server changes.
     */
    fun previewWorkspaceEdit(edit: JsonObject): List<String> {
        val ops = LspEdits.parseWorkspaceEdit(edit) { LspSpecs.uriToPath(it) }
        return ops.map { op ->
            when (op) {
                is WorkspaceFileOp.Edit -> {
                    val positions = op.edits.take(4).joinToString(", ") {
                        "${it.range.start.line + 1}:${it.range.start.character + 1}"
                    }
                    val more = if (op.edits.size > 4) ", …" else ""
                    "${op.path}: ${op.edits.size} edit(s) at $positions$more"
                }
                is WorkspaceFileOp.Create -> "create ${op.path}"
                is WorkspaceFileOp.Rename -> "rename ${op.from} -> ${op.to}"
                is WorkspaceFileOp.Delete -> "delete ${op.path}"
            }
        }
    }

    /**
     * Apply a `WorkspaceEdit` against the remote filesystem. Returns one human-readable line per
     * change so the tool result shows exactly what was mutated on someone's server.
     */
    fun applyWorkspaceEdit(edit: JsonObject): List<String> {
        val ops = LspEdits.parseWorkspaceEdit(edit) { LspSpecs.uriToPath(it) }
        val applied = ArrayList<String>()
        val textEdits = LinkedHashMap<String, MutableList<LspTextEdit>>()

        for (op in ops) {
            when (op) {
                is WorkspaceFileOp.Edit -> textEdits.getOrPut(op.path) { ArrayList() }.addAll(op.edits)
                is WorkspaceFileOp.Create -> {
                    if (files.exists(op.path) && !op.overwrite) {
                        throw LspException("refusing to create ${op.path}: it already exists")
                    }
                    if (!files.exists(op.path)) files.writeText(op.path, "")
                    applied.add("created ${op.path}")
                }
                is WorkspaceFileOp.Rename -> {
                    files.rename(op.from, op.to)
                    val uri = LspSpecs.pathToUri(op.from)
                    synchronized(documentLock) { documents.remove(uri) }
                    applied.add("renamed ${op.from} -> ${op.to}")
                }
                is WorkspaceFileOp.Delete -> {
                    if (files.exists(op.path)) {
                        if (files.isDirectory(op.path)) {
                            throw LspException("refusing to delete directory ${op.path} over SFTP")
                        }
                        files.delete(op.path)
                        applied.add("deleted ${op.path}")
                    } else if (!op.ignoreIfNotExists) {
                        throw LspException("cannot delete ${op.path}: it does not exist")
                    }
                }
            }
        }

        for ((path, edits) in textEdits) {
            val before = files.readText(path)
            val after = LspEdits.apply(before, edits)
            if (after != before) {
                files.writeText(path, after)
                syncAfterWrite(path, after)
                applied.add("edited $path (${edits.size} edit${if (edits.size == 1) "" else "s"})")
            }
        }
        return applied
    }

    // ---------------------------------------------------------------- helpers

    /** Escape hatch used by `action=request`: any LSP method with caller-supplied params. */
    suspend fun rawRequest(method: String, params: JsonElement?, timeoutMs: Long): JsonElement =
        request(method, params, timeoutMs)

    private fun textDocumentPosition(path: String, position: LspPosition): JsonObject = buildJsonObject {
        putJsonObject("textDocument") { put("uri", LspSpecs.pathToUri(path)) }
        put("position", positionObject(position))
    }

    private fun positionObject(position: LspPosition): JsonObject = buildJsonObject {
        put("line", position.line)
        put("character", position.character)
    }

    /** Normalise `Location` / `Location[]` / `LocationLink` / `LocationLink[]` into Location objects. */
    private fun locationObjects(result: JsonElement): List<JsonObject> {
        val raw = when (result) {
            is JsonArray -> result.toList()
            is JsonObject -> listOf(result)
            else -> emptyList()
        }
        return raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj["targetUri"] != null) {
                // LocationLink: the selection range is what the user means by "go to definition".
                buildJsonObject {
                    put("uri", obj["targetUri"]!!)
                    put("range", obj["targetSelectionRange"] ?: obj["targetRange"] ?: JsonNull)
                }
            } else {
                obj
            }
        }
    }

    private fun flattenMarkup(element: JsonElement): String = when (element) {
        is JsonArray -> element.joinToString("\n\n") { flattenMarkup(it) }
        is JsonObject -> {
            val value = element["value"]?.jsonPrimitive?.contentOrNull ?: ""
            val language = element["language"]?.jsonPrimitive?.contentOrNull
            if (language != null && value.isNotEmpty()) "```$language\n$value\n```" else value
        }
        JsonNull -> ""
        else -> element.jsonPrimitive.contentOrNull ?: ""
    }

    private fun clientCapabilities(): JsonObject = buildJsonObject {
        putJsonObject("general") {
            putJsonArray("positionEncodings") {
                add(kotlinx.serialization.json.JsonPrimitive("utf-16"))
            }
        }
        putJsonObject("window") {
            put("workDoneProgress", true)
            putJsonObject("showMessage") {}
        }
        putJsonObject("workspace") {
            put("workspaceFolders", true)
            put("configuration", true)
            put("applyEdit", true)
            putJsonObject("workspaceEdit") { put("documentChanges", true) }
            putJsonObject("symbol") {}
            putJsonObject("didChangeWatchedFiles") { put("dynamicRegistration", false) }
        }
        putJsonObject("textDocument") {
            putJsonObject("synchronization") {
                put("dynamicRegistration", false)
                put("willSave", false)
                put("willSaveWaitUntil", false)
                put("didSave", true)
            }
            putJsonObject("publishDiagnostics") {
                put("relatedInformation", true)
                put("versionSupport", true)
            }
            putJsonObject("definition") { put("linkSupport", true) }
            putJsonObject("typeDefinition") { put("linkSupport", true) }
            putJsonObject("implementation") { put("linkSupport", true) }
            putJsonObject("references") {}
            putJsonObject("hover") {
                putJsonArray("contentFormat") {
                    add(kotlinx.serialization.json.JsonPrimitive("markdown"))
                    add(kotlinx.serialization.json.JsonPrimitive("plaintext"))
                }
            }
            putJsonObject("documentSymbol") { put("hierarchicalDocumentSymbolSupport", true) }
            putJsonObject("codeAction") {
                putJsonObject("codeActionLiteralSupport") {
                    putJsonObject("codeActionKind") {
                        putJsonArray("valueSet") {
                            listOf(
                                "quickfix",
                                "refactor",
                                "refactor.extract",
                                "refactor.inline",
                                "refactor.rewrite",
                                "source",
                                "source.organizeImports",
                                "source.fixAll",
                            ).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    }
                }
                put("isPreferredSupport", true)
                put("disabledSupport", true)
            }
            putJsonObject("rename") { put("prepareSupport", false) }
        }
    }

    override fun close() {
        runCatching {
            peer.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", nextId.getAndIncrement())
                    put("method", "shutdown")
                    put("params", JsonNull)
                },
            )
        }
        runCatching { notify("exit", null) }
        runCatching { peer.close() }
        runCatching { exec.close() }
        serverRequestExecutor.shutdownNow()
    }
}
