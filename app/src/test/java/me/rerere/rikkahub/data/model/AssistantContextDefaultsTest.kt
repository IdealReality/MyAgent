package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.truncateToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults are the whole point of the change: an assistant that never touched these
 * settings used to get NO token budget on tool results and NO message-count ceiling, which is
 * how a real device ended up with prompts averaging ~165k tokens.
 *
 * These tests pin the contract rather than the numbers, so tuning the constants later stays
 * safe as long as the invariants hold.
 */
class AssistantContextDefaultsTest {

    @Test
    fun `a fresh assistant has a real tool-result budget instead of none`() {
        val assistant = Assistant()
        assertTrue(
            "toolResultMaxTokens must default to a positive budget, was ${assistant.toolResultMaxTokens}",
            (assistant.toolResultMaxTokens ?: 0) > 0,
        )
    }

    @Test
    fun `the default budget is small enough to bound one tool result`() {
        // One tool result should never be able to consume a whole conversation. The exact
        // number is a judgement call; "well under a typical context window" is the invariant.
        val budget = DEFAULT_TOOL_RESULT_MAX_TOKENS
        assertTrue("budget $budget should be at most 16k", budget <= 16_000)
        assertTrue("budget $budget should be at least 2k", budget >= 2_000)
    }

    @Test
    fun `a fresh assistant has a message-count backstop`() {
        val assistant = Assistant()
        assertTrue(
            "contextMessageLimit must default to a positive ceiling, was ${assistant.contextMessageLimit}",
            assistant.contextMessageLimit > 0,
        )
    }

    @Test
    fun `the message ceiling is above a healthy long conversation`() {
        // Too low and a legitimate multi-hour tool-using task gets truncated mid-evidence.
        assertTrue(
            "ceiling ${DEFAULT_CONTEXT_MESSAGE_LIMIT} is too tight",
            DEFAULT_CONTEXT_MESSAGE_LIMIT >= 80,
        )
    }

    @Test
    fun `the default budget actually trims an oversized result`() {
        val oversized = "x".repeat(DEFAULT_TOOL_RESULT_MAX_TOKENS * 8)
        val outcome = truncateToolResult(text = oversized, maxTokens = DEFAULT_TOOL_RESULT_MAX_TOKENS)
        assertTrue(outcome.truncated)
        assertTrue(
            "trimmed to ${outcome.resultTokens} tokens, over budget $DEFAULT_TOOL_RESULT_MAX_TOKENS",
            outcome.resultTokens <= DEFAULT_TOOL_RESULT_MAX_TOKENS,
        )
    }

    @Test
    fun `a result inside the budget is passed through untouched`() {
        val small = "a short tool result"
        val outcome = truncateToolResult(text = small, maxTokens = DEFAULT_TOOL_RESULT_MAX_TOKENS)
        assertFalse(outcome.truncated)
        assertEquals(small, outcome.text)
    }

    @Test
    fun `zero means explicitly unlimited, not an empty result`() {
        // A typo in a settings field must never blank out a tool result.
        val text = "y".repeat(100_000)
        val outcome = truncateToolResult(text = text, maxTokens = 0)
        assertFalse(outcome.truncated)
        assertEquals(text, outcome.text)
    }
}
