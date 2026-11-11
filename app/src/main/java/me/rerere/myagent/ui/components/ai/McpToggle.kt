package me.rerere.myagent.ui.components.ai

import me.rerere.myagent.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * 一个 MCP 服务器开关的纯变换。
 *
 * 抽成纯函数是为了能被单测覆盖：UI 层真正的事故是「连点两个开关，第二次基于过期快照写回，
 * 把第一次的勾选冲掉」。把它写成 `(Assistant) -> Assistant`，配合
 * `AssistantDetailVM.updateAssistant` 在同一把 mutex 里串行执行，就天然累积。
 */
internal fun Assistant.applyMcpToggle(
    serverId: Uuid,
    enable: Boolean,
    knownServerIds: Set<Uuid>,
): Assistant {
    val next = mcpServers.toMutableSet()
    if (enable) next.add(serverId) else next.remove(serverId)
    next.removeIf { it !in knownServerIds } // 已删除的服务器不留在助手上
    return copy(mcpServers = next)
}
