package me.rerere.myagent.data.dap

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [DapAdapters]: which adapter a target selects, and that an explicit request
 * always wins.
 *
 * The important behaviour is the *ordering*: a `.go` file must not fall through to `gdb` just
 * because gdb is installed, and an `adapter` parameter must never be second-guessed.
 */
class DapAdaptersTest {

    @Test
    fun `go file prefers dlv`() {
        val candidates = DapAdapters.candidatesFor("/srv/main.go")
        assertEquals("dlv", candidates.first().name)
    }

    @Test
    fun `python file prefers debugpy`() {
        val candidates = DapAdapters.candidatesFor("/srv/app.py").map { it.name }
        assertTrue(candidates.contains("debugpy"))
    }

    @Test
    fun `explicit adapter wins even without an extension match`() {
        val spec = DapAdapters.select("/srv/binary", "dlv", setOf("gdb", "dlv"))
        assertEquals("dlv", spec?.name)
        assertEquals(JsonPrimitive("debug"), spec?.launchDefaults?.get("mode"))
    }

    @Test
    fun `unknown explicit adapter becomes a raw command`() {
        val spec = DapAdapters.select(null, "my-adapter --stdio", emptySet())
        assertEquals("my-adapter --stdio", spec?.command)
        assertTrue(spec!!.fileTypes.isEmpty())
    }

    @Test
    fun `extension match must be available on the host`() {
        assertNull(DapAdapters.select("/srv/main.go", null, setOf("gdb")))
        assertEquals("dlv", DapAdapters.select("/srv/main.go", null, setOf("gdb", "dlv"))?.name)
    }

    @Test
    fun `no program and no explicit adapter selects nothing`() {
        assertNull(DapAdapters.select(null, null, setOf("dlv")))
    }

    @Test
    fun `probe output parsing ignores noise`() {
        val parsed = DapAdapters.parseProbeOutput("dlv\n\n  gdb  \nnot an adapter\n")
        assertEquals(setOf("dlv", "gdb"), parsed)
    }

    @Test
    fun `unknown extension has no candidates`() {
        assertTrue(DapAdapters.candidatesFor("/srv/thing.xyz").isEmpty())
        assertTrue(DapAdapters.candidatesFor("/srv/Makefile").isEmpty())
    }
}
