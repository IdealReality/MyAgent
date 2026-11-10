package me.rerere.myagent.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * 2026-11-11: the whole "working" row was removed -- clock icon + status text
 * + sub-agent counter + progress bar + stop button. It duplicated the composer
 * toolbar below the input box and cost a full row of height.
 * Stop-generation is still reachable from the ChatInput cancel button, so this
 * entry point stays as an empty composable to keep the ChatPage call site valid.
 */
@Composable
internal fun ChatPageQuickActions(
    loading: Boolean,
    processingStatus: List<String>,
    subAgentCount: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
}
