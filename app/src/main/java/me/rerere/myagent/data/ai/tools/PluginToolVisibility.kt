package me.rerere.myagent.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.myagent.data.model.Assistant

/**
 * Plugin tools are registered globally — the plugin directory is app state, not assistant
 * state — but a tool *surface* belongs to one assistant. The `trpg` runtime is the first
 * plugin whose tools only mean something inside a specific mode: a dice roller, a character
 * sheet, a scene tracker and a save-slot tool in an everyday chat are four schemas of noise
 * the model will occasionally try to use ("let me roll for that").
 *
 * So one assistant flag is the whole mode switch: [Assistant.trpgStateInjection] on means the
 * state snapshot is injected every turn **and** the `trpg_*` tools are offered; off means
 * neither, and the surface is byte-identical to a build without the plugin installed.
 *
 * Disabling the plugin in the Plugins page still wins: nothing here re-adds tools that the
 * registry did not produce.
 */
object PluginToolVisibility {
    const val TRPG_PREFIX = "trpg_"
    const val TRPG_PLUGIN_ID = "trpg"

    /** Plugins whose tools AND prompt contribution stay out of this assistant's requests. */
    fun hiddenPluginIds(assistant: Assistant): Set<String> =
        if (assistant.trpgStateInjection) emptySet() else setOf(TRPG_PLUGIN_ID)

    fun forAssistant(tools: List<Tool>, assistant: Assistant): List<Tool> {
        if (assistant.trpgStateInjection) return tools
        return tools.filterNot { it.name.startsWith(TRPG_PREFIX) }
    }
}
