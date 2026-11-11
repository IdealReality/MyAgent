package me.rerere.myagent.data.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LspEdits]: position arithmetic, edit application order, and
 * `WorkspaceEdit` flattening.
 *
 * Every case here is a way a rename can silently corrupt a file: a column resolved to the wrong
 * token, an edit applied before a right-hand edit shifted its offsets, an unhandled
 * `CreateFile` leaving a rename half-done.
 */
class LspEditsTest {

    private fun edit(startLine: Int, startChar: Int, endLine: Int, endChar: Int, text: String) =
        LspTextEdit(LspRange(LspPosition(startLine, startChar), LspPosition(endLine, endChar)), text)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `offset maps lines and utf16 columns`() {
        val text = "abc\ndef\nghi"
        assertEquals(0, LspEdits.offsetOf(text, LspPosition(0, 0)))
        assertEquals(2, LspEdits.offsetOf(text, LspPosition(0, 2)))
        assertEquals(4, LspEdits.offsetOf(text, LspPosition(1, 0)))
        assertEquals(10, LspEdits.offsetOf(text, LspPosition(2, 2)))
        // End of the last line (no trailing newline) is a valid insertion point.
        assertEquals(11, LspEdits.offsetOf(text, LspPosition(2, 3)))
    }

    @Test
    fun `crlf keeps character offsets before the carriage return`() {
        val text = "abc\r\ndef"
        assertEquals(3, LspEdits.offsetOf(text, LspPosition(0, 3)))
        // A server may report a range ending one past the visible line (before '\r').
        assertEquals(3, LspEdits.offsetOf(text, LspPosition(0, 4)))
        assertEquals(5, LspEdits.offsetOf(text, LspPosition(1, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `line past the end is rejected`() {
        LspEdits.offsetOf("a\nb", LspPosition(5, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `column past the end is rejected`() {
        LspEdits.offsetOf("abc", LspPosition(0, 99))
    }

    @Test
    fun `multiple edits apply right to left without shifting earlier offsets`() {
        val text = "let foo = foo + 1"
        val result = LspEdits.apply(
            text,
            listOf(
                edit(0, 4, 0, 7, "bar"),
                edit(0, 10, 0, 13, "bar"),
            ),
        )
        assertEquals("let bar = bar + 1", result)
    }

    @Test
    fun `insertion and deletion in the same line are both applied`() {
        val text = "alpha beta gamma"
        val result = LspEdits.apply(
            text,
            listOf(
                edit(0, 0, 0, 5, "A"),
                edit(0, 11, 0, 16, ""),
            ),
        )
        assertEquals("A beta ", result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlapping edits are rejected instead of mangling the file`() {
        val text = "abcdef"
        LspEdits.apply(
            text,
            listOf(
                edit(0, 0, 0, 4, "X"),
                edit(0, 2, 0, 6, "Y"),
            ),
        )
    }

    @Test
    fun `multiline replacement across lines works`() {
        val text = "one\ntwo\nthree\n"
        val result = LspEdits.apply(text, listOf(edit(0, 1, 2, 2, "X")))
        assertEquals("oXree\n", result)
    }

    @Test
    fun `workspace edit with changes shape maps uris to paths`() {
        val edit = json(
            """
            {"changes":{"file:///srv/a.kt":[{"range":{"start":{"line":0,"character":0},
            "end":{"line":0,"character":3}},"newText":"new"}]}}
            """.trimIndent(),
        )
        val ops = LspEdits.parseWorkspaceEdit(edit) { uri -> LspSpecs.uriToPath(uri) }
        assertEquals(1, ops.size)
        val op = ops.single() as WorkspaceFileOp.Edit
        assertEquals("/srv/a.kt", op.path)
        assertEquals("new", op.edits.single().newText)
    }

    @Test
    fun `document changes cover text edits and file create rename delete`() {
        val edit = json(
            """
            {"documentChanges":[
              {"textDocument":{"uri":"file:///srv/a.kt","version":3},
               "edits":[{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":0}},
                         "newText":"x"}]},
              {"kind":"create","uri":"file:///srv/new.kt","options":{"overwrite":true,"ignoreIfExists":true}},
              {"kind":"rename","oldUri":"file:///srv/old.kt","newUri":"file:///srv/renamed.kt","options":{"overwrite":false}},
              {"kind":"delete","uri":"file:///srv/dead.kt","options":{"recursive":false,"ignoreIfNotExists":true}}
            ]}
            """.trimIndent(),
        )
        val ops = LspEdits.parseWorkspaceEdit(edit) { uri -> LspSpecs.uriToPath(uri) }
        assertEquals(4, ops.size)
        assertEquals("/srv/a.kt", (ops[0] as WorkspaceFileOp.Edit).path)
        assertTrue((ops[1] as WorkspaceFileOp.Create).overwrite)
        assertEquals("/srv/old.kt", (ops[2] as WorkspaceFileOp.Rename).from)
        assertEquals("/srv/renamed.kt", (ops[2] as WorkspaceFileOp.Rename).to)
        assertEquals("/srv/dead.kt", (ops[3] as WorkspaceFileOp.Delete).path)
        assertTrue((ops[3] as WorkspaceFileOp.Delete).ignoreIfNotExists)
    }

    @Test
    fun `symbol column matches whole identifiers only`() {
        val line = "    val fooBar = foo + foo"
        // "foo" must not match inside "fooBar"; the first standalone foo is at column 17.
        assertEquals(17, LspEdits.resolveSymbolColumn(line, "foo"))
        assertEquals(23, LspEdits.resolveSymbolColumn(line, "foo", occurrence = 2))
        assertNull(LspEdits.resolveSymbolColumn(line, "foo", occurrence = 3))
    }

    @Test
    fun `non identifier symbols match as substrings and fall back to case insensitive`() {
        val line = "print(\"Hello World\") // Hello"
        assertEquals(7, LspEdits.resolveSymbolColumn(line, "Hello"))
        assertEquals(24, LspEdits.resolveSymbolColumn(line, "Hello", occurrence = 2))
        assertEquals(7, LspEdits.resolveSymbolColumn(line, "hello"))
    }

    @Test
    fun `occurrence selector is parsed only from the tail`() {
        assertEquals("foo" to 1, LspEdits.splitOccurrence("foo"))
        assertEquals("foo" to 3, LspEdits.splitOccurrence("foo#3"))
        assertEquals("foo#x" to 1, LspEdits.splitOccurrence("foo#x"))
        assertEquals("#1" to 1, LspEdits.splitOccurrence("#1"))
    }
}
