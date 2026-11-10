package me.rerere.myagent.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My Agent 决策（2026-10-30）：**headless 不再拒绝任何工具**。
 *
 * 上游这里的断言是「哪些工具在无审批通道时会话里不准跑」。本构建整体移除这一层：定时任务、
 * 子代理、workflow 需要写记忆、读通知、派发专家时，必须能真的跑起来——否则「没有始终需要
 * 审核的工具」这条要求就是空的。
 *
 * 保留的边界不是审核而是拒绝执行：[HardlineCommandGuard] 仍然拦危险命令；子代理面仍然不
 * 暴露 `subagent_` 前缀（防递归）。这两个各有自己的测试。
 */
class HeadlessToolApprovalPolicyTest {

    @Test
    fun `no tool is refused in a headless run`() {
        assertTrue(
            "REFUSED_TOOL_NAMES 必须为空，否则 headless 会话仍有工具会静默失败",
            HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.isEmpty(),
        )
    }

    @Test
    fun `the formerly refused tools all run headless now`() {
        // 逐个人名固定下来：集合演化不能悄悄把某个工具又变回「无人可批准所以不准跑」。
        for (name in listOf(
            // 上游 per-call-confirmation 组
            "eval_javascript",
            "browser_eval_js",
            "keystore_generate_key",
            "keystore_decrypt",
            "nfc_write_tag",
            "grant_directory_access",
            "mcp_add",
            "mcp_update",
            "skill_install_from_url",
            "skill_install_from_text",
            // 上游麦克风组
            "record_audio",
            "speech_to_text",
            // 上游隐私数据组
            "list_contacts",
            "search_contacts",
            "list_sms_inbox",
            "list_call_log",
            "memory_write",
            // 上游名册写入组
            "subagent_create",
            "subagent_update",
            "subagent_delete",
            "model_add",
            "model_update",
            "model_delete",
        )) {
            assertFalse("$name must run headless", HeadlessToolApprovalPolicy.isRefused(name))
            assertNull(HeadlessToolApprovalPolicy.refusalEnvelope(name))
            assertNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = true))
        }
    }

    @Test
    fun `an ordinary tool is never refused`() {
        assertFalse(HeadlessToolApprovalPolicy.isRefused("read_file"))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("web_fetch"))
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelope("ssh_exec"))
    }

    @Test
    fun `the refusal envelope shape is unused but still well-formed`() {
        // 保留这个形状是为了让「将来若恢复某条拒绝」不必重新设计载荷；现在它必须什么都不产出。
        assertEquals("tool_not_authorized", HeadlessToolApprovalPolicy.ERROR_CODE)
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelope("anything"))
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor("anything", headless = false))
    }
}
