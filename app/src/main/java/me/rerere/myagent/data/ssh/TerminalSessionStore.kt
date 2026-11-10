package me.rerere.myagent.data.ssh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一个 SSH 终端通道的落盘形态。
 *
 * 普通模式的通道本身不能跨进程存活（TCP + shell 进程都在连接里），但**用户看到的东西**可以：
 * 哪个主机、通道叫什么、屏幕上已经有什么。下次打开 app 时按这份快照把通道恢复出来，
 * 再对选中通道重新建连，历史输出继续留在上面。
 *
 * 持久模式（[TerminalChannelMode.Persistent]）把远端的进程托管给 mux，所以真正活下来的是
 * 远端那条会话；这份快照里额外记住模式与会话名，就是让重连找得回同一件事。
 */
@Serializable
data class SavedTerminalSession(
    val id: String,
    val hostName: String,
    val title: String,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    /** 已经滚出屏幕的历史行（最新的在末尾）。 */
    val lines: List<String> = emptyList(),
    /** 可见屏幕的网格，一行一个字符串（颜色同样编码在 SGR 里）。 */
    val screen: List<String> = emptyList(),
    /** 光标在屏内的位置；-1 表示远端隐藏了光标。 */
    val cursorRow: Int = -1,
    val cursorCol: Int = -1,
    /** 用户没有主动「断开」时置 true：进程重启后这个通道应当自己连回去。 */
    val autoConnect: Boolean = true,
    /** 通道模式：普通 shell 还是挂远端 mux 的持久会话。跟着通道走，不跟着主机走。 */
    val mode: TerminalChannelMode = TerminalChannelMode.DEFAULT,
    /**
     * 持久模式在远端 mux 里的会话名。
     *
     * 落盘而不是每次从 id 现算：名字一旦变了，重连就会挂到一条新会话上，"回到现场"就成了
     * "又开了一个 shell"。
     */
    val muxName: String = "",
    /**
     * 普通模式下、连接成功后自动敲进新 shell 的一行（Enter 由 App 补）。
     *
     * 持久模式不用它：那里由 App 自己把进程放进远端 mux，再在 mux 里跑登录 shell。同一行命令
     * 填在持久模式里反而会套出第二层 mux。
     */
    val startCommand: String = "",
)

/** 磁盘上保留的尾部行数：再多用户也不会翻回去，但会让每个快照白白大几倍。 */
const val MAX_SAVED_TERMINAL_LINES = 1_000

/** 单行上限：进度条/二进制误输出可能是几 MB 的一行。 */
const val MAX_SAVED_TERMINAL_LINE_CHARS = 2_000

/** 只保留尾部并截断超长行；纯函数，便于在 JVM 单测里钉住。 */
fun SavedTerminalSession.trimmed(): SavedTerminalSession = copy(
    lines = lines.takeLast(MAX_SAVED_TERMINAL_LINES).map { it.truncateSaved() },
    // The grid is at most a few dozen rows; only an absurd line can overflow.
    screen = screen.map { it.truncateSaved() },
)

private fun String.truncateSaved(): String =
    if (length > MAX_SAVED_TERMINAL_LINE_CHARS) take(MAX_SAVED_TERMINAL_LINE_CHARS) else this

/**
 * 每个终端标签一个 JSON 文件（`<filesDir>/ssh/terminals/<id>.json`）。
 *
 * 一个文件一个标签而不是一个总表，是刻意的：写一个标签不需要重写其它标签的转写，
 * 单个文件损坏也只丢一个标签。写入走 "写临时文件再改名"，避免进程被杀留下半个 JSON。
 */
class TerminalSessionStore(
    private val dir: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun loadAll(): List<SavedTerminalSession> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { json.decodeFromString<SavedTerminalSession>(file.readText()) }.getOrNull()
        }.sortedBy { it.createdAtMs }
    }

    fun save(session: SavedTerminalSession) {
        dir.mkdirs()
        val target = fileFor(session.id)
        // Unique temp name: two saves of the same tab can overlap (a debounced write and the
        // flush on page exit), and a shared temp file would let them corrupt each other.
        val temp = File.createTempFile(target.name, ".tmp", dir)
        temp.writeText(json.encodeToString(session))
        if (!temp.renameTo(target)) {
            // Windows/JVM 的 rename 不覆盖已存在的目标。
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "session" }
        return File(dir, safe + JSON_SUFFIX)
    }

    private companion object {
        const val JSON_SUFFIX = ".json"
    }
}
