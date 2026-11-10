package me.rerere.myagent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeAttachmentFilenameTest {

    // ---- normal filename ----

    @Test
    fun `normal filename passes through unchanged`() {
        assertEquals("Invoice.pdf", sanitizeAttachmentFilename("Invoice.pdf", "abc123", "pdf"))
    }

    @Test
    fun `filename with spaces is preserved`() {
        assertEquals("Invoice March 2026.pdf", sanitizeAttachmentFilename("Invoice March 2026.pdf", "abc123", "pdf"))
    }

    // ---- path traversal ----

    @Test
    fun `path separators stripped — only basename kept`() {
        assertEquals("passwd", sanitizeAttachmentFilename("../../etc/passwd", "abc123", "bin"))
    }

    @Test
    fun `windows path separators stripped`() {
        assertEquals("secret.txt", sanitizeAttachmentFilename("..\\..\\secret.txt", "abc123", "txt"))
    }

    @Test
    fun `absolute unix path — only last component`() {
        assertEquals("shadow", sanitizeAttachmentFilename("/etc/shadow", "abc123", "bin"))
    }

    // ---- leading dots ----

    @Test
    fun `leading dots stripped`() {
        val result = sanitizeAttachmentFilename(".hidden", "abc123", "txt")
        assertFalse("Result should not start with dot: $result", result.startsWith('.'))
    }

    @Test
    fun `leading dots stripped from traversal component`() {
        val result = sanitizeAttachmentFilename("../../.bashrc", "abc123", "txt")
        // After stripping separators we get ".bashrc", then strip leading dot -> "bashrc"
        assertFalse(result.startsWith('.'))
    }

    // ---- control characters ----

    @Test
    fun `control characters stripped`() {
        val withControl = "file\u0000name.txt"
        val result = sanitizeAttachmentFilename(withControl, "abc123", "txt")
        assertFalse(result.contains('\u0000'))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `null byte stripped`() {
        val withNull = "evil\u0000.pdf"
        val result = sanitizeAttachmentFilename(withNull, "abc123", "pdf")
        assertFalse(result.contains('\u0000'))
    }

    // ---- empty / blank after sanitization — fallback ----

    @Test
    fun `null raw name uses fallback`() {
        val result = sanitizeAttachmentFilename(null, "ABCDEFGH", "ogg")
        assertTrue("Fallback should end with .ogg: $result", result.endsWith(".ogg"))
        assertTrue("Fallback should contain file_id suffix", result.contains("ABCDEFGH"))
    }

    @Test
    fun `blank raw name uses fallback`() {
        val result = sanitizeAttachmentFilename("   ", "XYZABC12", "mp4")
        assertTrue(result.endsWith(".mp4"))
    }

    @Test
    fun `only dots raw name uses fallback`() {
        // "..." → stripped of leading dots → "" → fallback
        val result = sanitizeAttachmentFilename("...", "SUFFIX12", "bin")
        assertTrue(result.endsWith(".bin"))
        assertTrue(result.contains("SUFFIX12"))
    }

    // ---- very long names truncated ----

    @Test
    fun `name longer than 200 chars is truncated`() {
        val longBase = "a".repeat(250)
        val raw = "$longBase.pdf"
        val result = sanitizeAttachmentFilename(raw, "abc123", "pdf")
        assertTrue("Result length ${result.length} should be <= 200", result.length <= 200)
        assertTrue("Extension preserved", result.endsWith(".pdf"))
    }

    @Test
    fun `name exactly 200 chars is not truncated`() {
        // 196 chars + ".pdf" = 200 exactly
        val raw = "a".repeat(196) + ".pdf"
        assertEquals(200, raw.length)
        val result = sanitizeAttachmentFilename(raw, "abc123", "pdf")
        assertEquals(200, result.length)
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun `very long name with no extension is truncated to 200`() {
        val raw = "a".repeat(300)
        val result = sanitizeAttachmentFilename(raw, "abc123", "bin")
        assertTrue(result.length <= 200)
    }
}
