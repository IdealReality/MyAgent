package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tool_search` activating its own top hits removes one round-trip, but it also mutates
 * session state as a side effect of a read-looking call. These tests pin the parts a model
 * depends on: which names come back activated, that the cap is respected, and that a second
 * search does not re-announce what is already active.
 */
class ToolCatalogAutoActivationTest {

    private fun entry(name: String, summary: String = "does $name things") = ToolCatalogEntry(
        name = name,
        summary = summary,
        source = ToolCatalogSource.LOCAL,
        tool = Tool(
            name = name,
            description = summary,
            execute = { emptyList() },
        ),
    )

    private fun catalog() = ToolCatalog(
        listOf(
            entry("read_file", "read a file from disk"),
            entry("write_file", "write a file to disk"),
            entry("list_files", "list files in a directory"),
            entry("delete_file", "delete a file"),
        ),
    )

    private fun Tool.call(json: kotlinx.serialization.json.JsonObject): String = runBlocking {
        execute(json).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }

    private fun search(handler: Tool, query: String): String =
        handler.call(buildJsonObject { put("query", JsonPrimitive(query)) })

    private fun searchTool(catalog: ToolCatalog, activation: ToolActivationState) =
        buildToolCatalogTools(catalog, activation).first { it.name == "tool_search" }

    @Test
    fun `a search activates at most the configured number of hits`() {
        val activation = ToolActivationState()
        search(searchTool(catalog(), activation), "file")
        assertTrue(
            "activated ${activation.active().size}",
            activation.active().size <= TOOL_CATALOG_AUTO_ACTIVATE_LIMIT,
        )
    }

    @Test
    fun `a search activates the names it ranked first`() {
        val activation = ToolActivationState()
        val catalog = catalog()
        search(searchTool(catalog, activation), "file")
        val ranked = catalog.matchAll("file").take(TOOL_CATALOG_AUTO_ACTIVATE_LIMIT).map { it.name }
        assertEquals(ranked.toSet(), activation.active())
    }

    @Test
    fun `the response reports what was activated`() {
        val activation = ToolActivationState()
        val payload = search(searchTool(catalog(), activation), "read_file")
        assertTrue("expected autoActivated in $payload", payload.contains("\"autoActivated\""))
        assertTrue("expected read_file in $payload", payload.contains("read_file"))
    }

    @Test
    fun `a second identical search activates nothing new`() {
        val activation = ToolActivationState()
        val tool = searchTool(catalog(), activation)
        search(tool, "file")
        val first = activation.active()
        val payload = search(tool, "file")
        assertEquals(first, activation.active())
        // Nothing new was activated, so the field is absent rather than an empty list.
        assertTrue("unexpected autoActivated in $payload", !payload.contains("\"autoActivated\""))
    }

    @Test
    fun `the per-session schema cap still bounds auto-activation`() {
        val activation = ToolActivationState()
        val many = ToolCatalog((1..20).map { entry("tool_$it", "helper number $it for files") })
        for (round in 0 until 10) {
            search(searchTool(many, activation), "file round$round")
        }
        assertTrue(
            "active=${activation.active().size} exceeds cap=$TOOL_CATALOG_MAX_ACTIVE_SCHEMAS",
            activation.active().size <= TOOL_CATALOG_MAX_ACTIVE_SCHEMAS,
        )
    }

    @Test
    fun `a search that matches nothing activates nothing`() {
        val activation = ToolActivationState()
        val payload = search(searchTool(catalog(), activation), "zzz-no-such-tool")
        assertTrue(activation.active().isEmpty())
        assertTrue(payload.contains("No tools matched"))
    }

    @Test
    fun `tool_open still works for names a search did not activate`() {
        val activation = ToolActivationState()
        val tools = buildToolCatalogTools(catalog(), activation)
        tools.first { it.name == "tool_open" }.call(
            buildJsonObject {
                put("names", buildJsonArray { add(JsonPrimitive("delete_file")) })
            },
        )
        assertTrue(activation.active().contains("delete_file"))
    }

    @Test
    fun `the catalog builder still returns exactly the search and open pair`() {
        val tools = buildToolCatalogTools(catalog(), ToolActivationState())
        assertEquals(listOf("tool_search", "tool_open", "tool_close"), tools.map { it.name })
    }

    @Test
    fun `an unknown name is reported and not activated`() {
        val activation = ToolActivationState()
        val open = buildToolCatalogTools(catalog(), activation).first { it.name == "tool_open" }
        val payload = open.call(
            buildJsonObject {
                put("names", buildJsonArray { add(JsonPrimitive("no_such_tool")) })
            },
        )
        assertNotNull(payload)
        assertTrue(payload.contains("no_such_tool"))
        assertTrue(activation.active().isEmpty())
        assertNull(activation.active().firstOrNull())
    }
}
