package me.rerere.myagent.data.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LspSpecs]: file→server routing, language ids, file URIs and the remote
 * probe script.
 *
 * URI encoding gets its own attention because a wrong one is not a visible crash: the server
 * simply reports "file not found" or, worse, opens a *different* file with the same prefix.
 */
class LspSpecsTest {

    @Test
    fun `extension and basename handling works for POSIX and Windows style paths`() {
        assertEquals("Main.kt", LspSpecs.baseName("/srv/proj/src/Main.kt"))
        assertEquals("Main.kt", LspSpecs.baseName("C:\\proj\\Main.kt"))
        assertEquals("kt", LspSpecs.extensionOf("/srv/Main.kt"))
        assertEquals("", LspSpecs.extensionOf("/srv/Dockerfile"))
        assertEquals("", LspSpecs.extensionOf("/srv/trailing."))
    }

    @Test
    fun `go file routes to gopls`() {
        val candidates = LspSpecs.candidatesFor("/srv/app/main.go")
        assertTrue(candidates.any { it.name == "gopls" })
        assertEquals("gopls", candidates.first().name)
    }

    @Test
    fun `python prefers pyright over pylsp`() {
        val names = LspSpecs.candidatesFor("/srv/script.py").map { it.name }
        assertEquals(listOf("pyright", "pylsp"), names)
    }

    @Test
    fun `dockerfile matches by exact basename`() {
        val candidates = LspSpecs.candidatesFor("/srv/app/Dockerfile")
        assertTrue(candidates.any { it.name == "docker-langserver" })
    }

    @Test
    fun `unknown extension has no server and plaintext language id`() {
        assertTrue(LspSpecs.candidatesFor("/srv/notes.xyz").isEmpty())
        assertEquals("plaintext", LspSpecs.languageIdFor("/srv/notes.xyz"))
    }

    @Test
    fun `language id follows the extension and server overrides`() {
        assertEquals("kotlin", LspSpecs.languageIdFor("/a/Main.kt"))
        assertEquals("cpp", LspSpecs.languageIdFor("/a/lib.hpp"))
        assertEquals("dockerfile", LspSpecs.languageIdFor("/a/Dockerfile"))
        assertEquals("shellscript", LspSpecs.languageIdFor("/a/run.sh"))
    }

    @Test
    fun `file uri round trips spaces unicode plus and hash`() {
        val paths = listOf(
            "/srv/a b/Main.kt",
            "/srv/项目/文件.kt",
            "/srv/weird+a#b?c.kt",
            "/srv/100%/x.go",
        )
        for (path in paths) {
            val uri = LspSpecs.pathToUri(path)
            assertTrue("uri must start with file://: $uri", uri.startsWith("file:///"))
            assertEquals(path, LspSpecs.uriToPath(uri))
        }
    }

    @Test
    fun `uri decoding keeps plus literal and strips query and fragment`() {
        assertEquals("/a+b/c", LspSpecs.uriToPath("file:///a+b/c"))
        assertEquals("/a/c.kt", LspSpecs.uriToPath("file:///a/c.kt#L10"))
        assertEquals("/a/c.kt", LspSpecs.uriToPath("file:///a/c.kt?x=1"))
    }

    @Test
    fun `probe script quotes the root and never embeds a bare quote`() {
        val script = LspSpecs.probeScript("/srv/it's here", LspSpecs.candidatesFor("/srv/x.go"))
        assertTrue(script.startsWith("cd '/srv/it'\\''s here' 2>/dev/null || true"))
        // Inner tokens are shell-expanded by the remote shell, not by Kotlin.
        assertTrue(script.contains("\"\$PWD/node_modules/.bin/\$c\""))
        assertTrue(script.contains("command -v \"\$p\""))
    }

    @Test
    fun `probe output parses name and resolved path`() {
        val output = "clangd\t/usr/bin/clangd\ngopls\t/srv/p/bin/gopls\n\nbroken line\n"
        val parsed = LspSpecs.parseProbeOutput(output)
        assertEquals("/usr/bin/clangd", parsed["clangd"])
        assertEquals("/srv/p/bin/gopls", parsed["gopls"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `command line quotes every token`() {
        val spec = LspSpecs.builtIn.first { it.name == "pyright" }
        assertEquals("'pyright-langserver' '--stdio'", spec.commandLine)
    }

    @Test
    fun `built-in server names are unique`() {
        val names = LspSpecs.builtIn.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertFalse(LspSpecs.builtIn.any { it.command.isBlank() })
    }
}
