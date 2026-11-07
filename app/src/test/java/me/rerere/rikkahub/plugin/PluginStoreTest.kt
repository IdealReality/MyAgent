package me.rerere.rikkahub.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var root: File

    private fun newStore(): PluginStore {
        root = tempFolder.newFolder("plugins-${System.nanoTime()}")
        return PluginStore(root).also { it.ensure() }
    }

    private val manifestJson = """
        {
          "schema_version": 1,
          "id": "demo",
          "name": "Demo",
          "version": "1.0.0",
          "entry": "main.js",
          "permissions": ["storage"],
          "prompt": {"text": "demo prompt"},
          "tools": [{"name": "demo_echo", "description": "echo", "parameters": {"type": "object", "properties": {}}}]
        }
    """.trimIndent()

    @Test
    fun `create then read back a plugin`() {
        store = newStore()
        val validation = store.createPlugin("demo", manifestJson, "plugin.tool('demo_echo', function(){return 'x'})")
        assertTrue(validation.render(), validation.ok)

        val source = store.readManifest("demo")
        assertNotNull(source)
        assertEquals("demo", source!!.manifest.id)
        assertEquals("demo_echo", source.manifest.tools.single().name)
        assertTrue(source.state.enabled)
        assertEquals("plugin.tool", store.readFile("demo", "main.js")!!.substringBefore("("))
    }

    @Test
    fun `manifest id must match directory name`() {
        store = newStore()
        val result = store.createPlugin("other", manifestJson, null)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("does not match") })
        assertFalse(store.dir("other").exists())
    }

    @Test
    fun `writes cannot escape the plugin directory`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "x")
        val result = store.writeFile("demo", "../evil.js", "boom")
        assertFalse(result.ok)
        assertFalse(File(root, "evil.js").exists())
        assertNull(store.readFile("demo", "../evil.js"))
    }

    @Test
    fun `failures accumulate and auto-disable at the cap`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "x")
        repeat(PluginStore.MAX_FAILURES - 1) { store.recordFailure("demo", "boom") }
        assertTrue(store.stateOf("demo").enabled)
        store.recordFailure("demo", "boom")
        assertFalse(store.stateOf("demo").enabled)
        assertEquals(PluginStore.MAX_FAILURES, store.stateOf("demo").failures)
        // Enabling again clears the counter, otherwise the next failure would instantly
        // re-disable the plugin the user just re-enabled.
        store.setEnabled("demo", true)
        store.clearFailures("demo")
        assertEquals(0, store.stateOf("demo").failures)
    }

    @Test
    fun `rollback restores the previous file content`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "version-1")
        store.writeFile("demo", "main.js", "version-2")
        assertEquals("version-2", store.readFile("demo", "main.js"))

        assertTrue(store.rollback("demo"))
        assertEquals("version-1", store.readFile("demo", "main.js"))
    }

    @Test
    fun `snapshots are pruned to the configured depth`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "v0")
        repeat(PluginStore.MAX_SNAPSHOTS + 3) { index ->
            store.writeFile("demo", "main.js", "v$index")
        }
        assertTrue(store.snapshots("demo").size <= PluginStore.MAX_SNAPSHOTS)
    }

    @Test
    fun `key value store is scoped per plugin`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "x")
        store.createPlugin("other", manifestJson.replace("\"id\": \"demo\"", "\"id\": \"other\""), "x")
        store.kvSet("demo", "k", "demo-value")
        assertEquals("demo-value", store.kvGet("demo", "k"))
        assertNull(store.kvGet("other", "k"))
    }

    @Test
    fun `signature changes when a plugin file changes`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "v1")
        val before = store.signature()
        Thread.sleep(5)
        store.writeFile("demo", "main.js", "version-two-longer")
        assertNotEquals(before, store.signature())
    }

    @Test
    fun `delete keeps a snapshot and disables the plugin`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "x")
        assertTrue(store.deletePlugin("demo"))
        assertFalse(store.dir("demo").exists())
        assertFalse(store.stateOf("demo").enabled)
        assertTrue(store.snapshots("demo").isNotEmpty())
        assertTrue(store.rollback("demo"))
        assertTrue(store.dir("demo").exists())
    }

    @Test
    fun `listing files reports relative posix paths`() {
        store = newStore()
        store.createPlugin("demo", manifestJson, "x")
        store.writeFile("demo", "assets/a.txt", "a")
        val files = store.listFiles("demo")
        assertTrue(files.contains("manifest.json"))
        assertTrue(files.contains("assets/a.txt"))
    }
}
