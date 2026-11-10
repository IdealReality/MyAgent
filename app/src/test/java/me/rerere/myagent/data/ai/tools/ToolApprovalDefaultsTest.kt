package me.rerere.myagent.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My Agent 决策（2026-10-30）：**不存在始终需要审核的工具**。
 *
 * 上游这里断言的是「哪些工具在不可豁免的地板上」。本构建把地板整体移除，因此这些测试翻转成
 * 「地板必须为空」——它守的是用户的核心要求：任何工具都不该永久停留在需要人工确认的状态。
 * 危险命令仍然由 [HardlineCommandGuard] 直接拒绝（那是拒绝执行，不是审核）。
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `no tool is on a non-waivable floor`() {
        assertTrue(
            "硬地板必须为空，否则就有工具永久需要审核",
            ToolApprovalDefaults.NO_ALWAYS_ALLOW.isEmpty(),
        )
        for (name in listOf(
            "eval_javascript",
            "keystore_decrypt",
            "grant_directory_access",
            "mcp_add",
            "nfc_write_tag",
            "mcp__server__do_something",
            "mcp__",
        )) {
            assertFalse(name, ToolApprovalDefaults.isHardFloor(name))
        }
    }

    @Test
    fun `ordinary tools are not on the floor`() {
        assertFalse(ToolApprovalDefaults.isHardFloor("launch_activity"))
        assertFalse(ToolApprovalDefaults.isHardFloor("read_file"))
        assertFalse(ToolApprovalDefaults.isHardFloor(""))
    }

    @Test
    fun `the recommended set still lists the classic side-effecting tools`() {
        // 推荐黑名单保留了「用户想主动管起来时」的入口，只是默认不生效。
        assertTrue("launch_activity" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("ssh_exec" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("write_text_file" in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }
}
