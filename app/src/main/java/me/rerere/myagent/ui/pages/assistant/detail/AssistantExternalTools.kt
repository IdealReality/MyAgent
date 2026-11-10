package me.rerere.myagent.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.myagent.data.ai.mcp.McpCommonOptions
import me.rerere.myagent.data.ai.mcp.McpServerConfig
import me.rerere.myagent.data.datastore.SettingsStore
import me.rerere.myagent.data.model.Assistant
import me.rerere.myagent.ui.components.ai.McpPicker
import me.rerere.myagent.ui.components.ui.Tag
import me.rerere.myagent.ui.components.ui.TagType
import org.koin.compose.koinInject

/**
 * 「外部工具」来源模板：把另一台机器上的 agent 桥（见 bin/agent-bridge/server.mjs）
 * 注册成这台手机的 MCP server。
 *
 * URL 指向 arch 的统一入口（nginx :8933），omp 走反代到 Windows，dsh 走本机 ——
 * 手机因此只需要和 arch 同网段可达。
 */
data class ExternalToolTemplate(
    val key: String,
    val name: String,
    val host: String,
    val description: String,
    val url: String,
    val toolPreview: String,
)

val ExternalToolTemplates: List<ExternalToolTemplate> = listOf(
    ExternalToolTemplate(
        key = "omp",
        name = "omp",
        host = "Windows 工作站",
        description = "文件读写、Shell、正则检索，以及把整台机器交给 omp 的 agent 循环。",
        url = "http://192.168.0.10:8933/omp/mcp",
        toolPreview = "omp_run · omp_bash · omp_read · omp_write · omp_edit · omp_grep · omp_glob",
    ),
    ExternalToolTemplate(
        key = "dsh",
        name = "dsh",
        host = "Arch Linux",
        description = "同一套工具面，落在 arch 上：容器、服务、部署相关的活交给它。",
        url = "http://192.168.0.10:8933/dsh/mcp",
        toolPreview = "dsh_run · dsh_bash · dsh_read · dsh_write · dsh_edit · dsh_grep · dsh_glob",
    ),
)

/**
 * 创建助手时的「外部工具」面板：勾选已注册的 MCP server，或一键添加 omp / dsh 模板。
 */
@Composable
fun AssistantExternalToolsContent(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val settingsStore: SettingsStore = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val servers = settings.mcpServers

    var pendingTemplate by remember { mutableStateOf<ExternalToolTemplate?>(null) }
    var permissionRefresh by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { permissionRefresh++ }

    val needsLanPermission = Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (needsLanPermission) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要「局域网访问」权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "外部工具跑在你的局域网主机上。Android 17 起，没有该权限时连接会一直超时。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }) { Text("授予权限") }
                }
            }
        }

        if (servers.isEmpty()) {
            Text(
                "还没有注册任何外部工具源。先添加 omp 或 dsh，再勾选给这个助手。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            McpPicker(
                assistant = assistant,
                servers = servers,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 320.dp),
                contentPadding = contentPadding,
                onUpdateAssistant = onUpdateAssistant,
            )
        }

        Text("内置模板", style = MaterialTheme.typography.titleSmall)
        ExternalToolTemplates.forEach { template ->
            val registered = servers.any { it.commonOptions.name == template.name }
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Tag(type = TagType.INFO) { Text(template.host) }
                        }
                        Text(
                            template.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            template.toolPreview,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { pendingTemplate = template },
                        enabled = !registered,
                    ) { Text(if (registered) "已添加" else "添加") }
                }
            }
        }
    }

    pendingTemplate?.let { template ->
        AddExternalToolDialog(
            template = template,
            onDismiss = { pendingTemplate = null },
            onConfirm = { token ->
                val config = McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(
                        enable = true,
                        name = template.name,
                        headers = listOf("authorization" to "Bearer $token"),
                    ),
                    url = template.url,
                )
                scope.launch {
                    settingsStore.update { s -> s.copy(mcpServers = s.mcpServers + config) }
                    onUpdateAssistant(
                        assistant.copy(mcpServers = assistant.mcpServers + config.id)
                    )
                }
                pendingTemplate = null
            },
        )
    }
}

@Composable
private fun AddExternalToolDialog(
    template: ExternalToolTemplate,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 ${template.name}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "运行在 ${template.host}。粘贴宿主的访问令牌（启动桥时用的 --token）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = template.url,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("MCP 端点") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("访问令牌") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token) },
                enabled = token.isNotBlank(),
            ) { Text("添加并启用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
