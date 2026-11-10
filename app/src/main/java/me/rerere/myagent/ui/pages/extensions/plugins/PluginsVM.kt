package me.rerere.myagent.ui.pages.extensions.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.myagent.plugin.PluginRegistry
import me.rerere.myagent.plugin.PluginStatus

data class PluginsUiState(
    val loading: Boolean = true,
    val statuses: List<PluginStatus> = emptyList(),
    val checkResult: String? = null,
    val runResult: Pair<String, String>? = null,
    val message: String? = null,
)

class PluginsVM(
    private val registry: PluginRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val statuses = withContext(Dispatchers.IO) {
                runCatching {
                    registry.reload()
                    registry.statuses()
                }.getOrElse { emptyList() }
            }
            _state.value = _state.value.copy(loading = false, statuses = statuses)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                registry.store.setEnabled(id, enabled)
                if (enabled) registry.store.clearFailures(id)
                registry.reload()
            }
            refresh()
        }
    }

    fun rollback(id: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val restored = registry.store.rollback(id)
                registry.reload()
                restored
            }
            _state.value = _state.value.copy(
                message = if (ok) "Rolled back $id" else "No snapshot for $id",
            )
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                registry.store.deletePlugin(id)
                registry.reload()
            }
            refresh()
        }
    }

    fun inspect(id: String) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { registry.inspect(id) }
            _state.value = _state.value.copy(checkResult = report)
        }
    }

    fun runTool(id: String, tool: String) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { registry.invokeForTest(id, tool) }
            _state.value = _state.value.copy(runResult = "$id · $tool" to report)
            refresh()
        }
    }

    fun dismissRun() {
        _state.value = _state.value.copy(runResult = null)
    }

    fun dismissCheck() {
        _state.value = _state.value.copy(checkResult = null)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
