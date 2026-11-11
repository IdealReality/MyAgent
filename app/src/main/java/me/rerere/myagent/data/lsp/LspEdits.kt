package me.rerere.myagent.data.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class LspPosition(val line: Int, val character: Int)

internal data class LspRange(val start: LspPosition, val end: LspPosition)

internal data class LspTextEdit(val range: LspRange, val newText: String)

/**
 * A `WorkspaceEdit` flattened into the operations we can perform over SFTP. Kept as a sealed
 * hierarchy so the applier cannot silently ignore a file operation it does not handle: an
 * unhandled `CreateFile` would leave a rename half-applied.
 */
internal sealed interface WorkspaceFileOp {
    data class Edit(val path: String, val edits: List<LspTextEdit>) : WorkspaceFileOp
    data class Create(val path: String, val overwrite: Boolean) : WorkspaceFileOp
    data class Rename(val from: String, val to: String, val overwrite: Boolean) : WorkspaceFileOp
    data class Delete(val path: String, val recursive: Boolean, val ignoreIfNotExists: Boolean) : WorkspaceFileOp
}

/**
 * Text-edit arithmetic for LSP positions.
 *
 * LSP counts `character` in UTF-16 code units, and a Kotlin `String` is indexed in exactly the
 * same units, so the conversion is a line scan plus an addition — no surrogate-pair bookkeeping.
 * (A JavaScript client would have to think about this; we do not.)
 */
internal object LspEdits {

    /** Offset of [position] in [text]; throws when the position is outside the document. */
    fun offsetOf(text: String, position: LspPosition): Int {
        require(position.line >= 0) { "line must be >= 0 (LSP is 0-based), got ${position.line}" }
        require(position.character >= 0) { "character must be >= 0, got ${position.character}" }
        var offset = 0
        var line = 0
        while (line < position.line) {
            val newline = text.indexOf('\n', offset)
            if (newline < 0) {
                throw IllegalArgumentException("line ${position.line} is past the end of the document (${line} lines)")
            }
            offset = newline + 1
            line++
        }
        val newline = text.indexOf('\n', offset)
        val lineEnd = if (newline < 0) text.length else newline
        // `character` may address the position just before a CRLF's '\r' when a server reports
        // a range ending at the visual end of the line; clamp that one case instead of failing.
        val contentEnd = if (lineEnd > offset && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val target = offset + position.character
        if (target > contentEnd) {
            if (target <= lineEnd) return contentEnd
            throw IllegalArgumentException(
                "character ${position.character} is past the end of line ${position.line + 1} (length ${contentEnd - offset})",
            )
        }
        return target
    }

    /**
     * Apply non-overlapping [edits] to [text]. Edits are applied right-to-left so earlier
     * offsets stay valid; overlapping ranges are rejected rather than silently producing a
     * mangled file.
     */
    fun apply(text: String, edits: List<LspTextEdit>): String {
        if (edits.isEmpty()) return text
        data class Resolved(val start: Int, val end: Int, val newText: String)
        val resolved = edits.map {
            Resolved(
                start = offsetOf(text, it.range.start),
                end = offsetOf(text, it.range.end),
                newText = it.newText,
            )
        }.sortedByDescending { it.start }

        val builder = StringBuilder(text)
        var rightmostStart = Int.MAX_VALUE
        for (edit in resolved) {
            require(edit.start <= edit.end) {
                "inverted edit range at offset ${edit.start}..${edit.end}"
            }
            require(edit.end <= rightmostStart) {
                "overlapping text edits at offset ${edit.start}..${edit.end} (already edited from $rightmostStart)"
            }
            builder.replace(edit.start, edit.end, edit.newText)
            rightmostStart = edit.start
        }
        return builder.toString()
    }

    fun parseRange(obj: JsonObject): LspRange {
        val range = obj["range"]?.jsonObject ?: throw IllegalArgumentException("text edit has no range")
        return LspRange(
            start = parsePosition(range["start"]?.jsonObject ?: throw IllegalArgumentException("range has no start")),
            end = parsePosition(range["end"]?.jsonObject ?: throw IllegalArgumentException("range has no end")),
        )
    }

    private fun parsePosition(obj: JsonObject): LspPosition = LspPosition(
        line = obj["line"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("position has no line"),
        character = obj["character"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("position has no character"),
    )

    fun parseEdits(array: JsonArray?): List<LspTextEdit> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj["newText"] == null) return@mapNotNull null // SnippetTextEdit / AnnotatedTextEdit without text
            LspTextEdit(
                range = parseRange(obj),
                newText = obj["newText"]!!.jsonPrimitive.contentOrNull ?: "",
            )
        }
    }

    /**
     * Flatten a `WorkspaceEdit` into SFTP-able operations.
     *
     * Both shapes are handled because servers differ: tsserver and gopls send `changes`, while
     * clangd and rust-analyzer prefer `documentChanges` (which is also the only shape that can
     * express file creation/rename/deletion).
     */
    fun parseWorkspaceEdit(edit: JsonObject, uriToPath: (String) -> String): List<WorkspaceFileOp> {
        val ops = ArrayList<WorkspaceFileOp>()
        edit["changes"]?.jsonObject?.forEach { (uri, value) ->
            val edits = parseEdits(value as? JsonArray)
            if (edits.isNotEmpty()) ops.add(WorkspaceFileOp.Edit(uriToPath(uri), edits))
        }
        edit["documentChanges"]?.jsonArray?.forEach { element ->
            val obj = element.jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            if (kind == null) {
                val document = obj["textDocument"]?.jsonObject ?: return@forEach
                val uri = document["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val edits = parseEdits(obj["edits"] as? JsonArray)
                if (edits.isNotEmpty()) ops.add(WorkspaceFileOp.Edit(uriToPath(uri), edits))
                return@forEach
            }
            val options = obj["options"]?.jsonObject
            val overwrite = options?.get("overwrite")?.jsonPrimitive?.booleanOrNull ?: false
            when (kind) {
                "create" -> {
                    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    ops.add(WorkspaceFileOp.Create(uriToPath(uri), overwrite))
                }
                "rename" -> {
                    val oldUri = obj["oldUri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val newUri = obj["newUri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    ops.add(WorkspaceFileOp.Rename(uriToPath(oldUri), uriToPath(newUri), overwrite))
                }
                "delete" -> {
                    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val recursive = options?.get("recursive")?.jsonPrimitive?.booleanOrNull ?: false
                    val ignoreIfNotExists = options?.get("ignoreIfNotExists")?.jsonPrimitive?.booleanOrNull ?: false
                    ops.add(WorkspaceFileOp.Delete(uriToPath(uri), recursive, ignoreIfNotExists))
                }
            }
        }
        return ops
    }

    /**
     * Resolve the column a position-based request should use.
     *
     * `symbol` names the thing on that line; `name#2` picks the second occurrence. Bare
     * identifiers must match whole words (`foo` must not hit `foobar`), anything else is a plain
     * substring, and an exact-case match wins over a case-insensitive one — the same rules omp
     * uses, because a column resolved to the wrong token turns `rename` into a wrong-file edit.
     *
     * Returns null when the symbol does not occur on the line.
     */
    fun resolveSymbolColumn(lineText: String, symbol: String, occurrence: Int = 1): Int? {
        if (symbol.isEmpty()) return null
        val identifierLike = symbol.all { it.isLetterOrDigit() || it == '_' || it == '$' }
        var matched = 0
        var searchFrom = 0
        while (searchFrom <= lineText.length) {
            val index = lineText.indexOf(symbol, searchFrom, ignoreCase = false)
            if (index < 0) break
            if (!identifierLike || isWholeWord(lineText, index, symbol.length)) {
                matched++
                if (matched == occurrence) return index
            }
            searchFrom = index + 1
        }
        // Case-insensitive fallback: the exact-case scan found fewer than [occurrence] hits, so
        // selector semantics are re-applied from the start rather than returning null for a symbol
        // that is present only with different casing.
        if (matched < occurrence) {
            matched = 0
            searchFrom = 0
            while (searchFrom <= lineText.length) {
                val index = lineText.indexOf(symbol, searchFrom, ignoreCase = true)
                if (index < 0) break
                if (!identifierLike || isWholeWord(lineText, index, symbol.length)) {
                    matched++
                    if (matched == occurrence) return index
                }
                searchFrom = index + 1
            }
        }
        return null
    }

    /** Split `name#3` into ("name", 3); a selector is only recognised at the tail. */
    fun splitOccurrence(symbol: String): Pair<String, Int> {
        val hash = symbol.lastIndexOf('#')
        if (hash <= 0 || hash == symbol.length - 1) return symbol to 1
        val count = symbol.substring(hash + 1).toIntOrNull() ?: return symbol to 1
        if (count < 1) return symbol to 1
        return symbol.substring(0, hash) to count
    }

    private fun isWholeWord(text: String, index: Int, length: Int): Boolean {
        val before = if (index > 0) text[index - 1] else ' '
        val afterIndex = index + length
        val after = if (afterIndex < text.length) text[afterIndex] else ' '
        return !isIdentifierChar(before) && !isIdentifierChar(after)
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
