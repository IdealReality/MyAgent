package me.rerere.myagent.data.ssh

import kotlinx.serialization.Serializable

/**
 * 一条终端通道的两种连接形态。用户可以在「通道设置」里选，选择跟着通道落盘。
 *
 * 二者的差别只有一件事：**进程活在哪**。
 *  - [Direct]：进程活在 App 与远端之间的这条 TCP 里。链路一断，sshd 把 SIGHUP 送给 shell，
 *    远端进程随之结束；重连得到的是一个全新的 shell（旧屏幕只在本机转写里）。
 *  - [Persistent]：App 先连上去，把进程放进远端的 mux（tmux / screen / abduco），再挂到它上面。
 *    App 掉线、被系统杀掉、换 APK 都只是断传输：远端 mux server 继续跑，重连 `-A` 回同一个会话，
 *    历史输出与正在跑的命令都还在。
 *
 * 持久模式不改变本地语义：本地标签、转写、自动重连与「断开 / 关标签才终止」都照旧。
 */
@Serializable
enum class TerminalChannelMode {
    Direct,
    Persistent;

    companion object {
        /** 老快照里没有这个字段；缺省就是之前唯一的行为。 */
        val DEFAULT: TerminalChannelMode = Direct
    }
}

/** mux 会话名的前缀：一眼能看出这条会话是谁建的。 */
private const val MUX_PREFIX = "myagent-"

/**
 * 一个标签在远端 mux 里的会话名。
 *
 * 必须**稳定**：标签落盘的 id 不变，重连、重启、换 APK 之后 `-A` 才能接回同一个会话。
 * 只保留小写字母与数字（tmux / screen 的会话名字符集、以及 shell 引号三方面都安全）。
 */
fun muxSessionName(tabId: String): String {
    val cleaned = tabId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    return MUX_PREFIX + cleaned.take(MUX_NAME_BODY_CHARS).ifEmpty { "tab" }
}

/** 8 个十六进制字符＝32 位，同一台手机上撞名可以忽略；再长只是难读。 */
private const val MUX_NAME_BODY_CHARS = 12

/**
 * 持久模式下真正执行的远端命令（`exec` 请求的命令行）。
 *
 * 三个必须成立的点：
 *  1. **登录 shell 先被换成 `sh`**。sshd 用 `$SHELL -c <command>` 执行 exec 请求，而 `$SHELL`
 *     可能是 fish（arch 实测）：fish 不认识 `if … then … fi`，不包一层就会在解析期直接失败。
 *  2. **整段用单引号包住**。脚本里因此不能出现单引号——所有字面量都用双引号，`$` 也不需要。
 *  3. **按 tmux → screen → abduco 的顺序挑**。tmux 的 `new -A -D` 会踢掉这条会话上残留的旧
 *     客户端（掉线时那条 TCP 可能还没被 sshd 回收），新客户端于是拿到自己的行列数而不是两者
 *     的最小值；这是重连后 TUI 尺寸正确的关键。
 *
 * 一个 mux 都没有时打印一行红字并退出 127：错误出现在终端里，而不是一个"连上了但没反应"的黑屏。
 */
fun persistentAttachCommand(muxName: String): String {
    val name = muxName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .ifEmpty { MUX_PREFIX + "tab" }
    val script = "if command -v tmux >/dev/null 2>&1; then exec tmux new -A -D -s $name;" +
        " elif command -v screen >/dev/null 2>&1; then exec screen -D -RR $name;" +
        " elif command -v abduco >/dev/null 2>&1; then exec abduco -A $name;" +
        " else printf \"\\033[31m[myagent] 主机没有 tmux / screen / abduco，持久会话无法建立。\\033[0m\\n" +
        "\\033[33m可在「通道设置」切回普通模式，或先在主机上安装 tmux。\\033[0m\\n\"; exit 127; fi"
    return "sh -c '$script'"
}

/**
 * 持久模式里用户在会话上按了 detach（`Ctrl-b d`）或 tmux 被关掉，都会让这条 exec 通道正常
 * 结束（exit 0、链路还在）。这不是「用户要关掉通道」——会话可能还在远端——所以不能像普通
 * shell 的 `exit` 那样把 autoConnect 关掉。
 */
fun keepsAutoConnectAfterCleanExit(mode: TerminalChannelMode): Boolean =
    mode == TerminalChannelMode.Persistent
