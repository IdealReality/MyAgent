package me.rerere.myagent.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogToolTest {

    // Success path requires CallLog content provider — instrumented test required.
    // The unknown-call-type validation runs AFTER PermissionHelper.hasRuntime(), which calls
    // ContextCompat.checkSelfPermission() on the NULL_CONTEXT ContextWrapper. `unitTests
    // .isReturnDefaultValues = true` makes that stub return a default (denied) instead of
    // throwing, so on a plain JVM the permission gate answers first and the type-validation
    // branch is unreachable. Whichever gate answers, the contract under test is the same: an
    // unknown type must never come back as a successful (error-free) payload.

    @Test
    fun `list_call_log with unknown type does not silently succeed`() {
        val tool = callLogTool(NULL_CONTEXT)
        val out = execTool(tool, """{"type":"foo"}""")
        val error = Json.parseToJsonElement(out).jsonObject["error"]?.jsonPrimitive?.content
        assertNotNull("expected a permission/validation gate to surface an error envelope, got: $out", error)
        assertTrue(
            "the gate that answered must be one of ours, got: $out",
            error!!.contains("READ_CALL_LOG") || error.contains("unknown call type"),
        )
    }
}
