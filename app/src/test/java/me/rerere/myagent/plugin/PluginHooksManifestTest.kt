package me.rerere.myagent.plugin

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hooks` 是 schema 1 的可选扩展：解析要稳、函数名要校验、单独声明钩子也算有贡献。 */
class PluginHooksManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun validate(raw: String): PluginValidation =
        PluginValidationRules.validate(json.decodeFromString(PluginManifest.serializer(), raw), null)

    private fun manifest(hooks: String, tools: String = ""): String =
        """{"schema_version":1,"id":"demo","name":"Demo","version":"1.0.0","entry":"main.js",
           "permissions":[],"hooks":{$hooks}$tools}"""

    @Test
    fun `before_turn hook name must be a js identifier`() {
        assertTrue(validate(manifest("\"before_turn\":\"beforeTurn\"")).ok)
        assertTrue(validate(manifest("\"before_turn\":\"_hook1\"")).ok)

        val bad = validate(manifest("\"before_turn\":\"not a function!\""))
        assertFalse(bad.ok)
        assertTrue(bad.render(), bad.render().contains("hooks.before_turn"))
    }

    @Test
    fun `hooks alone count as a contribution`() {
        val validation = validate(manifest("\"before_turn\":\"beforeTurn\""))
        assertTrue(validation.ok)
        assertFalse(validation.render(), validation.render().contains("contributes nothing"))
    }

    @Test
    fun `unknown hook keys are ignored rather than rejected`() {
        val validation = validate(manifest("\"before_turn\":\"x\",\"after_turn\":\"y\""))
        assertTrue(validation.render(), validation.ok)
    }

    @Test
    fun `after_tool hook is validated like before_turn`() {
        assertTrue(validate(manifest("\"after_tool\":\"afterTool\"")).ok)

        val bad = validate(manifest("\"after_tool\":\"after tool\"")).render()
        assertTrue(bad, bad.contains("hooks.after_tool"))
    }

    @Test
    fun `both hooks declared are both reachable by name`() {
        val decoded = json.decodeFromString(
            PluginManifest.serializer(),
            manifest("\"before_turn\":\"beforeTurn\",\"after_tool\":\"afterTool\""),
        )
        val hooks = decoded.hooks!!
        assertEquals(listOf("before_turn", "after_tool"), hooks.declaredHooks)
        assertEquals("beforeTurn", hooks.functionFor("before_turn"))
        assertEquals("afterTool", hooks.functionFor("after_tool"))
        assertNull(hooks.functionFor("before_tool"))
    }
}
