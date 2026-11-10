package me.rerere.myagent.data.datastore

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-created group of skills. Lives in [Settings.skillGroups] and is the unit for
 * the three bulk operations on the Skills page: enable-all, disable-all, delete-all.
 *
 * A skill can belong to at most one group — [SkillGroupRules.assignToGroup] enforces
 * this by removing the skill from any other group before adding it to the target. The
 * "ungrouped" section on the Skills page is simply every skill whose name appears in no
 * group.
 *
 * Ownership decides who sees the group:
 *  - [ownerAssistantId] == null → **shared group**. Every assistant can use it, and its
 *    members are enabled for every assistant automatically (the group's [enabled] switch
 *    is the only off button).
 *  - [ownerAssistantId] != null → **assistant-private group**. Only that assistant's
 *    skill surface shows it; its members still need per-assistant `enabledSkills`.
 *
 * [skillNames] is an ordered list: the order is the display order inside the group and
 * is what drag-to-reorder on the Skills page rewrites. The group's own order is the
 * position of this object in [Settings.skillGroups].
 *
 * Core skills (see [SkillGroupRules.CORE_SKILL_NAMES]: `agent-core`, `autonomous-agent`)
 * are never offered for grouping: they are the system's own persona and must stay
 * unconditionally active. [SkillGroupRules.canGroupSkill] is the gate the UI consults before
 * listing a skill as selectable.
 *
 * The `enabled` flag is the group's on/off switch. When `false`, every skill in
 * [skillNames] is suppressed from the AI's available-skills surface even if the
 * assistant has it in its `enabledSkills` — see
 * [SkillGroupRules.disabledSkillNames] / [SkillGroupRules.effectiveEnabledSkills].
 */
@Serializable
data class SkillGroup(
    val id: String,
    val name: String,
    @SerialName("skill_names")
    val skillNames: List<String> = emptyList(),
    val enabled: Boolean = true,
    /**
     * null = shared with every assistant; otherwise the assistant id that owns this group.
     * Absent in older settings → shared (backwards compatible: pre-existing groups keep
     * behaving as global batches).
     */
    @SerialName("owner_assistant_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ownerAssistantId: String? = null,
)

/**
 * Pure functions over [SkillGroup] lists. Kept free of Android types so the behaviour is
 * unit-testable without a device (see `SkillGroupRulesTest`). The UI consults
 * [canGroupSkill] with a skill's name to decide what the group editor may offer.
 */
object SkillGroupRules {

    /**
     * The app's own persona skills. Bundled, auto-loaded into the system prompt every turn,
     * and what teaches the model how to behave — disabling or grouping them would break the
     * agent, so the Skills page pins them in a locked section. Kept as an explicit name set
     * (rather than "any `auto_load: true` skill") so a user's own auto-load skill can still
     * be grouped and switched like any other.
     */
    val CORE_SKILL_NAMES = setOf("agent-core", "autonomous-agent")

    /** Core skills are pinned; every other skill may be put in a group. */
    fun canGroupSkill(skillName: String): Boolean = skillName !in CORE_SKILL_NAMES

    /** Groups visible to [assistantId]: shared groups plus that assistant's own groups. */
    fun groupsFor(groups: List<SkillGroup>, assistantId: String): List<SkillGroup> =
        groups.filter { it.ownerAssistantId == null || it.ownerAssistantId == assistantId }

    fun sharedGroups(groups: List<SkillGroup>): List<SkillGroup> =
        groups.filter { it.ownerAssistantId == null }

    fun privateGroupsOf(groups: List<SkillGroup>, assistantId: String): List<SkillGroup> =
        groups.filter { it.ownerAssistantId == assistantId }

    /**
     * Members of shared, enabled groups — auto-enabled for every assistant. A shared group
     * that is switched off contributes nothing (its members then count as disabled via
     * [disabledSkillNames]).
     */
    fun sharedSkillNames(groups: List<SkillGroup>): Set<String> =
        groups.filter { it.ownerAssistantId == null && it.enabled }
            .flatMap { it.skillNames }
            .toSet()

    /**
     * The set of skill names suppressed by disabled groups. A skill is suppressed iff it
     * is a member of any group whose [SkillGroup.enabled] is `false`. Membership in a
     * disabled group is the only suppression vector; an enabled group never suppresses.
     */
    fun disabledSkillNames(groups: List<SkillGroup>): Set<String> =
        groups.filterNot { it.enabled }.flatMap { it.skillNames }.toSet()

    /**
     * Effective enabled-skill set for one assistant:
     * `enabledSkills ∪ sharedGroupMembers − disabledGroupMembers`.
     *
     * Private groups are NOT auto-enabled — their members appear on that assistant's skill
     * page and the user opts in, so grouping never silently expands a model's surface.
     */
    fun effectiveEnabledSkills(
        enabledSkills: Set<String>,
        groups: List<SkillGroup>,
        assistantId: String? = null,
    ): Set<String> {
        val visible = if (assistantId == null) groups else groupsFor(groups, assistantId)
        return (enabledSkills + sharedSkillNames(visible)) - disabledSkillNames(groups)
    }

    /**
     * Add [skillName] to group [groupId], first removing it from any other group so a
     * skill has at most one home. Returns the updated list; the original is untouched.
     * If [groupId] is not found the list is returned unchanged.
     */
    fun assignToGroup(
        groups: List<SkillGroup>,
        groupId: String,
        skillName: String,
    ): List<SkillGroup> {
        if (groups.none { it.id == groupId }) return groups
        // Pull the skill out of every group first (target included), then append it to the
        // target exactly once so the target's order stays append-based and duplicate-free.
        val cleared = groups.map { g ->
            if (g.skillNames.contains(skillName)) g.copy(skillNames = g.skillNames - skillName) else g
        }
        return cleared.map { g ->
            if (g.id == groupId) g.copy(skillNames = g.skillNames + skillName) else g
        }
    }

    /**
     * Remove [skillName] from whichever group holds it. Returns the updated list; the
     * original is untouched. A skill not in any group is a no-op.
     */
    fun removeFromGroup(
        groups: List<SkillGroup>,
        skillName: String,
    ): List<SkillGroup> = groups.map { g ->
        if (g.skillNames.contains(skillName)) g.copy(skillNames = g.skillNames - skillName) else g
    }

    /** Toggle [SkillGroup.enabled] for the group with [groupId]. Unknown id is a no-op. */
    fun setGroupEnabled(
        groups: List<SkillGroup>,
        groupId: String,
        enabled: Boolean,
    ): List<SkillGroup> = groups.map { g ->
        if (g.id == groupId) g.copy(enabled = enabled) else g
    }

    /** Rename the group with [groupId]. Unknown id is a no-op. */
    fun renameGroup(
        groups: List<SkillGroup>,
        groupId: String,
        name: String,
    ): List<SkillGroup> = groups.map { g ->
        if (g.id == groupId) g.copy(name = name) else g
    }

    /** Set (or clear, with null) the owning assistant of a group. Unknown id is a no-op. */
    fun setGroupOwner(
        groups: List<SkillGroup>,
        groupId: String,
        assistantId: String?,
    ): List<SkillGroup> = groups.map { g ->
        if (g.id == groupId) g.copy(ownerAssistantId = assistantId) else g
    }

    /**
     * Delete the group definition only — its skills stay on disk and fall back into the
     * "ungrouped" section. Use [reassignAll] + filter when the caller also wants to drop
     * the skills themselves.
     */
    fun deleteGroup(
        groups: List<SkillGroup>,
        groupId: String,
    ): List<SkillGroup> = groups.filterNot { it.id == groupId }

    /** All skill names across every group (enabled or not). */
    fun groupedSkillNames(groups: List<SkillGroup>): Set<String> =
        groups.flatMap { it.skillNames }.toSet()

    /**
     * Names in [allSkillNames] that are neither grouped nor core. Drives the
     * "ungrouped" section of the Skills page.
     *
     * [coreSkillNames] are the auto-load skills; they are excluded from the ungrouped
     * list because the page shows them in their own locked "core" section.
     */
    fun ungroupedSkillNames(
        allSkillNames: List<String>,
        groups: List<SkillGroup>,
        coreSkillNames: Set<String>,
    ): List<String> {
        val grouped = groupedSkillNames(groups)
        return allSkillNames.filter { it !in grouped && it !in coreSkillNames }
    }

    // ---- Ordering (drag to reorder) -----------------------------------------

    /** Move the element at [from] to [to] inside a copy of [list]. Out-of-range = no-op. */
    fun <T> moveInList(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** Reorder the group sections on the Skills page. */
    fun moveGroup(groups: List<SkillGroup>, from: Int, to: Int): List<SkillGroup> =
        moveInList(groups, from, to)

    /**
     * Apply a user-defined order to [names]. Names missing from [order] keep their input
     * relative order after the ordered ones; names in [order] that no longer exist are
     * ignored (so a deleted skill leaves no hole).
     */
    fun orderSkillNames(names: List<String>, order: List<String>): List<String> {
        if (order.isEmpty()) return names
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { i, n -> rank.putIfAbsent(n, i) }
        val ordered = names.filter { it in rank }.sortedBy { rank[it] }
        val rest = names.filter { it !in rank }
        return ordered + rest
    }
}
