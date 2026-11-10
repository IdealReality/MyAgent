package me.rerere.myagent.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ReviewStateBus {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    fun start(reason: String?) {
        val text = reason?.trim().orEmpty()
        _label.value = if (text.isEmpty()) "审查中…" else "审查中 · " + text
    }

    fun stop() {
        _label.value = null
    }
}
