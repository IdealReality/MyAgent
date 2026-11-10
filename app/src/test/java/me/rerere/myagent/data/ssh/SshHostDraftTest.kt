package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostDraftTest {

    private fun validDraft() = SshHostDraft(
        name = "vps",
        host = "43.108.97.235",
        port = "22",
        user = "root",
        authMethod = SshAuthMethod.PASSWORD,
        password = "hunter2",
    )

    @Test
    fun `a complete new host has no errors`() {
        assertTrue(SshHostDraftValidator.isValid(validDraft(), emptySet()))
    }

    @Test
    fun `blank name is rejected`() {
        val errors = SshHostDraftValidator.validate(validDraft().copy(name = "   "), emptySet())
        assertEquals(SshHostError.NAME_BLANK, errors[SshHostField.NAME])
    }

    @Test
    fun `a new host may not reuse an existing name`() {
        val errors = SshHostDraftValidator.validate(validDraft(), setOf("vps"))
        assertEquals(SshHostError.NAME_TAKEN, errors[SshHostField.NAME])
    }

    @Test
    fun `editing a host without renaming is not a collision`() {
        val draft = validDraft().copy(originalName = "vps", host = "example.com")
        assertTrue(SshHostDraftValidator.isValid(draft, setOf("vps")))
    }

    @Test
    fun `renaming onto another host is a collision`() {
        val draft = validDraft().copy(originalName = "vps", name = "staging")
        val errors = SshHostDraftValidator.validate(draft, setOf("vps", "staging"))
        assertEquals(SshHostError.NAME_TAKEN, errors[SshHostField.NAME])
    }

    @Test
    fun `port must be an integer in range`() {
        val nonNumeric = SshHostDraftValidator.validate(validDraft().copy(port = "abc"), emptySet())
        assertEquals(SshHostError.PORT_INVALID, nonNumeric[SshHostField.PORT])

        val zero = SshHostDraftValidator.validate(validDraft().copy(port = "0"), emptySet())
        assertEquals(SshHostError.PORT_INVALID, zero[SshHostField.PORT])

        val tooBig = SshHostDraftValidator.validate(validDraft().copy(port = "65536"), emptySet())
        assertEquals(SshHostError.PORT_INVALID, tooBig[SshHostField.PORT])

        assertEquals(65535, validDraft().copy(port = " 65535 ").portOrNull())
        assertNull(validDraft().copy(port = "").portOrNull())
    }

    @Test
    fun `host and user are required`() {
        val noHost = SshHostDraftValidator.validate(validDraft().copy(host = " "), emptySet())
        assertEquals(SshHostError.HOST_BLANK, noHost[SshHostField.HOST])

        val noUser = SshHostDraftValidator.validate(validDraft().copy(user = ""), emptySet())
        assertEquals(SshHostError.USER_BLANK, noUser[SshHostField.USER])
    }

    @Test
    fun `password auth requires a password`() {
        val errors = SshHostDraftValidator.validate(validDraft().copy(password = ""), emptySet())
        assertEquals(SshHostError.CREDENTIAL_MISSING, errors[SshHostField.CREDENTIAL])
    }

    @Test
    fun `private key auth requires a key and ignores the password field`() {
        val keyDraft = validDraft().copy(
            authMethod = SshAuthMethod.PRIVATE_KEY,
            password = "",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nxxxx\n-----END OPENSSH PRIVATE KEY-----",
        )
        assertTrue(SshHostDraftValidator.isValid(keyDraft, emptySet()))

        val missing = SshHostDraftValidator.validate(
            keyDraft.copy(privateKey = "  "),
            emptySet(),
        )
        assertEquals(SshHostError.CREDENTIAL_MISSING, missing[SshHostField.CREDENTIAL])
    }

    @Test
    fun `isNew tracks whether there is an original name`() {
        assertTrue(validDraft().isNew)
        assertFalse(validDraft().copy(originalName = "vps").isNew)
    }

    @Test
    fun `names are trimmed before the collision check`() {
        val draft = validDraft().copy(name = "  vps  ")
        val errors = SshHostDraftValidator.validate(draft, setOf("vps"))
        assertEquals(SshHostError.NAME_TAKEN, errors[SshHostField.NAME])
    }

    @Test
    fun `known hosts parser reads patterns and key types`() {
        val text = """
            # comment
            43.108.97.235 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample

            [example.com]:2222 ssh-rsa AAAAB3NzaC1yc2Eexample
            @cert-authority *.internal ssh-ed25519 AAAAC3Nzexample
        """.trimIndent()

        val entries = parseKnownHosts(text)
        assertEquals(3, entries.size)
        assertEquals("43.108.97.235", entries[0].pattern)
        assertEquals("ssh-ed25519", entries[0].keyType)
        assertEquals("[example.com]:2222", entries[1].pattern)
        assertEquals("ssh-rsa", entries[1].keyType)
        assertEquals("*.internal", entries[2].pattern)
    }

    @Test
    fun `known hosts parser tolerates malformed and blank text`() {
        assertTrue(parseKnownHosts("").isEmpty())
        assertTrue(parseKnownHosts("\n\n   \n").isEmpty())
        assertTrue(parseKnownHosts("onlyonefield").isEmpty())
        assertEquals(1, parseKnownHosts("\thost ssh-ed25519 k").size)
    }

    @Test
    fun `hashed host entries are flagged`() {
        val entries = parseKnownHosts("|1|base64hash=|morebase64= ssh-ed25519 AAAAkey")
        assertEquals(1, entries.size)
        assertTrue(entries[0].isHashed)
        assertFalse(parseKnownHosts("plain-host ssh-ed25519 AAAAkey")[0].isHashed)
    }

    @Test
    fun `removing a host drops only its own lines`() {
        val text = """
            # my comments survive
            192.168.0.10 ssh-ed25519 AAAAkey
            192.168.0.10 ecdsa-sha2-nistp256 BBBBkey
            192.168.0.100 ssh-ed25519 CCCCkey
            [192.168.0.10]:2222 ssh-ed25519 DDDDkey

        """.trimIndent()
        val removal = removeKnownHostLines(text, "192.168.0.10")
        assertEquals(2, removal.removed)
        assertTrue(removal.text.contains("# my comments survive"))
        assertTrue(removal.text.contains("192.168.0.100"))
        assertTrue(removal.text.contains("[192.168.0.10]:2222"))
        assertFalse(removal.text.contains("AAAAkey"))
        assertFalse(removal.text.contains("BBBBkey"))
        // Unrelated entries are byte-for-byte identical, including line order and the blank tail.
        assertEquals(
            listOf("# my comments survive", "192.168.0.100 ssh-ed25519 CCCCkey", "[192.168.0.10]:2222 ssh-ed25519 DDDDkey", ""),
            removal.text.split("\n"),
        )
    }

    @Test
    fun `removing an absent host changes nothing`() {
        val text = "# c\nhost ssh-ed25519 K\n"
        val removal = removeKnownHostLines(text, "other")
        assertEquals(0, removal.removed)
        assertEquals(text, removal.text)
    }

    @Test
    fun `a hashed entry is not matched by the plain name it hides`() {
        // Nothing on disk can map |1|...= back to a hostname; a plain pattern must not match it.
        val text = "|1|abc=|def= ssh-ed25519 K"
        val removal = removeKnownHostLines(text, "example.com")
        assertEquals(0, removal.removed)
        assertEquals(text, removal.text)
    }

    @Test
    fun `markers are ignored when deciding which line to drop`() {
        val text = "@cert-authority ca.example.com ssh-ed25519 K\n@revoked host ssh-ed25519 K"
        // Both lines carry a host field of the second token; @revoked for `host` goes, the CA
        // entry for another host stays.
        val removal = removeKnownHostLines(text, "host")
        assertEquals(1, removal.removed)
        assertEquals("@cert-authority ca.example.com ssh-ed25519 K", removal.text)
    }

    @Test
    fun `only http and https urls are allowed`() {
        assertTrue(isAllowedTerminalUrl("http://192.168.0.10:7681"))
        assertTrue(isAllowedTerminalUrl("https://term.example.com/x?y=1"))
        assertTrue(isAllowedTerminalUrl("HTTP://UPPER.CASE"))
        // The value is handed to the in-app WebView, so anything that is not a web page must not
        // get through — the socket accepts this field from any authenticated client.
        assertFalse(isAllowedTerminalUrl("javascript:alert(1)"))
        assertFalse(isAllowedTerminalUrl("file:///data/data/com.myagent.app/files/known_hosts"))
        assertFalse(isAllowedTerminalUrl("intent://scan/#Intent;end"))
        assertFalse(isAllowedTerminalUrl("ftp://example.com"))
        assertFalse(isAllowedTerminalUrl("192.168.0.10:7681"))
        assertFalse(isAllowedTerminalUrl("http://"))
    }

    @Test
    fun `the editor flags a non-web terminal url`() {
        val base = SshHostDraft(name = "x", host = "h", user = "u", password = "p")
        assertEquals(
            emptyMap<SshHostField, SshHostError>(),
            SshHostDraftValidator.validate(base.copy(webTerminalUrl = "http://h:7681"), emptySet()),
        )
        assertEquals(
            mapOf(SshHostField.WEB_TERMINAL to SshHostError.WEB_TERMINAL_INVALID),
            SshHostDraftValidator.validate(base.copy(webTerminalUrl = "javascript:1"), emptySet()),
        )
    }
}
