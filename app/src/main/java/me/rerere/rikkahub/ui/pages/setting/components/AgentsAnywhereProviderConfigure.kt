package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.agentsanywhere.AgentsAnywhereProvider
import me.rerere.rikkahub.data.agentsanywhere.AgentsAnywhereSessionStore
import org.koin.compose.koinInject

/**
 * Agents Anywhere 的连接配置。
 *
 * 这一屏只填「连到哪台机器上的哪个 agent」。模型不在这里选——远端目录由
 * [AgentsAnywhereProvider.fetchCatalog] 拉下来写进 provider 的 models，之后在「模型」页里
 * 像普通模型一样挑。这样选中等价于「让远端那个 agent 直接回答」，没有本地模型参与转述。
 */
@Composable
fun AgentsAnywhereProviderConfigure(
    provider: ProviderSetting.AgentsAnywhere,
    onEdit: (ProviderSetting.AgentsAnywhere) -> Unit,
) {
    val providerImpl: AgentsAnywhereProvider = koinInject()
    val sessionStore: AgentsAnywhereSessionStore = koinInject()
    val scope = rememberCoroutineScope()

    var edit by remember(provider) { mutableStateOf(provider) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var busy by remember(provider) { mutableStateOf(false) }

    fun commit(next: ProviderSetting.AgentsAnywhere) {
        edit = next
        onEdit(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "远端 agent 直接作为一个模型。填好地址与三项定位信息后，点「拉取模型」，" +
                "然后在「模型」页里选中远端模型即可对话——远端的推理、工具调用与流式输出都" +
                "原样出现在这里，不经过本地模型转述。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(
                    label = "服务器地址（只填 origin）",
                    value = edit.serverOrigin,
                    placeholder = "https://your-host:8443",
                    onChange = { commit(edit.copy(serverOrigin = it)) },
                )
                Field(
                    label = "Access token",
                    value = edit.accessToken,
                    placeholder = "/api/v2/auth/login 拿到的 accessToken",
                    secret = true,
                    onChange = { commit(edit.copy(accessToken = it)) },
                )
                Field(
                    label = "Connector ID",
                    value = edit.connectorId,
                    placeholder = "conn_xxx",
                    onChange = { commit(edit.copy(connectorId = it)) },
                )
                Field(
                    label = "Project ID",
                    value = edit.projectId,
                    placeholder = "proj_xxx",
                    onChange = { commit(edit.copy(projectId = it)) },
                )
                Field(
                    label = "Runtime ID",
                    value = edit.runtimeId,
                    placeholder = "rti_xxx",
                    onChange = { commit(edit.copy(runtimeId = it)) },
                )
                Field(
                    label = "工作目录（可选）",
                    value = edit.cwd,
                    placeholder = "必须与 project 的 workspacePath 一致",
                    onChange = { commit(edit.copy(cwd = it)) },
                )
                Field(
                    label = "权限档位 selectionId",
                    value = edit.permissionSelectionId,
                    placeholder = "full-auto",
                    onChange = { commit(edit.copy(permissionSelectionId = it)) },
                )
                Field(
                    label = "单轮超时（秒）",
                    value = (edit.turnTimeoutMillis / 1000L).toString(),
                    onChange = { raw ->
                        val seconds = raw.filter(Char::isDigit).toLongOrNull() ?: 600L
                        commit(edit.copy(turnTimeoutMillis = seconds.coerceAtLeast(30L) * 1000L))
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        val catalog = providerImpl.fetchCatalog(edit)
                        status = when {
                            catalog == null -> "拉取失败：检查地址 / token / connector / runtime"
                            catalog.isEmpty() -> "连上了，但远端 runtime 没有可用模型"
                            else -> {
                                val merged = catalog.map { remote ->
                                    edit.models.firstOrNull { it.modelId == remote.modelId }
                                        ?.let { existing -> remote.copy(id = existing.id) }
                                        ?: remote
                                }
                                commit(edit.copy(models = merged))
                                "已拉取 ${merged.size} 个远端模型，去「模型」页选中即可对话"
                            }
                        }
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "拉取中…" else "拉取模型")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        // 会话映射是「本地会话 → 远端会话」。换机器/换 connector 后旧映射会
                        // 指向不存在的远端会话，这里留一个清空入口。
                        sessionStore.clearAll()
                        status = "已清空本机的远端会话映射"
                    }
                },
            ) {
                Text("清空会话映射")
            }
        }

        if (edit.models.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = "已登记 ${edit.models.size} 个远端模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
