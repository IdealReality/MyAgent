package me.rerere.myagent.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 槽位与 `after_tool` 的运行时行为：查得到、跑得动、失败不外溢、没有声明时零成本。
 */
class PluginSlotActionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeExecutor : PluginExecutor {
        val actionCalls = mutableListOf<String>()
        val hookCalls = mutableListOf<String>()
        var actionResult: PluginInvocation = PluginInvocation(ok = true, text = """{"insert":"hi"}""")
        var hookResult: PluginInvocation = PluginInvocation(ok = true, text = "rewritten")
        /** 按插件 id 指定 after_tool 输出；未命中时回落到 [hookResult]。 */
        val hookByPlugin = mutableMapOf<String, String>()

        override suspend fun inspect(dir: File, manifest: PluginManifest): PluginInvocation =
            PluginInvocation(ok = true, registeredTools = manifest.tools.map { it.name })

        override suspend fun invoke(
            dir: File,
            manifest: PluginManifest,
            toolName: String,
            argsJson: String,
        ): PluginInvocation = PluginInvocation(ok = true, text = "tool")

        override suspend fun invokeHook(
            dir: File,
            manifest: PluginManifest,
            hookName: String,
            argsJson: String,
        ): PluginInvocation {
            hookCalls += "${manifest.id}:$hookName"
            val text = hookByPlugin[manifest.id]
            return if (text != null) hookResult.copy(text = text) else hookResult
        }

        override suspend fun invokeAction(
            dir: File,
            manifest: PluginManifest,
            actionName: String,
            argsJson: String,
        ): PluginInvocation {
            actionCalls += "${manifest.id}:$actionName"
            return actionResult
        }
    }

    private class Harness(val store: PluginStore, val executor: FakeExecutor, val registry: PluginRegistry)

    private fun harness(): Harness {
        val root = tempFolder.newFolder("plugins-${System.nanoTime()}")
        val store = PluginStore(root)
        store.ensure()
        val executor = FakeExecutor()
        val registry = PluginRegistry(store = store, runtime = executor, json = json)
        return Harness(store, executor, registry)
    }

    private fun manifest(
        id: String = "demo",
        slots: String = """[{"where":"composer","type":"chips","items":[{"label":"Go","action":"go"}]}]""",
        hooks: String? = null,
        withPanel: Boolean = false,
    ): String = buildString {
        append("""{"schema_version":1,"id":"$id","name":"Demo $id","version":"1.0.0","entry":"main.js","permissions":[],""")
        append(""""ui":{"panel":${if (withPanel) "\"panel.html\"" else "\"\""},"title":"Demo","slots":$slots}""")
        if (hooks != null) append(""","hooks":$hooks""")
        append("}")
    }

    @Test
    fun `slots are filtered by where and carry their html`() = runBlocking {
        val h = harness()
        h.store.createPlugin(
            "demo",
            manifest(
                slots = """[
                    {"where":"composer","type":"chips","items":[{"label":"Go","action":"go"}]},
                    {"where":"message","type":"html","file":"card.html","height":80}
                ]""",
            ),
            "// js",
        )
        h.store.writeFile("demo", "card.html", "<div>card</div>")

        val composer = h.registry.slots(PluginSlotWhere.COMPOSER)
        assertEquals(1, composer.size)
        assertEquals("demo", composer.single().pluginId)
        assertNull(composer.single().html)

        val message = h.registry.slots(PluginSlotWhere.MESSAGE)
        assertEquals(1, message.size)
        assertEquals("<div>card</div>", message.single().html)
        assertEquals(80, message.single().spec.height)
    }

    @Test
    fun `disabled plugin contributes no slots`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.store.setEnabled("demo", false)
        h.registry.reload()
        assertTrue(h.registry.slots(PluginSlotWhere.COMPOSER).isEmpty())
    }

    @Test
    fun `slot action result is parsed into ui intent`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        h.executor.actionResult = PluginInvocation(
            ok = true,
            text = """{"insert":"hello","toast":"done","send":"go","panel":""}""",
        )
        val result = h.registry.runSlotAction("demo", "go", "{}")
        assertTrue(result.ok)
        assertEquals("hello", result.insert)
        assertEquals("done", result.toast)
        assertEquals("go", result.send)
        assertEquals("demo", result.panel)
    }

    @Test
    fun `undeclared action is refused without touching the sandbox`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        val result = h.registry.runSlotAction("demo", "not_declared", "{}")
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("not declared"))
        assertTrue(h.executor.actionCalls.isEmpty())
    }

    @Test
    fun `failed action surfaces error and keeps plugin enabled`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        h.executor.actionResult = PluginInvocation(ok = false, error = "boom")
        val result = h.registry.runSlotAction("demo", "go", "{}")
        assertFalse(result.ok)
        assertEquals("boom", result.error)
        assertTrue(h.store.stateOf("demo").enabled)
        assertEquals(0, h.store.stateOf("demo").failures)
    }

    @Test
    fun `html slot can declare the action its page calls`() = runBlocking {
        val h = harness()
        h.store.createPlugin(
            "demo",
            manifest(
                slots = """[{"where":"message","type":"html","file":"card.html","items":[{"action":"refresh"}]}]""",
            ),
            "// js",
        )
        h.store.writeFile("demo", "card.html", "<div>card</div>")
        h.registry.reload()

        h.executor.actionResult = PluginInvocation(ok = true, text = """{"text":"payload"}""")
        val result = h.registry.runSlotAction("demo", "refresh", "{}")
        assertTrue(result.ok)
        assertEquals("payload", result.text)
    }

    @Test
    fun `after_tool hook replaces the output`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(hooks = """{"after_tool":"afterTool"}"""), "// js")
        h.registry.reload()

        val out = h.registry.runAfterToolHook("shell", """{"cmd":"ls"}""", "raw output")
        assertEquals("rewritten", out)
        assertEquals(listOf("demo:afterTool"), h.executor.hookCalls)
    }

    @Test
    fun `after_tool blank result keeps the original text`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(hooks = """{"after_tool":"afterTool"}"""), "// js")
        h.registry.reload()

        h.executor.hookResult = PluginInvocation(ok = true, text = "   ")
        assertEquals("raw output", h.registry.runAfterToolHook("shell", "{}", "raw output"))
    }

    @Test
    fun `no after_tool declaration means no js work`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        assertEquals("raw output", h.registry.runAfterToolHook("shell", "{}", "raw output"))
        assertTrue(h.executor.hookCalls.isEmpty())
    }

    @Test
    fun `statuses expose slot summary`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        assertEquals(listOf("composer:chips"), h.registry.statuses().single().slots)
    }

    @Test
    fun `slot html cache picks up an edited file`() = runBlocking {
        val h = harness()
        h.store.createPlugin(
            "demo",
            manifest(slots = """[{"where":"message","type":"html","file":"card.html","items":[{"action":"go"}]}]"""),
            "// js",
        )
        h.store.writeFile("demo", "card.html", "<div>v1</div>")
        assertEquals("<div>v1</div>", h.registry.slots(PluginSlotWhere.MESSAGE).single().html)

        // 第二次读命中缓存（内容一致），改文件后必须立刻失效。
        assertEquals("<div>v1</div>", h.registry.slots(PluginSlotWhere.MESSAGE).single().html)
        h.store.writeFile("demo", "card.html", "<div>version-two</div>")
        assertEquals("<div>version-two</div>", h.registry.slots(PluginSlotWhere.MESSAGE).single().html)
    }

    @Test
    fun `action panel target must be a plugin id`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        h.registry.reload()

        h.executor.actionResult = PluginInvocation(ok = true, text = """{"panel":"Bad/Id"}""")
        assertNull(h.registry.runSlotAction("demo", "go", "{}").panel)

        h.executor.actionResult = PluginInvocation(ok = true, text = """{"panel":"other"}""")
        assertEquals("other", h.registry.runSlotAction("demo", "go", "{}").panel)

        h.executor.actionResult = PluginInvocation(ok = true, text = """{"panel":""}""")
        assertEquals("demo", h.registry.runSlotAction("demo", "go", "{}").panel)
    }

    @Test
    fun `after_tool hooks chain across plugins`() = runBlocking {
        val h = harness()
        h.store.createPlugin("a", manifest(id = "a", hooks = """{"after_tool":"afterTool"}"""), "// js")
        h.store.createPlugin("b", manifest(id = "b", hooks = """{"after_tool":"afterTool"}"""), "// js")
        h.registry.reload()

        h.executor.hookByPlugin["a"] = "A(raw)"
        h.executor.hookByPlugin["b"] = "B(A(raw))"
        assertEquals("B(A(raw))", h.registry.runAfterToolHook("shell", "{}", "raw"))
    }

    @Test
    fun `after_tool output is capped`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(hooks = """{"after_tool":"afterTool"}"""), "// js")
        h.registry.reload()

        h.executor.hookResult = PluginInvocation(ok = true, text = "x".repeat(100_000))
        val out = h.registry.runAfterToolHook("shell", "{}", "raw")
        assertEquals(PluginRegistry.MAX_TOOL_HOOK_CHARS, out.length)
    }

    @Test
    fun `slot list is memoized until the plugin signature changes`() = runBlocking {
        val h = harness()
        h.store.createPlugin("demo", manifest(), "// js")
        val first = h.registry.slots(PluginSlotWhere.COMPOSER)
        assertSame(first, h.registry.slots(PluginSlotWhere.COMPOSER))

        h.store.writeFile("demo", "main.js", "// changed")
        val rebuilt = h.registry.slots(PluginSlotWhere.COMPOSER)
        assertNotSame(first, rebuilt)
        assertEquals(first.map { it.pluginId }, rebuilt.map { it.pluginId })
    }
}
