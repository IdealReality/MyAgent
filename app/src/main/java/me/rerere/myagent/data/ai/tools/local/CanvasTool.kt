package me.rerere.myagent.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/** Directory under filesDir that holds one JSON file per canvas. */
internal const val CANVAS_DIR = "canvas"

private const val CANVAS_MAX_NODES = 40
private const val CANVAS_MAX_EDGES = 80
private const val NODE_W = 180.0
private const val NODE_H = 76.0
private const val COL_GAP = 44.0
private const val ROW_GAP = 78.0
private const val PAD = 32.0
private const val MAX_LAYERS = 20

private val CANVAS_SHAPES = setOf("rect", "round", "diamond", "circle")
private val CANVAS_HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

private data class CanvasNodeInput(
    val id: String,
    val label: String,
    val note: String?,
    val shape: String,
    val color: String?,
    val x: Double?,
    val y: Double?,
)

private data class CanvasEdgeInput(
    val from: String,
    val to: String,
    val label: String?,
    val dashed: Boolean,
)

private fun JsonObject.canvasStr(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.canvasNum(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.canvasBool(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: false

internal fun canvasStoreDir(context: Context): File =
    File(context.filesDir, CANVAS_DIR).apply { mkdirs() }

fun canvasTool(context: Context): Tool = Tool(
    name = "canvas_render",
    description = """
        Render an editable diagram canvas inline in the chat — flowcharts, architecture maps,
        organisation or relationship graphs, decision trees, state machines.
        The user can drag nodes, rename them, delete nodes and reconnect edges directly on the
        canvas, and every edit is saved back to disk automatically: call canvas_read to see the
        user's current version before you change anything.
        Pass the same canvas_id to update an existing canvas in place instead of creating a new one.
        Coordinates are optional: omit node x/y and the canvas lays every node out top-down by
        dependency order. Supply x/y only when the automatic layout is not what you want.
        The canvas is rendered for the user, so do not repeat the node labels as a list in your reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("canvas_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Existing canvas to replace. Omit to create a new canvas.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Short title shown above the canvas.")
                })
                put("nodes", buildJsonObject {
                    put("type", "array")
                    put("description", "Canvas nodes, 1-$CANVAS_MAX_NODES, unique ids.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Stable id used by edges.")
                            })
                            put("label", buildJsonObject {
                                put("type", "string")
                                put("description", "Node text; keep it short, two lines at most.")
                            })
                            put("note", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional second line in smaller type.")
                            })
                            put("shape", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { CANVAS_SHAPES.forEach { add(it) } })
                                put("description", "Default rect.")
                            })
                            put("color", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional hex fill such as '#3B82F6'; text colour adapts.")
                            })
                            put("x", buildJsonObject {
                                put("type", "number")
                                put("description", "Board x in units. Omit for auto layout.")
                            })
                            put("y", buildJsonObject {
                                put("type", "number")
                                put("description", "Board y in units. Omit for auto layout.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("label")
                        })
                    })
                })
                put("edges", buildJsonObject {
                    put("type", "array")
                    put("description", "Connections between nodes, 0-$CANVAS_MAX_EDGES.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("from", buildJsonObject { put("type", "string") })
                            put("to", buildJsonObject { put("type", "string") })
                            put("label", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional edge label.")
                            })
                            put("dashed", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Dashed line for optional or async flows.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("from")
                            add("to")
                        })
                    })
                })
            },
            required = listOf("nodes"),
        )
    },
    execute = { args ->
        val argsObj = args.jsonObjectOrNullSafe()
        val payload = runCatching { buildCanvasPayload(argsObj) }.getOrElse { error ->
            buildJsonObject {
                put("ok", false)
                put("error", error.message ?: "invalid canvas payload")
            }
        }
        val text = if (payload.canvasStr("ok") == "true" || payload["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            val explicitId = argsObj.canvasStr("canvas_id")
            val id = explicitId ?: ("c" + System.currentTimeMillis().toString(36))
            val stored = JsonObject(payload + mapOf("canvas_id" to JsonPrimitive(id)))
            val failure = runCatching {
                File(canvasStoreDir(context), "$id.json").writeText(stored.toString())
                null
            }.getOrElse { it.message ?: "write failed" }
            val final = if (failure == null) {
                stored
            } else {
                JsonObject(stored + mapOf("storage_error" to JsonPrimitive(failure)))
            }
            final.toString()
        } else {
            payload.toString()
        }
        listOf(UIMessagePart.Text(text))
    },
)

fun canvasReadTool(context: Context): Tool = Tool(
    name = "canvas_read",
    description = """
        Read the current state of a canvas the user has been editing. Edits made on the canvas
        are written back to disk automatically, so this returns the user's version, including
        moved nodes, renamed labels and deleted nodes.
        Call this before updating a canvas the user has touched, instead of assuming it still
        matches what you drew. Omit canvas_id to read the most recently edited canvas.
        Set list to true to enumerate stored canvases by id and last-modified time.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("canvas_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Canvas id returned by canvas_render. Omit for the most recent one.")
                })
                put("list", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return the index of stored canvases instead of one canvas.")
                })
            }
        )
    },
    execute = { args ->
        val obj = args.jsonObjectOrNullSafe()
        val dir = canvasStoreDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()

        val index = buildJsonArray {
            files.sortedByDescending { it.lastModified() }.forEach { file ->
                add(buildJsonObject {
                    put("canvas_id", file.nameWithoutExtension)
                    put("updated_ms", file.lastModified())
                    put("bytes", file.length())
                })
            }
        }


        val wanted = obj.canvasStr("canvas_id")
        val payloadText = if (obj.canvasBool("list")) {
            buildJsonObject {
                put("ok", true)
                put("count", files.size)
                put("canvases", index)
            }.toString()
        } else {
            val file = if (wanted != null) {
                File(dir, "$wanted.json")
            } else {
                files.maxByOrNull { it.lastModified() }
            }
            if (file == null || !file.isFile) {
                buildJsonObject {
                    put("ok", false)
                    put("error", "canvas_not_found")
                    if (wanted != null) put("canvas_id", wanted)
                    put("canvases", index)
                }.toString()
            } else {
                val canvas: JsonElement = runCatching {
                    Json.parseToJsonElement(file.readText())
                }.getOrElse { JsonNull }
                buildJsonObject {
                    put("ok", true)
                    put("canvas_id", file.nameWithoutExtension)
                    put("updated_ms", file.lastModified())
                    put("canvas", canvas)
                }.toString()
            }
        }
        listOf(UIMessagePart.Text(payloadText))
    },
)

private fun JsonElement?.jsonObjectOrNullSafe(): JsonObject =
    runCatching { this?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun buildCanvasPayload(args: JsonObject): JsonObject {
    val title = args.canvasStr("title") ?: ""
    val rawNodes = args["nodes"] as? JsonArray ?: JsonArray(emptyList())
    if (rawNodes.isEmpty()) throw IllegalArgumentException("nodes must not be empty")
    if (rawNodes.size > CANVAS_MAX_NODES) {
        throw IllegalArgumentException("at most $CANVAS_MAX_NODES nodes")
    }

    val nodes = ArrayList<CanvasNodeInput>(rawNodes.size)
    val seen = HashSet<String>()
    for (element in rawNodes) {
        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("每 node 必须是 object")
        val id = obj.canvasStr("id") ?: throw IllegalArgumentException("every node needs an id")
        if (!seen.add(id)) throw IllegalArgumentException("duplicate node id: $id")
        val label = obj.canvasStr("label") ?: id
        val shape = obj.canvasStr("shape")?.lowercase()?.takeIf { it in CANVAS_SHAPES } ?: "rect"
        val color = obj.canvasStr("color")?.takeIf { CANVAS_HEX.matches(it) }
        nodes += CanvasNodeInput(
            id = id,
            label = label,
            note = obj.canvasStr("note"),
            shape = shape,
            color = color,
            x = obj.canvasNum("x"),
            y = obj.canvasNum("y"),
        )
    }

    val rawEdges = args["edges"] as? JsonArray ?: JsonArray(emptyList())
    if (rawEdges.size > CANVAS_MAX_EDGES) {
        throw IllegalArgumentException("at most $CANVAS_MAX_EDGES edges")
    }
    val edges = ArrayList<CanvasEdgeInput>(rawEdges.size)
    for (element in rawEdges) {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
        val from = obj.canvasStr("from") ?: continue
        val to = obj.canvasStr("to") ?: continue
        if (from !in seen || to !in seen) {
            throw IllegalArgumentException("edge references unknown node: $from -> $to")
        }
        edges += CanvasEdgeInput(
            from = from,
            to = to,
            label = obj.canvasStr("label"),
            dashed = obj.canvasBool("dashed"),
        )
    }

    val manual = nodes.all { it.x != null && it.y != null }
    val positions: Map<String, Pair<Double, Double>> =
        if (manual) {
            nodes.associate { it.id to ((it.x ?: 0.0) to (it.y ?: 0.0)) }
        } else {
            layerNodes(nodes, edges)
        }

    var maxX = 0.0
    var maxY = 0.0
    positions.forEach { (_, pos) ->
        if (pos.first + NODE_W > maxX) maxX = pos.first + NODE_W
        if (pos.second + NODE_H > maxY) maxY = pos.second + NODE_H
    }

    return buildJsonObject {
        put("ok", true)
        put("title", title)
        put("board", buildJsonObject {
            put("w", maxX + PAD)
            put("h", maxY + PAD)
            put("node_w", NODE_W)
            put("node_h", NODE_H)
        })
        put("nodes", buildJsonArray {
            nodes.forEach { node ->
                val pos = positions.getValue(node.id)
                add(buildJsonObject {
                    put("id", node.id)
                    put("label", node.label)
                    node.note?.let { put("note", it) }
                    put("shape", node.shape)
                    node.color?.let { put("color", it) }
                    put("x", pos.first)
                    put("y", pos.second)
                })
            }
        })
        put("edges", buildJsonArray {
            edges.forEach { edge ->
                add(buildJsonObject {
                    put("from", edge.from)
                    put("to", edge.to)
                    edge.label?.let { put("label", it) }
                    put("dashed", edge.dashed)
                })
            }
        })
    }
}

private fun layerNodes(
    nodes: List<CanvasNodeInput>,
    edges: List<CanvasEdgeInput>,
): Map<String, Pair<Double, Double>> {
    val ids = nodes.map { it.id }
    val incoming = ids.associateWith { 0 }.toMutableMap()
    val outgoing: MutableMap<String, MutableList<String>> =
        ids.associateWith { mutableListOf<String>() }.toMutableMap()

    for (edge in edges) {
        if (edge.from == edge.to) continue
        outgoing[edge.from]?.add(edge.to)
        incoming[edge.to] = (incoming[edge.to] ?: 0) + 1
    }

    val layerOf = LinkedHashMap<String, Int>()
    var frontier = ids.filter { (incoming[it] ?: 0) == 0 }
    if (frontier.isEmpty()) frontier = listOf(ids.first())
    var depth = 0
    while (frontier.isNotEmpty() && depth < MAX_LAYERS) {
        val next = mutableListOf<String>()
        for (id in frontier) {
            if (layerOf.containsKey(id)) continue
            layerOf[id] = depth
            outgoing[id]?.forEach { target ->
                if (!layerOf.containsKey(target)) next.add(target)
            }
        }
        frontier = next.distinct()
        depth++
    }
    if (layerOf.size < ids.size) {
        for (id in ids) if (!layerOf.containsKey(id)) layerOf[id] = depth
    }

    val rows = LinkedHashMap<Int, MutableList<String>>()
    for (id in ids) {
        val layer = layerOf[id] ?: 0
        rows.getOrPut(layer) { mutableListOf() }.add(id)
    }
    val ordered = rows.keys.sorted()
    var widest = 0.0
    ordered.forEach { layer ->
        val row = rows[layer] ?: return@forEach
        val width = row.size * NODE_W + (row.size - 1) * COL_GAP
        if (width > widest) widest = width
    }

    val result = LinkedHashMap<String, Pair<Double, Double>>()
    ordered.forEachIndexed { index, layer ->
        val row = rows[layer] ?: return@forEachIndexed
        val width = row.size * NODE_W + (row.size - 1) * COL_GAP
        val startX = PAD + (widest - width) / 2.0
        val y = PAD + index * (NODE_H + ROW_GAP)
        row.forEachIndexed { column, id ->
            result[id] = (startX + column * (NODE_W + COL_GAP)) to y
        }
    }
    return result
}
