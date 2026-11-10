package me.rerere.myagent.data.ssh

import kotlinx.serialization.json.Json
import me.rerere.myagent.data.db.entity.SshHostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The socket's save path is the only place a host can be created or edited from outside the
 * app, so its rules have to be provable without Android: a wrong `existing` merge silently
 * wipes a stored credential, and a wrong rename check silently forks a duplicate row.
 */
class SshHostProtocolTest {

    private fun upsert(
        name: String = "vps",
        host: String = "192.168.0.10",
        port: Int = 22,
        user: String = "root",
        authMethod: String = AUTH_PASSWORD,
        secret: SshHostSecretInput = SshHostSecretInput(password = "hunter2"),
        originalName: String? = null,
        webTerminalUrl: String? = null,
    ) = SshHostUpsert(
        originalName = originalName,
        name = name,
        host = host,
        port = port,
        user = user,
        authMethod = authMethod,
        webTerminalUrl = webTerminalUrl,
        secret = secret,
    )

    private fun entity(
        name: String = "vps",
        password: String? = "stored",
        privateKey: String? = null,
        passphrase: String? = null,
        createdAtMs: Long = 1_000L,
    ) = SshHostEntity(
        name = name,
        host = "10.0.0.1",
        port = 22,
        user = "root",
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        createdAtMs = createdAtMs,
    )

    @Test
    fun `a complete create resolves with the caller's values`() {
        val result = resolveSshHostUpsert(upsert(), existing = null, nameTaken = false, nowMs = 42L)
        val saved = result as SshHostResolution.Saved
        assertEquals("vps", saved.entity.name)
        assertEquals("192.168.0.10", saved.entity.host)
        assertEquals("hunter2", saved.entity.password)
        assertEquals(42L, saved.entity.createdAtMs)
        assertNull(saved.renamedFrom)
    }

    @Test
    fun `blank secret keeps what is stored`() {
        val result = resolveSshHostUpsert(
            upsert(secret = SshHostSecretInput(password = "")),
            existing = entity(password = "stored"),
            nameTaken = false,
            nowMs = 42L,
        )
        val saved = result as SshHostResolution.Saved
        assertEquals("stored", saved.entity.password)
    }

    @Test
    fun `omitted secret keeps what is stored`() {
        val result = resolveSshHostUpsert(
            upsert(secret = SshHostSecretInput()),
            existing = entity(password = "stored"),
            nameTaken = false,
            nowMs = 42L,
        )
        val saved = result as SshHostResolution.Saved
        assertEquals("stored", saved.entity.password)
    }

    @Test
    fun `editing keeps the original creation time`() {
        val result = resolveSshHostUpsert(
            upsert(originalName = "vps", user = "dsh"),
            existing = entity(createdAtMs = 1_000L),
            nameTaken = false,
            nowMs = 99_999L,
        )
        val saved = result as SshHostResolution.Saved
        assertEquals(1_000L, saved.entity.createdAtMs)
        assertEquals("dsh", saved.entity.user)
    }

    @Test
    fun `a rename reports the old name`() {
        val result = resolveSshHostUpsert(
            upsert(name = "renamed", originalName = "vps"),
            existing = entity(name = "vps"),
            nameTaken = false,
            nowMs = 42L,
        )
        assertEquals("vps", (result as SshHostResolution.Saved).renamedFrom)
    }

    @Test
    fun `re-saving under the same name is not a rename`() {
        val result = resolveSshHostUpsert(
            upsert(name = "vps", originalName = "vps"),
            existing = entity(),
            nameTaken = false,
            nowMs = 42L,
        )
        assertNull((result as SshHostResolution.Saved).renamedFrom)
    }

    @Test
    fun `a rename onto an occupied name is rejected`() {
        val result = resolveSshHostUpsert(
            upsert(name = "taken", originalName = "vps"),
            existing = entity(),
            nameTaken = true,
            nowMs = 42L,
        )
        val invalid = result as SshHostResolution.Invalid
        assertEquals("name_taken", invalid.errors[SshHostErrorField.NAME])
    }

    @Test
    fun `every missing required field is reported at once`() {
        val result = resolveSshHostUpsert(
            upsert(name = "  ", host = "", port = 0, user = "", secret = SshHostSecretInput()),
            existing = null,
            nameTaken = false,
            nowMs = 42L,
        )
        val invalid = result as SshHostResolution.Invalid
        assertEquals("name_blank", invalid.errors[SshHostErrorField.NAME])
        assertEquals("host_blank", invalid.errors[SshHostErrorField.HOST])
        assertEquals("port_invalid", invalid.errors[SshHostErrorField.PORT])
        assertEquals("user_blank", invalid.errors[SshHostErrorField.USER])
        assertEquals("credential_missing", invalid.errors[SshHostErrorField.SECRET])
    }

    @Test
    fun `a password host with only a stored key still needs a password`() {
        // The auth method selects which secret is required; a key left over from an earlier
        // configuration must not satisfy a password row.
        val result = resolveSshHostUpsert(
            upsert(authMethod = AUTH_PASSWORD, secret = SshHostSecretInput()),
            existing = entity(password = null, privateKey = "-----BEGIN-----"),
            nameTaken = false,
            nowMs = 42L,
        )
        val invalid = result as SshHostResolution.Invalid
        assertEquals("credential_missing", invalid.errors[SshHostErrorField.SECRET])
    }

    @Test
    fun `port bounds are inclusive`() {
        for (port in listOf(1, 22, 65535)) {
            val result = resolveSshHostUpsert(upsert(port = port), null, false, 42L)
            assertTrue("expected $port to be valid", result is SshHostResolution.Saved)
        }
        for (port in listOf(0, -1, 65536)) {
            val result = resolveSshHostUpsert(upsert(port = port), null, false, 42L)
            assertTrue("expected $port to be invalid", result is SshHostResolution.Invalid)
        }
    }

    @Test
    fun `auth method names a hand-written client may send`() {
        assertEquals(AUTH_PASSWORD, normalizeAuthMethod(""))
        assertEquals(AUTH_PASSWORD, normalizeAuthMethod("PASSWORD"))
        assertEquals(AUTH_PASSWORD, normalizeAuthMethod(" pwd "))
        assertEquals(AUTH_PRIVATE_KEY, normalizeAuthMethod("private-key"))
        assertEquals(AUTH_PRIVATE_KEY, normalizeAuthMethod("KEY"))
        assertNull(normalizeAuthMethod("kerberos"))
    }

    @Test
    fun `an unknown auth method is rejected instead of silently defaulting`() {
        val result = resolveSshHostUpsert(
            upsert(authMethod = "kerberos"),
            existing = null,
            nameTaken = false,
            nowMs = 42L,
        )
        val invalid = result as SshHostResolution.Invalid
        assertEquals("auth_method_invalid", invalid.errors[SshHostErrorField.AUTH_METHOD])
    }

    @Test
    fun `web terminal url is trimmed and blank becomes null`() {
        val withUrl = resolveSshHostUpsert(
            upsert(webTerminalUrl = "  http://h:7681  "), null, false, 42L,
        ) as SshHostResolution.Saved
        assertEquals("http://h:7681", withUrl.entity.webTerminalUrl)

        val blank = resolveSshHostUpsert(upsert(webTerminalUrl = "   "), null, false, 42L)
            as SshHostResolution.Saved
        assertNull(blank.entity.webTerminalUrl)
    }

    @Test
    fun `the dto never carries a secret value`() {
        val dto = entity(password = "hunter2", passphrase = "pp").toDto()
        assertTrue(dto.hasPassword)
        assertTrue(dto.hasPassphrase)
        assertFalse(dto.hasPrivateKey)
        assertEquals(AUTH_PASSWORD, dto.authMethod)
        // Nothing on the wire may contain the plaintext.
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            SshHostDto.serializer(), dto,
        )
        assertFalse(encoded.contains("hunter2"))
        assertFalse(encoded.contains("\"pp\""))
    }

    @Test
    fun `a host that stores a key reports the key method`() {
        val dto = entity(password = null, privateKey = "-----BEGIN-----").toDto()
        assertEquals(AUTH_PRIVATE_KEY, dto.authMethod)
        assertTrue(dto.hasPrivateKey)
        assertFalse(dto.hasPassword)
    }

    @Test
    fun `switching from a key to a password drops the key it would otherwise preempt`() {
        // The handshake tries publickey first. Keeping the old key would mean the password the
        // user just typed is never used — and a broken key (the usual reason for switching)
        // would keep failing the test while the password was correct.
        val result = resolveSshHostUpsert(
            upsert(authMethod = AUTH_PASSWORD, secret = SshHostSecretInput(password = "new")),
            existing = entity(password = null, privateKey = "-----BEGIN-----", passphrase = "pp"),
            nameTaken = false,
            nowMs = 42L,
        ) as SshHostResolution.Saved
        assertNull(result.entity.privateKey)
        assertNull(result.entity.passphrase)
        assertEquals("new", result.entity.password)
    }

    @Test
    fun `switching from a password to a key drops the password`() {
        val result = resolveSshHostUpsert(
            upsert(
                authMethod = AUTH_PRIVATE_KEY,
                secret = SshHostSecretInput(privateKey = "-----BEGIN-----"),
            ),
            existing = entity(password = "old"),
            nameTaken = false,
            nowMs = 42L,
        ) as SshHostResolution.Saved
        assertNull(result.entity.password)
        assertEquals("-----BEGIN-----", result.entity.privateKey)
    }

    // The socket's production Json always writes defaults, which is why a null id is on the wire
    // as `"id":null` rather than being omitted.
    private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a numeric id is accepted and echoed as text`() {
        // A hand-written client (or one whose integer type does not serialise as a string) used to
        // get the whole frame rejected as bad_request with no id, so it waited for its timeout.
        val request = wireJson.decodeFromString(SshHostRequest.serializer(), """{"id":7,"op":"ping"}""")
        assertEquals("7", request.id)

        val response = wireJson.encodeToString(SshHostResponse.serializer(), SshHostResponse(id = request.id))
        assertTrue(response, response.contains("\"id\":\"7\""))
        assertEquals(request.id, wireJson.decodeFromString(SshHostResponse.serializer(), response).id)
    }

    @Test
    fun `a null id stays null on the wire`() {
        val encoded = wireJson.encodeToString(SshHostResponse.serializer(), SshHostResponse(ok = true))
        assertTrue(encoded, encoded.contains("\"id\":null"))
        assertNull(wireJson.decodeFromString(SshHostResponse.serializer(), encoded).id)
    }

    @Test
    fun `a non-web terminal url is rejected with its field name`() {
        val invalid = resolveSshHostUpsert(
            upsert(webTerminalUrl = "javascript:alert(1)"),
            existing = null,
            nameTaken = false,
            nowMs = 42L,
        ) as SshHostResolution.Invalid
        assertEquals("web_terminal_invalid", invalid.errors[SshHostErrorField.WEB_TERMINAL])

        val saved = resolveSshHostUpsert(
            upsert(webTerminalUrl = " http://192.168.0.10:7681 "),
            existing = null,
            nameTaken = false,
            nowMs = 42L,
        ) as SshHostResolution.Saved
        assertEquals("http://192.168.0.10:7681", saved.entity.webTerminalUrl)
    }
}
