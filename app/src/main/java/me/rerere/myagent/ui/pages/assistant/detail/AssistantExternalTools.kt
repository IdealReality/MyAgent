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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.model.Assistant
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.ui.components.ai.McpPicker
import me.rerere.myagent.ui.components.ui.Tag
import me.rerere.myagent.ui.components.ui.TagType
import org.koin.compose.koinInject

/**
 * 「外部工具」来源模板：把另一台机器上的 agent 桥（见 bin/agent-bridge/server.mjs）
 * 注册成这台手机的 MCP server。
 *
 * 两种接入方式：
 *  - 令牌：HTTP 端点 + bearer token，URL 指向 arch 的统一入口（nginx :8933），手机需与 arch 同网段。
 *  - SSH：走已保存主机的 SSH 凭据，远端跑 `stdio.mjs`，JSON-RPC 直接走在 exec 通道的
 *    stdin/stdout 上 —— 不需要 token、不监听端口、不要求同网段。
 */
data class ExternalToolTemplate(
    val key: String,
    val name: String,
    val host: String,
    val description: String,
    val url: String,
    val toolPreview: String,
    /** SSH 模式下在远端执行的命令行（stdio 版桥）。 */
    val sshCommand: String,
    /** SSH 模式下的远端工作目录，null 表示登录目录。 */
    val sshRoot: String?,
    /** 远端是 Windows 时为 true：sshd 的默认 shell 是 cmd.exe，不能套 `sh -c`。 */
    val windowsShell: Boolean,
)

val ExternalToolTemplates: List<ExternalToolTemplate> = listOf(
    ExternalToolTemplate(
        key = "omp",
        name = "omp",
        host = "Windows 工作站",
        description = "文件读写、Shell、正则检索，以及把整台机器交给 omp 的 agent 循环。",
        url = "http://192.168.0.10:8933/omp/mcp",
        toolPreview = "omp_run · omp_bash · omp_read · omp_write · omp_edit · omp_grep · omp_glob",
        sshCommand = "node \"\$env:USERPROFILE\\bin\\agent-bridge\\stdio.mjs\" --prefix omp --agent omp --root \$env:USERPROFILE",
        sshRoot = null,
        windowsShell = true,
    ),
    ExternalToolTemplate(
        key = "dsh",
        name = "dsh",
        host = "Arch Linux",
        description = "同一套工具面，落在 arch 上：容器、服务、部署相关的活交给它。",
        url = "http://192.168.0.10:8933/dsh/mcp",
        toolPreview = "dsh_run · dsh_bash · dsh_read · dsh_write · dsh_edit · dsh_grep · dsh_glob",
        sshCommand = "node \"\$HOME/agent-bridge/stdio.mjs\" --prefix dsh --agent dsh " +
            "--agent-cmd \"\$HOME/agent-bridge/dsh-headless.sh\" " +
            "--agent-args '[\"headless\",\"--patch\",\"/home/dsh/plugins/dsh_bot/dsh-model.yml\"]' " +
            "--root \"\$HOME\"",
        sshRoot = null,
        windowsShell = false,
    ),
)

/**
 * 创建助手时的「外部工具」面板：勾选已注册的 MCP server，或一键添加 omp / dsh 模板。
 */
@Composable
fun AssistantExternalToolsContent(
    assistant: Assistant,
    /**
     * Atomic update. Both entry points (template install and the per-server switches) must
     * run against the *current* assistant, otherwise two quick taps write from the same
     * stale snapshot and the later one wins (see AssistantDetailVM.updateAssistant).
     */
    onUpdateAssistantTransform: ((Assistant) -> Assistant) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val settingsStore: SettingsStore = koinInject()
    val sshHostRepository: SshHostRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val servers = settings.mcpServers

    var pendingTemplate by remember { mutableStateOf<ExternalToolTemplate?>(null) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var savedHosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }

    LaunchedEffect(permissionRefresh) {
        savedHosts = runCatching { sshHostRepository.getAll() }.getOrDefault(emptyList())
    }

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
                    Text("需要「局域网访问」权限", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(
                        "外部工具跑在你的局域网主机上。Android 17 起，没有该权限时连接会一直超时 —— HTTP 端点和经 SSH 连私网主机（RFC1918 / Tailscale CGNAT）都一样需要它。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
                onUpdateAssistant = { updated -> onUpdateAssistantTransform { updated } },
                onUpdateAssistantTransform = onUpdateAssistantTransform,
            )
        }

        Text("内置模板", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
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
                            Text(template.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            Tag(type = TagType.INFO) { Text(template.host) }
                        }
                        Text(
                            template.description,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            template.toolPreview,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
            savedHosts = savedHosts,
            onDismiss = { pendingTemplate = null },
            onConfirm = { config ->
                val server = config
                scope.launch {
                    settingsStore.update { s -> s.copy(mcpServers = s.mcpServers + server) }
                    onUpdateAssistantTransform { current ->
                        current.copy(mcpServers = current.mcpServers + server.id)
                    }
                }
                pendingTemplate = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExternalToolDialog(
    template: ExternalToolTemplate,
    savedHosts: List<SshHostEntity>,
    onDismiss: () -> Unit,
    onConfirm: (McpServerConfig) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(template.url) }
    var sshMode by remember { mutableStateOf(false) }
    var selectedHost by remember { mutableStateOf(savedHosts.firstOrNull()?.name) }
    var command by remember { mutableStateOf(template.sshCommand) }

    val canConfirm = if (sshMode) {
        !selectedHost.isNullOrBlank() && command.isNotBlank()
    } else {
        token.isNotBlank() && endpoint.isNotBlank()
    }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !sshMode,
                        onClick = { sshMode = false },
                        label = { Text("访问令牌") },
                    )
                    FilterChip(
                        selected = sshMode,
                        onClick = { sshMode = true },
                        label = { Text("SSH 免令牌") },
                    )
                }

                if (!sshMode) {
                    Text(
                        "运行在 ${template.host}。粘贴宿主的访问令牌（启动桥时用的 --token）。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.trim() },
                        label = { Text("MCP 端点（可修改）") },
                        singleLine = true,
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
                } else {
                    Text(
                        "用一条 SSH 连接直达 ${template.host}，不需要令牌、不开放端口。" +
                            "远端进程在 stdin/stdout 上说 MCP（stdio.mjs）。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    if (savedHosts.isEmpty()) {
                        Text(
                            "还没有保存的 SSH 主机。先去「设置 → SSH 主机」添加一台，再回来。",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text("SSH 主机", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        savedHosts.forEach { host ->
                            FilterChip(
                                selected = selectedHost == host.name,
                                onClick = { selectedHost = host.name },
                                label = { Text("${host.name} · ${host.user}@${host.host}:${host.port}") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("远端命令") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (template.windowsShell) {
                        Text(
                            "远端是 Windows，命令按 cmd.exe 语义原样下发。",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = if (sshMode) {
                        McpServerConfig.SshStdioServer(
                            commonOptions = McpCommonOptions(
                                enable = true,
                                name = template.name,
                            ),
                            sshHost = selectedHost.orEmpty(),
                            command = command.trim(),
                            root = template.sshRoot,
                            windowsShell = template.windowsShell,
                        )
                    } else {
                        McpServerConfig.StreamableHTTPServer(
                            commonOptions = McpCommonOptions(
                                enable = true,
                                name = template.name,
                                headers = listOf("authorization" to "Bearer $token"),
                            ),
                            url = endpoint,
                        )
                    }
                    onConfirm(config)
                },
                enabled = canConfirm,
            ) { Text("添加并启用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
