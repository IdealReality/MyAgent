package me.rerere.myagent.data.ssh

/**
 * 一条终端通道断掉之后，App 自己拨回去的策略。
 *
 * 通道只活在双方的 TCP 连接里：App 进程在，通道就在（远端 sshd 侧也跟着在），离开页面、
 * 切标签都不会关它；进程被系统杀掉，通道随之消失，下次进终端页重新建连即可。
 */

/** Reconnect attempts before the tab stops dialling on its own and waits for the user. */
const val MAX_AUTO_RECONNECT = 8

/**
 * Backoff between automatic reconnects: 1s, 2s, 4s, 8s, 16s, then 30s / 60s.
 *
 * Deliberately capped: a phone on a train keeps switching cells, so the first retries must
 * be fast enough to feel like "it just came back", while a plane-mode phone must not burn
 * the radio every second for the rest of the day.
 */
fun reconnectDelayMs(attempt: Int): Long = when {
    attempt <= 0 -> 1_000L
    attempt >= 6 -> 60_000L
    else -> 1_000L shl attempt
}

/**
 * Did the user end this shell themselves (`exit` / Ctrl-D), or did the link die under it?
 *
 * `exit` closes the channel with status 0 **while the SSH session is still connected** — that is
 * the only signal that separates it from a dropped connection, because sshd closing a pty on
 * transport loss can also report 0. Reading a dropped link as a deliberate exit is what used to
 * switch `autoConnect` off for good, after which every app start demanded a manual 重连.
 */
fun isUserInitiatedExit(exitStatus: Int, linkAlive: Boolean): Boolean =
    exitStatus == 0 && linkAlive
