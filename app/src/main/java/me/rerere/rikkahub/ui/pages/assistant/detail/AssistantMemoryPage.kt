package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.memory.AssistantMemoryEngine
import me.rerere.rikkahub.data.memory.EpisodeSegment
import me.rerere.rikkahub.data.memory.MemoryPrompts
import me.rerere.rikkahub.data.memory.MemorySnapshot
import me.rerere.rikkahub.data.memory.TaskBoundary
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆引擎 v1 · 助手记忆页（P5 五区重建）：
 *
 *  1. 总览：四层统计 + L1 摘要查看/编辑
 *  2. 原话流：L2 段落（时间/标题/回合/答复节选），只读
 *  3. 知识库：L3 docs 目录的 Markdown 文档列表，可查看/删除
 *  4. 快照：快照列表 + 恢复
 *  5. 设置：记忆开关/全局共享/autolearn 模型角色
 *
 * 旧热记录编辑器（memory_tool create/edit/delete）随 P1 停用一并移除；
 * 冷记忆（coldMemoryEnabled 区块）保留——它是 L3 的工作区入口，与 memory_write
 * 并行存在，后续版本再收编。
 */
@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val engine = koinInject<AssistantMemoryEngine>()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val scopeId = engine.scopeIdOf(assistant)
    val store = engine.store(scopeId)

    // 数据（keyed 刷新：切助手/切 scope/手动 reload token 变化时重算）
    var reloadKey by remember(assistant.id, assistant.useGlobalMemory) { mutableStateOf(0) }
    var stats by remember(reloadKey) { mutableStateOf(store.stats(scopeId)) }
    var episodes by remember(reloadKey) { mutableStateOf(store.readAllEpisodes(scopeId)) }
    var docs by remember(reloadKey) {
        mutableStateOf(
            store.docsDir(scopeId)
                .listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                ?.map { it.name to it.length() }
                ?.sortedBy { it.first }
                ?: emptyList()
        )
    }
    var snapshots by remember(reloadKey) { mutableStateOf(MemorySnapshot.list(store, scopeId)) }
    var l1Text by remember(reloadKey) {
        mutableStateOf(store.readL1(scopeId).ifBlank { MemoryPrompts.defaultL1Skeleton() })
    }

    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_memory)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            // 五区 Tab（FilterChip 横排）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tabLabels = listOf(
                    stringResource(R.string.memory_tab_overview),
                    stringResource(R.string.memory_tab_episodes),
                    stringResource(R.string.memory_tab_docs),
                    stringResource(R.string.memory_tab_snapshots),
                    stringResource(R.string.memory_tab_settings),
                )
                tabLabels.forEachIndexed { i, label ->
                    FilterChip(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            when (tab) {
                0 -> MemoryOverviewTab(
                    stats = stats,
                    l1Text = l1Text,
                    onEditL1 = { newText ->
                        scope.launch(Dispatchers.IO) {
                            store.writeL1(scopeId, newText)
                            stats = store.stats(scopeId)
                            l1Text = newText
                        }
                    },
                )
                1 -> MemoryEpisodesTab(episodes = episodes)
                2 -> MemoryDocsTab(
                    docs = docs,
                    onDelete = { name ->
                        scope.launch(Dispatchers.IO) {
                            java.io.File(store.docsDir(scopeId), name).delete()
                            docs = store.docsDir(scopeId)
                                .listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                                ?.map { it.name to it.length() }
                                ?.sortedBy { it.first } ?: emptyList()
                            stats = store.stats(scopeId)
                        }
                    },
                )
                3 -> MemorySnapshotsTab(
                    snapshots = snapshots,
                    onRestore = { snap ->
                        scope.launch(Dispatchers.IO) {
                            MemorySnapshot.restore(store, scopeId, snap.dir)
                            l1Text = store.readL1(scopeId).ifBlank { MemoryPrompts.defaultL1Skeleton() }
                            stats = store.stats(scopeId)
                            episodes = store.readAllEpisodes(scopeId)
                        }
                    },
                )
            }
            if (tab == 4) {
                MemorySettingsSection(
                    assistant = assistant,
                    onUpdateAssistant = { vm.update(it) },
                )
            }
        }
    }
}

@Composable
private fun MemoryOverviewTab(
    stats: AssistantMemoryEngineStats,
    l1Text: String,
    onEditL1: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup {
            item(headlineContent = { Text("L1 常驻摘要") }, supportingContent = { Text("${stats.l1Chars} 字 · 唯一自动注入的记忆正文") })
            item(headlineContent = { Text("L2 原话流") }, supportingContent = { Text("${stats.episodeCount} 段（${stats.unlearnedCount} 段待整理）") })
            item(headlineContent = { Text("L3 知识库") }, supportingContent = { Text("${stats.docsCount} 篇文档") })
            item(headlineContent = { Text("L4 会话目录") }, supportingContent = { Text("${stats.sessionCount} 个会话") })
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("L1 摘要正文", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        draft = l1Text
                        editing = true
                    }) { Text("编辑") }
                }
                Text(
                    text = l1Text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑 L1 摘要") },
            text = {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 8,
                    maxLines = 16,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditL1(draft)
                    editing = false
                }) { Text(stringResource(R.string.assistant_page_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.assistant_page_cancel)) }
            },
        )
    }
}

private typealias AssistantMemoryEngineStats = me.rerere.rikkahub.data.memory.AssistantMemoryStore.Stats

@Composable
private fun MemoryEpisodesTab(episodes: List<EpisodeSegment>) {
    if (episodes.isEmpty()) {
        EmptyHint(text = "暂无原话流段落；对话结束后自动落段")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(episodes, key = { it.id }) { e ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = fmtTime(e.startTs) + " · " + fmtTime(e.endTs) + " · " + e.turns + " 回合",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = e.title.ifBlank { e.firstQuery.take(60) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (e.firstQuery.isNotBlank()) {
                        Text(
                            text = "问: " + e.firstQuery.take(120),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (e.replyExcerpt.isNotBlank()) {
                        Text(
                            text = "答: " + e.replyExcerpt.take(160),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.gist.isNotBlank()) {
                        Text(
                            text = "要点: " + e.gist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryDocsTab(
    docs: List<Pair<String, Long>>,
    onDelete: (String) -> Unit,
) {
    if (docs.isEmpty()) {
        EmptyHint(text = "暂无 L3 文档；模型可用 memory_write 写入")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(docs, key = { it.first }) { (name, size) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "$size 字节",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(name) }) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.assistant_page_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorySnapshotsTab(
    snapshots: List<MemorySnapshot.SnapshotInfo>,
    onRestore: (MemorySnapshot.SnapshotInfo) -> Unit,
) {
    if (snapshots.isEmpty()) {
        EmptyHint(text = "暂无快照；autolearn 重写 L1 前自动创建")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(snapshots, key = { it.timestamp }) { s ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(fmtTime(s.timestamp), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "manifest ${s.fileCount} 字节",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(s) }) { Text("恢复") }
                }
            }
        }
    }
}

@Composable
private fun MemorySettingsSection(
    assistant: me.rerere.rikkahub.data.model.Assistant,
    onUpdateAssistant: (me.rerere.rikkahub.data.model.Assistant) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = { Text(stringResource(R.string.memory_engine_enable_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) },
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(useGlobalMemory = it)) },
                        enabled = assistant.enableMemory,
                    )
                },
            )
        }
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.memory_engine_autolearn_title)) },
                supportingContent = { Text(stringResource(R.string.memory_engine_autolearn_desc)) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.memory_engine_boundary_title)) },
                supportingContent = { Text(stringResource(R.string.memory_engine_boundary_desc)) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.memory_engine_ccr_title)) },
                supportingContent = { Text(stringResource(R.string.memory_engine_ccr_desc)) },
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmtTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
