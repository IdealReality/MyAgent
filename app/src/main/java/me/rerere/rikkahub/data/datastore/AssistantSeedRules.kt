package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The re-seed rule for bundled assistants, extracted from the settings pipeline so it can be
 * unit-tested without a DataStore.
 *
 * ## Why this exists
 *
 * [DEFAULT_ASSISTANTS] used to be re-added unconditionally on every settings read: any bundled
 * assistant missing from the stored list was appended back. That made the delete action
 * cosmetic — `AssistantVM.removeAssistant` removed the row, and the very next read put it
 * back, so the UI needed a "built-in assistants cannot be deleted" gate just to hide the fact.
 *
 * [Settings.deletedBuiltInAssistantIds] is the deletion record: `removeAssistant` adds the id,
 * and this function skips it forever after. Deleting a bundled assistant therefore sticks
 * across restarts, while a *newly shipped* bundled assistant still appears on upgrade (nothing
 * recorded it as deleted, so it is unknown to the user and safe to add).
 */
object AssistantSeedRules {

    /**
     * Merge [stored] with the bundled defaults, dropping the ones in [deletedIds].
     *
     * Order is preserved: the stored order wins, and surviving defaults append at the end in
     * [DEFAULT_ASSISTANTS] order. An **empty result is never returned** — `AssistantResolver`
     * resolves the current assistant with `first()` and throws on an empty list, so a corrupt or
     * over-deleted store falls back to re-seeding every bundled assistant. That case can only be
     * reached by hand-editing the store: `removeAssistant` refuses to delete the last assistant,
     * and [deletedIds] never accumulates every bundled id through the UI.
     */
    fun reseedAssistants(
        stored: List<Assistant>,
        deletedIds: Set<Uuid>,
        defaults: List<Assistant> = DEFAULT_ASSISTANTS,
    ): List<Assistant> {
        val seeded = stored
            .ifEmpty { defaults.filter { it.id !in deletedIds } }
            .toMutableList()
        defaults.forEach { defaultAssistant ->
            if (defaultAssistant.id in deletedIds) return@forEach
            if (seeded.none { it.id == defaultAssistant.id }) {
                seeded.add(defaultAssistant.copy())
            }
        }
        return if (seeded.isEmpty()) defaults.toList() else seeded
    }

    /**
     * Drop rows whose id belongs to a bundled assistant this build no longer ships.
     *
     * [reseedAssistants] only ever *adds*: deleting an id from [DEFAULT_ASSISTANTS] stops it
     * being reseeded but leaves the row already persisted on upgraded installs, so a removed
     * bundled assistant would survive forever. The removal record lives in
     * [REMOVED_BUILTIN_ASSISTANT_IDS] and is applied here, before the reseed pass — a plain
     * filter, so it is idempotent and order-preserving for every other row.
     */
    fun dropRemovedAssistants(
        stored: List<Assistant>,
        removedIds: Set<Uuid> = REMOVED_BUILTIN_ASSISTANT_IDS,
    ): List<Assistant> = stored.filterNot { it.id in removedIds }
}
