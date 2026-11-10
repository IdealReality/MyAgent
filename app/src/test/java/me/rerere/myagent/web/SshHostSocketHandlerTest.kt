package me.rerere.myagent.web

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import me.rerere.myagent.data.ssh.AUTH_PASSWORD
import me.rerere.myagent.data.ssh.KnownHostEntry
import me.rerere.myagent.data.ssh.SshHostDto
import me.rerere.myagent.data.ssh.SshHostEvent
import me.rerere.myagent.data.ssh.SshHostOp
import me.rerere.myagent.data.ssh.SshHostRequest
import me.rerere.myagent.data.ssh.SshHostResponse
import me.rerere.myagent.data.ssh.SshHostSecretInput
import me.rerere.myagent.data.ssh.SshHostTestResult
import me.rerere.myagent.data.ssh.SshHostUpsert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the socket's request/response contract without a server: what a client gets back for
 * each op, how failures are shaped, and that a mutation notifies other listeners exactly once.
 */
class SshHostSocketHandlerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class FakeBackend : SshHostBackend {
        val hosts = linkedMapOf<String, SshHostDto>()
        var failNextSave: SshHostResponse? = null
        var savedInputs = mutableListOf<SshHostUpsert>()
        var forgetCalls = mutableListOf<String>()
        var forgetResult = 1

        /** Lets a test observe that the socket answers `test` after the frame loop moved on. */
        var testDelayMs = 0L

        override suspend fun list(): List<SshHostDto> = hosts.values.toList()

        override suspend fun get(name: String): SshHostDto? = hosts[name]

        override suspend fun save(input: SshHostUpsert): SshHostResponse {
            savedInputs += input
            failNextSave?.let { failure ->
                failNextSave = null
                return failure
            }
            val dto = SshHostDto(
                name = input.name,
                host = input.host,
                port = input.port,
                user = input.user,
                authMethod = input.authMethod,
                hasPassword = !input.secret.password.isNullOrBlank(),
                hasPrivateKey = !input.secret.privateKey.isNullOrBlank(),
                hasPassphrase = !input.secret.passphrase.isNullOrBlank(),
                webTerminalUrl = input.webTerminalUrl,
                createdAtMs = 1L,
            )
            input.originalName?.takeIf { it != input.name }?.let { hosts.remove(it) }
            hosts[input.name] = dto
            return SshHostResponse(ok = true, host = dto)
        }

        override suspend fun delete(name: String): Boolean = hosts.remove(name) != null

        override suspend fun test(name: String): SshHostTestResult? {
            if (testDelayMs > 0) kotlinx.coroutines.delay(testDelayMs)
            return if (hosts.containsKey(name)) SshHostTestResult(ok = true) else null
        }

        override suspend fun knownHosts(): List<KnownHostEntry> =
            listOf(KnownHostEntry("192.168.0.10", "ssh-ed25519"))

        override suspend fun forgetHostKey(pattern: String): Int {
            forgetCalls += pattern
            return forgetResult
        }
    }

    private fun host(name: String = "vps") = SshHostDto(
        name = name,
        host = "192.168.0.10",
        port = 22,
        user = "root",
        authMethod = AUTH_PASSWORD,
        hasPassword = true,
        hasPrivateKey = false,
        hasPassphrase = false,
        createdAtMs = 1L,
    )

    private fun handler(backend: FakeBackend = FakeBackend()) =
        backend to SshHostSocketHandler(
            backend = backend,
            json = json,
            // android.util.Log throws in a plain JVM unit test; keep the loop's own failures
            // out of the assertion path.
            logError = { _, _ -> },
        )

    @Test
    fun `list returns every host`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["a"] = host("a")
        backend.hosts["b"] = host("b")

        val response = handler.dispatch(SshHostRequest(id = "1", op = SshHostOp.LIST))

        assertTrue(response.ok)
        assertEquals("1", response.id)
        assertEquals(listOf("a", "b"), response.hosts?.map { it.name })
    }

    @Test
    fun `get on a missing host is not_found`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest(id = "1", op = SshHostOp.GET, name = "nope"))
        assertFalse(response.ok)
        assertEquals("not_found", response.error)
        assertEquals("1", response.id)
    }

    @Test
    fun `get without a name is rejected before touching the backend`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest(op = SshHostOp.GET))
        assertFalse(response.ok)
        assertEquals("missing_name", response.error)
        assertNull(response.id)
    }

    @Test
    fun `save without a payload is rejected`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest(id = "9", op = SshHostOp.SAVE))
        assertFalse(response.ok)
        assertEquals("missing_host", response.error)
        assertEquals("9", response.id)
    }

    @Test
    fun `a successful save notifies other listeners once`() = runBlockingTest {
        val (_, handler) = handler()
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        val response = handler.dispatch(
            SshHostRequest(
                id = "1",
                op = SshHostOp.SAVE,
                host = SshHostUpsert(
                    name = "vps",
                    host = "192.168.0.10",
                    user = "root",
                    secret = SshHostSecretInput(password = "x"),
                ),
            ),
        )

        assertTrue(response.ok)
        assertEquals(listOf(SshHostEvent.HOSTS_CHANGED), events.map { it.event })
        assertEquals("1", response.id)
        assertEquals("vps", response.host?.name)
    }

    @Test
    fun `a broadcast frame is a serialisable response, not a bare event name`() = runBlockingTest {
        // Regression: the event used to be sent as the raw string `hosts_changed`, which every
        // client's JSON.parse rejected — the frame arrived and was dropped, so a second open
        // tab never refreshed. The invariant is that whatever listeners receive must encode to
        // the same JSON envelope as a normal response.
        val (_, handler) = handler()
        val received = mutableListOf<SshHostResponse>()
        handler.attach { received += it }

        handler.dispatch(
            SshHostRequest(
                op = SshHostOp.SAVE,
                host = SshHostUpsert(name = "vps", host = "h", user = "root"),
            ),
        )

        val encoded = json.encodeToString(SshHostResponse.serializer(), received.single())
        assertTrue(encoded.startsWith("{"))
        val decoded = json.decodeFromString(SshHostResponse.serializer(), encoded)
        assertEquals(SshHostEvent.HOSTS_CHANGED, decoded.event)
        assertNull(decoded.id)
    }

    @Test
    fun `a rejected save broadcasts nothing`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.failNextSave = SshHostResponse(
            ok = false,
            error = "invalid_host",
            errors = mapOf("name" to "name_taken"),
        )
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        val response = handler.dispatch(
            SshHostRequest(op = SshHostOp.SAVE, host = SshHostUpsert(name = "dup")),
        )

        assertFalse(response.ok)
        assertEquals("invalid_host", response.error)
        assertEquals("name_taken", response.errors?.get("name"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a change is not echoed to the connection that caused it`() = runBlockingTest {
        // Regression: the origin used to receive its own `hosts_changed` on top of the
        // response, so every save cost the originating tab two full `list` round trips.
        val (_, handler) = handler()
        val originFrames = mutableListOf<SshHostResponse>()
        val otherFrames = mutableListOf<SshHostResponse>()
        val origin: suspend (SshHostResponse) -> Unit = { originFrames += it }
        handler.attach(origin)
        handler.attach { otherFrames += it }

        val response = handler.dispatch(
            SshHostRequest(
                op = SshHostOp.SAVE,
                host = SshHostUpsert(name = "vps", host = "h", user = "root"),
            ),
            origin = origin,
        )

        assertTrue(response.ok)
        assertTrue(originFrames.isEmpty())
        assertEquals(listOf(SshHostEvent.HOSTS_CHANGED), otherFrames.map { it.event })
    }

    @Test
    fun `ping is answered without touching the backend`() = runBlockingTest {
        val broken = object : SshHostBackend by FakeBackend() {
            override suspend fun list(): List<SshHostDto> = throw IllegalStateException("db closed")
        }
        val handler = SshHostSocketHandler(backend = broken, json = json, logError = { _, _ -> })

        val response = handler.dispatch(SshHostRequest(id = "ping-1", op = SshHostOp.PING))

        assertTrue(response.ok)
        assertEquals("ping-1", response.id)
        assertEquals(SshHostOp.PING, response.op)
    }

    @Test
    fun `list is sorted by name so the UI cannot reshuffle`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["zulu"] = host("zulu")
        backend.hosts["alpha"] = host("alpha")
        backend.hosts["mike"] = host("mike")

        val response = handler.dispatch(SshHostRequest(op = SshHostOp.LIST))

        assertEquals(listOf("alpha", "mike", "zulu"), response.hosts?.map { it.name })
    }

    @Test
    fun `forgetting a host key broadcasts a known hosts change`() = runBlockingTest {
        val (backend, handler) = handler()
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        backend.forgetResult = 1
        handler.dispatch(SshHostRequest(op = SshHostOp.FORGET_HOST_KEY, pattern = "h"))

        assertEquals(listOf(SshHostEvent.KNOWN_HOSTS_CHANGED), events.map { it.event })
    }

    @Test
    fun `forgetting a key that matched nothing broadcasts nothing`() = runBlockingTest {
        val (backend, handler) = handler()
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        backend.forgetResult = 0
        handler.dispatch(SshHostRequest(op = SshHostOp.FORGET_HOST_KEY, pattern = "h"))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `test answers from a background coroutine, not the frame loop`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["vps"] = host()
        backend.testDelayMs = 50
        val frames = mutableListOf<SshHostResponse>()
        val jobs = mutableListOf<Job>()

        val immediate = handler.startTest(
            request = SshHostRequest(id = "t1", op = SshHostOp.TEST, name = "vps"),
            op = SshHostOp.TEST,
            sink = { frames += it },
            activeTests = AtomicInteger(),
            scope = this,
            testJobs = jobs,
        )

        // The request is accepted at once and answered later: that gap is what stops a 30s
        // handshake from blocking `list` behind it.
        assertNull(immediate)
        assertTrue(frames.isEmpty())

        jobs.forEach { it.join() }
        assertEquals("t1", frames.single().id)
        assertEquals(SshHostOp.TEST, frames.single().op)
        assertTrue(frames.single().test?.ok == true)
    }

    @Test
    fun `a fifth concurrent test is rejected without starting a probe`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["vps"] = host()
        val frames = mutableListOf<SshHostResponse>()

        val rejection = handler.startTest(
            request = SshHostRequest(op = SshHostOp.TEST, name = "vps"),
            op = SshHostOp.TEST,
            sink = { frames += it },
            activeTests = AtomicInteger(4),
            scope = this,
            testJobs = mutableListOf(),
        )

        assertEquals("too_many_tests", rejection?.error)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `a sink that throws while delivering a result is not fatal`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["vps"] = host()
        val jobs = mutableListOf<Job>()

        handler.startTest(
            request = SshHostRequest(op = SshHostOp.TEST, name = "vps"),
            op = SshHostOp.TEST,
            sink = { throw IllegalStateException("socket gone") },
            activeTests = AtomicInteger(),
            scope = this,
            testJobs = jobs,
        )
        jobs.forEach { it.join() }

        // A peer that vanished between the probe and the reply must not take the handler down.
        assertTrue(jobs.single().isCompleted)
        assertFalse(jobs.single().isCancelled)
    }

    @Test
    fun `a detached listener stops receiving events`() = runBlockingTest {
        val (_, handler) = handler()
        val events = mutableListOf<SshHostResponse>()
        val listener: suspend (SshHostResponse) -> Unit = { events += it }
        handler.attach(listener)
        handler.detach(listener)

        handler.dispatch(
            SshHostRequest(
                op = SshHostOp.SAVE,
                host = SshHostUpsert(name = "vps", host = "h", user = "root"),
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `one failing listener does not stop the others`() = runBlockingTest {
        val (_, handler) = handler()
        val received = mutableListOf<SshHostResponse>()
        handler.attach { throw IllegalStateException("boom") }
        handler.attach { received += it }

        handler.dispatch(
            SshHostRequest(
                op = SshHostOp.SAVE,
                host = SshHostUpsert(name = "vps", host = "h", user = "root"),
            ),
        )

        assertEquals(listOf(SshHostEvent.HOSTS_CHANGED), received.map { it.event })
    }

    @Test
    fun `delete of a missing host is not_found and does not broadcast`() = runBlockingTest {
        val (_, handler) = handler()
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        val response = handler.dispatch(SshHostRequest(op = SshHostOp.DELETE, name = "ghost"))

        assertFalse(response.ok)
        assertEquals("not_found", response.error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `delete of an existing host broadcasts`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["vps"] = host()
        val events = mutableListOf<SshHostResponse>()
        handler.attach { events += it }

        val response = handler.dispatch(SshHostRequest(op = SshHostOp.DELETE, name = "vps"))

        assertTrue(response.ok)
        assertEquals(listOf(SshHostEvent.HOSTS_CHANGED), events.map { it.event })
    }

    @Test
    fun `test reports the probe result and not_found for an unknown host`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.hosts["vps"] = host()

        val ok = handler.dispatch(SshHostRequest(op = SshHostOp.TEST, name = "vps"))
        assertTrue(ok.ok)
        assertTrue(ok.test?.ok == true)

        val missing = handler.dispatch(SshHostRequest(op = SshHostOp.TEST, name = "ghost"))
        assertFalse(missing.ok)
        assertEquals("not_found", missing.error)
    }

    @Test
    fun `forget_host_key calls through and reports the count`() = runBlockingTest {
        val (backend, handler) = handler()
        backend.forgetResult = 2

        val response = handler.dispatch(
            SshHostRequest(op = SshHostOp.FORGET_HOST_KEY, pattern = "192.168.0.10"),
        )

        assertTrue(response.ok)
        assertEquals(listOf("192.168.0.10"), backend.forgetCalls)
        assertEquals("removed 2 key(s)", response.message)
    }

    @Test
    fun `an unknown operation fails loudly`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest(op = "explode"))
        assertFalse(response.ok)
        assertEquals("unknown_op", response.error)
    }

    @Test
    fun `an empty operation is unknown rather than a default`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest())
        assertFalse(response.ok)
        assertEquals("unknown_op", response.error)
    }

    @Test
    fun `a backend exception becomes an error frame instead of killing the socket`() = runBlockingTest {
        val broken = object : SshHostBackend by FakeBackend() {
            override suspend fun list(): List<SshHostDto> = throw IllegalStateException("db closed")
        }
        val handler = SshHostSocketHandler(backend = broken, json = json, logError = { _, _ -> })

        val response = handler.dispatch(SshHostRequest(id = "7", op = SshHostOp.LIST))

        assertFalse(response.ok)
        assertEquals("internal_error", response.error)
        assertEquals("7", response.id)
        assertEquals("db closed", response.message)
    }

    @Test
    fun `op case and surrounding spaces are tolerated`() = runBlockingTest {
        val (_, handler) = handler()
        val response = handler.dispatch(SshHostRequest(op = "  LiSt  "))
        assertTrue(response.ok)
    }

    @Test
    fun `responses round-trip through the wire format`() {
        val response = SshHostResponse(
            id = "a",
            op = SshHostOp.LIST,
            ok = true,
            hosts = listOf(host()),
            knownHosts = listOf(KnownHostEntry("h", "ssh-ed25519")),
            test = SshHostTestResult(ok = false, errorCode = "auth_failed"),
        )
        val encoded = json.encodeToString(SshHostResponse.serializer(), response)
        val decoded = json.decodeFromString(SshHostResponse.serializer(), encoded)
        assertEquals(response, decoded)
    }

    @Test
    fun `a wedged listener does not delay the change event for the others`() = runBlockingTest {
        val handler = SshHostSocketHandler(
            backend = FakeBackend(),
            json = json,
            logError = { _, _ -> },
            listenerSendTimeoutMs = 50L,
        )
        val received = mutableListOf<SshHostResponse>()
        // A client whose connection is still open but no longer reading: `send` never returns.
        handler.attach { kotlinx.coroutines.delay(30_000) }
        handler.attach { received += it }

        val startedAt = System.currentTimeMillis()
        handler.dispatch(
            SshHostRequest(
                op = SshHostOp.SAVE,
                host = SshHostUpsert(name = "vps", host = "h", user = "root"),
            ),
        )
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(listOf(SshHostEvent.HOSTS_CHANGED), received.map { it.event })
        assertTrue("broadcast took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `a test job cancelled before it starts returns its slot`() = runBlockingTest {
        val (_, handler) = handler()
        val scopeJob = kotlinx.coroutines.Job()
        scopeJob.cancel()
        val scope = kotlinx.coroutines.CoroutineScope(scopeJob)
        val active = AtomicInteger()
        val jobs = mutableListOf<kotlinx.coroutines.Job>()

        val rejection = handler.startTest(
            SshHostRequest(id = "1", op = SshHostOp.TEST, name = "vps"),
            SshHostOp.TEST,
            sink = {},
            activeTests = active,
            scope = scope,
            testJobs = jobs,
        )

        assertNull(rejection)
        // Cancellation is delivered asynchronously; the slot must be back once the job settles.
        jobs.forEach { it.join() }
        // Four leaked slots would make the connection refuse every later test.
        assertEquals(0, active.get())
    }

    @Test
    fun `an undecodable frame still yields its id when there is one`() {
        // `name` must be a string; before the id was rescued, the client got bad_request with no
        // id and had to wait out its request timeout to learn anything at all.
        val (_, handler) = handler()
        assertEquals("42", handler.bestEffortId("""{"id":42,"op":"get","name":123}"""))
        assertEquals("abc", handler.bestEffortId("""{"id":"abc","op":"get","name":[]}"""))
        assertNull(handler.bestEffortId("""{"op":"get"}"""))
        assertNull(handler.bestEffortId("""{"id":null,"op":"get"}"""))
        assertNull(handler.bestEffortId("not json at all"))
    }

    @Test
    fun `a request decodes from the documented client shape`() {
        val decoded = json.decodeFromString(
            SshHostRequest.serializer(),
            """{"id":"1","op":"save","host":{"name":"vps","host":"h","port":2222,"user":"root","secret":{"password":"p"}}}""",
        )
        assertEquals("save", decoded.op)
        assertEquals(2222, decoded.host?.port)
        assertEquals("p", decoded.host?.secret?.password)
    }

    /** Minimal `runBlocking` stand-in so this test needs no coroutines-test dependency. */
    private fun runBlockingTest(block: suspend CoroutineScope.() -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
