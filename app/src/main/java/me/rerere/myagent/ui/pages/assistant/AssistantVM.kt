package me.rerere.myagent.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.myagent.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.myagent.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.myagent.data.datastore.getAssistantById
import me.rerere.myagent.data.datastore.Settings
import me.rerere.myagent.data.datastore.SettingsStore
import me.rerere.myagent.data.files.FilesManager
import me.rerere.myagent.data.model.Assistant
import me.rerere.myagent.data.model.Avatar
import me.rerere.myagent.data.repository.ConversationRepository
import me.rerere.myagent.data.repository.FolderRepository
import me.rerere.myagent.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    // Dragging reorders a local snapshot (see AssistantPage). This writes that
    // order onto the latest settings, so a gesture cannot clobber a concurrent edit.
    fun reorderAssistants(orderedIds: List<Uuid>) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    assistants = AssistantReorder.merge(current.assistants, orderedIds) { it.id },
                )
            }
        }
    }

    fun reorderAssistantTags(orderedIds: List<Uuid>) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    assistantTags = AssistantReorder.merge(current.assistantTags, orderedIds) { it.id },
                )
            }
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(assistant)
                )
            )
        }
    }

    /**
     * 删除助手。
     *
     * 顺序是有意的：
     *  1. 先把该助手名下的对话整体改派给默认助手，并解除它们的文件夹归属
     *     （复用既有的 ConversationDAO.updateAssistantId；folder_id 必须一并清空，否则
     *     对话换了 assistant_id 却仍挂在旧助手的文件夹里，新助手的列表按 folder_id = ''
     *     取会话，用户会看到「对话消失」。两者在同一 Room 事务里完成）。
     *  2. 删掉该助手的文件夹（文件夹是助手私有配置）。
     *  3. 一次 settingsStore.update 完成「移除助手 + 当前指针切到默认助手 + 清归档槽位」，
     *     而不是分两次写盘 —— 两次写盘之间存在中间态：助手已从列表消失、assistantId
     *     却仍指向它，此时进程被杀就会留下悬空指针。
     *  4. 记忆与头像/背景文件这些不可回滚的清理放到最后，只在设置已成功落盘后执行。
     *
     * 「默认助手」的口径与全局一致：DEFAULT_ASSISTANT_ID（Settings.assistantId 的缺省值），
     * 若它已不在列表中则退到剩余的第一个助手；与 AssistantResolver.current 的兜底规则相同。
     */
    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val remaining = settings.assistants.filter { it.id != assistant.id }
            // 至少保留一个助手：AssistantResolver.current 在空列表上会抛异常，
            // 删光会让所有 getCurrentAssistant() 调用点崩溃。UI 在只剩一个时已经禁用
            // 删除入口，这里是并发/过期快照下的第二道闸。
            if (remaining.isEmpty()) return@launch

            val fallback = settings
                .getAssistantById(DEFAULT_ASSISTANT_ID)
                ?.takeIf { it.id != assistant.id }
                ?: remaining.first()

            // 1) 对话改派 + 解除文件夹归属（Room 事务）
            conversationRepo.reassignAndUnfileConversationsOfAssistant(assistant.id, fallback.id)
            // 2) 删除该助手的文件夹
            folderRepository.deleteFoldersOfAssistant(assistant.id)

            // 3) 一次落盘：移除助手 + 当前选中助手安全切换 + 清掉归档文件夹槽位
            //    + 记下内置助手的删除（否则 PreferencesStore 的 re-seed 下次读设置就把它加回来）
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.filter { it.id != assistant.id },
                    deletedBuiltInAssistantIds = if (assistant.id in DEFAULT_ASSISTANTS_IDS) {
                        current.deletedBuiltInAssistantIds + assistant.id
                    } else {
                        current.deletedBuiltInAssistantIds
                    },
                    assistantId = if (current.assistantId == assistant.id) {
                        fallback.id
                    } else {
                        current.assistantId
                    },
                    subAgentArchiveFolders = current.subAgentArchiveFolders - assistant.id.toString(),
                )
            }

            // 4) 不可回滚的清理放最后
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            cleanupAssistantFiles(assistant)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
            assistant.darkBackground?.let { add(it.toUri()) }
            assistant.lightBackground?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
