package me.rerere.myagent.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.myagent.R
import me.rerere.myagent.data.files.SkillMetadata
import me.rerere.myagent.data.datastore.SkillGroup
import me.rerere.myagent.data.datastore.SkillGroupRules
import me.rerere.myagent.data.model.Lorebook
import me.rerere.myagent.data.model.PromptInjection
import me.rerere.myagent.data.model.QuickMessage

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections, key = { it.id }) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked -> onToggle(injection.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(lorebooks, key = { it.id }) { lorebook ->
            ListItem(
                headlineContent = {
                    Text(lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                },
                supportingContent = if (lorebook.description.isNotBlank()) {
                    {
                        Text(
                            text = lorebook.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(lorebook.id),
                        onCheckedChange = { checked -> onToggle(lorebook.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(skills, key = { it.skillDir.absolutePath }) { skill ->
            ListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = if (skill.description.isNotBlank()) {
                    {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = enabledSkills.contains(skill.name),
                        onCheckedChange = { checked -> onToggle(skill.name, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            ListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}

/**
 * 助手技能面（v2）：把技能分成两个区块。
 *
 *  - **共用技能**：core（auto_load）技能 + 归属为「共用」的组。共用组的成员对所有助手
 *    自动生效，因此这里的开关只读；组总开关关闭时整组变灰、不进入模型上下文。
 *  - **专属技能**：归属为本助手的组 + 未分组技能。专属组只出现在这个助手的页面上，
 *    成员仍由 [enabledSkills] 逐个勾选。
 *
 * 与 [SkillsContent] 的区别：后者是「全部技能平铺」，不知道归属，两个助手看到的一样。
 */
@Composable
fun AssistantSkillsContent(
    skills: List<SkillMetadata>,
    groups: List<SkillGroup>,
    assistantId: String,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    val byName = skills.associateBy { it.name }
    val visibleGroups = SkillGroupRules.groupsFor(groups, assistantId)
    val sharedGroups = visibleGroups.filter { it.ownerAssistantId == null }
    val privateGroups = visibleGroups.filter { it.ownerAssistantId == assistantId }
    // Grouped = every group, not just the visible ones: a skill owned by another assistant's
    // private group must not appear here as an "other" skill that can be switched on.
    val grouped = SkillGroupRules.groupedSkillNames(groups)
    val core = skills.filter { it.autoLoad }
    val others = skills.filter { !it.autoLoad && it.name !in grouped }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (core.isNotEmpty() || sharedGroups.isNotEmpty()) {
            item(key = "shared-title") {
                SkillSectionTitle(
                    title = "共用技能",
                    subtitle = "所有助手都能用；共用组的技能自动启用",
                )
            }
            items(core, key = { "core-${it.name}" }) { skill ->
                AssistantSkillRow(skill = skill, checked = true, switchEnabled = false)
            }
            sharedGroups.forEach { group ->
                item(key = "shared-group-${group.id}") {
                    SkillSubTitle(group.name, enabled = group.enabled)
                }
                val members = group.skillNames.mapNotNull { byName[it] }
                items(members, key = { "shared-${group.id}-${it.name}" }) { skill ->
                    AssistantSkillRow(
                        skill = skill,
                        checked = group.enabled,
                        switchEnabled = false,
                        dimmed = !group.enabled,
                    )
                }
            }
        }

        if (privateGroups.isNotEmpty() || others.isNotEmpty()) {
            item(key = "private-title") {
                SkillSectionTitle(
                    title = "专属技能",
                    subtitle = "只属于这个助手；勾选后才会交给模型",
                )
            }
            privateGroups.forEach { group ->
                item(key = "private-group-${group.id}") {
                    SkillSubTitle(group.name, enabled = group.enabled)
                }
                val members = group.skillNames.mapNotNull { byName[it] }
                items(members, key = { "private-${group.id}-${it.name}" }) { skill ->
                    AssistantSkillRow(
                        skill = skill,
                        checked = skill.name in enabledSkills && group.enabled,
                        switchEnabled = group.enabled,
                        dimmed = !group.enabled,
                        onToggle = { checked -> onToggle(skill.name, checked) },
                    )
                }
            }
            items(others, key = { "other-${it.name}" }) { skill ->
                AssistantSkillRow(
                    skill = skill,
                    checked = skill.name in enabledSkills,
                    switchEnabled = true,
                    onToggle = { checked -> onToggle(skill.name, checked) },
                )
            }
        }

        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
private fun SkillSectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillSubTitle(name: String, enabled: Boolean) {
    Text(
        text = name + if (enabled) "" else "（已停用）",
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun AssistantSkillRow(
    skill: SkillMetadata,
    checked: Boolean,
    switchEnabled: Boolean,
    dimmed: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(skill.name) },
        supportingContent = if (skill.description.isNotBlank()) {
            {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.35f else 0.6f),
                    maxLines = 2,
                )
            }
        } else null,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = switchEnabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
