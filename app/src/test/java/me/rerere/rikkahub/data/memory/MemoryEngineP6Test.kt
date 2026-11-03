package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P6 增强单测：gist 批量解析 / 新鲜度令牌 / 完整块命中 / 快照份数 / per-assistant 上限。
 */
class MemoryEngineP6Test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- AutoLearn.parseGists ----------

    @Test
    fun `parse gists extracts id-pipe-gist lines`() {
        val out = AutoLearn.parseGists(
            "e-20261103-101000-ab12 | 讨论了BM25参数调优，确定k1=1.2\n" +
                "e-20261103-110000-cd34 | 部署了记忆引擎到真机\n" +
                "not a gist line\n" +
                "| empty id\n" +
                "e-20261103-120000-ef56 | \n"
        )
        assertEquals(2, out.size)
        assertEquals("讨论了BM25参数调优，确定k1=1.2", out["e-20261103-101000-ab12"])
        assertEquals("部署了记忆引擎到真机", out["e-20261103-110000-cd34"])
    }

    @Test
    fun `gist prompt lists every segment id`() {
        val segs = listOf(
            EpisodeSegment(id = "e-1", startTs = 0, endTs = 1, turns = 1, month = "2026-11", firstQuery = "写检索"),
            EpisodeSegment(id = "e-2", startTs = 2, endTs = 3, turns = 2, month = "2026-11", firstQuery = "装回手机"),
        )
        val p = AutoLearn.buildGistPrompt(segs)
        assertTrue(p.contains("e-1"))
        assertTrue(p.contains("e-2"))
        assertTrue(p.contains("<id> | <gist>"))
    }

    // ---------- MemoryFreshness ----------

    @Test
    fun `freshness token stable when files unchanged`() {
        val root = tmp.newFolder("scope")
        File(root, "L1_summary.md").writeText("# L1")
        assertEquals(MemoryFreshness.token(root), MemoryFreshness.token(root))
    }

    @Test
    fun `freshness token changes on modify and ignores snapshots`() {
        val root = tmp.newFolder("scope2")
        File(root, "L1_summary.md").writeText("# L1")
        val t1 = MemoryFreshness.token(root)
        File(root, "L1_summary.md").writeText("# L1 v2 longer content")
        assertNotEquals(t1, MemoryFreshness.token(root))
        val t3 = MemoryFreshness.token(root)
        val snap = File(root, "snapshots/123/manifest.json")
        snap.parentFile?.mkdirs()
        snap.writeText("{}")
        assertEquals(t3, MemoryFreshness.token(root))
    }

    // ---------- MemoryIndex full-text hits ----------

    @Test
    fun `hit text carries the chunk body`() {
        val store = AssistantMemoryStore(tmp.newFolder("mem"))
        store.writeL1("s1", "# 项目事实\n\nMyAgent 使用 BM25 检索引擎进行记忆检索，k1 系数是 1.2。")
        val idx = MemoryIndex(MemoryCatalog.buildDocs(store, "s1"))
        val hits = idx.search("BM25 检索引擎 k1", minAnchors = 0)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].text.contains("k1"))
    }

    // ---------- MemorySnapshot keep param ----------

    @Test
    fun `prune respects custom keep`() {
        val store = AssistantMemoryStore(tmp.newFolder("mem2"))
        store.writeL1("s2", "# L1") // create() 要求 scope 目录已存在
        repeat(5) { i ->
            val dir = MemorySnapshot.create(store, "s2", nowMs = 1000L + i)
            checkNotNull(dir) { "create #$i returned null" }
        }
        val snapRoot = File(store.dirFor("s2"), "snapshots")
        check(snapRoot.listFiles { f -> f.isDirectory }?.size == 5) { "expected 5 snap dirs before prune" }
        MemorySnapshot.prune(snapRoot, keep = 2)
        assertEquals(2, MemorySnapshot.list(store, "s2").size)
    }

    // ---------- parseRewrite with custom cap ----------

    @Test
    fun `parse rewrite respects per-assistant cap`() {
        val long = "x".repeat(5000)
        assertEquals(2000, AutoLearn.parseRewrite(long).length)
        assertEquals(800, AutoLearn.parseRewrite(long, maxChars = 800).length)
    }

    @Test
    fun `detect kind still routes`() {
        assertFalse(ToolOutputPool.detectKind("plain text only") == ToolOutputPool.Kind.DIFF)
    }
}
