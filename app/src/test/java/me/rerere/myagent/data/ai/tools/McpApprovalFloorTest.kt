package me.rerere.myagent.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My Agent 决策（2026-10-30）：`mcp__*` 中继工具与 MCP 安装工具**都不再需要审核**。
 *
 * 上游把「对 MCP 一概不信任」做成了一条不可豁免的规则，结果是任何带 MCP 服务器的助手都无法
 * 自动工作。本构建移除该规则：MCP 工具与本地工具走同一条策略（Auto 放行 / Semi 看黑名单 /
 * MANUAL 全问）。想让某个 MCP 工具重新要确认，把它加进黑名单即可。
 */
class McpApprovalFloorTest {

    @Test
    fun `mcp install tools are not on the floor`() {
        assertFalse(ToolApprovalDefaults.isHardFloor("mcp_add"))
        assertFalse(ToolApprovalDefaults.isHardFloor("mcp_update"))
    }

    @Test
    fun `eval_javascript is not on the floor but stays recommended`() {
        assertFalse(ToolApprovalDefaults.isHardFloor("eval_javascript"))
        assertTrue("eval_javascript" in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }

    @Test
    fun `every mcp control write stays in the recommended set`() {
        for (name in listOf(
            "mcp_add", "mcp_update", "mcp_delete", "mcp_set_enabled", "mcp_set_tool_approval",
        )) {
            assertFalse(name, ToolApprovalDefaults.isHardFloor(name))
            assertTrue("$name should be offered in the recommended set", name in ToolApprovalDefaults.DANGEROUS_TOOLS)
        }
    }

    @Test
    fun `read-only mcp control tools are not on the floor`() {
        for (name in listOf("mcp_list", "mcp_get", "mcp_test", "mcp_list_tools")) {
            assertFalse(name, ToolApprovalDefaults.isHardFloor(name))
        }
    }

    @Test
    fun `relayed mcp tools are not on the floor either`() {
        assertFalse(ToolApprovalDefaults.isHardFloor("mcp__server__anything"))
        assertFalse(ToolApprovalDefaults.isHardFloor("mcp__server__do_something"))
    }
}
