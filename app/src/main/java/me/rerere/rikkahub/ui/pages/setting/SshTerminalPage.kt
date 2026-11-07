package me.rerere.rikkahub.ui.pages.setting

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.networkRaceSocketFactory
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.openSshSession
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.TerminalScreen
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

private const val MAX_TRANSCRIPT_LINES = 2_000
private const val PTY_COLS = 100
private const val PTY_ROWS = 40

/** Emit at most ~30fps: a `cat` can produce hundreds of reads per second. */
private const val EMIT_INTERVAL_MS = 33L

/** Soft-keyboard slide-in; long enough that the post-IME layout has settled. */
private const val IME_SETTLE_MS = 240L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalPage(hostName: String) {
    val context = LocalContext.current
    val repository = koinInject<SshHostRepository>()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf<SshHostEntity?>(null) }
    var status by remember { mutableStateOf("加载中…") }
    var connected by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    var currentLine by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The keyboard is attached to a 1dp field the user never sees, so every soft-keyboard
    // keystroke lands on the terminal instead of in a composer below it.
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

    // JSch 对象在组合期持有；session/channel 用普通引用由引擎协程管理
    val engine = remember { TerminalEngine(context) }

    LaunchedEffect(hostName) {
        val loaded = withContext(Dispatchers.IO) { repository.getByName(hostName) }
        host = loaded
        if (loaded == null) {
            status = "找不到主机 $hostName"
            return@LaunchedEffect
        }
        status = "连接中…"
        val ok = withContext(Dispatchers.IO) {
            engine.connect(loaded) { update ->
                scope.launch {
                    if (update.clear) lines.clear()
                    if (update.appended.isNotEmpty()) {
                        lines.addAll(update.appended)
                        if (lines.size > MAX_TRANSCRIPT_LINES) {
                            lines.subList(0, lines.size - MAX_TRANSCRIPT_LINES).clear()
                        }
                    }
                    currentLine = update.current
                }
            }
        }
        connected = ok
        status = if (ok) "已连接 ${loaded.user}@${loaded.host}" else engine.lastError ?: "连接失败"
    }

    val itemCount = lines.size + if (currentLine.isNotEmpty()) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
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
        // Compose focus alone does not give the window a focused view, and ViewRootImpl routes
        // key events to that view — without this the field is "focused" but receives nothing.
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
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(host?.name ?: hostName)
                        Text(
                            // The bottom composer is gone, so this is the only place telling
                            // the user how keys reach the shell.
                            text = if (connected) "$status · 点按终端输入" else status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = {
                        lines.clear()
                        currentLine = ""
                    }) { Text("清空") }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The scaffold already padded out the system bars; without consuming them here
                // imePadding would add the IME height *on top* of the navigation bar.
                .consumeWindowInsets(padding)
                // The activity is edge-to-edge, so `adjustResize` never shrinks this content:
                // without imePadding the keyboard covers the bottom half of the transcript.
                .imePadding()
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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        // An empty string collapses to zero height in a LazyColumn row.
                        text = line.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (currentLine.isNotEmpty()) {
                    item {
                        Text(
                            text = currentLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Everything the keyboard types goes straight to the channel: the remote PTY echoes
            // it back through TerminalScreen, so the transcript *is* the edit buffer. This field
            // is 1dp and invisible — it exists only because a soft keyboard needs a focused
            // editor to talk to; `imeEdit` turns each change into bytes or DELs.
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
                            engine.writeText(edit.value)
                            imeShadow = next.text
                        }
                        TerminalEdit.Backspace -> {
                            engine.writeBytes(BACKSPACE)
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
                    .onPreviewKeyEvent { event -> forwardHardwareKey(event, engine) },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
            )
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
private fun forwardHardwareKey(event: KeyEvent, engine: TerminalEngine): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val bytes = terminalKeyBytes(event.key, event.isCtrlPressed) ?: return false
    engine.writeBytes(bytes)
    return true
}

/** Pure mapping so the escape sequences can be checked without an Android runtime. */
internal fun terminalKeyBytes(key: Key, ctrlPressed: Boolean): ByteArray? {
    if (ctrlPressed) {
        val code = ctrlCodes[key] ?: return null
        return byteArrayOf(code.toByte())
    }
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
 * 持有 JSch session + shell channel 的读循环。
 * 连接是阻塞调用，调用方必须在 IO 线程上调用；读循环自己起在 IO 线程。
 *
 * The reader decodes through [TerminalScreen] instead of handing raw bytes to the UI: the raw
 * stream is full of ANSI/OSC sequences, splits UTF-8 characters across reads, and carries
 * terminal queries (fish blocks ten seconds on DA1 if they go unanswered).
 */
private class TerminalEngine(private val context: android.content.Context) {
    private val jsch = newJSch(context)
    private var session: Session? = null
    private var channel: ChannelShell? = null
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
     */
    private val writeQueue = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ssh-terminal-writer").apply { isDaemon = true }
    }

    var lastError: String? = null
        private set

    fun connect(host: SshHostEntity, onUpdate: (TerminalScreen.Update) -> Unit): Boolean {
        lastError = null
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
            val ch = s.openChannel("shell") as ChannelShell
            // openSshSession configures keep-alive for one-shot commands (30s x 3). An
            // interactive terminal legitimately sits idle for minutes, servers do not answer
            // SSH_MSG_IGNORE, and JSch drops the connection once the *count of unanswered
            // probes* exceeds this value — `0` means "drop after the very first one", which is
            // why the old value killed a healthy session after 30s of silence. Keep probing,
            // never judge the peer by unanswered probes.
            s.serverAliveCountMax = Int.MAX_VALUE
            // Without an explicit PTY type JSch requests "dumb": no colours, no cursor control,
            // and every prompt redraw arrives as literal escape text.
            ch.setPtyType("xterm-256color")
            ch.setPtySize(PTY_COLS, PTY_ROWS, PTY_COLS * 8, PTY_ROWS * 17)
            // sshd only honours these when AcceptEnv lists them; harmless otherwise.
            ch.setEnv("TERM", "xterm-256color")
            ch.setEnv("LANG", "zh_CN.UTF-8")
            ch.setEnv("LC_ALL", "zh_CN.UTF-8")
            ch.setInputStream(null)
            ch.connect(15000)
            session = s
            channel = ch

            // The output stream is taken *after* connect on purpose: JSch closes a stream that
            // was created before connect, and every write against it then throws
            // "java.io.IOException: Already closed" (which is how the terminal ended up
            // silently discarding keystrokes, *including* the DA1 answer fish blocks on).
            val stream = ch.inputStream
            writer = ch.outputStream

            val thread = Thread {
                val screen = TerminalScreen()
                val buffer = ByteArray(8192)
                val pendingLines = ArrayList<String>()
                var current = ""
                var clearRequested = false
                var lastEmitted: String? = null
                var lastEmitAt = 0L

                fun emit() {
                    if (pendingLines.isEmpty() && !clearRequested && current == lastEmitted) return
                    onUpdate(
                        TerminalScreen.Update(
                            appended = pendingLines.toList(),
                            current = current,
                            clear = clearRequested,
                            replies = emptyList(),
                        )
                    )
                    pendingLines.clear()
                    clearRequested = false
                    lastEmitted = current
                }

                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        val update = screen.feed(buffer, n)
                        // Answer terminal queries immediately: the shell is blocked on them.
                        if (update.replies.isNotEmpty()) {
                            for (reply in update.replies) writeBytes(reply)
                        }
                        pendingLines.addAll(update.appended)
                        current = update.current
                        if (update.clear) {
                            pendingLines.clear()
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
                    if (t !is java.io.IOException) Log.w("TerminalEngine", "reader stopped", t)
                } finally {
                    emit()
                }
            }
            thread.isDaemon = true
            thread.start()
            reader = thread
            true
        } catch (t: Throwable) {
            val detail = factory.failures.joinToString("; ") { (label, reason) -> "$label=$reason" }
            lastError = (t.message ?: t.javaClass.simpleName) + if (detail.isEmpty()) "" else " | " + detail
            close()
            false
        }
    }

    /** Typed characters, exactly as the keyboard produced them (the PTY echoes them back). */
    fun writeText(text: String) {
        if (text.isEmpty()) return
        writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    /** Fire-and-forget: the bytes are written off the main thread, in submission order. */
    fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (writeQueue.isShutdown) return
        writeQueue.execute { writeRaw(bytes) }
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
            Log.w("TerminalEngine", "write failed", t)
            writer = null
            false
        }
    }

    fun close() {
        val readerThread = reader
        val ch = channel
        val s = session
        reader = null
        channel = null
        session = null
        writer = null
        writeQueue.shutdownNow()
        readerThread?.interrupt()
        // Disconnecting writes to the socket too, so it belongs on the same off-main path.
        Thread({
            try { ch?.disconnect() } catch (_: Throwable) {}
            try { s?.disconnect() } catch (_: Throwable) {}
        }, "ssh-terminal-close").apply { isDaemon = true }.start()
    }
}
