package me.rerere.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandTest {

    @Test
    fun `plain words stay unquoted`() {
        assertEquals("qemu-system-x86_64", "qemu-system-x86_64".shellQuote())
        assertEquals("-m", "-m".shellQuote())
        assertEquals("/usr/bin/qemu-system-x86_64", "/usr/bin/qemu-system-x86_64".shellQuote())
    }

    @Test
    fun `commas and colons are safe inside a single arg`() {
        val arg = "user,id=net0,hostfwd=tcp:127.0.0.1:5555-:5555"
        assertEquals(arg, arg.shellQuote())
    }

    @Test
    fun `empty and spaces get quoted`() {
        assertEquals("''", "".shellQuote())
        assertEquals("'a b'", "a b".shellQuote())
    }

    @Test
    fun `single quotes are escaped`() {
        assertEquals("'it'\\''s'", "it's".shellQuote())
    }

    @Test
    fun `shell metacharacters cannot escape`() {
        val evil = "x; rm -rf / #\$(id)"
        val quoted = evil.shellQuote()
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertEquals(evil, quoted.removeSurrounding("'").replace("'\\''", "'"))
    }

    @Test
    fun `joined command separates args`() {
        assertEquals("-m 2048", listOf("-m", "2048").toShellCommand())
    }
}
