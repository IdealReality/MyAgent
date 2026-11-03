package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode-era approval decision, exercised as a pure function so the whole matrix runs on
 * a bare JVM.
 *
 * My Agent 决策（2026-10-30）：**不存在始终需要审核的工具**。上游的「硬地板」和「所有
 * `mcp__*` 一律要问」两条规则已整体移除，所以这里断言的是「Auto 下没有任何工具会问」，
 * 以及 Semi / MANUAL 仍然按用户自己的黑名单和档位工作（用户想要摩擦时依然拿得到）。
 *
 * `ask_user` / `request_secret` 不是审核，是「需要人回答」——它们照旧在有界面时停下来。
 */
class ToolApprovalPolicyTest {

    private fun decide(
        toolName: String,
        blacklist: Set<String> = emptySet(),
        mode: ApprovalMode = ApprovalMode.SEMI,
        headless: Boolean = false,
        allowedForChat: Boolean = false,
    ): Boolean = ToolApprovalPolicy.shouldAutoApprove(
        toolName = toolName,
        blacklist = blacklist,
        mode = mode,
        headless = headless,
        allowedForChat = allowedForChat,
    )

    // --- AUTO mode: 零摩擦 ---

    @Test
    fun `auto approves an ordinary tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.AUTO))
    }

    @Test
    fun `auto ignores the blacklist`() {
        assertTrue(decide("ssh_exec", blacklist = setOf("ssh_exec"), mode = ApprovalMode.AUTO))
    }

    /**
     * 核心断言：上游会在这里问的每一个工具，Auto 都必须放行。逐个人名列出，避免集合演化后
     * 静默漏掉某个工具又变回「永久审核」。
     */
    @Test
    fun `auto never asks for any former hard-floor tool`() {
        for (name in listOf(
            "eval_javascript",
            "grant_directory_access",
            "mcp_add",
            "mcp_update",
            "skill_install_from_url",
            "skill_install_from_text",
            "browser_eval_js",
            "keystore_generate_key",
            "keystore_decrypt",
            "nfc_write_tag",
        )) {
            assertTrue("$name must run without prompting in Auto", decide(name, mode = ApprovalMode.AUTO))
        }
    }

    @Test
    fun `auto approves relayed mcp tools`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.AUTO))
    }

    // --- SEMI mode (blacklist) ---

    @Test
    fun `semi auto-approves an ordinary tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi asks for a blacklisted tool`() {
        assertFalse(decide("ssh_exec", blacklist = setOf("ssh_exec"), mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi only affects the named tool`() {
        val blacklist = setOf("ssh_exec")
        assertFalse(decide("ssh_exec", blacklist = blacklist, mode = ApprovalMode.SEMI))
        assertTrue(decide("get_battery_status", blacklist = blacklist, mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi auto-approves mcp tools that are not blacklisted`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi asks for a blacklisted mcp tool`() {
        assertFalse(decide("mcp__server__tool", blacklist = setOf("mcp__server__tool"), mode = ApprovalMode.SEMI))
    }

    // --- MANUAL mode ---

    @Test
    fun `manual asks for an ordinary tool`() {
        assertFalse(decide("read_file", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `manual asks for a blacklisted tool`() {
        assertFalse(decide("ssh_exec", blacklist = setOf("ssh_exec"), mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `manual allows a chat-granted tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.MANUAL, allowedForChat = true))
    }

    @Test
    fun `manual allows a chat-granted mcp tool`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.MANUAL, allowedForChat = true))
    }

    // --- "allow for this chat" ---

    @Test
    fun `a chat grant suppresses a blacklisted tool in semi`() {
        assertTrue(
            decide("ssh_exec", blacklist = setOf("ssh_exec"), mode = ApprovalMode.SEMI, allowedForChat = true)
        )
    }

    // --- headless (no approval channel) ---

    @Test
    fun `headless auto-approves everything`() {
        assertTrue(decide("eval_javascript", headless = true, mode = ApprovalMode.MANUAL))
        assertTrue(decide("mcp__server__tool", headless = true, mode = ApprovalMode.SEMI))
    }

    @Test
    fun `headless overrides manual`() {
        assertTrue(decide("read_file", headless = true, mode = ApprovalMode.MANUAL))
    }

    // --- ask_user / request_secret (human-input tools, not approval gates) ---

    @Test
    fun `ask_user asks when not headless`() {
        assertFalse(decide("ask_user", mode = ApprovalMode.AUTO))
        assertFalse(decide("ask_user", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `ask_user auto-approves when headless`() {
        assertTrue(decide("ask_user", headless = true))
    }

    @Test
    fun `request_secret asks when not headless`() {
        assertFalse(decide("request_secret", mode = ApprovalMode.AUTO))
        assertFalse(decide("request_secret", mode = ApprovalMode.MANUAL))
        assertFalse(decide("request_secret", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `request_secret auto-approves when headless`() {
        // Headless then hits the tool body which returns request_secret_unavailable.
        assertTrue(decide("request_secret", headless = true))
    }
}
