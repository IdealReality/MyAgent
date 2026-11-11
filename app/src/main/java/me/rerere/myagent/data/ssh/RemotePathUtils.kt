package me.rerere.myagent.data.ssh

import com.jcraft.jsch.Session

/**
 * Remote path and probe helpers shared by the SSH code-intelligence tools.
 *
 * They live in `data.ssh`, not in the tool package, because they are pure protocol/filesystem
 * logic: no Android dependency, directly callable from a JVM harness against a real host.
 */

/** POSIX parent directory; `/a` and `/a/` both yield `/`. */
internal fun remoteParent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    val cut = trimmed.lastIndexOf('/')
    return when {
        cut < 0 -> "/"
        cut == 0 -> "/"
        else -> trimmed.substring(0, cut)
    }
}

internal fun remoteJoin(dir: String, name: String): String =
    if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

/**
 * Walk up from [targetPath] until a directory contains one of [markers].
 *
 * A language server is given a root, not a file: clangd without a `compile_commands.json`
 * directory still needs the project root for includes and diagnostics, and gopls refuses to work
 * outside the module root. The nearest marked ancestor is the best available guess and is what
 * the user means by "the project".
 *
 * Falls back to [targetPath] itself (for a directory) or its parent (for a file) when no marker
 * is found within [maxDepth] levels.
 */
internal fun locateRemoteRoot(
    files: RemoteFileClient,
    targetPath: String,
    markers: List<String>,
    maxDepth: Int = 8,
): String {
    val fallback = if (files.isDirectory(targetPath)) targetPath else remoteParent(targetPath)
    var dir = fallback
    var depth = 0
    while (depth < maxDepth) {
        for (marker in markers) {
            if (marker.contains('*')) continue // wildcard markers are not worth a directory scan
            if (files.exists(remoteJoin(dir, marker))) return dir
        }
        if (dir == "/") break
        dir = remoteParent(dir)
        depth++
    }
    return fallback
}

/** Run [script] on the remote host and return stdout (stderr folded in when stdout is empty). */
internal fun probeRemote(
    session: Session,
    script: String,
    timeoutMs: Int = 20_000,
): String {
    val result = RemoteExec.run(session, script, timeoutMs)
    return if (result.stdout.isNotBlank()) result.stdout else result.stderr
}

/** True when [command] resolves anywhere the remote shell would find it. */
internal fun remoteCommandExists(session: Session, command: String): Boolean {
    val script = "command -v ${posixShellQuote(command)} >/dev/null 2>&1 && printf yes"
    return probeRemote(session, script, 10_000).trim() == "yes"
}
