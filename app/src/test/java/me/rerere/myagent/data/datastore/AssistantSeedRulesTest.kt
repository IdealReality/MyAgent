package me.rerere.myagent.data.datastore

import me.rerere.myagent.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the re-seed rule that makes deleting a bundled assistant stick.
 *
 * The shipped bug this guards: `DEFAULT_ASSISTANTS` were re-appended on every settings read, so
 * `removeAssistant` looked like it worked and the row reappeared on the next launch. The UI hid
 * it behind a "built-in assistants cannot be deleted" message instead of fixing the rule.
 */
class AssistantSeedRulesTest {

    private val a = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val b = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")
    private val bundled1 = Uuid.parse("bbbbbbbb-0000-0000-0000-0000000000b1")
    private val bundled2 = Uuid.parse("bbbbbbbb-0000-0000-0000-0000000000b2")

    private val userA = Assistant(id = a, name = "A")
    private val userB = Assistant(id = b, name = "B")
    private val shipped1 = Assistant(id = bundled1, name = "Built-in 1")
    private val shipped2 = Assistant(id = bundled2, name = "Built-in 2")
    private val defaults = listOf(shipped1, shipped2)

    private fun reseed(stored: List<Assistant>, deleted: Set<Uuid>) =
        AssistantSeedRules.reseedAssistants(stored, deleted, defaults)

    @Test
    fun `deleting a bundled assistant removes it and it does not come back`() {
        val stored = listOf(userA, shipped1, shipped2)

        val afterDelete = reseed(stored.filter { it.id != shipped1.id }, setOf(shipped1.id))

        assertEquals(listOf(userA.id, shipped2.id), afterDelete.map { it.id })
        // The next read must be a fixed point: no resurrection, no reordering.
        assertEquals(afterDelete.map { it.id }, reseed(afterDelete, setOf(shipped1.id)).map { it.id })
    }

    @Test
    fun `a bundled assistant the user never deleted is still seeded on upgrade`() {
        // shipped2 ships later: it is unknown to the user, so it appends after the stored order.
        val seeded = reseed(listOf(userA, userB), setOf(shipped1.id))

        assertEquals(listOf(userA.id, userB.id, shipped2.id), seeded.map { it.id })
    }

    @Test
    fun `stored order is preserved and defaults append in declared order`() {
        val seeded = reseed(listOf(userB, userA), emptySet())

        assertEquals(listOf(userB.id, userA.id, shipped1.id, shipped2.id), seeded.map { it.id })
    }

    @Test
    fun `deleting every assistant still leaves a non-empty list`() {
        // AssistantResolver.current uses first() and throws on an empty list; over-deleting a
        // corrupt store must not produce a state that crashes every getCurrentAssistant() caller.
        val allDefaultsDeleted = reseed(emptyList(), setOf(bundled1, bundled2))

        assertTrue(allDefaultsDeleted.isNotEmpty())
        assertEquals(defaults.map { it.id }, allDefaultsDeleted.map { it.id })
    }

    @Test
    fun `an empty store with no deletions seeds every bundled assistant`() {
        assertEquals(defaults.map { it.id }, reseed(emptyList(), emptySet()).map { it.id })
    }

    @Test
    fun `duplicate stored ids are not duplicated by the seed pass`() {
        val seeded = reseed(listOf(userA, shipped1), emptySet())

        assertEquals(seeded.map { it.id }.distinct().size, seeded.size)
        assertEquals(listOf(userA.id, shipped1.id, shipped2.id), seeded.map { it.id })
    }

    @Test
    fun `a removed bundled assistant is dropped from an upgraded install`() {
        // reseedAssistants only adds — dropping an id from DEFAULT_ASSISTANTS would leave the
        // already-persisted row on every upgraded install forever. dropRemovedAssistants is the
        // other half: the row must be gone, and neighbours keep their order.
        val removedId = Uuid.parse("cccccccc-0000-0000-0000-00000000000c")
        val stored = listOf(userA, Assistant(id = removedId, name = "Removed"), userB)

        val kept = AssistantSeedRules.dropRemovedAssistants(stored, setOf(removedId))

        assertEquals(listOf(userA.id, userB.id), kept.map { it.id })
        // Idempotent: a second read must not change anything.
        assertEquals(kept.map { it.id }, AssistantSeedRules.dropRemovedAssistants(kept, setOf(removedId)).map { it.id })
    }

    @Test
    fun `an install that only had the removed assistant still seeds a usable list`() {
        // removeAssistant refuses to delete the last assistant, but a store that predates that
        // gate can hold exactly one row, and it may be the removed one. The read pass must not
        // hand an empty list to AssistantResolver.current (which calls first() and throws).
        val removedId = Uuid.parse("cccccccc-0000-0000-0000-00000000000c")
        val stored = listOf(Assistant(id = removedId, name = "Removed"))

        val seeded = reseed(AssistantSeedRules.dropRemovedAssistants(stored, setOf(removedId)), emptySet())

        assertTrue(seeded.isNotEmpty())
        assertEquals(defaults.map { it.id }, seeded.map { it.id })
    }
}
