package me.rerere.myagent.ui.pages.extensions.vm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MonitorDot
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Terminal
import me.rerere.myagent.R
import me.rerere.myagent.Screen
import me.rerere.myagent.data.db.entity.VmInstanceEntity
import me.rerere.myagent.data.repository.VmRepository
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.context.LocalNavController
import me.rerere.vm.VmImage
import me.rerere.vm.VmInstallProgress
import me.rerere.vm.VmState
import org.koin.compose.koinInject

/**
 * 工作区里的虚拟环境管理页。
 *
 * 页面只做三件事：装（从镜像库下载）、跑（QEMU 生命周期）、连（画面/终端交给别的页）。
 * 所有状态都以数据库为准，避免「UI 显示 RUNNING 但进程早没了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmPage(workspaceId: String) {
    val navController = LocalNavController.current
    val repository = koinInject<VmRepository>()
    val scope = rememberCoroutineScope()
    val instances by repository.listFlow(workspaceId).collectAsStateWithLifecycle(initialValue = emptyList())

    var showCatalog by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(runCatching { repository.catalog() }.getOrDefault(me.rerere.vm.VmCatalog())) }
    var busy by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableStateOf<VmInstallProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var terminalTarget by remember { mutableStateOf<VmInstanceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<VmInstanceEntity?>(null) }

    LaunchedEffect(instances) {
        // 进程可能被系统杀掉：进页面时用一次探活纠正过期的 RUNNING
        instances.filter { it.vmState() == VmState.RUNNING }.forEach {
            runCatching { repository.refreshState(workspaceId, it.id) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Virtual environments") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = "Add image")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCatalog = true }) {
                Icon(HugeIcons.Add01, contentDescription = "Add image")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (instances.isEmpty()) {
                item {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No virtual machine yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add one from the image catalog. The workspace rootfs hosts QEMU; images are downloaded separately.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(instances, key = { it.id }) { instance ->
                VmInstanceCard(
                    instance = instance,
                    busy = busy == instance.id,
                    onStart = {
                        busy = instance.id
                        error = null
                        scope.launch {
                            runCatching { repository.start(workspaceId, instance.id, catalog) }
                                .onFailure { error = it.message }
                            busy = null
                        }
                    },
                    onStop = {
                        busy = instance.id
                        scope.launch {
                            runCatching { repository.stop(workspaceId, instance.id) }
                                .onFailure { error = it.message }
                            busy = null
                        }
                    },
                    onScreen = {
                        navController.navigate(Screen.VmScreen(workspaceId, instance.id))
                    },
                    onTerminal = { terminalTarget = instance },
                    onDelete = { deleteTarget = instance },
                )
            }
        }

        installProgress?.let { progress ->
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    progress.fileName.ifBlank { "Installing image" },
                    style = MaterialTheme.typography.bodySmall,
                )
                val total = progress.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.bytesRead / 1024 / 1024} / ${total / 1024 / 1024} MB",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showCatalog) {
        VmCatalogSheet(
            catalog = catalog,
            onLoadCatalog = { url ->
                runCatching { repository.catalog(url) }
                    .onSuccess { catalog = it; error = null }
                    .onFailure { error = it.message }
            },
            installing = busy,
            onDismiss = { showCatalog = false },
            onInstall = { image ->
                showCatalog = false
                busy = image.id
                error = null
                scope.launch {
                    runCatching {
                        repository.install(workspaceId, catalog, image.id) { progress ->
                            installProgress = progress
                        }
                    }.onFailure { error = it.message }
                    installProgress = null
                    busy = null
                }
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Virtual machine error") },
            text = {
                SelectionContainer {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    terminalTarget?.let { instance ->
        VmTerminalDialog(
            repository = repository,
            workspaceId = workspaceId,
            instance = instance,
            onDismiss = { terminalTarget = null },
        )
    }

    deleteTarget?.let { instance ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${instance.name}?") },
            text = { Text("The downloaded image and the persistent data disk will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { repository.delete(workspaceId, instance.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun VmInstanceCard(
    instance: VmInstanceEntity,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScreen: () -> Unit,
    onTerminal: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = instance.vmState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        instance.imageName.ifBlank { instance.imageId },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    state.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        VmState.RUNNING -> MaterialTheme.colorScheme.primary
                        VmState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            instance.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                when (state) {
                    VmState.NOT_INSTALLED, VmState.INSTALLING -> Unit
                    VmState.RUNNING -> {
                        OutlinedButton(onClick = onStop, enabled = !busy) {
                            Icon(HugeIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Stop", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onScreen, enabled = !busy) {
                            Icon(HugeIcons.MonitorDot, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Screen", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onTerminal, enabled = !busy) {
                            Icon(HugeIcons.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Shell", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    else -> {
                        Button(onClick = onStart, enabled = !busy) {
                            Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Start", modifier = Modifier.padding(start = 6.dp))
                        }
                        IconButton(onClick = onDelete, enabled = !busy) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
            Text(
                "adb 127.0.0.1:${instance.adbPort} · vnc 127.0.0.1:${instance.vncPort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VmCatalogSheet(
    catalog: me.rerere.vm.VmCatalog,
    onLoadCatalog: (String) -> Unit,
    installing: String?,
    onDismiss: () -> Unit,
    onInstall: (VmImage) -> Unit,
) {
    var catalogUrl by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Image catalog", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it },
                    singleLine = true,
                    label = { Text("Catalog URL (optional)") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (catalogUrl.isNotBlank()) onLoadCatalog(catalogUrl.trim()) }) {
                    Text("Load")
                }
            }
            catalog.images.forEach { image ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = installing == null) { onInstall(image) },
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(image.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(
                                image.sizeBytes?.let { "${it / 1024 / 1024} MB" } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            image.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${image.kind.name.lowercase()} · ${image.arch}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VmTerminalDialog(
    repository: VmRepository,
    workspaceId: String,
    instance: VmInstanceEntity,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${instance.name} shell") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command (runs in guest)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                OutlinedButton(
                    onClick = {
                        val cmd = command.trim()
                        if (cmd.isEmpty() || running) return@OutlinedButton
                        running = true
                        scope.launch {
                            val result = runCatching { repository.shell(workspaceId, instance.id, cmd) }
                            output = result.fold(
                                onSuccess = { r ->
                                    buildString {
                                        append(output)
                                        append("\n$ ")
                                        append(cmd)
                                        append("\n")
                                        if (r.stdout.isNotBlank()) append(r.stdout)
                                        if (r.stderr.isNotBlank()) append("[stderr] ").append(r.stderr)
                                        append("[exit ").append(r.exitCode).append("]")
                                    }
                                },
                                onFailure = { "$output\n[error] ${it.message}" },
                            )
                            running = false
                        }
                    },
                    enabled = !running,
                ) { Text(if (running) "Running…" else "Run") }
                SelectionContainer {
                    Text(
                        text = output.takeLast(8000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
