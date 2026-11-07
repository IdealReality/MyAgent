package me.rerere.rikkahub.ui.pages.setting

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
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
    var input by rememberSaveable { mutableStateOf("") }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(host?.name ?: hostName)
                        Text(
                            text = status,
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入命令") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cmd = input
                        if (cmd.isNotEmpty() && engine.send(cmd)) {
                            input = ""
                        }
                    },
                    enabled = connected,
                ) { Text("发送") }
            }
            Text(
                text = "行式终端：支持普通命令与交互式 shell，不支持 vim/htop 等全屏程序。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

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
    private val writeLock = Any()

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
            // interactive terminal legitimately sits idle for minutes, and servers do not
            // answer SSH_MSG_IGNORE, so that count would declare a healthy session dead after
            // 90s of silence. Keep probing, never judge the peer by unanswered probes.
            s.serverAliveCountMax = 0
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

            val stream = ch.inputStream
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
                            for (reply in update.replies) writeRaw(reply)
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
                } catch (_: Throwable) {
                    // Channel closed (user left the page or the session died).
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

    fun send(command: String): Boolean {
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        return writeRaw(bytes)
    }

    private fun writeRaw(bytes: ByteArray): Boolean {
        val ch = channel ?: return false
        return try {
            synchronized(writeLock) {
                val out = ch.outputStream
                out.write(bytes)
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w("TerminalEngine", "write failed", t)
            false
        }
    }

    fun close() {
        reader?.interrupt()
        reader = null
        try { channel?.disconnect() } catch (_: Throwable) {}
        channel = null
        try { session?.disconnect() } catch (_: Throwable) {}
        session = null
    }
}
