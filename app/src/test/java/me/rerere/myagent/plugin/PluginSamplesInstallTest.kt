package me.rerere.myagent.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The install policy for plugins bundled in the APK.
 *
 * The rule that matters (and that the old "seed only into an empty directory" version got
 * wrong) is asymmetric: a directory that exists belongs to the user/model and must never be
 * touched, while a *missing* bundled plugin is an app capability that was never installed.
 * Getting either half backwards is silent — either plugins never arrive, or user edits get
 * clobbered on every launch — so both halves are pinned here.
 */
class PluginSamplesInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing bundled plugins are installed`() {
        val copied = mutableListOf<String>()
        val installed = PluginSamples.installMissingBundled(
            root = tmp.root,
            bundledIds = listOf("trpg", "sample-clock"),
            copy = { id ->
                copied += id
                File(tmp.root, id).mkdirs()
            },
        )

        assertEquals(listOf("trpg", "sample-clock"), installed)
        assertEquals(listOf("trpg", "sample-clock"), copied)
        assertTrue(File(tmp.root, "trpg").isDirectory)
    }

    @Test
    fun `an existing plugin directory is never overwritten`() {
        val existing = File(tmp.root, "trpg").apply { mkdirs() }
        File(existing, "main.js").writeText("user edit")
        val copied = mutableListOf<String>()

        val installed = PluginSamples.installMissingBundled(
            root = tmp.root,
            bundledIds = listOf("trpg", "sample-clock"),
            copy = { copied += it },
        )

        assertEquals(listOf("sample-clock"), installed)
        assertEquals(listOf("sample-clock"), copied)
        assertEquals("user edit", File(existing, "main.js").readText())
    }

    @Test
    fun `a file with the plugin id also counts as present`() {
        File(tmp.root, "trpg").writeText("not a directory")
        var called = false

        val installed = PluginSamples.installMissingBundled(
            root = tmp.root,
            bundledIds = listOf("trpg"),
            copy = { called = true },
        )

        assertTrue(installed.isEmpty())
        assertTrue("must not clobber a path it does not understand", !called)
    }

    @Test
    fun `no bundled ids is a no-op`() {
        assertTrue(PluginSamples.installMissingBundled(tmp.root, emptyList<String>()) { }.isEmpty())
    }
}
