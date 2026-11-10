package me.rerere.myagent.ui.pages.setting

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.myagent.AppScope
import me.rerere.myagent.data.ai.tools.local.SshAuth
import me.rerere.myagent.data.ai.tools.local.networkRaceSocketFactory
import me.rerere.myagent.data.ai.tools.local.newJSch
import me.rerere.myagent.data.ai.tools.local.openSshSession
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.MAX_AUTO_RECONNECT
import me.rerere.myagent.data.ssh.SavedTerminalSession
import me.rerere.myagent.data.ssh.TerminalChannelMode
import me.rerere.myagent.data.ssh.TerminalCursor
import me.rerere.myagent.data.ssh.TerminalLine
import me.rerere.myagent.data.ssh.TerminalScreen
import me.rerere.myagent.data.ssh.TerminalSessionStore
import me.rerere.myagent.data.ssh.isUserInitiatedExit
import me.rerere.myagent.data.ssh.keepsAutoConnectAfterCleanExit
import me.rerere.myagent.data.ssh.muxSessionName
import me.rerere.myagent.data.ssh.persistentAttachCommand
import me.rerere.myagent.data.ssh.reconnectDelayMs
import me.rerere.myagent.data.ssh.trimmed
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** How long one tab may keep changing before its transcript is written to disk. */
private const val SAVE_DEBOUNCE_MS = 1_200L

/**
 * Upper bound between two writes. Debouncing alone never fires while a command streams output
 * (`tail -f`, a build): every update cancels the pending write, so the tab would only be saved
 * after the output stops — and a process kill in between loses all of it.
 */
private const val SAVE_MAX_INTERVAL_MS = 15_000L

/** Quiet period after the last size change before a live window-change is sent (ms). */
private const val RESIZE_DEBOUNCE_MS = 250L

/** Kept equal to what the page renders: the disk copy is a subset of the live transcript. */
private const val MAX_TRANSCRIPT_LINES = 2_000

/** `bodySmall` + Monospace: 12sp advance ≈ 0.6em, 16sp line height. */
private const val PTY_CELL_WIDTH_SP = 7.2f
private const val PTY_CELL_HEIGHT_SP = 16f
private const val PTY_HORIZONTAL_PADDING_DP = 16f
private const val PTY_MIN_COLS = 20
private const val PTY_MAX_COLS = 240
private const val PTY_MIN_ROWS = 10
private const val PTY_MAX_ROWS = 200

/** Emit at most ~30fps: a `cat` can produce hundreds of reads per second. */
private const val EMIT_INTERVAL_MS = 33L

private const val TAG = "SshTerminal"

enum class SshTerminalStatus {
    /** A connect attempt is in flight. */
    Connecting,

    /** Shell channel open, reader running. */
    Connected,

    /** Never connected yet, or the channel went away. */
    Disconnected,

    /** The last connect attempt failed; see `lastError`. */
    Failed,
}

/**
 * One terminal tab.
 *
 * Holds the transcript as Compose state so a tab keeps rendering exactly as it was while it is
 * not the selected one — the page detaches, the session does not. The JSch session behind
 * [engine] also survives leaving the page: only closing the tab (or the channel dying) ends it.
 */
class SshTerminalSession internal constructor(
    val id: String,
    val hostName: String,
    title: String,
    val createdAtMs: Long,
    /**
     * 远端 mux 会话名。老快照里没有这个字段时按 [id] 现算，算出来的名字此后不再变化。
     */
    val muxName: String = muxSessionName(id),
) {
    var title by mutableStateOf(title)
        internal set

    var status by mutableStateOf(SshTerminalStatus.Disconnected)
        internal set

    var lastError by mutableStateOf<String?>(null)
        internal set

    /** `user@host` once a connect attempt resolved the saved host row. */
    var address by mutableStateOf<String?>(null)
        internal set

    /** Persisted wish: this tab reconnects by itself after a restart (cleared by 断开). */
    var autoConnect by mutableStateOf(true)
        internal set

    /** Typed into every freshly opened shell; empty means a plain login shell. */
    var startCommand by mutableStateOf("")
        internal set

    /**
     * 普通 = 一个登录 shell；持久 = 挂到远端 mux 的会话上（见 [TerminalChannelMode]）。
     * 改它只影响下一次连接，正在跑的 shell 不会被悄悄换掉。
     */
    var mode by mutableStateOf(TerminalChannelMode.DEFAULT)
        internal set

    /**
     * 当前这条活着的通道是用哪个模式建立的；断开时为 null。
     *
     * 和 [mode] 分开是因为 [mode] 是「下次连接用什么」：刚切到持久、还没重连的那段时间里，
     * 标签上写着「持久」会骗人——真掉线时这条 shell 还是会跟着 TCP 一起没。
     */
    var activeMode by mutableStateOf<TerminalChannelMode?>(null)
        internal set

    /** Consecutive automatic retries; reset by a successful connect or a manual action. */
    var retryAttempt by mutableStateOf(0)
        internal set

    /** When the next automatic attempt is due; null when none is scheduled. */
    var retryAtMs by mutableStateOf<Long?>(null)
        internal set

    /** Set when the user asked to disconnect, so switching tabs does not silently reconnect. */
    internal var manualDisconnect = false

    /**
     * 本地通道是被「网络没了」拆掉的，不是用户点的断开：等下一个可用网络回来就自动拨回去。
     * 不落盘——跨进程重启后 resumeAll 本来就各拨一次。
     */
    internal var awaitingNetwork = false

    /** Last grid the page measured: the size every later connect asks the PTY for. */
    internal var grid: PtyGrid? = null

    /** Rendered transcript lines, each carrying the SGR runs the shell sent. */
    val lines = mutableStateListOf<TerminalLine>()

    /** The visible grid, top row first. Replaces the old single "current line". */
    var screen by mutableStateOf<List<TerminalLine>>(emptyList())
        internal set

    /** Cursor inside [screen]; null while the remote has it hidden. */
    var cursor by mutableStateOf<TerminalCursor?>(null)
        internal set

    /** Kept per tab: switching tabs must not lose your place in the scrollback. */
    val listState = LazyListState()

    internal var engine: TerminalEngine? = null
    internal var saveJob: Job? = null
    internal var connectJob: Job? = null
    internal var retryJob: Job? = null

    /** Debounced window-change: the keyboard animates through several sizes on its way in. */
    internal var resizeJob: Job? = null
    internal var connectedAtMs = 0L
    internal var lastSavedAtMs = 0L

    internal fun apply(update: TerminalScreen.Update) {
        if (update.resetTranscript) lines.clear()
        if (update.scrolled.isNotEmpty()) {
            lines.addAll(update.scrolled)
            val overflow = lines.size - MAX_TRANSCRIPT_LINES
            if (overflow > 0) lines.subList(0, overflow).clear()
        }
        screen = update.screen
        cursor = if (update.cursorVisible && update.screen.isNotEmpty()) {
            TerminalCursor(
                row = update.cursorRow.coerceIn(0, update.screen.lastIndex),
                col = update.cursorCol.coerceAtLeast(0),
            )
        } else {
            null
        }
    }

    internal fun snapshot(nowMs: Long = System.currentTimeMillis()): SavedTerminalSession =
        SavedTerminalSession(
            id = id,
            hostName = hostName,
            title = title,
            createdAtMs = createdAtMs,
            updatedAtMs = nowMs,
            // The snapshot keeps the plain JSON shape (a string per line); encoding the runs
            // back to SGR is what lets a restored tab stay coloured.
            lines = lines.toList().map { it.toAnsi() },
            screen = screen.map { it.toAnsi() },
            cursorRow = cursor?.row ?: -1,
            cursorCol = cursor?.col ?: -1,
            autoConnect = autoConnect,
            startCommand = startCommand,
            mode = mode,
            muxName = muxName,
        ).trimmed()

    fun writeText(text: String) = engine?.writeText(text)

    fun writeBytes(bytes: ByteArray) = engine?.writeBytes(bytes)
}

/**
 * Owns every terminal channel for the process, and keeps them alive across page navigation.
 *
 * Two layers of "the channel is still there":
 *  1. In-process: the page only attaches UI. Leaving it, or switching channels, does not close
 *     anything: the SSH channel is held here together with its shell, so coming back lands on
 *     the same live channel with its scrollback untouched. Only 断开 or closing the channel's
 *     tab ends it.
 *  2. On disk: each channel's transcript, host, title and start command is persisted; after a
 *     process restart the channels come back with their output on screen. Nothing dials during
 *     the restore itself — the page dials every channel once when the user opens it.
 *
 * A channel's own copy of the work can outlive even that: a configured start command
 * (`tmux new -A -s omp`) attaches the tab to a session on the host, so an APK replacement only
 * drops the transport, never the process.
 *
 * When a channel dies unexpectedly the manager also retries on its own ([reconnectDelayMs],
 * capped by [MAX_AUTO_RECONNECT]) and retries immediately when Android reports a new default
 * network, instead of waiting for the user to tap the tab.
 */
class SshTerminalSessionManager internal constructor(
    context: Context,
    private val repository: SshHostRepository,
    private val appScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val store = TerminalSessionStore(File(File(appContext.filesDir, "ssh"), "terminals"))
    private val restored = AtomicBoolean(false)

    /** Tab order is insertion order; the UI renders it left to right. */
    val tabs = mutableStateListOf<SshTerminalSession>()

    var selectedId by mutableStateOf<String?>(null)
        private set

    var restoring by mutableStateOf(true)
        private set

    val selected: SshTerminalSession?
        get() = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()

    init {
        registerNetworkRetry()
    }

    /** Reads the persisted tabs once per process. Safe to call from every page entry. */
    suspend fun restore() {
        if (!restored.compareAndSet(false, true)) return
        restoring = true
        val saved = runCatching {
            withContext(Dispatchers.IO) { store.loadAll() }
        }.getOrDefault(emptyList())
        for (entry in saved) {
            if (tabs.any { it.id == entry.id }) continue
            val session = SshTerminalSession(
                id = entry.id,
                hostName = entry.hostName,
                title = entry.title.ifBlank { entry.hostName },
                createdAtMs = entry.createdAtMs,
                muxName = entry.muxName.ifBlank { muxSessionName(entry.id) },
            )
            session.lines.addAll(entry.lines.map(TerminalLine::fromAnsi))
            // The grid is saved separately: restoring a full-screen program's last frame would
            // be wrong, but a prompt sitting mid-screen is exactly what the user left.
            session.screen = entry.screen.map(TerminalLine::fromAnsi)
            if (entry.cursorRow >= 0 && entry.cursorCol >= 0) {
                session.cursor = TerminalCursor(entry.cursorRow, entry.cursorCol)
            }
            session.autoConnect = entry.autoConnect
            session.startCommand = entry.startCommand
            session.mode = entry.mode
            session.status = SshTerminalStatus.Disconnected
            tabs += session
        }
        if (selectedId == null) selectedId = tabs.lastOrNull()?.id
        restoring = false
        // Deliberately no dial here. Restoring runs when the app process starts, and a cold
        // start must not poke every host on the phone's radio before the user has even opened
        // the terminal. The page calls [resumeAll] the moment it is on screen.
    }

    /**
     * Entry point from the host list: reuse the tab that is already bound to this host (that is
     * what "still the previous channel" means), otherwise create the first one.
     */
    fun openHost(hostName: String) {
        val existing = tabs.firstOrNull { it.hostName == hostName }
        if (existing != null) {
            selectTab(existing.id)
        } else {
            newTab(hostName)
        }
    }

    /** Always a fresh tab — several channels to the same host are legitimate. */
    fun newTab(hostName: String) {
        val id = UUID.randomUUID().toString()
        val session = SshTerminalSession(
            id = id,
            hostName = hostName,
            title = uniqueTabTitle(hostName, tabs.map { it.title }),
            createdAtMs = System.currentTimeMillis(),
        )
        // A new tab inherits the mode of the tab in front of the user: opening several
        // persistent channels in a row is the normal case, and re-selecting the mode each time
        // would be friction. With no tabs at all this is the previous default (plain shell).
        session.mode = selected?.mode ?: TerminalChannelMode.DEFAULT
        tabs += session
        selectedId = session.id
        scheduleSave(session)
        connect(session)
    }

    fun selectTab(id: String) {
        val session = tabs.firstOrNull { it.id == id } ?: return
        selectedId = id
        if (session.status == SshTerminalStatus.Disconnected && !session.manualDisconnect) {
            connect(session)
        } else if (session.status == SshTerminalStatus.Failed && !session.manualDisconnect) {
            connect(session)
        }
    }

    /**
     * Page entry, every channel: each tab the user did not switch off is dialled once, so
     * walking back into the terminal after an APK update does not turn into "tap 重连 on six
     * tabs". A channel that already died is in exactly the same state as a restored one
     * ([SshTerminalStatus.Disconnected]), so both are covered by the same pass.
     *
     * `auto = false` on purpose: this is a single attempt. A host that is really down reports
     * its error and waits for a manual 重连 instead of retrying eight times in the background.
     */
    fun resumeAll() {
        tabs.toList().forEach { session ->
            if (!session.autoConnect || session.manualDisconnect) return@forEach
            if (session.status == SshTerminalStatus.Connected ||
                session.status == SshTerminalStatus.Connecting
            ) {
                return@forEach
            }
            session.retryJob?.cancel()
            session.retryJob = null
            session.retryAttempt = 0
            session.retryAtMs = null
            startConnect(session, auto = false)
        }
    }

    /** Rename a channel: the name belongs to the channel, not to the host. */
    fun rename(session: SshTerminalSession, title: String) {
        val clean = title.trim()
        if (clean.isEmpty() || clean == session.title) return
        session.title = clean
        scheduleSave(session)
    }

    /**
     * Takes effect on the next connect: a live shell is never typed into behind the user's back.
     */
    fun setStartCommand(session: SshTerminalSession, command: String) {
        val clean = command.trim()
        if (clean == session.startCommand) return
        session.startCommand = clean
        scheduleSave(session)
    }

    /**
     * 切换通道模式。只影响下一次连接：正在跑的 shell 不会被背地里换成另一种形态。
     *
     * 从普通切到持久时，当前那条普通 shell 还挂着；用户点「断开」再「重连」就会挂到 mux 上。
     */
    fun setMode(session: SshTerminalSession, mode: TerminalChannelMode) {
        if (mode == session.mode) return
        session.mode = mode
        scheduleSave(session)
    }

    /** User-initiated connect: clears the backoff and marks the tab as "wants to be live". */
    fun connect(session: SshTerminalSession) {
        if (session.status == SshTerminalStatus.Connecting ||
            session.status == SshTerminalStatus.Connected
        ) {
            return
        }
        session.retryJob?.cancel()
        session.retryJob = null
        session.resizeJob?.cancel()
        session.resizeJob = null
        session.retryAtMs = null
        session.manualDisconnect = false
        session.autoConnect = true
        session.awaitingNetwork = false
        startConnect(session, auto = false)
    }

    /**
     * One dial attempt.
     *
     * @param auto true for a retry the app decided on (after a drop, a network switch or a
     *   process restart). Those keep going on their own; a dial the user asked for reports the
     *   failure once instead of hammering a host they know is down.
     */
    private fun startConnect(session: SshTerminalSession, auto: Boolean) {
        if (session.status == SshTerminalStatus.Connecting ||
            session.status == SshTerminalStatus.Connected
        ) {
            return
        }
        session.connectJob?.cancel()
        session.status = SshTerminalStatus.Connecting
        session.lastError = null
        // 这一次拨号用的是哪个模式：模式可以在连接过程中改，标签要显示的是**这条**通道的形态。
        val mode = session.mode
        session.connectJob = appScope.launch {
            val host = runCatching {
                withContext(Dispatchers.IO) { repository.getByName(session.hostName) }
            }.getOrNull()
            if (host == null) {
                session.status = SshTerminalStatus.Failed
                session.lastError = "主机 ${session.hostName} 已不存在"
                return@launch
            }
            session.address = "${host.user}@${host.host}"
            val engine = TerminalEngine(appContext)
            session.engine = engine
            val grid = session.grid ?: ptyGrid(appContext)
            // The reader runs off the main thread, so both callbacks hop back before touching
            // Compose state. `engine` identity guards against a stale reader from a previous
            // attempt writing into a session that has since been reconnected.
            val connected = withContext(Dispatchers.IO) {
                engine.connect(
                    host = host,
                    grid = grid,
                    startCommand = session.startCommand,
                    mode = mode,
                    muxName = session.muxName,
                    onUpdate = { update ->
                        appScope.launch { if (session.engine === engine) onOutput(session, update) }
                    },
                    onClosed = { reason, exitStatus, linkAlive ->
                        appScope.launch {
                            if (session.engine !== engine) return@launch
                            session.engine = null
                            session.activeMode = null
                            session.status = SshTerminalStatus.Disconnected
                            if (reason != null) session.lastError = reason
                            scheduleSave(session)
                            // Only a shell that ended while the link was still up was ended by
                            // the user (`exit`). A dropped link can close the channel with
                            // status 0 as well, and reading that as "user left" is what used to
                            // switch autoConnect off for good — after which every later start
                            // needed a manual 重连.
                            val cleanExit = isUserInitiatedExit(exitStatus, linkAlive)
                            // 持久模式里 detach（Ctrl-b d）或 mux 被关掉同样表现为干净结束。
                            // 那多半是用户主动离开，不该马上又拨回去，但也**不能**把
                            // autoConnect 关掉：远端会话可能还活着，下次进来/点重连要能回去。
                            val persistentDetach =
                                cleanExit && keepsAutoConnectAfterCleanExit(session.mode)
                            val userEnded = session.manualDisconnect || (cleanExit && !persistentDetach)
                            when {
                                persistentDetach -> {
                                    session.retryAttempt = 0
                                    session.retryAtMs = null
                                    scheduleSave(session)
                                }

                                userEnded -> {
                                    session.retryAttempt = 0
                                    session.retryAtMs = null
                                    session.autoConnect = false
                                    scheduleSave(session)
                                }

                                else -> scheduleRetry(session)
                            }
                        }
                    },
                )
            }
            if (session.engine !== engine) {
                // The user closed or reconnected the tab while we were dialling.
                engine.disconnect()
                return@launch
            }
            if (connected) {
                session.status = SshTerminalStatus.Connected
                session.activeMode = mode
                session.retryAttempt = 0
                session.retryAtMs = null
                session.connectedAtMs = System.currentTimeMillis()
                scheduleSave(session)
            } else {
                session.engine = null
                session.activeMode = null
                session.status = SshTerminalStatus.Failed
                session.lastError = engine.lastError
                scheduleSave(session)
                if (auto) scheduleRetry(session)
            }
        }
    }

    /**
     * Queue the next automatic attempt.
     *
     * Runs off the same tab the user is looking at, so the delay is visible in the status line
     * ("N 秒后重连") rather than looking like a dead screen. Manual disconnect wins: a tab the
     * user switched off never dials on its own.
     */
    private fun scheduleRetry(session: SshTerminalSession) {
        if (session.manualDisconnect) return
        if (session.retryAttempt >= MAX_AUTO_RECONNECT) {
            session.retryAtMs = null
            session.status = SshTerminalStatus.Failed
            session.lastError = session.lastError ?: "自动重连 $MAX_AUTO_RECONNECT 次仍未成功"
            scheduleSave(session)
            return
        }
        val delayMs = reconnectDelayMs(session.retryAttempt)
        session.retryAttempt += 1
        session.retryAtMs = System.currentTimeMillis() + delayMs
        session.retryJob = appScope.launch {
            delay(delayMs)
            if (session.manualDisconnect) return@launch
            session.retryAtMs = null
            startConnect(session, auto = true)
        }
        scheduleSave(session)
    }

    /**
     * Network transitions, i.e. the app's own side of a dropped channel.
     *
     * `onLost` is a **trustworthy** signal that the transport is gone: Android only reports the
     * default network here, and a plain (unbound) socket dies with it. Relying on the socket to
     * notice instead is what makes a terminal sit on "已连接" for hours after a Wi-Fi switch —
     * the reader is blocked in `read()` and TCP will not time out on its own.
     *
     * So a lost network tears the local channel down **at once**: the tab goes 已断开 (and in
     * persistent mode the process keeps running inside the remote mux), and the next usable
     * network dials back. Nothing the user started is ended by this — only the transport is.
     */
    private fun registerNetworkRetry() {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        appScope.launch {
                            tabs.toList().forEach { tab ->
                                if (tab.manualDisconnect) return@forEach
                                // Only channels that were actually live: a restored tab that
                                // never dialled must stay silent.
                                val engine = tab.engine ?: return@forEach
                                tab.engine = null
                                tab.activeMode = null
                                tab.status = SshTerminalStatus.Disconnected
                                tab.awaitingNetwork = true
                                tab.retryJob?.cancel()
                                tab.retryJob = null
                                tab.retryAtMs = null
                                engine.disconnect()
                                // 网络可能只是换了一条（Wi-Fi → 流量）而不再是「没有」：等
                                // onAvailable 会漏掉「先 onAvailable、后 onLost」的顺序，所以
                                // 自己也开始退避重试；onAvailable 到了就把退避清零立即拨。
                                scheduleRetry(tab)
                                scheduleSave(tab)
                            }
                        }
                    }

                    override fun onAvailable(network: Network) {
                        appScope.launch {
                            tabs.toList().forEach { tab ->
                                // A tab the user switched off never dials on its own, and a
                                // channel that is already live has nothing to redo.
                                if (tab.manualDisconnect) return@forEach
                                val wasWaiting = tab.awaitingNetwork ||
                                    tab.retryJob != null || tab.retryAtMs != null ||
                                    (tab.status == SshTerminalStatus.Failed && tab.retryAttempt > 0)
                                if (!wasWaiting) return@forEach
                                tab.awaitingNetwork = false
                                tab.retryJob?.cancel()
                                tab.retryJob = null
                                tab.retryAtMs = null
                                tab.retryAttempt = 0
                                tab.status = SshTerminalStatus.Disconnected
                                startConnect(tab, auto = true)
                            }
                        }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "network callback unavailable", it) }
    }

    /**
     * The page measured its terminal area: apply it to the PTY when that is safe.
     *
     * A full-screen program ([TerminalScreen.usesCursorAddressing]) repaints on `SIGWINCH`, so it
     * can be told about the new size immediately — that is what keeps an Ink TUI's prompt visible
     * when the keyboard covers the lower half of the screen. A plain shell only reprints its
     * prompt, so starting a new emulator at another width would garble the output already on
     * screen; there the new size waits for the next connect.
     */
    fun resize(session: SshTerminalSession, widthPx: Int, heightPx: Int, density: Float) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return
        val grid = ptyGrid(widthPx, heightPx, density)
        if (grid == session.grid) return
        session.grid = grid
        // The IME slides in over ~200 ms and fires onSizeChanged for every step of it; each step
        // would be a window-change, a fresh emulator and a full TUI repaint. Coalesce them.
        session.resizeJob?.cancel()
        session.resizeJob = appScope.launch {
            delay(RESIZE_DEBOUNCE_MS)
            val target = session.grid ?: return@launch
            session.engine?.resize(target)
        }
    }

    fun disconnect(session: SshTerminalSession) {
        session.manualDisconnect = true
        session.awaitingNetwork = false
        // A manual disconnect also cancels the retry loop: otherwise the backoff that was
        // already scheduled would reconnect a tab the user just switched off.
        session.retryJob?.cancel()
        session.retryJob = null
        session.resizeJob?.cancel()
        session.resizeJob = null
        session.retryAtMs = null
        session.retryAttempt = 0
        session.autoConnect = false
        session.connectJob?.cancel()
        val engine = session.engine
        session.engine = null
        // 收哪条远端会话看的是这条通道**实际**的形态：模式可能刚被改成普通，而活着的仍是
        // 持久通道。先取出来，再把 activeMode 清掉。
        val killMux = muxToEnd(session)
        session.activeMode = null
        // 持久模式的「断开」就是终止通道：远端那条 mux 会话一起结束（见 TerminalEngine.disconnect）。
        engine?.disconnect(killMux = killMux)
        if (session.status != SshTerminalStatus.Failed) {
            session.status = SshTerminalStatus.Disconnected
        }
        scheduleSave(session)
    }

    /** 断开一条**活着的持久通道**时要在远端收走的会话名；普通通道没有远端会话可收。 */
    private fun muxToEnd(session: SshTerminalSession): String? =
        if (session.activeMode == TerminalChannelMode.Persistent) session.muxName else null

    fun clear(session: SshTerminalSession) {
        session.lines.clear()
        session.screen = emptyList()
        session.cursor = null
        scheduleSave(session)
    }

    fun closeTab(session: SshTerminalSession) {
        val index = tabs.indexOfFirst { it.id == session.id }
        if (index < 0) return
        session.saveJob?.cancel()
        session.connectJob?.cancel()
        session.retryJob?.cancel()
        session.retryJob = null
        session.resizeJob?.cancel()
        session.resizeJob = null
        session.retryAtMs = null
        val engine = session.engine
        val killMux = muxToEnd(session)
        session.engine = null
        session.activeMode = null
        tabs.removeAt(index)
        // Closing the tab is one of the two ways a channel ends (the other is 断开): the shell
        // must go with it, otherwise closing a tab would leak a shell — or, in persistent mode,
        // a whole mux session — on the host.
        engine?.disconnect(killMux = killMux)
        if (selectedId == session.id) {
            selectedId = tabs.getOrNull(index)?.id ?: tabs.getOrNull(index - 1)?.id
        }
        appScope.launch { withContext(Dispatchers.IO) { store.delete(session.id) } }
        // A tab that just became the visible one should be usable without a second tap.
        selectedId?.let { selectTab(it) }
    }

    /** Called when the page leaves: keep the shells, flush what they produced. */
    fun saveAll() {
        tabs.forEach { scheduleSave(it) }
    }

    private fun onOutput(session: SshTerminalSession, update: TerminalScreen.Update) {
        session.apply(update)
        scheduleSave(session)
    }

    private fun scheduleSave(session: SshTerminalSession) {
        session.saveJob?.cancel()
        val due = System.currentTimeMillis() - session.lastSavedAtMs >= SAVE_MAX_INTERVAL_MS
        session.saveJob = appScope.launch {
            if (!due) delay(SAVE_DEBOUNCE_MS)
            persist(session)
        }
    }

    private suspend fun persist(session: SshTerminalSession) {
        // Snapshot on the main thread: the Compose list must not be walked from IO while the
        // reader is appending to it.
        val snapshot = session.snapshot()
        withContext(Dispatchers.IO) { store.save(snapshot) }
        session.lastSavedAtMs = System.currentTimeMillis()
    }
}

/** `work` → `work`, `work 2`, `work 3` … */
internal fun uniqueTabTitle(base: String, existing: Collection<String>): String {
    val taken = existing.toSet()
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}

/**
 * 持有 JSch session + shell channel 的读循环。
 * 连接是阻塞调用，调用方必须在 IO 线程上调用；读循环自己起在 IO 线程。
 *
 * The reader decodes through [TerminalScreen] instead of handing raw bytes to the UI: the raw
 * stream is full of ANSI/OSC sequences, splits UTF-8 characters across reads, and carries
 * terminal queries (fish blocks ten seconds on DA1 if they go unanswered).
 */
/** Columns/rows to ask the remote PTY for. */
internal data class PtyGrid(val columns: Int, val rows: Int)

/**
 * PTY size for this phone.
 *
 * The old fixed 100×40 is a desktop-shaped window: on a ~1080px screen every line soft-wrapped,
 * so the shell's idea of the current line and what the page drew drifted apart — prompts broke
 * in the wrong place, and a full-screen program could never have fitted. Deriving the grid from
 * the display makes the page's line width and the PTY's column count the same number.
 */
internal fun ptyGrid(widthPx: Int, heightPx: Int, density: Float): PtyGrid {
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) {
        return PtyGrid(PTY_MIN_COLS, PTY_MIN_ROWS)
    }
    val cellWidth = PTY_CELL_WIDTH_SP * density
    val cellHeight = PTY_CELL_HEIGHT_SP * density
    val usable = widthPx - (PTY_HORIZONTAL_PADDING_DP * density).toInt()
    return PtyGrid(
        columns = (usable / cellWidth).toInt().coerceIn(PTY_MIN_COLS, PTY_MAX_COLS),
        rows = (heightPx / cellHeight).toInt().coerceIn(PTY_MIN_ROWS, PTY_MAX_ROWS),
    )
}

private fun ptyGrid(context: Context): PtyGrid {
    val metrics = context.resources.displayMetrics
    return ptyGrid(metrics.widthPixels, metrics.heightPixels, metrics.scaledDensity)
}

/** Pixel size reported to the PTY; only its width/height ratio matters to the line discipline. */
private fun ptyWidthPx(grid: PtyGrid): Int = (grid.columns * PTY_CELL_WIDTH_SP).toInt()

private fun ptyHeightPx(grid: PtyGrid): Int = (grid.rows * PTY_CELL_HEIGHT_SP).toInt()

internal class TerminalEngine(private val context: Context) {
    private val jsch = newJSch(context)
    private var session: Session? = null
    private var channel: Channel? = null
    private var reader: Thread? = null
    /** The channel's output stream, re-acquired if JSch closes it; see [writeRaw]. */
    private var writer: java.io.OutputStream? = null
    private val writeLock = Any()

    /**
     * Every byte that reaches the SSH channel goes through this queue.
     *
     * Writing on the UI thread is not merely slow: JSch flushes the socket from `write()`, and
     * Android throws `NetworkOnMainThreadException` for that — which JSch wraps in an
     * `IOException`. The terminal then swallowed every keystroke, including the DA1 answer fish
     * blocks on, so the page looked connected but ignored input entirely. One thread also keeps
     * the byte order intact, which a pool would not.
     *
     * Recreated per connect so a reconnect never writes into a shut-down executor.
     */
    @Volatile
    private var writeQueue: java.util.concurrent.ExecutorService? = null

    /**
     * Set by [disconnect]. A close can race the blocking dial (the user closes the tab while
     * the handshake is still in flight): the dial then completes into an engine nobody will ever
     * disconnect again, leaving an open shell. Every step after the handshake re-checks this.
     */
    @Volatile
    private var closed = false

    /**
     * The live emulator. Replaced — not mutated — by [resize], and read under [screenLock] so a
     * window-change can never land in the middle of a `feed` and lose bytes.
     */
    private var screen: TerminalScreen? = null
    private val screenLock = Any()

    /**
     * A live window-change, for programs that repaint themselves.
     *
     * JSch's `setPtySize` on a connected channel sends an SSH `window-change` request, which is
     * what makes the kernel raise `SIGWINCH`; that is exactly why this is only done for a TUI
     * ([TerminalScreen.usesCursorAddressing]). A shell prompt stays where it was, so resizing
     * under it would garble the visible grid for no benefit — there the new size still waits for
     * the next connect. Returns false when the size was not applied.
     */
    fun resize(grid: PtyGrid): Boolean {
        // Persistent mode's channel is an `exec` one; both classes carry the same setter but it
        // is declared per subclass, so the cast cannot be to a single type.
        val ch = channel ?: return false
        if (ch !is ChannelShell && ch !is ChannelExec) return false
        val swapped = synchronized(screenLock) {
            val current = screen
            if (current == null || !current.usesCursorAddressing) return@synchronized false
            screen = current.resized(grid.columns, grid.rows)
            true
        }
        if (!swapped) return false
        // Off the main thread: JSch writes the packet to the socket synchronously.
        writeQueue?.execute {
            runCatching { applyPtySize(ch, grid) }
                .onFailure { Log.w(TAG, "window-change failed", it) }
        }
        return true
    }

    /**
     * A window-change on a connected channel. 持久模式里这一格尤其重要：tmux 会把窗格缩到
     * client 的大小，键盘弹起后不发就会一直按旧行数排版。
     */
    private fun applyPtySize(ch: Channel, grid: PtyGrid) {
        val (cols, rows) = grid.columns to grid.rows
        when (ch) {
            is ChannelShell -> ch.setPtySize(cols, rows, ptyWidthPx(grid), ptyHeightPx(grid))
            is ChannelExec -> ch.setPtySize(cols, rows, ptyWidthPx(grid), ptyHeightPx(grid))
        }
    }

    var lastError: String? = null
        private set

    fun connect(
        host: SshHostEntity,
        grid: PtyGrid,
        startCommand: String = "",
        /**
         * 普通模式开 `shell` 通道（登录 shell 就活在这条 TCP 里）；持久模式开 `exec` 通道执行
         * mux attach，进程活在远端 mux server 里。
         */
        mode: TerminalChannelMode = TerminalChannelMode.Direct,
        /** 持久模式挂的远端会话名（[muxSessionName]）。 */
        muxName: String = "",
        onUpdate: (TerminalScreen.Update) -> Unit,
        onClosed: (String?, Int, Boolean) -> Unit = { _, _, _ -> },
    ): Boolean {
        lastError = null
        writeQueue = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ssh-terminal-writer").apply { isDaemon = true }
        }
        val factory = networkRaceSocketFactory(context)
        return try {
            val s = openSshSession(
                jsch = jsch,
                host = host.host,
                port = host.port,
                user = host.user,
                auth = SshAuth(host.password, host.privateKey, host.passphrase),
                timeoutMs = 20000,
                socketFactory = factory,
            )
            // openSshSession configures keep-alive for one-shot commands (30s x 3). An
            // interactive terminal legitimately sits idle for minutes, servers do not answer
            // SSH_MSG_IGNORE, and JSch drops the connection once the *count of unanswered
            // probes* exceeds this value — `0` means "drop after the very first one", which is
            // why the old value killed a healthy session after 30s of silence. Keep probing,
            // never judge the peer by unanswered probes.
            //
            // This is also what keeps the channel alive on the host while the app is in the
            // background: as long as the app process lives, the shell it opened does too, and
            // walking back into the terminal page finds it still there.
            s.serverAliveCountMax = Int.MAX_VALUE
            // Both kinds of channel need a PTY so the remote sees a terminal: `exec` only
            // because mux attach refuses to run without one ("not a terminal").
            val ch: Channel
            if (mode == TerminalChannelMode.Persistent) {
                val exec = s.openChannel("exec") as ChannelExec
                // JSch's exec channel starts without a PTY; the term type alone does not switch
                // it on (unlike ChannelShell, whose setters imply it).
                exec.setPty(true)
                exec.setPtyType(PTY_TYPE)
                exec.setPtySize(grid.columns, grid.rows, ptyWidthPx(grid), ptyHeightPx(grid))
                configureEnv(exec::setEnv)
                exec.setCommand(persistentAttachCommand(muxName))
                ch = exec
            } else {
                val shell = s.openChannel("shell") as ChannelShell
                configurePty(shell, grid)
                configureEnv(shell::setEnv)
                shell.setInputStream(null)
                ch = shell
            }
            ch.connect(15000)
            if (closed) {
                runCatching { ch.disconnect() }
                runCatching { s.disconnect() }
                return false
            }
            session = s
            channel = ch

            // The output stream is taken *after* connect on purpose: JSch closes a stream that
            // was created before connect, and every write against it then throws
            // "java.io.IOException: Already closed" (which is how the terminal ended up
            // silently discarding keystrokes, *including* the DA1 answer fish blocks on).
            val stream = ch.inputStream
            writer = ch.outputStream

            // A configured start command is typed into the shell the moment the PTY is up —
            // 普通模式才有意义。持久模式的进程已经在 mux 里了：再敲一行 `tmux new -A` 只会套出
            // 第二层 mux，所以那里不发。
            val boot = startCommand.trim()
            if (mode == TerminalChannelMode.Direct && boot.isNotEmpty()) {
                writeBytes((boot + "\r").toByteArray(Charsets.UTF_8))
            }

            val thread = Thread {
                // Grid size must match the PTY the remote was told about, or every line wraps
                // at a different column than the one the shell believes in. The instance is a
                // field because [resize] swaps it after a live window-change.
                synchronized(screenLock) { screen = TerminalScreen(grid.columns, grid.rows) }
                val buffer = ByteArray(8192)
                val pendingScrolled = ArrayList<TerminalLine>()
                var visible: List<TerminalLine> = emptyList()
                var cursorRow = 0
                var cursorCol = 0
                var cursorVisible = true
                var clearRequested = false
                var lastScreen: List<TerminalLine>? = null
                var lastEmitAt = 0L
                var endedBecauseOf: String? = null

                fun emit() {
                    if (pendingScrolled.isEmpty() && !clearRequested && visible == lastScreen) return
                    onUpdate(
                        TerminalScreen.Update(
                            scrolled = pendingScrolled.toList(),
                            screen = visible,
                            cursorRow = cursorRow,
                            cursorCol = cursorCol,
                            cursorVisible = cursorVisible,
                            resetTranscript = clearRequested,
                            replies = emptyList(),
                        )
                    )
                    pendingScrolled.clear()
                    clearRequested = false
                    lastScreen = visible
                }

                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        val update = synchronized(screenLock) { screen?.feed(buffer, n) } ?: break
                        // Answer terminal queries immediately: the shell is blocked on them.
                        if (update.replies.isNotEmpty()) {
                            for (reply in update.replies) writeBytes(reply)
                        }
                        pendingScrolled.addAll(update.scrolled)
                        visible = update.screen
                        cursorRow = update.cursorRow
                        cursorCol = update.cursorCol
                        cursorVisible = update.cursorVisible
                        if (update.resetTranscript) {
                            pendingScrolled.clear()
                            clearRequested = true
                        }
                        // Throttle bursts, but never defer the last frame: once the peer
                        // stops writing the loop blocks in read(), so a skipped emit here
                        // would leave the screen empty until the user typed something.
                        val now = System.currentTimeMillis()
                        if (stream.available() <= 0 || now - lastEmitAt >= EMIT_INTERVAL_MS) {
                            emit()
                            lastEmitAt = now
                        }
                    }
                } catch (t: Throwable) {
                    // Channel closed (the user left the page, or the session died): nothing to
                    // do but keep what was already on screen.
                    if (t !is java.io.IOException) Log.w(TAG, "reader stopped", t)
                    if (Thread.currentThread().isInterrupted) return@Thread
                    endedBecauseOf = t.message ?: t.javaClass.simpleName
                } finally {
                    emit()
                }
                // A dead channel must not keep claiming "已连接": tell the manager so the tab
                // flips to disconnected and becomes reconnectable. The exit status separates
                // "the user typed exit" (0) from "the link died" (-1 / non-zero) — only the
                // second one is worth an automatic retry.
                val exit = runCatching { ch.exitStatus }.getOrDefault(-1)
                // A shell that ended while the transport is still up ended on its own (`exit`);
                // a dropped link leaves isConnected() false. status alone cannot tell them apart.
                val linkAlive = runCatching { s.isConnected }.getOrDefault(false)
                runCatching { onClosed(endedBecauseOf, exit, linkAlive) }
            }
            thread.isDaemon = true
            thread.start()
            reader = thread
            true
        } catch (t: Throwable) {
            val detail = factory.failures.joinToString("; ") { (label, reason) -> "$label=$reason" }
            lastError = (t.message ?: t.javaClass.simpleName) + if (detail.isEmpty()) "" else " | " + detail
            disconnect()
            false
        }
    }

    /**
     * PTY type + size + environment for the shell channel.
     *
     * JSch keeps these setters on `ChannelSession`, which is package-private, so the calls
     * have to go through the concrete subclass. Without an explicit PTY type JSch requests
     * "dumb": no colours, no cursor control, and every prompt redraw arrives as literal
     * escape text.
     */
    private fun configurePty(ch: ChannelShell, grid: PtyGrid) {
        ch.setPtyType(PTY_TYPE)
        ch.setPtySize(grid.columns, grid.rows, ptyWidthPx(grid), ptyHeightPx(grid))
    }

    /**
     * Env for the remote process. sshd only honours these when `AcceptEnv` lists them; harmless
     * otherwise. 持久模式尤其需要 LANG：tmux 在 LANG 为空时按 C 处理宽字符，中文会按一格排版，
     * 于是整屏错列。
     */
    private fun configureEnv(setEnv: (String, String) -> Unit) {
        setEnv("TERM", PTY_TYPE)
        setEnv("LANG", "zh_CN.UTF-8")
        setEnv("LC_ALL", "zh_CN.UTF-8")
    }

    /** Typed characters (the PTY echoes them back); Enter is sent as the CR a terminal expects. */
    fun writeText(text: String) {
        if (text.isEmpty()) return
        // A multi-character insertion is a paste (clipboard, IME commit, autocorrect). When the
        // remote asked for bracketed paste, frame it: without the framing an Ink TUI treats each
        // newline as Enter and submits half a message. Single keystrokes stay plain so Enter
        // still means "send".
        val bracketed = text.length > 1 && screen?.bracketedPaste == true
        writeBytes(terminalInputBytes(text, bracketedPaste = bracketed))
    }

    /** Fire-and-forget: the bytes are written off the main thread, in submission order. */
    fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val queue = writeQueue ?: return
        if (queue.isShutdown) return
        queue.execute { writeRaw(bytes) }
    }

    private fun writeRaw(bytes: ByteArray): Boolean {
        val ch = channel ?: return false
        return try {
            synchronized(writeLock) {
                // Cached, but re-acquired after a failure: a stream that JSch has closed keeps
                // throwing "Already closed" and would swallow the rest of the session's input.
                val out = writer ?: ch.outputStream.also { writer = it }
                out.write(bytes)
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
            writer = null
            false
        }
    }

    /**
     * @param killMux 持久模式才传：点「断开」/关标签就是「终止通道」，远端那条 mux 会话也要
     *   跟着结束，否则每关一个标签就在主机上留一条常驻会话。
     */
    fun disconnect(killMux: String? = null) {
        closed = true
        val readerThread = reader
        val ch = channel
        val s = session
        reader = null
        channel = null
        session = null
        writer = null
        synchronized(screenLock) { screen = null }
        writeQueue?.shutdownNow()
        writeQueue = null
        readerThread?.interrupt()
        // Disconnecting writes to the socket too, so it belongs on the same off-main path.
        // Order matters: the attach channel goes first, then the mux session is ended over a
        // second channel on the same transport, and only then the transport itself.
        Thread({
            try { ch?.disconnect() } catch (_: Throwable) {}
            if (killMux != null) endMuxSession(s, killMux)
            try { s?.disconnect() } catch (_: Throwable) {}
        }, "ssh-terminal-close").apply { isDaemon = true }.start()
    }

    /**
     * 结束远端那条 mux 会话。一条短命的 exec 通道，失败就算了——主机都不可达的时候，这件事
     * 本来也做不到（那边会留下一条常驻会话，重新建同名标签再接回去即可）。
     */
    private fun endMuxSession(session: Session?, muxName: String) {
        val s = session ?: return
        val name = muxName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (name.isEmpty()) return
        runCatching {
            if (!s.isConnected) return
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand(
                "sh -c 'tmux kill-session -t $name 2>/dev/null; screen -S $name -X quit 2>/dev/null; exit 0'",
            )
            ch.connect(5000)
            ch.disconnect()
        }.onFailure { Log.w(TAG, "could not end the remote mux session", it) }
    }

    private companion object {
        const val TAG = "TerminalEngine"

        /**
         * PTY 的终端类型。颜色、光标寻址、宽字符都按它协商；持久模式下 tmux 客户端也用它
         * 决定能力集（TERM 为空时 tmux 会拒绝 attach：`terminal does not support clear`）。
         */
        const val PTY_TYPE = "xterm-256color"
    }
}
