package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.local.SshHostProbe
import me.rerere.rikkahub.data.ai.tools.local.forgetHostKey
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.KnownHostEntry
import me.rerere.rikkahub.data.ssh.SshHostDto
import me.rerere.rikkahub.data.ssh.SshHostEvent
import me.rerere.rikkahub.data.ssh.SshHostOp
import me.rerere.rikkahub.data.ssh.SshHostRequest
import me.rerere.rikkahub.data.ssh.SshHostResponse
import me.rerere.rikkahub.data.ssh.SshHostResolution
import me.rerere.rikkahub.data.ssh.SshHostTestResult
import me.rerere.rikkahub.data.ssh.SshHostUpsert
import me.rerere.rikkahub.data.ssh.parseKnownHosts
import me.rerere.rikkahub.data.ssh.resolveSshHostUpsert
import me.rerere.rikkahub.data.ssh.toDto

private const val TAG = "SshHostSocket"

/**
 * One-shot operations a socket may ask for. An interface, not the Android pieces directly,
 * so the request/response loop can be exercised on a plain JVM with a fake backend.
 */
interface SshHostBackend {
    suspend fun list(): List<SshHostDto>
    suspend fun get(name: String): SshHostDto?
    suspend fun save(input: SshHostUpsert): SshHostResponse
    suspend fun delete(name: String): Boolean
    suspend fun test(name: String): SshHostTestResult?
    suspend fun knownHosts(): List<KnownHostEntry>
    suspend fun forgetHostKey(pattern: String): Int
}

/**
 * Frames for the `/api/ssh/hosts/ws` socket.
 *
 * Every request is answered with exactly one frame carrying the same [SshHostRequest.id]
 * (null when the client sent none) — a client that pipelines requests can match them up
 * without a stream of out-of-band state. Mutations additionally broadcast [SshHostEvent.HOSTS_CHANGED]
 * to every other listener on this socket's registry, so two open tabs converge without polling.
 *
 * Frames are handled strictly one at a time: `test` dials the network for up to 30s, and
 * servicing concurrent frames on one connection would let a fast `list` overtake the save
 * that triggered it, which a UI would render as "saved, then vanished".
 */
class SshHostSocketHandler(
    private val backend: SshHostBackend,
    private val json: Json,
    /** Max frames handled before the socket closes itself; guards against a hot loop. */
    private val maxFramesPerSession: Int = 10_000,
    /**
     * Where internal failures go. Injected because `android.util.Log` throws in a plain JVM
     * unit test ("not mocked"), and the whole request loop is otherwise Android-free.
     */
    private val logError: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
    /** Registers [send] as a frame sink; call [detach] with the same lambda on disconnect. */
    private val listeners = mutableSetOf<suspend (SshHostResponse) -> Unit>()
    private val listenerLock = Mutex()

    suspend fun attach(send: suspend (SshHostResponse) -> Unit) {
        listenerLock.withLock { listeners += send }
    }

    suspend fun detach(send: suspend (SshHostResponse) -> Unit) {
        listenerLock.withLock { listeners -= send }
    }

    /**
     * Push [event] to every attached sink.
     *
     * The frame is encoded here, not by the sink, so a listener can only ever put a complete
     * `SshHostResponse` on the wire. Sending the bare event name was the earlier shape and it
     * silently broke every client: the frame arrived, failed to parse as JSON, and the change
     * notification was dropped — the UI only refreshed because it also polls after its own
     * mutations.
     */
    private suspend fun broadcast(event: String) {
        val snapshot = listenerLock.withLock { listeners.toList() }
        if (snapshot.isEmpty()) return
        val frame = SshHostResponse(ok = true, event = event)
        snapshot.forEach { listener ->
            runCatching { listener(frame) }
                .onFailure { logError("listener failed while sending $event", it) }
        }
    }

    /** Serve one connection until the peer closes it or the frame budget is exhausted. */
    suspend fun serve(session: DefaultWebSocketServerSession) {
        val sender: suspend (SshHostResponse) -> Unit = { frame ->
            session.send(Frame.Text(json.encodeToString(SshHostResponse.serializer(), frame)))
        }
        attach(sender)
        try {
            var handled = 0
            for (frame in session.incoming) {
                // Binary frames carry no meaning here; ignore rather than kill the socket —
                // a keepalive ping from a browser-ish client arrives as Ping, not Text.
                if (frame !is Frame.Text) continue
                if (handled++ >= maxFramesPerSession) {
                    reply(
                        session,
                        SshHostResponse(error = "too_many_frames", message = "Frame budget exhausted"),
                    )
                    break
                }
                val text = frame.readText()
                val request = runCatching { json.decodeFromString(SshHostRequest.serializer(), text) }
                    .getOrElse { e ->
                        reply(
                            session,
                            SshHostResponse(
                                error = "bad_request",
                                message = e.message ?: "Malformed JSON",
                            ),
                        )
                        continue
                    }
                val response = dispatch(request)
                reply(session, response)
            }
        } finally {
            detach(sender)
        }
    }

    private suspend fun reply(session: DefaultWebSocketServerSession, response: SshHostResponse) {
        val text = json.encodeToString(SshHostResponse.serializer(), response)
        session.send(Frame.Text(text))
    }

    /**
     * Execute [request]. Never throws: every failure becomes an error response, because a
     * thrown exception inside the frame loop would close the socket and a UI would have to
     * reconnect to learn that a name was taken.
     */
    suspend fun dispatch(request: SshHostRequest): SshHostResponse {
        val op = request.op.trim().lowercase()
        return try {
            when (op) {
                SshHostOp.LIST -> SshHostResponse(
                    id = request.id,
                    op = op,
                    ok = true,
                    hosts = backend.list(),
                )

                SshHostOp.GET -> {
                    val name = request.name?.trim()
                    if (name.isNullOrEmpty()) {
                        fail(request, op, "missing_name")
                    } else {
                        val host = backend.get(name)
                        if (host == null) {
                            fail(request, op, "not_found", "No host named $name")
                        } else {
                            SshHostResponse(id = request.id, op = op, ok = true, host = host)
                        }
                    }
                }

                SshHostOp.SAVE -> {
                    val input = request.host
                    if (input == null) {
                        fail(request, op, "missing_host")
                    } else {
                        val saved = backend.save(input)
                        if (saved.ok) broadcast(SshHostEvent.HOSTS_CHANGED)
                        saved.copy(id = request.id, op = op)
                    }
                }

                SshHostOp.DELETE -> {
                    val name = request.name?.trim()
                    if (name.isNullOrEmpty()) {
                        fail(request, op, "missing_name")
                    } else if (!backend.delete(name)) {
                        fail(request, op, "not_found", "No host named $name")
                    } else {
                        broadcast(SshHostEvent.HOSTS_CHANGED)
                        SshHostResponse(id = request.id, op = op, ok = true)
                    }
                }

                SshHostOp.TEST -> {
                    val name = request.name?.trim()
                    if (name.isNullOrEmpty()) {
                        fail(request, op, "missing_name")
                    } else {
                        val result = backend.test(name)
                        if (result == null) {
                            fail(request, op, "not_found", "No host named $name")
                        } else {
                            SshHostResponse(id = request.id, op = op, ok = true, test = result)
                        }
                    }
                }

                SshHostOp.KNOWN_HOSTS -> SshHostResponse(
                    id = request.id,
                    op = op,
                    ok = true,
                    knownHosts = backend.knownHosts(),
                )

                SshHostOp.FORGET_HOST_KEY -> {
                    val pattern = request.pattern?.trim()
                    if (pattern.isNullOrEmpty()) {
                        fail(request, op, "missing_pattern")
                    } else {
                        val removed = backend.forgetHostKey(pattern)
                        SshHostResponse(
                            id = request.id,
                            op = op,
                            ok = true,
                            message = "removed $removed key(s)",
                        )
                    }
                }

                else -> fail(request, op, "unknown_op", "Unknown op: ${request.op}")
            }
        } catch (e: CancellationException) {
            // The connection is going away; let cancellation propagate.
            throw e
        } catch (e: Throwable) {
            logError("op $op failed", e)
            fail(request, op, "internal_error", e.message ?: e::class.simpleName)
        }
    }

    private fun fail(request: SshHostRequest, op: String, code: String, message: String? = null) =
        SshHostResponse(id = request.id, op = op, ok = false, error = code, message = message)
}

/**
 * Production backend: Room for the hosts, the shared [SshHostProbe] for connectivity, and the
 * app's single `known_hosts` file for the trusted-key rows.
 */
class RepositorySshHostBackend(
    private val context: Context,
    private val repository: SshHostRepository,
) : SshHostBackend {

    override suspend fun list(): List<SshHostDto> =
        repository.getAll().map { it.toDto() }

    override suspend fun get(name: String): SshHostDto? =
        repository.getByName(name)?.toDto()

    override suspend fun save(input: SshHostUpsert): SshHostResponse {
        val original = input.originalName?.trim()?.takeIf { it.isNotEmpty() }
        val existing = original?.let { repository.getByName(it) }
        // Only a *new* name can collide; re-saving under the same name must stay legal.
        val name = input.name.trim()
        val nameTaken = name.isNotEmpty() && name != original && repository.getByName(name) != null

        return when (val resolution = resolveSshHostUpsert(input, existing, nameTaken, System.currentTimeMillis())) {
            is SshHostResolution.Invalid -> SshHostResponse(
                ok = false,
                error = "invalid_host",
                message = resolution.message,
                errors = resolution.errors,
            )

            is SshHostResolution.Saved -> {
                // `name` is the primary key, so a rename is a delete + insert.
                resolution.renamedFrom?.let { repository.deleteByName(it) }
                repository.upsert(resolution.entity)
                SshHostResponse(ok = true, host = resolution.entity.toDto())
            }
        }
    }

    override suspend fun delete(name: String): Boolean {
        if (repository.getByName(name) == null) return false
        repository.deleteByName(name)
        return true
    }

    override suspend fun test(name: String): SshHostTestResult? {
        val host = repository.getByName(name) ?: return null
        // The probe dials the network for up to 30s; cap it a little above that so a stuck
        // socket cannot hold the frame loop (and every other request on this connection).
        val result = withTimeoutOrNull(SshHostProbe.TIMEOUT_MS + 5_000L) {
            SshHostProbe.probe(context, host)
        } ?: return SshHostTestResult(
            ok = false,
            errorCode = "timeout",
            detail = "No result after ${(SshHostProbe.TIMEOUT_MS + 5_000) / 1000}s",
        )
        return SshHostTestResult(
            ok = result.ok,
            errorCode = result.errorCode,
            detail = result.detail,
        )
    }

    override suspend fun knownHosts(): List<KnownHostEntry> {
        val file = knownHostsFile(context)
        if (!file.exists()) return emptyList()
        return parseKnownHosts(file.readText())
    }

    override suspend fun forgetHostKey(pattern: String): Int = forgetHostKey(context, pattern)
}
