package me.rerere.myagent.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-logic tests for [SkillGroupRules]. No Android dependency — the rules operate on
 * plain [SkillGroup] lists, so these run under kcheck without a device.
 */
class SkillGroupRulesTest {

    private val g1 = SkillGroup(id = "a", name = "Alpha", skillNames = listOf("s1", "s2"), enabled = true)
    private val g2 = SkillGroup(id = "b", name = "Beta", skillNames = listOf("s3"), enabled = false)
    private val g3 = SkillGroup(
        id = "c",
        name = "Gamma",
        skillNames = listOf("s4", "s5"),
        enabled = false,
        ownerAssistantId = "assistant-1",
    )
    private val groups = listOf(g1, g2, g3)

    @Test
    fun `disabled skill names come only from disabled groups`() {
        val disabled = SkillGroupRules.disabledSkillNames(groups)
        assertEquals(setOf("s3", "s4", "s5"), disabled)
    }

    @Test
    fun `disabled set is empty when all groups are enabled`() {
        val allOn = listOf(g1, g2.copy(enabled = true), g3.copy(enabled = true))
        assertTrue(SkillGroupRules.disabledSkillNames(allOn).isEmpty())
    }

    @Test
    fun `effective enabled skills subtracts disabled members`() {
        val enabled = setOf("s1", "s2", "s3", "s6")
        val effective = SkillGroupRules.effectiveEnabledSkills(enabled, groups, "assistant-2")
        // s3 is in disabled g2; s4/s5 belong to another assistant's disabled group; s6 is ungrouped.
        assertEquals(setOf("s1", "s2", "s6"), effective)
    }

    @Test
    fun `shared groups are auto-enabled for every assistant`() {
        val sharedOn = listOf(g1, g2.copy(enabled = true))
        // No per-assistant opt-in at all, yet the shared member comes through.
        assertEquals(
            setOf("s1", "s2", "s3"),
            SkillGroupRules.effectiveEnabledSkills(emptySet(), sharedOn, "any-assistant"),
        )
    }

    @Test
    fun `private groups are not auto-enabled`() {
        val private = listOf(g3.copy(enabled = true))
        assertEquals(
            emptySet<String>(),
            SkillGroupRules.effectiveEnabledSkills(emptySet(), private, "assistant-1"),
        )
        // ...but the assistant that owns it still sees it in its group list.
        assertEquals(1, SkillGroupRules.groupsFor(private, "assistant-1").size)
        assertEquals(0, SkillGroupRules.groupsFor(private, "assistant-2").size)
    }

    @Test
    fun `groups for an assistant keeps shared plus its own`() {
        val visible = SkillGroupRules.groupsFor(groups, "assistant-1")
        assertEquals(listOf("a", "b", "c"), visible.map { it.id })
        assertEquals(listOf("a", "b"), SkillGroupRules.groupsFor(groups, "assistant-2").map { it.id })
    }

    @Test
    fun `core skills cannot be grouped`() {
        assertFalse(SkillGroupRules.canGroupSkill("agent-core"))
        assertFalse(SkillGroupRules.canGroupSkill("autonomous-agent"))
        assertTrue(SkillGroupRules.canGroupSkill("auto-reply"))
        assertTrue(SkillGroupRules.canGroupSkill("my-own-skill"))
    }

    @Test
    fun `assign to group removes from previous group`() {
        // s2 is in g1; assign it to g2 → it leaves g1.
        val updated = SkillGroupRules.assignToGroup(groups, "b", "s2")
        val g1u = updated.first { it.id == "a" }
        val g2u = updated.first { it.id == "b" }
        assertEquals(listOf("s1"), g1u.skillNames)
        assertEquals(listOf("s3", "s2"), g2u.skillNames)
    }

    @Test
    fun `assign is idempotent and keeps a single copy`() {
        val once = SkillGroupRules.assignToGroup(groups, "b", "s2")
        val twice = SkillGroupRules.assignToGroup(once, "b", "s2")
        assertEquals(once, twice)
        assertEquals(1, twice.first { it.id == "b" }.skillNames.count { it == "s2" })
    }

    @Test
    fun `assign to unknown group is a no-op`() {
        val updated = SkillGroupRules.assignToGroup(groups, "zzz", "s1")
        assertEquals(groups, updated)
    }

    @Test
    fun `remove from group plucks it out`() {
        val updated = SkillGroupRules.removeFromGroup(groups, "s3")
        val g2u = updated.first { it.id == "b" }
        assertTrue("s3" !in g2u.skillNames)
    }

    @Test
    fun `remove a skill that is in no group is a no-op`() {
        val updated = SkillGroupRules.removeFromGroup(groups, "s99")
        assertEquals(groups, updated)
    }

    @Test
    fun `set group enabled toggles only the target`() {
        val off = SkillGroupRules.setGroupEnabled(groups, "a", enabled = false)
        assertFalse(off.first { it.id == "a" }.enabled)
        assertFalse(off.first { it.id == "b" }.enabled)
        assertEquals(listOf("s3"), off.first { it.id == "b" }.skillNames)
        val on = SkillGroupRules.setGroupEnabled(groups, "b", enabled = true)
        assertTrue(on.first { it.id == "b" }.enabled)
        assertTrue(on.first { it.id == "a" }.enabled)
    }

    @Test
    fun `rename group changes only the name`() {
        val updated = SkillGroupRules.renameGroup(groups, "a", "Renamed")
        assertEquals("Renamed", updated.first { it.id == "a" }.name)
        assertEquals("Beta", updated.first { it.id == "b" }.name)
    }

    @Test
    fun `set group owner moves a group between shared and private`() {
        val owned = SkillGroupRules.setGroupOwner(groups, "a", "assistant-9")
        assertEquals("assistant-9", owned.first { it.id == "a" }.ownerAssistantId)
        val back = SkillGroupRules.setGroupOwner(owned, "a", null)
        assertNull(back.first { it.id == "a" }.ownerAssistantId)
    }

    @Test
    fun `delete group removes definition but skills stay`() {
        val updated = SkillGroupRules.deleteGroup(groups, "b")
        assertEquals(2, updated.size)
        assertTrue("s3" !in SkillGroupRules.groupedSkillNames(updated))
    }

    @Test
    fun `grouped skill names is the union across all groups`() {
        assertEquals(setOf("s1", "s2", "s3", "s4", "s5"), SkillGroupRules.groupedSkillNames(groups))
    }

    @Test
    fun `ungrouped excludes grouped and core skills`() {
        val all = listOf("s1", "s2", "s3", "s6", "s7", "agent-core", "autonomous-agent")
        val core = setOf("agent-core", "autonomous-agent")
        val ungrouped = SkillGroupRules.ungroupedSkillNames(all, groups, core)
        assertEquals(listOf("s6", "s7"), ungrouped)
    }

    @Test
    fun `empty groups yield empty disabled set`() {
        assertTrue(SkillGroupRules.disabledSkillNames(emptyList()).isEmpty())
    }

    // ---- ordering -----------------------------------------------------------

    @Test
    fun `move group reorders the section list`() {
        val moved = SkillGroupRules.moveGroup(groups, 0, 2)
        assertEquals(listOf("b", "c", "a"), moved.map { it.id })
    }

    @Test
    fun `move group out of range is a no-op`() {
        assertEquals(groups, SkillGroupRules.moveGroup(groups, 0, 9))
        assertEquals(groups, SkillGroupRules.moveGroup(groups, -1, 0))
    }

    @Test
    fun `order skill names applies the saved order and keeps new names`() {
        val names = listOf("a", "b", "c", "d")
        assertEquals(
            listOf("c", "a", "b", "d"),
            SkillGroupRules.orderSkillNames(names, listOf("c", "a")),
        )
        // Stale entry for a deleted skill is ignored.
        assertEquals(
            listOf("c", "a", "b", "d"),
            SkillGroupRules.orderSkillNames(names, listOf("gone", "c")),
        )
    }

    @Test
    fun `legacy json without owner decodes as a shared group`() {
        val legacy = """[{"id":"g1","name":"Old","skill_names":["x","y"],"enabled":true}]"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<SkillGroup>>(legacy)
        assertEquals(1, decoded.size)
        assertEquals(listOf("x", "y"), decoded[0].skillNames)
        assertNull(decoded[0].ownerAssistantId)
    }
}
