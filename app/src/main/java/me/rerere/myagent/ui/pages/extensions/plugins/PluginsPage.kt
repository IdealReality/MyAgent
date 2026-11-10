package me.rerere.myagent.ui.pages.extensions.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Plug01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.myagent.R
import me.rerere.myagent.Screen
import me.rerere.myagent.plugin.PluginStatus
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.context.LocalNavController
import me.rerere.myagent.ui.theme.CustomColors
import me.rerere.myagent.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun PluginsPage() {
    val vm = koinViewModel<PluginsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDelete by remember { mutableStateOf<PluginStatus?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.plugins_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.plugins_page_reload),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.statuses.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Plug01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.plugins_page_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.statuses, key = { it.id }) { status ->
                PluginCard(
                    status = status,
                    onToggle = { vm.setEnabled(status.id, it) },
                    onCheck = { vm.inspect(status.id) },
                    onRunTool = { tool -> vm.runTool(status.id, tool) },
                    onRollback = { vm.rollback(status.id) },
                    onDelete = { pendingDelete = status },
                    onOpenPanel = status.panel?.let {
                        { navController.navigate(Screen.PluginPanel(status.id)) }
                    },
                )
            }
        }
    }

    pendingDelete?.let { status ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.plugins_page_delete)) },
            text = { Text(stringResource(R.string.plugins_page_delete_confirm, status.id)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(status.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.plugins_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    state.runResult?.let { (title, report) ->
        AlertDialog(
            onDismissRequest = { vm.dismissRun() },
            title = { Text("$title · ${stringResource(R.string.plugins_page_run_tool)}") },
            text = {
                Text(text = report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissRun() }) {
                    Text(stringResource(R.string.plugins_page_close))
                }
            },
        )
    }

    state.checkResult?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.dismissCheck() },
            title = { Text(stringResource(R.string.plugins_page_checked)) },
            text = {
                Text(text = report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissCheck() }) {
                    Text(stringResource(R.string.plugins_page_close))
                }
            },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            title = { Text(stringResource(R.string.plugins_page_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissMessage() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun PluginCard(
    status: PluginStatus,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onRunTool: (String) -> Unit,
    onRollback: () -> Unit,
    onDelete: () -> Unit,
    onOpenPanel: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (status.version.isBlank()) status.name else "${status.name} · ${status.version}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = status.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = status.enabled, onCheckedChange = onToggle)
            }

            if (status.description.isNotBlank()) {
                Text(text = status.description, style = MaterialTheme.typography.bodySmall)
            }

            if (status.declaredTools.isNotEmpty()) {
                Text(
                    text = "${stringResource(R.string.plugins_page_tools)}: ${status.declaredTools.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (status.enabled && status.loadError == null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        status.declaredTools.forEach { tool ->
                            TextButton(onClick = { onRunTool(tool) }) {
                                Icon(HugeIcons.Play, contentDescription = null)
                                Text(text = tool, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }

            if (status.promptChars > 0) {
                Text(
                    text = "${stringResource(R.string.plugins_page_prompt_chars)}: ${status.promptChars} chars",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status.slots.isNotEmpty()) {
                Text(
                    text = "slots: ${status.slots.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status.loadError?.let { error ->
                Text(
                    text = "${stringResource(R.string.plugins_page_error)}: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (status.failures > 0) {
                Text(
                    text = stringResource(R.string.plugins_page_failures, status.failures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            status.lastError?.let { error ->
                Text(
                    text = stringResource(R.string.plugins_page_last_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onOpenPanel != null) {
                    TextButton(onClick = onOpenPanel) {
                        Icon(HugeIcons.Eye, contentDescription = null)
                        Text(
                            text = stringResource(R.string.plugins_page_open_panel),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                TextButton(onClick = onCheck) {
                    Icon(HugeIcons.Plug01, contentDescription = null)
                    Text(
                        text = stringResource(R.string.plugins_page_check),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TextButton(onClick = onRollback) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                    Text(
                        text = stringResource(R.string.plugins_page_rollback),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TextButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, contentDescription = null)
                    Text(
                        text = stringResource(R.string.plugins_page_delete),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
