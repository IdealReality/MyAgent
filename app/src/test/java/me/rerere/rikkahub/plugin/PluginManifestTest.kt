package me.rerere.rikkahub.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(
        id: String = "demo-plugin",
        version: String = "1.0.0",
        entry: String = "main.js",
        permissions: List<String> = emptyList(),
        tools: List<PluginToolSpec> = listOf(PluginToolSpec(name = "demo_echo", description = "echo")),
        prompt: PluginPromptSpec? = null,
    ) = PluginManifest(
        id = id,
        name = "Demo",
        version = version,
        entry = entry,
        permissions = permissions,
        prompt = prompt,
        tools = tools,
    )

    @Test
    fun `minimal valid manifest passes`() {
        val result = PluginValidationRules.validate(manifest(), dir = null)
        assertTrue(result.render(), result.ok)
        assertEquals(emptyList<String>(), result.errors)
    }

    @Test
    fun `invalid id and version are errors`() {
        val bad = PluginValidationRules.validate(manifest(id = "Demo Plugin", version = "1"), dir = null)
        assertFalse(bad.ok)
        assertTrue(bad.errors.any { it.contains("id") })
        assertTrue(bad.errors.any { it.contains("version") })
    }

    @Test
    fun `reserved id is refused`() {
        val result = PluginValidationRules.validate(manifest(id = "core"), dir = null)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("reserved") })
    }

    @Test
    fun `path traversal and absolute paths are refused`() {
        for (path in listOf("../escape.js", "/etc/passwd", "C:/windows/x.js", "sub\\win.js", "a//b.js")) {
            assertNotNull("expected unsafe: $path", PluginValidationRules.unsafeRelativePath(path))
        }
        assertEquals(null, PluginValidationRules.unsafeRelativePath("sub/main.js"))
    }

    @Test
    fun `unknown permission is a warning not an error`() {
        val result = PluginValidationRules.validate(
            manifest(permissions = listOf("http", "telepathy")),
            dir = null,
        )
        assertTrue(result.render(), result.ok)
        assertTrue(result.warnings.any { it.contains("telepathy") })
    }

    @Test
    fun `tool names must be snake case and unique`() {
        val result = PluginValidationRules.validate(
            manifest(tools = listOf(
                PluginToolSpec(name = "Bad-Name", description = "x"),
                PluginToolSpec(name = "ok_name", description = "x"),
                PluginToolSpec(name = "ok_name", description = "dup"),
            )),
            dir = null,
        )
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("Bad-Name") })
        assertTrue(result.errors.any { it.contains("duplicate") })
    }

    @Test
    fun `parameters are projected into properties and required`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("q", buildJsonObject { put("type", "string") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("q"))
            })
        }
        val spec = PluginToolSpec(name = "search_it", parameters = schema)
        assertEquals(setOf("q"), spec.propertyMap.keys)
        assertEquals(listOf("q"), spec.requiredNames)
    }

    @Test
    fun `manifest json round trips with unknown keys ignored`() {
        val raw = """
            {"schema_version":1,"id":"x","name":"X","version":"1.0.0","extra_key":true,
             "tools":[{"name":"x_tool","description":"d"}]}
        """.trimIndent()
        val parsed = json.decodeFromString(PluginManifest.serializer(), raw)
        assertEquals("x", parsed.id)
        assertEquals("x_tool", parsed.tools.single().name)
    }

    @Test
    fun `existing entry file is checked when dir is supplied`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plugin-manifest-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val missing = PluginValidationRules.validate(manifest(), dir)
            assertFalse(missing.ok)
            assertTrue(missing.errors.any { it.contains("does not exist") })

            File(dir, "main.js").writeText("plugin.tool('demo_echo', function(){return 'ok'});")
            val present = PluginValidationRules.validate(manifest(), dir)
            assertTrue(present.render(), present.ok)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `json object null helper`() {
        val element: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap())
        assertNotNull(element.jsonObjectOrNull)
    }
}
