package me.rerere.myagent.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P4 上下文体系单测：任务边界判定/摘要 + CCR 原文池。
 */
class MemoryEngineP4Test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- TaskBoundary ----------

    @Test
    fun `continuation prompts never fold`() {
        for (t in listOf("继续", "还有吗", "顺便把日志也看一下", "好的", "OK")) {
            assertFalse(
                "text=$t",
                TaskBoundary.looksLikeNewTask(t),
            )
        }
    }

    @Test
    fun `new task prompt folds when prior context enough`() {
        val d = TaskBoundary.shouldFold(
            priorUserPrompts = listOf("帮我写一个检索引擎", "再加个BM25评分"),
            lastReply = "完成了，两个模块都已经提交，测试通过。".repeat(3),
            newTaskPrompt = "帮我规划一下明天的部署时间表，包括回滚预案和验证步骤",
            priorTokensEstimate = 8000,
        )
        assertTrue(d.shouldFold)
    }

    @Test
    fun `unfinished reply never folds`() {
        val d = TaskBoundary.shouldFold(
            priorUserPrompts = listOf("帮我写一个检索引擎"),
            lastReply = "好的我马上处理，正在查资料，接下来我会先看一下配置。",
            newTaskPrompt = "帮我规划一下明天的部署时间表，包括回滚预案和验证步骤",
            priorTokensEstimate = 8000,
        )
        assertFalse(d.shouldFold)
    }

    @Test
    fun `small context never folds`() {
        val d = TaskBoundary.shouldFold(
            priorUserPrompts = listOf("帮我写一个检索引擎"),
            lastReply = "完成了，两个模块都已经提交。".repeat(3),
            newTaskPrompt = "帮我规划一下明天的部署时间表，包括回滚预案和验证步骤",
            priorTokensEstimate = 100,
        )
        assertFalse(d.shouldFold)
    }

    @Test
    fun `digest within budget and has three sections`() {
        val digest = TaskBoundary.buildDigest(
            priorUserPrompts = listOf("第一个任务", "第二个任务", "最后一个任务"),
            lastReply = "答复内容",
            conversationId = "c1",
        )
        assertTrue(digest.contains("任务边界摘要"))
        assertTrue(digest.contains("上一任务总结"))
        assertTrue(digest.contains("更早对话概览"))
        assertTrue(digest.length <= TaskBoundary.DIGEST_MAX_CHARS + 30)
    }

    // ---------- ToolOutputPool ----------

    @Test
    fun `detect kind routes by content`() {
        assertEquals(ToolOutputPool.Kind.SEARCH, ToolOutputPool.detectKind("搜索结果: 1. foo\n2. bar"))
        assertEquals(ToolOutputPool.Kind.DIFF, ToolOutputPool.detectKind("diff --git a/x b/x\n+++ b/x\n@@ -1,2 +1,3 @@"))
        assertEquals(ToolOutputPool.Kind.LOG, ToolOutputPool.detectKind("12:00:01 [ERROR] boom\n\tat com.foo.Bar.baz(Bar.kt:10)"))
        assertEquals(ToolOutputPool.Kind.TEXT, ToolOutputPool.detectKind("just a plain sentence"))
    }

    @Test
    fun `smart preview keeps head and tail lines`() {
        val text = (1..100).joinToString("\n") { "line $it: ${"x".repeat(80)}" }
        val preview = ToolOutputPool.smartPreview(text, headChars = 500, tailChars = 300)
        assertTrue(preview.contains("line 1:"))
        assertTrue(preview.contains("line 100:"))
        assertTrue(preview.contains("中间省略"))
        assertTrue(preview.length < text.length)
    }

    @Test
    fun `handle is stable for same content`() {
        val h1 = ToolOutputPool.handleOf("same content")
        val h2 = ToolOutputPool.handleOf("same content")
        val h3 = ToolOutputPool.handleOf("different")
        assertEquals(h1, h2)
        assertFalse(h1 == h3)
        assertEquals(16, h1.length)
    }

    @Test
    fun `handle notice contains sed hint`() {
        val n = ToolOutputPool.handleNotice("abc123", "/tool_outputs/ccr-abc123.txt", 100, 4000, ToolOutputPool.Kind.LOG)
        assertTrue(n.contains("sed -n"))
        assertTrue(n.contains("handle: abc123"))
    }
}
