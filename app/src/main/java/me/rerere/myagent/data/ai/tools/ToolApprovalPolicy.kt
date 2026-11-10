package me.rerere.myagent.data.ai.tools

/**
 * The three posture levels the device owner picks in Settings → Tool approvals.
 *
 * - [AUTO]: no friction. Every tool runs without prompting, relayed `mcp__*` tools included.
 *   My Agent 的默认档位（2026-10-30）：这个 App 的定位是「能自己干活的手机助手」，
 *   任何一次多余的确认都可能让定时任务 / 子代理 / workflow 永久卡住。
 * - [SEMI]: the blacklist decides. A tool on the blacklist asks; everything else
 *   auto-approves. This is the fine-grained mode — the user curates which specific tools
 *   need a confirmation.
 * - [MANUAL]: every tool call asks. The only exceptions are the in-memory "allow for this
 *   chat" grant (so a long agent session isn't drowned in prompts after the first OK) and
 *   `ask_user` in a headless run (nobody to answer).
 *
 * Ordering, top to bottom — first match wins:
 *
 *  1. `ask_user` / `request_secret` are human-input requests, not permission gates: they must
 *     pause for the user wherever there is a surface to ask on, and auto-approve only in a
 *     headless run (where they degrade to an unavailable envelope).
 *  2. A **headless** conversation (cron / sub-agent / workflow / external automation) has no
 *     approval channel: asking would hang the turn forever, so everything auto-approves.
 *  3. An in-memory **"allow for this chat"** grant suppresses the prompt for this conversation.
 *  4. The **mode** then decides: Auto → approve; Semi → blacklist; Manual → ask.
 *
 * 上游还有一个「硬地板」（[ToolApprovalDefaults.NO_ALWAYS_ALLOW]）和一条「所有 `mcp__*`
 * 一律要问」的规则，二者叠加会让一部分工具永远需要人工确认。My Agent 已整体移除这两层：
 * 见 [ToolApprovalDefaults.NO_ALWAYS_ALLOW] 处的说明。命令级红线 [HardlineCommandGuard]
 * 是另一回事，仍然无条件拒绝危险命令。
 */
enum class ApprovalMode {
    AUTO,
    SEMI,
    MANUAL,
}

object ToolApprovalPolicy {

    /** Local tools that request human input rather than a capability grant. */
    const val ASK_USER_TOOL_NAME: String = "ask_user"
    const val REQUEST_SECRET_TOOL_NAME: String = "request_secret"

    private val HUMAN_INPUT_TOOLS: Set<String> = setOf(
        ASK_USER_TOOL_NAME,
        REQUEST_SECRET_TOOL_NAME,
    )

    /**
     * True when [toolName] may run without prompting.
     *
     * Pure: every input is a parameter, so callers own the (async) lookups and the function
     * itself never touches Android, DataStore or globals beyond the constant floor.
     */
    fun shouldAutoApprove(
        toolName: String,
        blacklist: Set<String>,
        mode: ApprovalMode,
        headless: Boolean,
        allowedForChat: Boolean,
        hardFloor: Set<String> = ToolApprovalDefaults.NO_ALWAYS_ALLOW,
    ): Boolean {
        if (toolName in HUMAN_INPUT_TOOLS) return headless
        if (headless) return true
        if (toolName in hardFloor) return false
        if (allowedForChat) return true
        return when (mode) {
            ApprovalMode.AUTO -> true
            ApprovalMode.SEMI -> toolName !in blacklist
            ApprovalMode.MANUAL -> false
        }
    }
}
