package me.rerere.myagent.ui.pages.assistant

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.myagent.R
import me.rerere.myagent.Screen
import me.rerere.myagent.data.datastore.Settings
import me.rerere.myagent.data.model.Assistant
import me.rerere.myagent.data.model.AssistantMemory
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.components.ui.FormItem
import me.rerere.myagent.ui.components.ui.Tag
import me.rerere.myagent.ui.components.ui.TagType
import me.rerere.myagent.ui.components.ui.AssistantAvatar
import me.rerere.myagent.ui.context.LocalNavController
import me.rerere.myagent.ui.hooks.EditState
import me.rerere.myagent.ui.hooks.EditStateContent
import me.rerere.myagent.ui.hooks.heroAnimation
import me.rerere.myagent.ui.hooks.useEditState
import me.rerere.myagent.ui.modifier.onClick
import me.rerere.myagent.ui.pages.assistant.detail.AssistantExternalToolsContent
import me.rerere.myagent.ui.pages.assistant.detail.AssistantImporter
import me.rerere.myagent.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import androidx.compose.foundation.lazy.items as lazyItems

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    // 操作菜单状态
    var actionSheetAssistant by remember { mutableStateOf<Assistant?>(null) }

    // onMove 必须在返回前换位。写设置要先落盘，列表随后才变，库会把拖着的行
    // 停在预测位移上再弹回，这就是抽搐。手势期间只改这份快照，松手再落盘。
    // 拖动中不跟设置回写，否则落盘那一帧会把行弹回旧顺序。
    val assistants = remember { mutableStateListOf<Assistant>().apply { addAll(settings.assistants) } }
    var reordering by remember { mutableStateOf(false) }
    LaunchedEffect(settings.assistants) {
        if (!reordering && assistants.toList() != settings.assistants) {
            assistants.clear()
            assistants.addAll(settings.assistants)
        }
    }
    val isFiltering = selectedTagIds.isNotEmpty() || searchQuery.isNotBlank()
    val displayedAssistants: List<Assistant> = if (isFiltering) {
        assistants.filter { assistant ->
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesSearch && matchesTags
        }
    } else {
        assistants
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            createState.open(Assistant())
                        }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.assistant_page_add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(top = 16.dp)
                .consumeWindowInsets(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                if (!isFiltering) {
                    AssistantReorder.move(assistants, from.index, to.index)
                }
            }
            val haptic = LocalHapticFeedback.current

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.accessibility_clear_text))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 标签过滤器
            AssistantTagsFilterRow(
                settings = settings,
                vm = vm,
                selectedTagIds = selectedTagIds,
                onUpdateSelectedTagIds = { ids ->
                    selectedTagIds = ids
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                lazyItems(displayedAssistants, key = { assistant -> assistant.id }) { assistant ->
                    ReorderableItem(
                        state = reorderableState,
                        key = assistant.id,
                    ) { isDragging ->
                        val memories by vm.getMemories(assistant).collectAsStateWithLifecycle(
                            initialValue = emptyList(),
                        )
                        AssistantItem(
                            assistant = assistant,
                            settings = settings,
                            memories = memories,
                            onEdit = {
                                navController.navigate(Screen.AssistantDetail(id = assistant.id.toString()))
                            },
                            onShowActions = {
                                actionSheetAssistant = assistant
                            },
                            // ReorderableItem 已经给没被拖的行加了 animateItem()。
                            // 卡片上再加一次会和拖拽位移叠成两段位移动画，行会跳离手指。
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth()
                                .then(
                                    if (!isFiltering) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = {
                                                reordering = true
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                val orderedIds = assistants.map { it.id }
                                                reordering = false
                                                if (orderedIds != settings.assistants.map { it.id }) {
                                                    vm.reorderAssistants(orderedIds)
                                                }
                                            },
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
    }

    AssistantCreationSheet(createState)

    // 操作菜单 Bottom Sheet
    actionSheetAssistant?.let { assistant ->
        AssistantActionSheet(
            assistant = assistant,
            settings = settings,
            onDismiss = { actionSheetAssistant = null },
            onCopy = {
                vm.copyAssistant(assistant)
                actionSheetAssistant = null
            },
            onDelete = {
                vm.removeAssistant(assistant)
                actionSheetAssistant = null
            }
        )
    }
}

@Composable
private fun AssistantTagsFilterRow(
    settings: Settings,
    vm: AssistantVM,
    selectedTagIds: Set<Uuid>,
    onUpdateSelectedTagIds: (Set<Uuid>) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    if (settings.assistantTags.isNotEmpty()) {
        val tags = remember {
            mutableStateListOf<me.rerere.myagent.data.model.Tag>().apply {
                addAll(settings.assistantTags)
            }
        }
        var reorderingTags by remember { mutableStateOf(false) }
        LaunchedEffect(settings.assistantTags) {
            if (!reorderingTags && tags.toList() != settings.assistantTags) {
                tags.clear()
                tags.addAll(settings.assistantTags)
            }
        }
        val tagsListState = rememberLazyListState()
        val tagsReorderableState = rememberReorderableLazyListState(tagsListState) { from, to ->
            AssistantReorder.move(tags, from.index, to.index)
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
            state = tagsListState
        ) {
            lazyItems(items = tags, key = { tag -> tag.id }) { tag ->
                ReorderableItem(
                    state = tagsReorderableState, key = tag.id
                ) { isDragging ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                onUpdateSelectedTagIds(
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                )
                            },
                            label = {
                                Text(tag.name)
                            },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        reorderingTags = true
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        val orderedIds = tags.map { it.id }
                                        reorderingTags = false
                                        if (orderedIds != settings.assistantTags.map { it.id }) {
                                            vm.reorderAssistantTags(orderedIds)
                                        }
                                    },
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        var showImporter by remember { mutableStateOf(false) }
        var showExternalTools by remember { mutableStateOf(false) }
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FormItem(
                        label = {
                            Text(stringResource(R.string.assistant_page_name))
                        },
                    ) {
                        OutlinedTextField(
                            value = assistant.name, onValueChange = {
                                update(
                                    assistant.copy(
                                        name = it
                                    )
                                )
                            }, modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 两个来源入口：酒馆角色卡，或别的机器上的 agent 工具面。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showImporter = !showImporter },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (showImporter) "收起酒馆导入" else "酒馆导入")
                        }
                        OutlinedButton(
                            onClick = { showExternalTools = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            val selected = assistant.mcpServers.size
                            Text(if (selected > 0) "外部工具 ($selected)" else "外部工具")
                        }
                    }

                    if (showImporter) {
                        AssistantImporter(
                            onUpdate = {
                                update(it)
                                showImporter = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    TextButton(
                        onClick = {
                            state.confirm()
                        }) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }

        if (showExternalTools) {
            Dialog(onDismissRequest = { showExternalTools = false }) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("外部工具", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "勾选后，这个助手的模型可以调用对应主机上的工具。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AssistantExternalToolsContent(
                            assistant = assistant,
                            // 新建助手是一份草稿，没有并发写入；用当前草稿套 transform 即可。
                            onUpdateAssistantTransform = { transform -> update(transform(assistant)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { showExternalTools = false }) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantItem(
    assistant: Assistant,
    settings: Settings,
    modifier: Modifier = Modifier,
    memories: List<AssistantMemory>,
    onEdit: () -> Unit,
    onShowActions: () -> Unit,
) {
    // 长按 = 打开同一个操作面板（MoreVertical 的等价入口）。仓里没有长按菜单惯例，
    // 这里用「长按打开既有 BottomSheet」而不是另造一套菜单，交互只有一个去向。
    // 注意：clickable 由 combinedClickable 提供，Card 不能再传 onClick（否则双击语义冲突，
    // 且 Material3 的 clickable Card 内部已有自己的 clickable modifier）。
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEdit,
                onLongClick = onShowActions,
            ),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistantAvatar(
                assistant = assistant,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                modifier = Modifier
                    .size(48.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {

                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (assistant.enableMemory) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.assistant_page_memory_count, memories.size))
                        }
                    }

                    if (assistant.tags.isNotEmpty()) {
                        assistant.tags.take(2).fastForEach { tagId ->
                            val tag = settings.assistantTags.find { it.id == tagId }
                                ?: return@fastForEach
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = tag.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (assistant.tags.size > 2) {
                            Text(
                                text = "+${assistant.tags.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onShowActions
            ) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.assistant_page_actions)
                )
            }
        }
    }
}

@Composable
private fun AssistantActionSheet(
    assistant: Assistant,
    settings: Settings,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // 助手信息头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistantAvatar(
                    assistant = assistant,
                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 克隆选项
            ListItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_clone)) },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.onClick { onCopy() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            // 删除选项。内置助手同样可删：删除会记进 deletedBuiltInAssistantIds，
            // 下次读设置时 re-seed 会跳过它。唯一硬约束是「至少留一个」——
            // AssistantResolver.current 在空列表上 first() 会抛，删光会让所有
            // getCurrentAssistant() 调用点崩溃，所以对最后一个显示禁用态 + 原因。
            val deletable = (settings.assistants.size > 1)
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.assistant_page_delete),
                        color = if (deletable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                supportingContent = if (deletable) {
                    null
                } else {
                    {
                        Text(stringResource(R.string.assistant_page_delete_last_hint))
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        tint = if (deletable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                modifier = if (deletable) {
                    Modifier.onClick { showDeleteDialog = true }
                } else {
                    Modifier
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.assistant_page_delete)) },
            text = { Text(stringResource(R.string.assistant_page_delete_dialog_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
