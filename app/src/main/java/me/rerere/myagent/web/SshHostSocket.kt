package me.rerere.myagent.web

import android.content.Context
import android.util.Log
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.myagent.data.ai.tools.local.SshHostProbe
import me.rerere.myagent.data.ai.tools.local.forgetHostKey
import me.rerere.myagent.data.ai.tools.local.knownHostsFile
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.KnownHostEntry
import me.rerere.myagent.data.ssh.SshHostDto
import me.rerere.myagent.data.ssh.SshHostEvent
import me.rerere.myagent.data.ssh.SshHostOp
import me.rerere.myagent.data.ssh.SshHostRequest
import me.rerere.myagent.data.ssh.SshHostResponse
import me.rerere.myagent.data.ssh.SshHostResolution
import me.rerere.myagent.data.ssh.SshHostTestResult
import me.rerere.myagent.data.ssh.SshHostUpsert
import me.rerere.myagent.data.ssh.parseKnownHosts
import me.rerere.myagent.data.ssh.resolveSshHostUpsert
import me.rerere.myagent.data.ssh.toDto

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
 * without a stream of out-of-band state. `test` is the one op whose single answer arrives
 * later: the reply is emitted from a child coroutine so a 30s handshake cannot stall the
 * frames behind it. Mutations additionally broadcast [SshHostEvent.HOSTS_CHANGED] to every
 * other listener on this socket's registry, so two open tabs converge without polling.
 *
 * Frames other than `test` are handled strictly one at a time: servicing concurrent frames on
 * one connection would let a fast `list` overtake the save that triggered it, which a UI would
 * render as "saved, then vanished".
 */
class SshHostSocketHandler(
    private val backend: SshHostBackend,
    private val json: Json,
    /** Max frames handled before the socket closes itself; guards against a hot loop. */
    private val maxFramesPerSession: Int = 10_000,
    /**
     * How many `test` probes one connection may have in flight. They used to be serialised
     * behind the frame loop, which meant a second test clicked by the user timed out at 45s
     * while the first was still dialling.
     */
    private val maxConcurrentTests: Int = 4,
    /**
     * Where internal failures go. Injected because `android.util.Log` throws in a plain JVM
     * unit test ("not mocked"), and the whole request loop is otherwise Android-free.
     */
    private val logError: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) },
    /**
     * How long one socket may take to accept a change event before it is considered wedged.
     *
     * Broadcasting used to await each listener in turn: one client whose TCP window is full
     * (a browser tab on a suspended laptop, a phone that left Wi-Fi) stalled the event for
     * every other client on the same registry, which is exactly the "two tabs disagree"
     * symptom the event exists to prevent.
     */
    private val listenerSendTimeoutMs: Long = 2_000L,
    /**
     * How long one reply may take to be accepted by the peer before the connection is treated as
     * dead. A browser tab whose TCP window is full would otherwise suspend `send` forever: the
     * frame loop would stop reading (so the same client could not even ping its way out) and the
     * connection, its listener registration and its coroutine would live until the OS gave up.
     */
    private val replySendTimeoutMs: Long = listenerSendTimeoutMs,
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
     * Push [event] to every attached sink except [origin].
     *
     * Excluding the origin is what makes the client's `hosts_changed` handler safe to treat as
     * "someone else changed the data": the origin already has the authoritative response and
     * refreshes once, instead of refreshing twice per save.
     *
     * The frame is encoded here, not by the sink, so a listener can only ever put a complete
     * `SshHostResponse` on the wire. Sending the bare event name was the earlier shape and it
     * silently broke every client: the frame arrived, failed to parse as JSON, and the change
     * notification was dropped — the UI only refreshed because it also polls after its own
     * mutations.
     */
    private suspend fun broadcast(
        event: String,
        origin: (suspend (SshHostResponse) -> Unit)?,
    ) {
        val snapshot = listenerLock.withLock { listeners.toList() }
        val recipients = if (origin == null) snapshot else snapshot.filter { it !== origin }
        if (recipients.isEmpty()) return
        val frame = SshHostResponse(ok = true, event = event)
        recipients.forEach { listener ->
            // One wedged peer must not delay the frame for the others, and its own eventual
            // close will drop it from the registry through `detach`.
            try {
                val accepted = withTimeoutOrNull(listenerSendTimeoutMs) { listener(frame) }
                if (accepted == null) {
                    logError("listener timed out while sending $event", TimeoutException())
                }
            } catch (t: Throwable) {
                logError("listener failed while sending $event", t)
            }
        }
    }

    /** Serve one connection until the peer closes it or the frame budget is exhausted. */
    suspend fun serve(session: DefaultWebSocketServerSession) {
        val sender: suspend (SshHostResponse) -> Unit = { frame -> reply(session, frame) }
        attach(sender)
        // Probes run on the connection's own job: closing the socket cancels them, so a
        // reconnecting client cannot leave handshakes dialling in the background.
        val scope = CoroutineScope(session.coroutineContext)
        val activeTests = AtomicInteger()
        val testJobs = mutableListOf<Job>()
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
                                // Even an undecodable frame usually still carries the id; echoing
                                // it turns a client-side timeout into an immediate, matchable error.
                                id = bestEffortId(text),
                                error = "bad_request",
                                message = e.message ?: "Malformed JSON",
                            ),
                        )
                        continue
                    }
                val op = request.op.trim().lowercase()
                if (op == SshHostOp.TEST) {
                    val rejection = startTest(request, op, sender, activeTests, scope, testJobs)
                    if (rejection != null) reply(session, rejection)
                    continue
                }
                val response = dispatch(request, origin = sender)
                reply(session, response)
            }
        } finally {
            // Cancel before detaching, so a probe that finishes later cannot try to write to
            // a socket that is already closing.
            testJobs.forEach { it.cancel() }
            detach(sender)
        }
    }

    /**
     * Start a `test` probe in the background.
     *
     * Returns the immediate rejection when the request cannot be started, or null once the
     * probe is running — its answer is a later frame carrying the same request id, which the
     * client matches like any other response. Deliberately not awaited here: a 30s SSH
     * handshake used to block every other op on the connection, including the second test the
     * user just clicked.
     */
    internal fun startTest(
        request: SshHostRequest,
        op: String,
        sink: suspend (SshHostResponse) -> Unit,
        activeTests: AtomicInteger,
        scope: CoroutineScope,
        testJobs: MutableList<Job>,
    ): SshHostResponse? {
        val name = request.name?.trim()
        if (name.isNullOrEmpty()) return fail(request, op, "missing_name")
        if (activeTests.get() >= maxConcurrentTests) {
            return fail(
                request,
                op,
                "too_many_tests",
                "At most $maxConcurrentTests tests may run at once",
            )
        }
        activeTests.incrementAndGet()
        // Completed jobs are only kept to be cancellable on disconnect; trim them so a long
        // lived connection does not accumulate one entry per test ever run.
        if (testJobs.size >= 32) testJobs.removeAll { it.isCompleted }
        val job = scope.launch {
            val frame = try {
                runTest(request, op)
            } catch (e: CancellationException) {
                // The connection is going away; let cancellation propagate.
                throw e
            } catch (e: Throwable) {
                logError("test $name failed", e)
                fail(request, op, "internal_error", e.message ?: e::class.simpleName)
            }
            runCatching { sink(frame) }
                .onFailure { logError("could not deliver the test result for $name", it) }
        }
        // Released on completion, not in the coroutine body: a job cancelled before its first
        // dispatch (socket closed right after the frame arrived) never runs the body at all, and
        // its slot would leak — after four such races the connection would refuse every test.
        job.invokeOnCompletion { activeTests.decrementAndGet() }
        testJobs += job
        return null
    }

    /**
     * The probe itself, shared by the socket's asynchronous path and [dispatch]'s synchronous
     * one so both report the same `not_found` shape.
     */
    private suspend fun runTest(request: SshHostRequest, op: String): SshHostResponse {
        val name = request.name?.trim()
        if (name.isNullOrEmpty()) return fail(request, op, "missing_name")
        val result = backend.test(name)
        return if (result == null) {
            fail(request, op, "not_found", "No host named $name")
        } else {
            SshHostResponse(id = request.id, op = op, ok = true, test = result)
        }
    }

    /**
     * Encode and send one frame. Throws [TimeoutException] when the peer stopped accepting bytes,
     * which unwinds [serve] and closes the connection instead of blocking it forever.
     */
    private suspend fun reply(session: DefaultWebSocketServerSession, response: SshHostResponse) {
        val text = json.encodeToString(SshHostResponse.serializer(), response)
        val delivered = withTimeoutOrNull(replySendTimeoutMs) { session.send(Frame.Text(text)) }
        if (delivered == null) {
            throw TimeoutException("peer did not accept a frame within ${replySendTimeoutMs}ms")
        }
    }

    /**
     * Execute [request]. Never throws: every failure becomes an error response, because a
     * thrown exception inside the frame loop would close the socket and a UI would have to
     * reconnect to learn that a name was taken.
     *
     * @param origin the sink the request arrived on, so change events are not echoed back to
     *   the client that caused them. Null (the default) broadcasts to everyone, which is what
     *   a caller outside a connection — a test, or a future timer — wants.
     */
    suspend fun dispatch(
        request: SshHostRequest,
        origin: (suspend (SshHostResponse) -> Unit)? = null,
    ): SshHostResponse {
        val op = request.op.trim().lowercase()
        return try {
            when (op) {
                SshHostOp.LIST -> SshHostResponse(
                    id = request.id,
                    op = op,
                    ok = true,
                    // Room returns rows in no promised order; a stable name sort keeps the
                    // list from reshuffling between the broadcast refresh and a manual one.
                    hosts = backend.list().sortedBy { it.name },
                )

                SshHostOp.PING -> SshHostResponse(id = request.id, op = op, ok = true)

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
                        if (saved.ok) broadcast(SshHostEvent.HOSTS_CHANGED, origin)
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
                        broadcast(SshHostEvent.HOSTS_CHANGED, origin)
                        SshHostResponse(id = request.id, op = op, ok = true)
                    }
                }

                // Reached only by callers outside a socket connection (tests, future REST
                // shims): serve() intercepts `test` and runs it off the frame loop.
                SshHostOp.TEST -> runTest(request, op)

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
                        // A removed key changes what the next connection trusts; the host
                        // list itself is untouched, so this is its own event.
                        if (removed > 0) broadcast(SshHostEvent.KNOWN_HOSTS_CHANGED, origin)
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

    /**
     * Pull the request id out of a frame that failed to decode. Best effort by definition: a frame
     * that is not JSON at all has nothing to match, and the client falls back to its timeout.
     */
    internal fun bestEffortId(text: String): String? = runCatching {
        val element = json.parseToJsonElement(text).jsonObject["id"]
        if (element == null || element is JsonNull) null
        else (element as? JsonPrimitive)?.content ?: element.toString()
    }.getOrNull()

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
                // `name` is the primary key, so a rename is a delete + insert — inside one Room
                // transaction, or a crash between the two halves loses the whole host.
                resolution.renamedFrom
                    ?.let { repository.rename(it, resolution.entity) }
                    ?: repository.upsert(resolution.entity)
                SshHostResponse(ok = true, host = resolution.entity.toDto())
            }
        }
    }

    // One statement instead of get-then-delete: a row deleted between the two calls used to be
    // reported as "deleted" (it was) or, worse, as not_found for a row that had just existed.
    override suspend fun delete(name: String): Boolean = repository.deleteByName(name) > 0

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
