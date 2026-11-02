package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P1 存储层单测：切段判定 + 四层目录读写 + jsonl 解析容错。
 */
class MemoryEngineP1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- EpisodeSegmenter ----------

    @Test
    fun `continue when within idle window and same topic`() {
        val cursor = MemoryCursor(
            scopeId = "a-1",
            conversationId = "c1",
            startTs = 0,
            lastActivityTs = 10 * 60 * 1000L,
            turns = 2,
            firstQuery = "帮我写一个BM25检索引擎",
        )
        val d = EpisodeSegmenter.decide(cursor, nowMs = 15 * 60 * 1000L, text = "BM25的k1参数怎么调")
        assertTrue(d is EpisodeSegmenter.Decision.Continue)
    }

    @Test
    fun `close on idle over 2h`() {
        val cursor = MemoryCursor(
            scopeId = "a-1",
            conversationId = "c1",
            startTs = 0,
            lastActivityTs = 0,
            turns = 2,
            firstQuery = "帮我写一个BM25检索引擎",
        )
        val d = EpisodeSegmenter.decide(cursor, nowMs = 3 * 60 * 60 * 1000L, text = "再来一个需求")
        assertTrue(d is EpisodeSegmenter.Decision.CloseAndStart)
    }

    @Test
    fun `close on turns over limit`() {
        val cursor = MemoryCursor(
            scopeId = "a-1", conversationId = "c1",
            startTs = 0, lastActivityTs = 1000, turns = 24, firstQuery = "写个爬虫",
        )
        val d = EpisodeSegmenter.decide(cursor, nowMs = 2000, text = "顺便加个代理池")
        // 「顺便」是续跑词，但回合超限优先收段。
        assertTrue(d is EpisodeSegmenter.Decision.CloseAndStart)
    }

    @Test
    fun `continuation message never opens new topic segment`() {
        val cursor = MemoryCursor(
            scopeId = "a-1", conversationId = "c1",
            startTs = 0, lastActivityTs = 1000, turns = 3, firstQuery = "帮我规划部署方案",
        )
        for (text in listOf("继续", "然后呢", "还有吗", "再补充一点细节", "OK")) {
            val d = EpisodeSegmenter.decide(cursor, nowMs = 2000, text = text)
            assertTrue("text=$text -> $d", d is EpisodeSegmenter.Decision.Continue)
        }
    }

    @Test
    fun `topic change closes segment`() {
        val cursor = MemoryCursor(
            scopeId = "a-1", conversationId = "c1",
            startTs = 0, lastActivityTs = 1000, turns = 5, firstQuery = "帮我写一个俄语学习计划",
        )
        val d = EpisodeSegmenter.decide(cursor, nowMs = 2000, text = "查一下明天北京的天气怎么样")
        assertTrue(d is EpisodeSegmenter.Decision.CloseAndStart)
    }

    // ---------- AssistantMemoryStore ----------

    private fun newStore(): AssistantMemoryStore = AssistantMemoryStore(tmp.newFolder("memory"))

    @Test
    fun `l1 roundtrip and default empty`() {
        val store = newStore()
        assertEquals("", store.readL1("a-1"))
        store.writeL1("a-1", "# L1\nhello")
        assertEquals("# L1\nhello", store.readL1("a-1"))
    }

    @Test
    fun `episode append and parse`() {
        val store = newStore()
        val seg = EpisodeSegment(
            id = newEpisodeId(1793600000000L), startTs = 1, endTs = 2, turns = 3,
            month = monthKey(1793600000000L), firstQuery = "q1", lastQuery = "q2",
        )
        store.appendEpisode("a-1", seg)
        val all = store.readAllEpisodes("a-1")
        assertEquals(1, all.size)
        assertEquals(seg.id, all[0].id)
        assertEquals("q1", all[0].firstQuery)
    }

    @Test
    fun `episode parse skips corrupted lines`() {
        val store = newStore()
        val dir = File(tmp.newFolder("memory"), "a-2/episodes")
        dir.mkdirs()
        File(dir, "2026-10.jsonl").writeText(
            "{\"id\":\"e1\",\"startTs\":1,\"endTs\":2,\"turns\":1,\"month\":\"2026-10\"}\n" +
                "not a json line\n" +
                "\n" +
                "{\"id\":\"e2\",\"startTs\":3,\"endTs\":4,\"turns\":2,\"month\":\"2026-10\"}\n"
        )
        val all = store.readAllEpisodes("a-2")
        assertEquals(2, all.size)
        assertEquals(listOf("e1", "e2"), all.map { it.id })
    }

    @Test
    fun `update episode marks learned`() {
        val store = newStore()
        val seg = EpisodeSegment(
            id = "e-1", startTs = 1, endTs = 2, turns = 1, month = "2026-10", firstQuery = "q",
        )
        store.appendEpisode("a-3", seg)
        assertTrue(store.updateEpisode("a-3", seg.copy(learned = true, gist = "g")))
        val all = store.readAllEpisodes("a-3")
        assertTrue(all[0].learned)
        assertEquals("g", all[0].gist)
    }

    @Test
    fun `sessions index upsert dedups and caps`() {
        val store = newStore()
        repeat(10) { i ->
            store.upsertSessionEntry(
                "a-4",
                SessionIndexEntry(conversationId = "c$i", title = "t$i", endTs = i.toLong(), turns = 1)
            )
        }
        store.upsertSessionEntry("a-4", SessionIndexEntry(conversationId = "c3", title = "t3-new", endTs = 99, turns = 2))
        val idx = store.readSessionsIndex("a-4")
        assertEquals(10, idx.sessions.size)
        assertEquals("t3-new", idx.sessions.first { it.conversationId == "c3" }.title)
        // 上限
        repeat(AssistantMemoryStore.MAX_SESSION_ENTRIES) { i ->
            store.upsertSessionEntry("a-4", SessionIndexEntry(conversationId = "x$i", endTs = (1000 + i).toLong()))
        }
        assertEquals(AssistantMemoryStore.MAX_SESSION_ENTRIES, store.readSessionsIndex("a-4").sessions.size)
    }

    // ---------- EpisodeWriter ----------

    @Test
    fun `writer closes idle segment and starts new one`() {
        val store = newStore()
        val writer = EpisodeWriter(store)
        writer.onUserTurn("a-5", "c1", "帮我写检索引擎", "检索", nowMs = 0)
        writer.onAssistantReply("a-5", "c1", "好的，方案如下……", "检索", nowMs = 1000)
        // 3 小时后新话题
        writer.onUserTurn("a-5", "c1", "明天天气如何", "", nowMs = 3 * 60 * 60 * 1000L + 2000)
        val eps = store.readAllEpisodes("a-5")
        assertEquals(1, eps.size)
        assertEquals("帮我写检索引擎", eps[0].firstQuery)
        assertEquals("好的，方案如下……", eps[0].replyExcerpt.take(9))
        assertEquals(1, eps[0].turns)
        // 游标已开新段
        val cursor = store.readCursor("a-5")
        assertNotNull(cursor)
        assertEquals("明天天气如何", cursor?.firstQuery)
    }

    @Test
    fun `writer end conversation writes L4 entry`() {
        val store = newStore()
        val writer = EpisodeWriter(store)
        writer.onUserTurn("a-6", "c9", "设计四层记忆", "", nowMs = 0)
        val seg = writer.onConversationEnded("a-6", "c9", nowMs = 5000)
        assertNotNull(seg)
        assertEquals("设计四层记忆", seg?.firstQuery)
        val idx = store.readSessionsIndex("a-6")
        assertEquals(1, idx.sessions.size)
        assertEquals("c9", idx.sessions[0].conversationId)
        // 收完游标清空
        assertNull(store.readCursor("a-6"))
    }

    @Test
    fun `writer ignores second end call`() {
        val store = newStore()
        val writer = EpisodeWriter(store)
        writer.onUserTurn("a-7", "c1", "q", "", nowMs = 0)
        assertNotNull(writer.onConversationEnded("a-7", "c1", nowMs = 10))
        assertNull(writer.onConversationEnded("a-7", "c1", nowMs = 20))
    }

    @Test
    fun `memory prompts empty when nothing to inject`() {
        assertEquals("", MemoryPrompts.buildMemoryV1Prompt("", ""))
        val p = MemoryPrompts.buildMemoryV1Prompt("# L1\n内容", MemoryPrompts.toolHint(hasL1 = true))
        assertTrue(p.contains("**Memory (L1 summary)**"))
        assertTrue(p.contains("memory_recall"))
        assertFalse(p.contains("(empty)"))
    }
}
