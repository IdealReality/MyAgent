package me.rerere.myagent.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(
        id: String = "demo",
        withPrompt: Boolean = true,
        toolName: String = "demo_echo",
    ): String = buildString {
        append("{")
        append("\"schema_version\":1,\"id\":\"$id\",\"name\":\"Demo $id\",\"version\":\"1.0.0\",")
        append("\"entry\":\"main.js\",\"permissions\":[],")
        if (withPrompt) append("\"prompt\":{\"text\":\"prompt-$id\"},")
        append("\"tools\":[{\"name\":\"$toolName\",\"description\":\"echo\",")
        append("\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}]")
        append("}")
    }

    private class FakeExecutor : PluginExecutor {
        var nextResult: PluginInvocation = PluginInvocation(ok = true, text = "ok-result")
        val invoked = mutableListOf<String>()
        var inspectCalls = 0

        /** 钩子调用记录（`id:function`）与可替换结果。 */
        val hookCalls = mutableListOf<String>()
        var hookResult: PluginInvocation = PluginInvocation(ok = true, text = "hook-text")

        override suspend fun inspect(dir: File, manifest: PluginManifest): PluginInvocation {
            inspectCalls++
            return PluginInvocation(ok = true, registeredTools = manifest.tools.map { it.name })
        }

        override suspend fun invoke(
            dir: File,
            manifest: PluginManifest,
            toolName: String,
            argsJson: String,
        ): PluginInvocation {
            invoked += toolName
            return nextResult
        }

        override suspend fun invokeHook(
            dir: File,
            manifest: PluginManifest,
            hookName: String,
            argsJson: String,
        ): PluginInvocation {
            hookCalls += "${manifest.id}:$hookName"
            return hookResult
        }
    }

    private class Harness(
        val store: PluginStore,
        val executor: FakeExecutor,
        val registry: PluginRegistry,
    )

    private fun harness(): Harness {
        val root = tempFolder.newFolder("plugins-${System.nanoTime()}")
        val store = PluginStore(root)
        store.ensure()
        val executor = FakeExecutor()
        val registry = PluginRegistry(
            store = store,
            runtime = executor,
            json = json,
        )
        return Harness(store, executor, registry)
    }

    @Test
    fun `loads enabled plugin with tools and prompt`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")

        val snap = h.registry.reload()
        assertEquals(1, snap.plugins.size)
        assertEquals("demo", snap.plugins.single().manifest.id)
        assertTrue(h.registry.prompt().contains("prompt-demo"))

        val tool = h.registry.tools().single()
        assertEquals("demo_echo", tool.name)
        assertTrue(tool.description.contains("plugin:demo"))
        assertNotNull(tool.parameters())
    }

    @Test
    fun `disabled plugin contributes nothing`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.store.setEnabled("demo", false)

        val snap = h.registry.reload()
        assertTrue(snap.plugins.isEmpty())
        assertEquals("demo", snap.errors.single().id)
        assertTrue(snap.errors.single().message.contains("disabled"))
        assertEquals("", h.registry.prompt())
        assertTrue(h.registry.tools().isEmpty())
    }

    @Test
    fun `invalid manifest is reported without breaking other plugins`() = runBlocking {
        val h = harness()
        h.store.createPlugin("good", manifest(id = "good"), "// js")
        // Write a manifest whose id does not match its directory (the store refuses this
        // through createPlugin, so write the file directly to simulate a hand-edited dir).
        val broken = h.store.dir("broken")
        broken.mkdirs()
        File(broken, "manifest.json").writeText(manifest(id = "other"))
        File(broken, "main.js").writeText("// js")

        val snap = h.registry.reload()
        assertEquals(listOf("good"), snap.plugins.map { it.manifest.id })
        assertTrue(snap.errors.any { it.id == "broken" })
    }

    @Test
    fun `tool execution returns text and failure disables after the cap`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()
        val tool = h.registry.tools().single()

        val okParts = tool.execute(json.parseToJsonElement("""{"q":"hi"}"""))
        assertEquals("ok-result", (okParts.single() as UIMessagePart.Text).text)

        h.executor.nextResult = PluginInvocation(ok = false, error = "boom")
        repeat(PluginStore.MAX_FAILURES) {
            val parts = tool.execute(json.parseToJsonElement("""{"q":"hi"}"""))
            val text = (parts.single() as UIMessagePart.Text).text
            assertTrue(text, text.contains("plugin_tool_failed"))
        }
        // The failure counter lives in the store; a disabled plugin must disappear from the
        // next snapshot, which is what makes the loop self-limiting.
        assertFalse(h.store.stateOf("demo").enabled)
        assertTrue(h.registry.snapshot().plugins.isEmpty())
        assertEquals(3, h.executor.invoked.size - 1) // one success + three failures
    }

    @Test
    fun `snapshot is cached until the signature changes`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        val first = h.registry.reload()
        val second = h.registry.snapshot()
        assertEquals(first.loadedAt, second.loadedAt)

        Thread.sleep(5)
        h.store.writeFile("demo", "main.js", "// js changed with more bytes")
        val third = h.registry.snapshot()
        assertTrue(third.loadedAt >= first.loadedAt)
        assertEquals(1, third.plugins.size)
    }

    @Test
    fun `statuses expose loaded and broken entries`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()
        val statuses = h.registry.statuses()
        val demo = statuses.single { it.id == "demo" }
        assertEquals("Demo demo", demo.name)
        assertTrue(demo.enabled)
        assertEquals(listOf("demo_echo"), demo.declaredTools)
        assertTrue(demo.promptChars > 0)
        assertNull(demo.loadError)
    }

    @Test
    fun `inspect reports registered tools`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        val report = h.registry.inspect("demo")
        assertTrue(report, report.startsWith("ok:"))
        assertTrue(report.contains("registered: demo_echo"))
        assertEquals(1, h.executor.inspectCalls)
    }

    @Test
    fun `admin tools cover the self-modification surface`() {
        val h = harness()
        val names = pluginAdminTools(h.registry, h.store, json).map { it.name }.toSet()
        assertEquals(
            setOf(
                "plugin_list", "plugin_read", "plugin_write", "plugin_create", "plugin_check",
                "plugin_set_enabled", "plugin_delete", "plugin_rollback", "plugin_reload",
            ),
            names,
        )
    }

    @Test
    fun `plugin_write rejects traversal and reports validation`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        val write = pluginAdminTools(h.registry, h.store, json).single { it.name == "plugin_write" }
        val bad = write.execute(
            json.parseToJsonElement(
                """{"plugin_id":"demo","path":"../../escape.js","content":"x"}"""
            )
        )
        val badText = (bad.single() as UIMessagePart.Text).text
        assertTrue(badText, badText.contains("unsafe"))

        val good = write.execute(
            json.parseToJsonElement(
                """{"plugin_id":"demo","path":"main.js","content":"// updated"}"""
            )
        )
        val goodJson = json.parseToJsonElement((good.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ok", goodJson["validation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ui panel html is readable and surfaced in status`() = runBlocking {
        val h = harness()
        val withPanel = manifest().replace(
            "\"tools\":[",
            "\"ui\":{\"panel\":\"panel.html\",\"title\":\"Demo Panel\"},\"tools\":[",
        )
        h.store.createPlugin("demo", withPanel, "// js")
        h.store.writeFile("demo", "panel.html", "<h1>panel</h1>")
        h.registry.reload()

        assertEquals("<h1>panel</h1>", h.registry.panelHtml("demo"))
        assertEquals("Demo Panel", h.registry.panelTitleOf("demo"))
        val status = h.registry.statuses().single { it.id == "demo" }
        assertEquals("panel.html", status.panel)
        assertEquals("Demo Panel", status.panelTitle)
    }

    @Test
    fun `plugin without ui exposes no panel`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()
        assertNull(h.registry.panelHtml("demo"))
        // 没有 ui.title 时退回插件名，界面不会显示一个空标题。
        assertEquals("Demo demo", h.registry.panelTitleOf("demo"))
        assertNull(h.registry.statuses().single { it.id == "demo" }.panel)
    }

    @Test
    fun `panel cannot point outside the plugin directory`() = runBlocking {
        val h = harness()
        val escaping = manifest().replace(
            "\"tools\":[",
            "\"ui\":{\"panel\":\"../evil.html\"},\"tools\":[",
        )
        val validation = h.store.createPlugin("demo", escaping, "// js")
        assertFalse(validation.ok)
        assertTrue(validation.render(), validation.render().contains("must not contain '..'"))
        h.registry.reload()
        // 校验失败 → 不加载；面板读不到也不会越界读文件。
        assertNull(h.registry.panelHtml("demo"))
    }

    private fun manifestWithHook(id: String, function: String = "beforeTurn"): String =
        manifest(id).replace("\"tools\":[", "\"hooks\":{\"before_turn\":\"$function\"},\"tools\":[")

    @Test
    fun `before_turn hook runs only for enabled plugins that declare it`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifestWithHook("demo"), "// js")
        h.store.createPlugin("plain", manifest("plain"), "// js")
        h.registry.reload()

        val out = h.registry.runHooks("before_turn")
        assertTrue(out, out.contains("### Hook: Demo demo (before_turn)"))
        assertTrue(out, out.contains("hook-text"))
        assertEquals(listOf("demo:beforeTurn"), h.executor.hookCalls)
    }

    @Test
    fun `no declared hook means no executor work at all`() = runBlocking {
        val h = harness()
        h.store.createPlugin("plain", manifest("plain"), "// js")
        h.registry.reload()
        assertEquals("", h.registry.runHooks("before_turn"))
        assertTrue(h.executor.hookCalls.isEmpty())
    }

    @Test
    fun `disabled plugin hooks are skipped`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifestWithHook("demo"), "// js")
        h.registry.reload()
        h.store.setEnabled("demo", false)
        h.registry.reload()

        assertEquals("", h.registry.runHooks("before_turn"))
        assertTrue(h.executor.hookCalls.isEmpty())
    }

    @Test
    fun `hook failure is swallowed and does not disable the plugin`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifestWithHook("demo"), "// js")
        h.registry.reload()
        h.executor.hookResult = PluginInvocation(ok = false, error = "boom")

        assertEquals("", h.registry.runHooks("before_turn"))
        assertTrue("hook failures must not auto-disable", h.store.stateOf("demo").enabled)
        assertEquals(0, h.store.stateOf("demo").failures)
    }

    @Test
    fun `blank hook output is not injected`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifestWithHook("demo"), "// js")
        h.registry.reload()
        h.executor.hookResult = PluginInvocation(ok = true, text = "   \n ")

        assertEquals("", h.registry.runHooks("before_turn"))
    }

    @Test
    fun `one plugin can expose several tools at once`() = runBlocking {
        val h = harness()
        val two = """{"schema_version":1,"id":"demo","name":"Demo demo","version":"1.0.0",
            "entry":"main.js","permissions":[],"tools":[
            {"name":"demo_a","description":"a","parameters":{"type":"object","properties":{}}},
            {"name":"demo_b","description":"b","parameters":{"type":"object","properties":{}}}]}"""
        h.store.createPlugin("demo", two, "// js")
        h.registry.reload()

        assertEquals(setOf("demo_a", "demo_b"), h.registry.tools().map { it.name }.toSet())
    }

    @Test
    fun `tool names stay globally unique across plugins`() = runBlocking {
        val h = harness()
        h.store.createPlugin("aaa", manifest(id = "aaa", toolName = "same_tool"), "// js")
        h.store.createPlugin("bbb", manifest(id = "bbb", toolName = "same_tool"), "// js")
        h.registry.reload()

        // 同名工具只保留先注册的那个（按插件 id 排序），后一个被跳过而不是覆盖。
        assertEquals(listOf("same_tool"), h.registry.tools().map { it.name })
    }
}
