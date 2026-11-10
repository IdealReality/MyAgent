package me.rerere.myagent.ui.pages.setting

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.TerminalChannelMode
import me.rerere.myagent.data.ssh.TerminalCursor
import me.rerere.myagent.data.ssh.TerminalLine
import me.rerere.myagent.ui.components.nav.BackButton
import org.koin.compose.koinInject

/** Soft-keyboard slide-in; long enough that the post-IME layout has settled. */
private const val IME_SETTLE_MS = 240L

/**
 * SSH 终端页：一个主机列表入口，多个**本地保存**的终端标签。
 *
 * 页面本身不再持有连接。标签与转写由 [SshTerminalSessionManager] 持有：
 *  - 离开页面不关闭 shell，回到页面还是同一个通道（同一进程内）；
 *  - 标签（主机、标题、转写尾部、启动命令）落盘在 `filesDir/ssh/terminals/<id>.json`，
 *    进程重启后原样恢复；进入本页时每个通道各拨一次，旧输出仍在屏幕上。
 *
 * [hostName] 只是"从主机列表点进来时打开哪一个"：该主机已经有标签就选中它——
 * 这正是"下次打开仍然是之前的终端通道"。为 null 时（聊天页抽屉里的入口没有指定主机）
 * 只恢复已有标签，不会凭空建一个空标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalPage(hostName: String?) {
    val manager: SshTerminalSessionManager = koinInject()
    val repository: SshHostRepository = koinInject()

    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Restore the persisted tabs before deciding what the route asked for: openHost() must
        // see the restored list, otherwise it would create a duplicate tab for the same host.
        manager.restore()
        hosts = withContext(Dispatchers.IO) { repository.getAll() }
        hostName?.takeIf { it.isNotBlank() }?.let { manager.openHost(it) }
        // Re-entering the terminal is itself the "reconnect" intent: every channel the user did
        // not switch off comes back in one pass, so a fresh APK does not mean tapping 重连 on
        // each tab. Nothing dials before this point — a cold app start stays silent.
        manager.resumeAll()
    }

    // Leaving the page keeps every shell: only flush what they produced.
    DisposableEffect(Unit) {
        onDispose { manager.saveAll() }
    }

    val session = manager.selected
    // A scheduled retry has a countdown in the status line; tick it so "5s 后重连" actually
    // counts down instead of freezing at whatever value the recomposition happened to see.
    val nowMs = rememberRetryClock(session?.retryAtMs)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(session?.title ?: "终端")
                            Text(
                                text = statusLine(session, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { BackButton() },
                    actions = {
                        if (session != null) {
                            TextButton(onClick = {
                                if (session.status == SshTerminalStatus.Connected ||
                                    session.status == SshTerminalStatus.Connecting
                                ) {
                                    manager.disconnect(session)
                                } else {
                                    manager.connect(session)
                                }
                            }) {
                                Text(
                                    if (session.status == SshTerminalStatus.Connected ||
                                        session.status == SshTerminalStatus.Connecting
                                    ) {
                                        "断开"
                                    } else {
                                        "重连"
                                    }
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        imageVector = HugeIcons.MoreVertical,
                                        contentDescription = "更多",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("通道设置") },
                                        onClick = {
                                            menuOpen = false
                                            editingId = session.id
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清空输出") },
                                        onClick = {
                                            menuOpen = false
                                            manager.clear(session)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                TerminalTabStrip(
                    manager = manager,
                    onAdd = { picking = true },
                )
            }
        },
    ) { padding ->
        if (session == null) {
            EmptyTerminalState(
                restoring = manager.restoring,
                hasHosts = hosts.isNotEmpty(),
                onCreate = { picking = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            // `key` is load-bearing: the invisible IME field's shadow buffer belongs to one
            // channel, so switching tabs must rebuild it rather than diff against the old tab.
            key(session.id) {
                TerminalPane(
                    session = session,
                    padding = padding,
                    onResize = { width, height, density ->
                        manager.resize(session, width, height, density)
                    },
                )
            }
        }
    }

    if (picking) {
        HostPickerSheet(
            hosts = hosts,
            onPick = { host ->
                manager.newTab(host.name)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    val editing = editingId?.let { id -> manager.tabs.firstOrNull { it.id == id } }
    if (editing != null) {
        ChannelSettingsSheet(
            session = editing,
            onRename = { manager.rename(editing, it) },
            onStartCommand = { manager.setStartCommand(editing, it) },
            onMode = { manager.setMode(editing, it) },
            onDismiss = { editingId = null },
        )
    }
}

/**
 * Per-channel settings: the name shown on the tab, and the line typed into every fresh shell.
 *
 * 两条轴：通道模式（普通 shell / 远端持久会话）和选项。模式是唯一会影响「掉线之后还剩什么」
 * 的开关；普通模式下的启动命令是另一条把进程挪出这条 TCP 的老路（例如 `tmux new -A -s omp`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSettingsSheet(
    session: SshTerminalSession,
    onRename: (String) -> Unit,
    onStartCommand: (String) -> Unit,
    onMode: (TerminalChannelMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(session.id) { mutableStateOf(session.title) }
    var command by remember(session.id) { mutableStateOf(session.startCommand) }
    var mode by remember(session.id) { mutableStateOf(session.mode) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            Text("通道设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("通道模式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            // 选择栏：普通 / 持久。两个选项的差别只有「进程活在哪」，所以文案只说这件事。
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == TerminalChannelMode.Direct,
                    onClick = { mode = TerminalChannelMode.Direct },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("普通") }
                SegmentedButton(
                    selected = mode == TerminalChannelMode.Persistent,
                    onClick = { mode = TerminalChannelMode.Persistent },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("持久") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (mode == TerminalChannelMode.Persistent) {
                    "持久：连接后自动挂到远端的 tmux / screen 会话。App 掉线、被杀掉、换 APK 都只断传输，" +
                        "点「重连」回到同一会话，正在跑的命令与历史输出都还在。主机需要装有 tmux（推荐）或 screen。" +
                        "点「断开」或关标签会连远端会话一起结束。"
                } else {
                    "普通：每次连接开一个新的登录 shell。链路断开后远端进程随之结束，" +
                        "重连得到一个新 shell（本机转写仍在）。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode == TerminalChannelMode.Direct) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("连接后自动执行") },
                    placeholder = { Text("tmux new -A -s omp") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "可选：连接建立后自动敲进新 shell 的一行。填 tmux new -A -s omp 之类可以自己把" +
                        "进程挪进远端 tmux；留空则是普通登录 shell。改了在下次连接时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "持久模式的会话名固定为 ${session.muxName}；远端已存在同名会话时直接接回，" +
                        "残留的旧连接会被踢掉。切模式与改设置都在下次连接时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    onRename(name)
                    onStartCommand(command)
                    onMode(mode)
                    onDismiss()
                }) { Text("保存") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Wall clock that starts ticking only while a retry is scheduled.
 *
 * Returns the current time otherwise, so callers can pass it unconditionally.
 */
@Composable
private fun rememberRetryClock(retryAtMs: Long?): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAtMs) {
        now = System.currentTimeMillis()
        if (retryAtMs == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= retryAtMs) break
            delay(500)
        }
    }
    return now
}

private fun statusLine(
    session: SshTerminalSession?,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (session == null) return ""
    // 已连接时说的是这条通道**实际**是什么形态（模式改了但还没重连时不能说谎）；
    // 断开时说下次连接会是什么。
    val persistent = (session.activeMode ?: session.mode) == TerminalChannelMode.Persistent
    // A pending retry outranks the static status: the user must be able to tell that the app
    // is going to reconnect by itself, and how long that will take.
    val retryAt = session.retryAtMs
    if (retryAt != null) {
        val attempt = "第 ${session.retryAttempt} 次"
        val left = ((retryAt - nowMs) / 1000L).coerceAtLeast(0)
        return if (left > 0) "已断开 · ${left}s 后自动重连（$attempt）" else "已断开 · 正在重连（$attempt）"
    }
    return when (session.status) {
        SshTerminalStatus.Connecting ->
            if (session.retryAttempt > 0) "重连中…（第 ${session.retryAttempt} 次）" else "连接中…"

        SshTerminalStatus.Connected ->
            "已连接 ${session.address ?: session.hostName}" +
                if (session.activeMode == TerminalChannelMode.Persistent) {
                    " · 持久（${session.muxName}）"
                } else {
                    ""
                }

        SshTerminalStatus.Disconnected ->
            // Only 断开 or the tab's × ends a channel; anything else here means it dropped on
            // its own and coming back to this tab (or tapping 重连) reopens it.
            if (session.manualDisconnect) {
                "已断开 · 点「重连」恢复"
            } else if (persistent) {
                // 持久模式里断开的是传输，不是进程：这句话就是让用户知道屏上的空白不是丢了。
                "已断开 · 会话在远端，点「重连」接回"
            } else {
                "未连接 · 点击标签或「重连」恢复"
            }

        SshTerminalStatus.Failed -> session.lastError ?: "连接失败"
    }
}

@Composable
private fun statusColor(status: SshTerminalStatus): Color = when (status) {
    SshTerminalStatus.Connected -> MaterialTheme.colorScheme.primary
    SshTerminalStatus.Connecting -> MaterialTheme.colorScheme.tertiary
    SshTerminalStatus.Failed -> MaterialTheme.colorScheme.error
    SshTerminalStatus.Disconnected -> MaterialTheme.colorScheme.outline
}

@Composable
private fun TerminalTabStrip(
    manager: SshTerminalSessionManager,
    onAdd: () -> Unit,
) {
    val tabs = manager.tabs
    if (tabs.isEmpty()) return
    val listState = rememberLazyListState()

    // Keep the selected tab in view when it changes from the outside (new tab, close).
    LaunchedEffect(manager.selectedId, tabs.size) {
        val index = tabs.indexOfFirst { it.id == manager.selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.id }) { tab ->
            TerminalTabChip(
                tab = tab,
                selected = tab.id == manager.selectedId,
                onSelect = { manager.selectTab(tab.id) },
                onClose = { manager.closeTab(tab) },
            )
        }
        item(key = "add-tab") {
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Add01, contentDescription = "新建终端", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TerminalTabChip(
    tab: SshTerminalSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusColor(tab.status)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = "关闭 ${tab.title}",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTerminalState(
    restoring: Boolean,
    hasHosts: Boolean,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (restoring) "恢复终端…" else "没有打开的终端",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (!restoring) {
            Text(
                text = if (hasHosts) {
                    "标签与输出会保存在本机，下次打开还是这些通道。"
                } else {
                    "还没有保存的主机。先在「设置 → SSH 主机」里添加一台。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCreate, enabled = hasHosts) { Text("新建终端") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostPickerSheet(
    hosts: List<SshHostEntity>,
    onPick: (SshHostEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("新建终端", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (hosts.isEmpty()) {
                Text(
                    text = "还没有保存的主机。先在「设置 → SSH 主机」里添加一台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                hosts.forEach { host ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(host) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${host.user}@${host.host}:${host.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A typed line never needs more than this before the shadow is reset. */
private const val IME_LINE_LIMIT = 512

/** What one change of the invisible field means for the shell. */
internal sealed interface TerminalEdit {
    /** The IME is still composing; hold the keystrokes until it commits. */
    data object Buffer : TerminalEdit

    /** Text appeared past the shadow: send the suffix. */
    data class Append(val value: String) : TerminalEdit

    /** The shadow shrank: send one DEL per removed character. */
    data object Backspace : TerminalEdit

    /** The IME rewrote the buffer wholesale; nothing sensible to forward. */
    data object Resync : TerminalEdit
}

/**
 * Turns the invisible field's contents into the shell edit they represent, by diffing against
 * [shadow] (what the field held after the last forwarded edit).
 *
 * Deleting is only observable as a *shrink*: with an empty field a backspace changes nothing,
 * so the shadow is what lets `"ls x"` → `"ls "` become a DEL byte instead of a lost keystroke.
 */
internal fun imeEdit(text: String, shadow: String, composing: Boolean): TerminalEdit = when {
    composing -> TerminalEdit.Buffer
    text.length >= shadow.length && text.startsWith(shadow) ->
        TerminalEdit.Append(text.substring(shadow.length))
    shadow.startsWith(text) -> TerminalEdit.Backspace
    else -> TerminalEdit.Resync
}

private val BACKSPACE = byteArrayOf(0x7f)

private fun isImeVisible(view: android.view.View): Boolean =
    androidx.core.view.ViewCompat.getRootWindowInsets(view)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true

/**
 * Keys a soft keyboard cannot produce (arrows, tab, escape, home/end, ctrl-combos) are taken
 * before the text field consumes them for caret movement. Enter and Backspace are deliberately
 * *not* handled here: both already arrive as text edits (an appended newline, a shrunken
 * shadow) and intercepting them would send every delete twice.
 */
private fun forwardHardwareKey(event: KeyEvent, session: SshTerminalSession): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val bytes = terminalKeyBytes(event.key, event.isCtrlPressed, event.isShiftPressed) ?: return false
    session.writeBytes(bytes)
    return true
}

/**
 * Text typed on the soft keyboard, as bytes for the pty.
 *
 * A terminal's Enter key is CR, not LF. On Linux the line discipline turns either into a
 * newline so the difference is invisible; Windows' ConPTY does not translate a bare LF, so
 * PowerShell read it as "continue the line" and answered every submitted command with a
 * leftover `>>` prompt. Normalising here also covers pasted multi-line text.
 *
 * With [bracketedPaste] the whole insertion is framed in `CSI 200 ~ … CSI 201 ~`: a TUI that
 * enabled that mode (every Ink app does) then inserts the text literally instead of treating each
 * line as a submitted keystroke. Single characters are never framed — Enter must still submit.
 */
internal fun terminalInputBytes(text: String, bracketedPaste: Boolean = false): ByteArray {
    val body = text.replace("\r\n", "\r").replace('\n', '\r')
    return (if (bracketedPaste) "\u001b[200~$body\u001b[201~" else body).toByteArray(Charsets.UTF_8)
}

/** Pure mapping so the escape sequences can be checked without an Android runtime. */
internal fun terminalKeyBytes(key: Key, ctrlPressed: Boolean, shiftPressed: Boolean = false): ByteArray? {
    if (ctrlPressed) {
        val code = ctrlCodes[key] ?: return null
        return byteArrayOf(code.toByte())
    }
    // Back-tab. omp, fzf and readline-style prompts all bind it (omp: cycle thinking effort);
    // no soft keyboard can produce it, only an external one.
    if (shiftPressed && key == Key.Tab) return ESCAPE_SEQUENCES.getValue("back_tab")
    return when (key) {
        Key.Tab -> ESCAPE_SEQUENCES.getValue("tab")
        Key.Escape -> ESCAPE_SEQUENCES.getValue("escape")
        Key.DirectionUp -> ESCAPE_SEQUENCES.getValue("up")
        Key.DirectionDown -> ESCAPE_SEQUENCES.getValue("down")
        Key.DirectionRight -> ESCAPE_SEQUENCES.getValue("right")
        Key.DirectionLeft -> ESCAPE_SEQUENCES.getValue("left")
        Key.MoveHome -> ESCAPE_SEQUENCES.getValue("home")
        Key.MoveEnd -> ESCAPE_SEQUENCES.getValue("end")
        Key.PageUp -> ESCAPE_SEQUENCES.getValue("page_up")
        Key.PageDown -> ESCAPE_SEQUENCES.getValue("page_down")
        Key.Insert -> ESCAPE_SEQUENCES.getValue("insert")
        Key.Delete -> ESCAPE_SEQUENCES.getValue("delete")
        else -> null
    }
}

private val ESCAPE_SEQUENCES: Map<String, ByteArray> = mapOf(
    "tab" to "\t".toByteArray(),
    "back_tab" to "\u001b[Z".toByteArray(),
    "escape" to "\u001b".toByteArray(),
    "up" to "\u001b[A".toByteArray(),
    "down" to "\u001b[B".toByteArray(),
    "right" to "\u001b[C".toByteArray(),
    "left" to "\u001b[D".toByteArray(),
    "home" to "\u001b[H".toByteArray(),
    "end" to "\u001b[F".toByteArray(),
    "page_up" to "\u001b[5~".toByteArray(),
    "page_down" to "\u001b[6~".toByteArray(),
    "insert" to "\u001b[2~".toByteArray(),
    "delete" to "\u001b[3~".toByteArray(),
)

/** Ctrl-A..Ctrl-Z in the PTY's control-byte convention. */
private val ctrlCodes: Map<Key, Int> = mapOf(
    Key.A to 0x01, Key.B to 0x02, Key.C to 0x03, Key.D to 0x04, Key.E to 0x05,
    Key.F to 0x06, Key.G to 0x07, Key.H to 0x08, Key.I to 0x09, Key.J to 0x0a,
    Key.K to 0x0b, Key.L to 0x0c, Key.M to 0x0d, Key.N to 0x0e, Key.O to 0x0f,
    Key.P to 0x10, Key.Q to 0x11, Key.R to 0x12, Key.S to 0x13, Key.T to 0x14,
    Key.U to 0x15, Key.V to 0x16, Key.W to 0x17, Key.X to 0x18, Key.Y to 0x19,
    Key.Z to 0x1a,
)

/**
 * The visible transcript + the invisible text field the soft keyboard talks to.
 *
 * Everything the keyboard types goes straight to the channel: the remote PTY echoes it back
 * through [me.rerere.myagent.data.ssh.TerminalScreen], so the transcript *is* the edit buffer.
 */
@Composable
private fun TerminalPane(
    session: SshTerminalSession,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onResize: (widthPx: Int, heightPx: Int, density: Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val imeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Compose focus alone does not give the *window* a focused view, and injected/hardware
    // keys are routed by ViewRootImpl to the focused view. Ask the root view for it explicitly.
    val rootView = LocalView.current
    // The invisible field keeps a shadow of what the shell already has on its current line.
    // Resetting it on every keystroke is what made each character arrive twice: writing the
    // value back inside `onValueChange` lets Compose re-apply the pending edit, so the same
    // character was forwarded again. Diffing against the shadow needs no per-key reset.
    var imeValue by remember { mutableStateOf(TextFieldValue("")) }
    var imeShadow by remember { mutableStateOf("") }
    var imeOpenTick by remember { mutableStateOf(0) }

    val itemCount = session.lines.size + if (session.screen.isNotEmpty()) 1 else 0

    // Follow the tail only when the user is already looking at it: scrolling through the
    // scrollback must not be yanked back by incoming output.
    LaunchedEffect(itemCount) {
        if (itemCount <= 0) return@LaunchedEffect
        val info = session.listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible < 0 || lastVisible >= info.totalItemsCount - 2) {
            session.listState.scrollToItem(itemCount - 1)
        }
    }

    // Switching tabs rebuilds this pane; if the keyboard was up it belongs to the *new* tab.
    LaunchedEffect(Unit) {
        if (isImeVisible(rootView)) {
            imeFocus.requestFocus()
            rootView.requestFocus()
            keyboard?.show()
        }
    }

    // The shadow only has to cover the line being typed: once the shell has the newline (or the
    // buffer grows past anything a line needs) start over. Doing it here, in an effect, keeps
    // the reset out of `onValueChange` where it would double every character.
    LaunchedEffect(imeValue.text) {
        if (imeValue.text.length > IME_LINE_LIMIT || imeValue.text.contains('\n')) {
            imeValue = TextFieldValue("")
            imeShadow = ""
        }
    }

    // Focus, then ask for the keyboard, then wait out its slide-in before scrolling: the
    // transcript shrinks by the IME height (`imePadding`), so a prompt near the bottom would
    // otherwise end up behind the keyboard on the very tap meant to reach it.
    LaunchedEffect(imeOpenTick) {
        if (imeOpenTick == 0) return@LaunchedEffect
        imeFocus.requestFocus()
        rootView.requestFocus()
        withFrameNanos { }
        keyboard?.show()
        // The Compose controller is a no-op once the input session went stale (another app took
        // focus, or the IME was swapped), so ask the platform directly as well.
        rootView.post {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(rootView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        delay(IME_SETTLE_MS)
        if (itemCount > 0) session.listState.scrollToItem(itemCount - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The scaffold already padded out the system bars; without consuming them here
            // imePadding would add the IME height *on top* of the navigation bar.
            .padding(padding)
            .consumeWindowInsets(padding)
            // The activity is edge-to-edge, so `adjustResize` never shrinks this content:
            // without imePadding the keyboard covers the bottom half of the transcript.
            .imePadding()
            // Report the area actually drawn: it is what the PTY's columns/rows must match,
            // and it changes on rotation and when the keyboard slides in.
            .onSizeChanged { size -> onResize(size.width, size.height, density) }
            // There is no visible input field any more, so the terminal surface itself is
            // the way into the keyboard. `pointerInput` rather than `clickable`: a clickable
            // node is focusable, and a tap would move Compose focus onto it, silently taking
            // the input session away from the invisible text field.
            .pointerInput(Unit) {
                detectTapGestures {
                    // Ask for the keyboard only when it is not already up: the effect below
                    // also scrolls to the bottom, which must not yank a user who is reading
                    // scrollback while typing.
                    if (!isImeVisible(rootView)) imeOpenTick += 1
                }
            },
    ) {
        LazyColumn(
            state = session.listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(session.lines) { _, line ->
                TerminalLineText(line)
            }
            if (session.screen.isNotEmpty()) {
                item {
                    // The grid is one Text, not one row per item: full-screen programs move the
                    // cursor between rows and every row must keep exactly the same metrics.
                    TerminalGrid(session.screen, session.cursor)
                }
            }
        }

        BasicTextField(
            value = imeValue,
            onValueChange = { next ->
                when (val edit = imeEdit(next.text, imeShadow, composing = next.composition != null)) {
                    // Keep the IME's own buffer so its composition survives.
                    TerminalEdit.Buffer -> {
                        imeValue = next
                        return@BasicTextField
                    }
                    is TerminalEdit.Append -> {
                        session.writeText(edit.value)
                        imeShadow = next.text
                    }
                    TerminalEdit.Backspace -> {
                        session.writeBytes(BACKSPACE)
                        imeShadow = next.text
                    }
                    TerminalEdit.Resync -> imeShadow = next.text
                }
                imeValue = next
            },
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart)
                .focusRequester(imeFocus)
                .onPreviewKeyEvent { event -> forwardHardwareKey(event, session) },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
        )
    }
}

/**
 * One transcript line. SGR runs become [SpanStyle]s here rather than in the model, so
 * [TerminalLine] stays Compose-free and can be built/parsed on a plain JVM.
 */
@Composable
private fun TerminalLineText(line: TerminalLine) {
    val scheme = MaterialTheme.colorScheme
    val annotated = remember(line, scheme) {
        buildAnnotatedString { appendStyled(line, scheme.onSurface, scheme.surfaceContainerLowest) }
    }
    Text(
        // An empty string collapses to zero height in a LazyColumn row.
        text = if (line.text.isEmpty()) AnnotatedString(" ") else annotated,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = scheme.onSurface,
    )
}

/** The visible grid plus a block cursor, rendered as one annotated string. */
@Composable
private fun TerminalGrid(screen: List<TerminalLine>, cursor: TerminalCursor?) {
    val scheme = MaterialTheme.colorScheme
    val annotated = remember(screen, cursor, scheme) {
        buildAnnotatedString {
            screen.forEachIndexed { row, line ->
                if (row > 0) append('\n')
                val lineStart = length
                appendStyled(line, scheme.onSurface, scheme.surfaceContainerLowest)
                if (cursor != null && cursor.row == row) {
                    val col = cursor.col.coerceAtLeast(0)
                    // Pad past the last glyph so the cursor can sit at the end of the line.
                    while (length - lineStart < col + 1) append(' ')
                    addStyle(SpanStyle(background = scheme.primary), lineStart + col, lineStart + col + 1)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = scheme.onSurface,
        // The grid is already exactly `cols` cells wide. Letting it soft-wrap would push every
        // following row down whenever a glyph renders a hair wider than the cell model (emoji
        // fallback fonts do), desyncing the whole screen from the cursor overlay.
        softWrap = false,
    )
}

private fun AnnotatedString.Builder.appendStyled(line: TerminalLine, defaultFg: Color, defaultBg: Color) {
    val offset = length
    append(line.text)
    line.spans.forEach { span ->
        val start = span.start.coerceIn(0, line.text.length)
        val end = span.end.coerceIn(start, line.text.length)
        if (end == start) return@forEach
        val style = span.style
        val fg = style.fg.toComposeColor() ?: defaultFg
        val bg = style.bg.toComposeColor() ?: defaultBg
        // Inverse video is how a shell highlights a selection or a status bar: swap the two
        // colours instead of inventing a third one.
        val textColor = if (style.inverse) bg else fg
        addStyle(
            SpanStyle(
                color = if (style.dim) textColor.copy(alpha = 0.65f) else textColor,
                background = if (style.inverse) fg else bg,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
                textDecoration = when {
                    style.underline && style.strike ->
                        TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                        )

                    style.underline -> TextDecoration.Underline
                    style.strike -> TextDecoration.LineThrough
                    else -> null
                },
            ),
            offset + start,
            offset + end,
        )
    }
}

private fun Int?.toComposeColor(): Color? = this?.let { Color(0xFF000000.toInt() or it) }
