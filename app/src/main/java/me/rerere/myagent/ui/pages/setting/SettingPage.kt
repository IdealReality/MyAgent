package me.rerere.myagent.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.myagent.R
import me.rerere.myagent.Screen
import me.rerere.myagent.data.datastore.SettingsHubStyle
import me.rerere.myagent.data.datastore.isNotConfigured
import me.rerere.myagent.data.files.FilesManager
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.components.ui.SettingIcon
import me.rerere.myagent.ui.components.ui.CardGroup
import me.rerere.myagent.ui.components.ui.permission.LocalNetworkPermissionCard
import me.rerere.myagent.ui.components.ui.permission.rememberLocalNetworkPermissionState
import me.rerere.myagent.ui.context.LocalNavController
import me.rerere.myagent.ui.context.Navigator
import me.rerere.myagent.ui.theme.CustomColors
import me.rerere.myagent.utils.openUrl
import me.rerere.myagent.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The settings hub.
 *
 * Rows and grouping come from [settingsHubSections] / [settingsHubItems]. [SettingsHubStyle]
 * only swaps labels and which section owns Network — destinations never fork. Icons and the
 * Share / About trailing actions stay here so Compose stays out of the catalog.
 *
 * Search index hub rows are derived from the same catalog (see [settingsHubSearchRows]).
 */
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val context = LocalContext.current
val localNetworkPermission = rememberLocalNetworkPermissionState()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.SettingsSearch)
                        }
                    ) {
                        Icon(HugeIcons.GlobalSearch, stringResource(R.string.accessibility_search))
                    }
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(HugeIcons.Developer, stringResource(R.string.accessibility_developer_options))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val hubStyle = settings.displaySetting.settingsHubStyle
        val sections = remember(hubStyle) { settingsHubSections(hubStyle) }
        val shareLabel = stringResource(R.string.setting_page_share)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            if (localNetworkPermission?.allRequiredPermissionsGranted == false) {
                item("localNetworkPermission") {
                    LocalNetworkPermissionCard(permissionState = localNetworkPermission)
                }
            }

            sections.forEach { section ->
                item(section.id.name) {
                    // Files row needs live storage stats; compute only when that section is shown.
                    val needsStorage = section.items.contains(SettingsHubItemId.FILES)
                    val storageState by produceState(-1 to 0L, needsStorage) {
                        if (needsStorage) {
                            value = filesManager.countChatFiles()
                        }
                    }
                    // 分组的色相：外观/模型/助手/设备/连接/数据/关于各自一档，
                    // 扫读时不用读标题也能分出组（见 SettingIcon 的注释）。
                    // 必须在 CardGroup 之外求值：CardGroupScope 的 content 不是 @Composable。
                    val sectionTint = hubSectionTint(section.id)
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(section.titleRes(hubStyle))) },
                    ) {
                        section.items.forEach { itemId ->
                            val def = settingsHubItems.getValue(itemId)
                            val copy = def.copyFor(hubStyle)
                            val icon = hubItemIcon(itemId)
                            val iconTint = sectionTint
                            when (itemId) {
                                SettingsHubItemId.FILES -> {
                                    item(
                                        onClick = { navController.navigate(Screen.SettingFiles) },
                                        leadingContent = { SettingIcon(icon, tint = iconTint) },
                                        supportingContent = {
                                            if (storageState.first == -1) {
                                                Text(stringResource(R.string.calculating))
                                            } else {
                                                Text(
                                                    stringResource(
                                                        R.string.setting_page_chat_storage_desc,
                                                        storageState.first,
                                                        storageState.second / 1024 / 1024.0
                                                    )
                                                )
                                            }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                SettingsHubItemId.ABOUT -> {
                                    item(
                                        onClick = { navController.navigate(Screen.SettingAbout) },
                                        leadingContent = { SettingIcon(icon, tint = iconTint) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                SettingsHubItemId.SHARE -> {
                                    item(
                                        onClick = { shareApk(context, shareLabel) },
                                        leadingContent = { SettingIcon(icon, tint = iconTint) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                else -> {
                                    val route = def.route
                                    item(
                                        onClick = {
                                            if (route != null) navController.navigate(route)
                                        },
                                        leadingContent = { SettingIcon(icon, tint = iconTint) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 把当前已安装的 APK 本体分享出去（安装包文件，不是下载链接）。
 * APK 放 cacheDir 后经 FileProvider 暴露；接收方拿到的是可安装的 .apk 文件。
 */
private fun shareApk(context: Context, label: String) {
    val appInfo = context.applicationInfo
    val apk = File(appInfo.sourceDir)
    val target = File(context.cacheDir, "share/${apk.name}")
    target.parentFile?.mkdirs()
    if (!target.exists() || target.length() != apk.length()) {
        apk.copyTo(target, overwrite = true)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, label))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.setting_page_no_share_app), Toast.LENGTH_SHORT).show()
    }
}


/**
 * 每组的图标色相：外观 = tertiary（陶土），模型 = primary，助手 = secondary，
 * 设备 = tertiary，连接 = primary，数据/关于 = onSurfaceVariant。
 * 用主题语义色而不是硬编码色值，用户自定义主题与动态取色下都能跟着走。
 */
@Composable
private fun hubSectionTint(id: SettingsHubSectionId): Color = when (id) {
    SettingsHubSectionId.APPEARANCE -> MaterialTheme.colorScheme.tertiary
    SettingsHubSectionId.MODELS -> MaterialTheme.colorScheme.primary
    SettingsHubSectionId.ASSISTANT -> MaterialTheme.colorScheme.secondary
    SettingsHubSectionId.DEVICE -> MaterialTheme.colorScheme.tertiary
    SettingsHubSectionId.CONNECTIONS -> MaterialTheme.colorScheme.primary
    SettingsHubSectionId.DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    SettingsHubSectionId.ABOUT -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun hubItemIcon(id: SettingsHubItemId): ImageVector = when (id) {
    SettingsHubItemId.THEME -> HugeIcons.Sun01
    SettingsHubItemId.UI -> HugeIcons.View
    SettingsHubItemId.GENERAL -> HugeIcons.CursorPointer01
    SettingsHubItemId.NOTIF_PREFS -> HugeIcons.Notification01
    SettingsHubItemId.MODELS -> HugeIcons.AiMagic
    SettingsHubItemId.PROVIDERS -> HugeIcons.Brain02
    SettingsHubItemId.SEARCH -> HugeIcons.GlobalSearch
    SettingsHubItemId.SPEECH -> HugeIcons.Megaphone01
    SettingsHubItemId.NETWORK -> HugeIcons.Internet
    SettingsHubItemId.ASSISTANT -> HugeIcons.LookTop
    SettingsHubItemId.EXTENSIONS -> HugeIcons.Package
    SettingsHubItemId.SUB_AGENTS -> HugeIcons.Robot01
    SettingsHubItemId.MCP -> HugeIcons.McpServer
    SettingsHubItemId.WORKFLOWS -> HugeIcons.Connect
    SettingsHubItemId.SCHEDULED -> HugeIcons.Clock02
    SettingsHubItemId.TOOL_APPROVALS -> HugeIcons.Tick01
    SettingsHubItemId.ACCESSIBILITY -> HugeIcons.SmartPhone01
    SettingsHubItemId.FLOATING_BALL -> HugeIcons.Message01
    SettingsHubItemId.NOTIF_ACCESS -> HugeIcons.Notification01
    SettingsHubItemId.PERMISSIONS -> HugeIcons.Shield01
    SettingsHubItemId.SHIZUKU -> HugeIcons.Console
    SettingsHubItemId.SSH -> HugeIcons.Link01
    SettingsHubItemId.SECRETS -> HugeIcons.Lock
    SettingsHubItemId.WEB -> HugeIcons.ServerStack01
    SettingsHubItemId.TELEGRAM -> HugeIcons.Telegram
    SettingsHubItemId.BROWSER -> HugeIcons.Earth
    SettingsHubItemId.BACKUP -> HugeIcons.Database02
    SettingsHubItemId.FILES -> HugeIcons.ImageUpload
    SettingsHubItemId.DOCTOR -> HugeIcons.Wrench01
    SettingsHubItemId.LOGS -> HugeIcons.Bookshelf01
    SettingsHubItemId.ABOUT -> HugeIcons.Clapping01
    SettingsHubItemId.SHARE -> HugeIcons.Share04
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            ) {
                Text(stringResource(R.string.setting_page_config_api_title))
            }

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}


