package me.rerere.myagent.ui.pages.extensions.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.dokar.sonner.ToastType
import me.rerere.myagent.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.myagent.data.datastore.SkillGroup
import me.rerere.myagent.data.datastore.SkillGroupRules
import me.rerere.myagent.data.files.SkillFrontmatterParser
import me.rerere.myagent.data.files.SkillMetadata
import me.rerere.myagent.Screen
import me.rerere.myagent.skills.CatalogEntry
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.components.ui.RikkaConfirmDialog
import me.rerere.myagent.ui.context.LocalNavController
import me.rerere.myagent.ui.context.LocalToaster
import me.rerere.myagent.ui.theme.CustomColors
import me.rerere.myagent.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val groups by vm.skillGroups.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }
    // Group management UI state. `groupEditor` is null when closed, New when creating, or
    // Edit(group) when renaming / re-picking members.
    var groupEditor by remember { mutableStateOf<GroupEditorTarget?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<SkillGroup?>(null) }
    var deleteGroupSkillsToo by remember { mutableStateOf(false) }

    val byName = remember(skills) { skills.associateBy { it.name } }
    // Core (auto_load) skills get their own locked section: they are the system persona and
    // cannot be grouped or disabled (see SkillGroupRules.canGroupSkill).
    val coreSkills = remember(skills) { skills.filter { it.autoLoad } }
    val groupableSkills = remember(skills) { skills.filter { SkillGroupRules.canGroupSkill(it.name) } }

    // ---- Drag-to-reorder ----------------------------------------------------
    // `groups`/`order` come from settings; `localGroups`/`localOrder` are the live list the
    // drag mutates. Persisting on drag-stop (not on every onMove frame) keeps the write
    // atomic and avoids index drift between the async settings write and the pointer.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistants = settings.assistants
    var localGroups by remember { mutableStateOf(groups) }
    var localOrder by remember { mutableStateOf(settings.skillOrder) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(groups) { if (!dragging) localGroups = groups }
    LaunchedEffect(settings.skillOrder) { if (!dragging) localOrder = settings.skillOrder }

    val ungroupedSkills = remember(skills, localGroups, localOrder) {
        val grouped = SkillGroupRules.groupedSkillNames(localGroups)
        val raw = skills.filter { !it.autoLoad && it.name !in grouped }
        val ordered = SkillGroupRules.orderSkillNames(raw.map { it.name }, localOrder)
        ordered.mapNotNull { byName[it] }
    }

    val rows = remember(coreSkills, localGroups, ungroupedSkills, byName, skills) {
        buildList {
            // Fixed rows first: the LazyColumn's absolute index is what the reorder
            // callback reports, so every rendered item must correspond to one row here.
            if (skills.isEmpty()) add(SkillRow.EmptyState)
            add(SkillRow.NewGroup)
            if (coreSkills.isNotEmpty()) {
                add(SkillRow.CoreHeader)
                coreSkills.forEach { add(SkillRow.CoreSkill(it)) }
            }
            localGroups.forEach { group ->
                add(SkillRow.GroupHeader(group))
                val members = group.skillNames.mapNotNull { byName[it] }
                if (members.isEmpty()) {
                    add(SkillRow.GroupEmpty(group))
                } else {
                    members.forEach { add(SkillRow.Member(group.id, it)) }
                }
            }
            if (ungroupedSkills.isNotEmpty()) {
                add(SkillRow.UngroupedHeader)
                ungroupedSkills.forEach { add(SkillRow.UngroupedSkill(it)) }
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    fun persistOrder() {
        vm.setSkillLayout(
            groupOrder = localGroups.map { it.id },
            memberOrders = localGroups.associate { it.id to it.skillNames },
            ungroupedOrder = ungroupedSkills.map { it.name },
        )
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val a = rows.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val b = rows.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        when {
            a is SkillRow.GroupHeader -> {
                // Accept a drop anywhere inside another group: its header, an empty
                // placeholder, or one of its members. Anything else (core section,
                // ungrouped section) is not a valid destination for a group header.
                val targetId = when (b) {
                    is SkillRow.GroupHeader -> b.group.id
                    is SkillRow.Member -> b.groupId
                    is SkillRow.GroupEmpty -> b.group.id
                    else -> null
                }
                if (targetId != null && targetId != a.group.id) {
                    val gi = localGroups.indexOfFirst { it.id == a.group.id }
                    val gj = localGroups.indexOfFirst { it.id == targetId }
                    if (gi >= 0 && gj >= 0) {
                        localGroups = SkillGroupRules.moveGroup(localGroups, gi, gj)
                    }
                }
            }

            a is SkillRow.Member && b is SkillRow.Member && a.groupId == b.groupId -> {
                localGroups = localGroups.map { g ->
                    if (g.id != a.groupId) g else {
                        val i = g.skillNames.indexOf(a.skill.name)
                        val j = g.skillNames.indexOf(b.skill.name)
                        if (i < 0 || j < 0) g else {
                            g.copy(skillNames = SkillGroupRules.moveInList(g.skillNames, i, j))
                        }
                    }
                }
            }

            a is SkillRow.UngroupedSkill && b is SkillRow.UngroupedSkill -> {
                val i = ungroupedSkills.indexOfFirst { it.name == a.skill.name }
                val j = ungroupedSkills.indexOfFirst { it.name == b.skill.name }
                if (i >= 0 && j >= 0) {
                    localOrder = SkillGroupRules.moveInList(
                        ungroupedSkills.map { it.name }, i, j,
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(
                            imageVector = Lucide.Globe,
                            contentDescription = stringResource(R.string.skill_catalog_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.skills_page_import_from_github)
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.accessibility_add_skill))
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One flat row model so group headers and their members live in the same
            // LazyColumn: drag can then compare rows[from]/rows[to] and only accept a move
            // inside the same kind (header↔header, member↔member of one group, ungrouped↔
            // ungrouped). Core skills are rendered as rows too but never accept a move.
            items(rows, key = { it.key }) { row ->
                ReorderableItem(state = reorderableState, key = row.key) { isDragging ->
                    val dragHandle: Modifier = when (row) {
                        is SkillRow.EmptyState,
                        is SkillRow.NewGroup,
                        is SkillRow.CoreHeader,
                        is SkillRow.CoreSkill,
                        is SkillRow.GroupEmpty,
                        -> Modifier

                        else -> Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                dragging = true
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                dragging = false
                                persistOrder()
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                        )
                    }
                    val reveal = Modifier
                        .fillMaxWidth()
                        .scale(if (isDragging) 0.97f else 1f)
                        .then(dragHandle)

                    when (row) {
                        is SkillRow.EmptyState -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Puzzle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.skills_page_empty_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.skills_page_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is SkillRow.NewGroup -> OutlinedButton(
                            onClick = { groupEditor = GroupEditorTarget.New },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.skills_page_new_group),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        is SkillRow.CoreHeader -> SectionHeader(
                            title = stringResource(R.string.skills_page_group_core),
                            subtitle = stringResource(R.string.skills_page_group_core_hint),
                        )

                        is SkillRow.CoreSkill -> SkillCard(
                            skill = row.skill,
                            onClick = { navController.navigate(Screen.SkillDetail(row.skill.name)) },
                            onDelete = { deleteTarget = row.skill },
                            modifier = reveal,
                        )

                        is SkillRow.GroupHeader -> GroupHeader(
                            group = row.group,
                            assistants = assistants,
                            onToggle = { vm.setGroupEnabled(row.group.id, it) },
                            onEdit = { groupEditor = GroupEditorTarget.Edit(row.group) },
                            onOwnerChange = { owner -> vm.setGroupOwner(row.group.id, owner) },
                            onDelete = { withSkills ->
                                deleteGroupSkillsToo = withSkills
                                deleteGroupTarget = row.group
                            },
                            modifier = reveal,
                        )

                        is SkillRow.GroupEmpty -> Text(
                            text = stringResource(R.string.skills_page_group_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )

                        is SkillRow.Member -> {
                            val group = localGroups.firstOrNull { it.id == row.groupId }
                            SkillCard(
                                skill = row.skill,
                                onClick = { navController.navigate(Screen.SkillDetail(row.skill.name)) },
                                onDelete = { deleteTarget = row.skill },
                                dimmed = group?.enabled == false,
                                modifier = reveal,
                            )
                        }

                        is SkillRow.UngroupedHeader -> SectionHeader(
                            title = stringResource(R.string.skills_page_group_ungrouped),
                            subtitle = null,
                        )

                        is SkillRow.UngroupedSkill -> SkillCard(
                            skill = row.skill,
                            onClick = { navController.navigate(Screen.SkillDetail(row.skill.name)) },
                            onDelete = { deleteTarget = row.skill },
                            modifier = reveal,
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(context.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    // Phase 19C — local-file picker. Lifted to screen scope so the launcher survives
    // dialog dismiss/recreate (per Compose docs: `rememberLauncherForActivityResult`
    // belongs at the highest stable composable, NOT inside a dialog body).
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importFromLocalFile(uri) { success, message ->
                showImportDialog = false
                if (success) {
                    toaster.show(context.getString(R.string.skills_page_import_success, message))
                } else {
                    val key = mapImportErrorKeyToString(context, message)
                    toaster.show(context.getString(R.string.skills_page_import_failed, key))
                }
            }
        }
    }

    // Batch import — pick several `.md` / `.zip` files at once from the system file manager.
    val openMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importFromLocalFiles(uris) { result ->
                showImportDialog = false
                if (result.failures.isEmpty()) {
                    toaster.show(
                        context.getString(R.string.skills_page_import_batch_success, result.imported),
                        type = ToastType.Success,
                    )
                } else {
                    val reason = mapImportErrorKeyToString(context, result.failures.first().message)
                    toaster.show(
                        context.getString(
                            R.string.skills_page_import_batch_partial,
                            result.imported,
                            result.failures.size,
                            reason,
                        ),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
            onPickFile = {
                openDocumentLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
            onPickMultipleFiles = {
                openMultipleLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    // Group deletion — one dialog, two modes: keep skills (they fall back to Ungrouped) or
    // uninstall them along with the group.
    RikkaConfirmDialog(
        show = deleteGroupTarget != null,
        title = stringResource(R.string.skills_page_group_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val target = deleteGroupTarget
            if (target != null) {
                if (deleteGroupSkillsToo) vm.deleteGroupWithSkills(target.id) else vm.deleteGroup(target.id)
            }
            deleteGroupTarget = null
            deleteGroupSkillsToo = false
        },
        onDismiss = {
            deleteGroupTarget = null
            deleteGroupSkillsToo = false
        },
    ) {
        val target = deleteGroupTarget
        val message = if (deleteGroupSkillsToo) {
            stringResource(
                R.string.skills_page_group_delete_with_skills_message,
                target?.name ?: "",
                target?.skillNames?.size ?: 0,
            )
        } else {
            stringResource(R.string.skills_page_group_delete_message, target?.name ?: "")
        }
        Text(message)
    }

    groupEditor?.let { target ->
        val editing = target as? GroupEditorTarget.Edit
        SkillGroupEditorDialog(
            initialName = editing?.group?.name ?: "",
            initialSelected = editing?.group?.skillNames?.toSet() ?: emptySet(),
            allSkills = groupableSkills,
            onDismiss = { groupEditor = null },
            onConfirm = { name, selected ->
                when (target) {
                    is GroupEditorTarget.New -> {
                        vm.createGroup(name, selected)
                        toaster.show(context.getString(R.string.skills_page_group_created))
                    }

                    is GroupEditorTarget.Edit -> {
                        vm.renameGroup(target.group.id, name)
                        vm.setGroupMembers(target.group.id, selected)
                    }
                }
                groupEditor = null
            },
        )
    }

    if (showCatalog) {
        val installedNames by vm.installedSkillNames.collectAsStateWithLifecycle()
        FeaturedCatalogSheet(
            entries = vm.catalog.skills,
            installedNames = installedNames,
            onInstall = { entry, onDone ->
                vm.installFromCatalog(entry) { success, message ->
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(context.getString(R.string.skill_catalog_install_failed, key))
                    }
                    onDone()
                }
            },
            onDismiss = { showCatalog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeaturedCatalogSheet(
    entries: List<CatalogEntry>,
    installedNames: Set<String>,
    onInstall: (CatalogEntry, onResult: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_catalog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        CatalogRow(
                            entry = entry,
                            installed = entry.name in installedNames,
                            installing = entry.name in installing,
                            onInstall = {
                                installing = installing + entry.name
                                onInstall(entry) {
                                    installing = installing - entry.name
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${entry.category} · ${entry.sizeKb} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installed && !installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    when {
                        installed -> stringResource(R.string.skill_catalog_installed)
                        installing -> stringResource(R.string.skill_catalog_installing)
                        else -> stringResource(R.string.skill_catalog_install)
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    /** Render faded when the owning group is disabled (its skills are suppressed). */
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Puzzle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (!skill.compatibility.isNullOrBlank() || !skill.license.isNullOrBlank()) {
                    Text(
                        text = listOfNotNull(
                            skill.compatibility?.takeIf { it.isNotBlank() },
                            skill.license?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"...\"\n---\n\nSkill body goes here...",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    if (nameError) Text(
                        stringResource(R.string.skills_page_name_error),
                        color = MaterialTheme.colorScheme.error
                    )
                    else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                    else Text(stringResource(R.string.skills_page_paste_hint))
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Phase 19C — secondary action that defers to the screen-level file
                // launcher. The dialog passes through; SkillsPage handles the SAF
                // OpenDocument result and routes to the VM.
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_import_from_file_label))
                }
                // Batch entry point: the picker allows selecting several bundles at once and
                // the VM reports back how many landed / failed.
                OutlinedButton(
                    onClick = onPickMultipleFiles,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skills_page_import_batch))
                }
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Map a VM-emitted error key (one of the `skill_import_*` resource names) to its
 * localized string. We use this rather than passing string-resource IDs out of the VM
 * because the VM has no `Context` callback path for stringResource lookups.
 */
private fun mapImportErrorKeyToString(context: android.content.Context, key: String): String {
    return when (key) {
        "skill_import_unsupported_file_type" ->
            context.getString(R.string.skill_import_unsupported_file_type)
        "skill_import_missing_skill_md" ->
            context.getString(R.string.skill_import_missing_skill_md)
        "skill_import_path_traversal" ->
            context.getString(R.string.skill_import_path_traversal)
        "skill_import_zip_too_large" ->
            context.getString(R.string.skill_import_zip_too_large)
        "skill_import_md_too_large" ->
            context.getString(R.string.skill_import_md_too_large)
        "skill_import_empty_file" ->
            context.getString(R.string.skill_import_empty_file)
        else -> key  // already a free-form message (e.g. importer's "html_response" detail)
    }
}

/** Small labelled divider above a section of skill cards. */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A group's header row: name, member count, on/off switch, and an overflow menu with
 * edit / delete-group / delete-group-and-skills. The switch is the whole-group toggle —
 * flipping it off suppresses every member from the AI surface without touching any
 * assistant's `enabledSkills`.
 */
@Composable
private fun GroupHeader(
    group: SkillGroup,
    assistants: List<me.rerere.myagent.data.model.Assistant>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onOwnerChange: (String?) -> Unit,
    onDelete: (withSkills: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var ownerDialog by remember { mutableStateOf(false) }
    val ownerName = group.ownerAssistantId?.let { id ->
        assistants.firstOrNull { it.id.toString() == id }?.name
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                text = buildString {
                    append(stringResource(R.string.skills_page_group_members, group.skillNames.size))
                    append(" · ")
                    append(if (group.ownerAssistantId == null) "共用" else (ownerName ?: "专属"))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = group.enabled, onCheckedChange = onToggle)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.skills_page_more_actions),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skills_page_group_edit)) },
                    leadingIcon = {
                        Icon(HugeIcons.Puzzle, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("归属 / 共用") },
                    leadingIcon = { Icon(HugeIcons.Puzzle, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        ownerDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skills_page_group_delete)) },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete(false)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.skills_page_group_delete_with_skills),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete(true)
                    },
                )
            }
        }
    }

    if (ownerDialog) {
        AlertDialog(
            onDismissRequest = { ownerDialog = false },
            title = { Text("归属 / 共用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "共用组的技能对所有助手自动生效；专属组只出现在该助手的技能页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOwnerChange(null)
                                ownerDialog = false
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = group.ownerAssistantId == null,
                            onClick = {
                                onOwnerChange(null)
                                ownerDialog = false
                            },
                        )
                        Text("共用（所有助手）", modifier = Modifier.padding(start = 8.dp))
                    }
                    assistants.forEach { assistant ->
                        val id = assistant.id.toString()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOwnerChange(id)
                                    ownerDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = group.ownerAssistantId == id,
                                onClick = {
                                    onOwnerChange(id)
                                    ownerDialog = false
                                },
                            )
                            Text(
                                text = assistant.name.ifBlank { id.take(8) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ownerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Create / edit dialog for a skill group: a name field plus a checkbox list of the skills
 * that may be grouped. Core (auto_load) skills are never passed in — they cannot be grouped.
 */
@Composable
private fun SkillGroupEditorDialog(
    initialName: String,
    initialSelected: Set<String>,
    allSkills: List<SkillMetadata>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, selected: Set<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initialName.isBlank()) R.string.skills_page_new_group
                    else R.string.skills_page_group_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_page_group_name)) },
                    placeholder = { Text(stringResource(R.string.skills_page_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.skills_page_group_select_skills),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (allSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_page_group_no_skills),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(allSkills, key = { it.name }) { skill ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (skill.name in selected) {
                                            selected - skill.name
                                        } else {
                                            selected + skill.name
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = skill.name in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + skill.name else selected - skill.name
                                    },
                                )
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            text = skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, selected) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Which group the editor dialog is open for: a fresh one, or an existing group being edited. */
private sealed interface GroupEditorTarget {
    data object New : GroupEditorTarget
    data class Edit(val group: SkillGroup) : GroupEditorTarget
}

/**
 * One flat row per LazyColumn item: group headers and their members share a list so the
 * reorder callback can decide whether a drag stays inside its section. Core (auto_load)
 * skills are rows too (index stability) but never accept a move.
 */
private sealed interface SkillRow {
    val key: String

    data object EmptyState : SkillRow {
        override val key: String = "empty-state"
    }

    data object NewGroup : SkillRow {
        override val key: String = "new-group"
    }

    data object CoreHeader : SkillRow {
        override val key: String = "core-header"
    }

    data class CoreSkill(val skill: SkillMetadata) : SkillRow {
        override val key: String get() = "core-${skill.name}"
    }

    data class GroupHeader(val group: SkillGroup) : SkillRow {
        override val key: String get() = "grp-${group.id}"
    }

    data class GroupEmpty(val group: SkillGroup) : SkillRow {
        override val key: String get() = "grp-empty-${group.id}"
    }

    data class Member(val groupId: String, val skill: SkillMetadata) : SkillRow {
        override val key: String get() = "m-$groupId-${skill.name}"
    }

    data object UngroupedHeader : SkillRow {
        override val key: String = "ungrouped-header"
    }

    data class UngroupedSkill(val skill: SkillMetadata) : SkillRow {
        override val key: String get() = "u-${skill.name}"
    }
}
