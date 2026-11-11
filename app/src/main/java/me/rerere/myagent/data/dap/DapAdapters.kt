package me.rerere.myagent.data.dap

import kotlinx.serialization.json.JsonPrimitive

/**
 * A debug adapter the remote host can run, plus the file types that make it a candidate.
 *
 * Only stdio adapters are modelled. Every adapter we ship speaks DAP over stdin/stdout, which
 * maps one-to-one onto an SSH exec channel; the TCP/socket transports omp supports exist for
 * adapters that need a listening socket (js-debug), and adding a port forward for them without
 * an adapter to test it against would be untested code on a phone.
 */
internal data class DapAdapterSpec(
    val name: String,
    val command: String,
    val fileTypes: List<String>,
    /** Prepended to every `launch` request — e.g. Delve needs `mode` before `program`. */
    val launchDefaults: Map<String, JsonPrimitive> = emptyMap(),
)

internal object DapAdapters {

    val builtIn: List<DapAdapterSpec> = listOf(
        // Delve is a first-class DAP server: `dlv dap` with no --listen reads/writes stdio.
        DapAdapterSpec(
            name = "dlv",
            command = "dlv dap",
            fileTypes = listOf(".go"),
            launchDefaults = mapOf("mode" to JsonPrimitive("debug")),
        ),
        // `python -m debugpy.adapter` is the adapter half of debugpy and speaks stdio DAP.
        DapAdapterSpec(
            name = "debugpy",
            command = "python3 -m debugpy.adapter",
            fileTypes = listOf(".py"),
        ),
        DapAdapterSpec(
            name = "debugpy-python",
            command = "python -m debugpy.adapter",
            fileTypes = listOf(".py"),
        ),
        DapAdapterSpec(
            name = "lldb-dap",
            command = "lldb-dap",
            fileTypes = listOf(".c", ".cc", ".cpp", ".cxx", ".rs", ".swift"),
        ),
        // GDB 14+ ships a DAP interpreter; the `-i dap` flag is the whole adapter.
        DapAdapterSpec(
            name = "gdb",
            command = "gdb -q -i dap",
            fileTypes = listOf(".c", ".cc", ".cpp", ".cxx"),
        ),
    )

    /**
     * Shell probe: which adapters exist on the remote host. `command -v` covers binaries;
     * debugpy is a Python module, so it is probed by importing it.
     */
    fun probeScript(): String = buildString {
        append("for c in dlv lldb-dap gdb; do if command -v \"\$c\" >/dev/null 2>&1; then printf '%s\\n' \"\$c\"; fi; done\n")
        append("if python3 -c 'import debugpy' >/dev/null 2>&1; then printf 'debugpy\\n'; fi\n")
        append("if python -c 'import debugpy' >/dev/null 2>&1; then printf 'debugpy-python\\n'; fi\n")
    }

    /**
     * Parse probe output into adapter names. Only names we know are accepted: a shell that prints
     * an error or a warning on stdout must not turn into a bogus "adapter".
     */
    fun parseProbeOutput(stdout: String): Set<String> {
        val known = builtIn.map { it.name }.toSet()
        return stdout.lineSequence()
            .map { it.trim() }
            .filter { it in known }
            .toSet()
    }

    /** Adapters that claim [program] by extension, in table order. */
    fun candidatesFor(program: String): List<DapAdapterSpec> {
        val ext = program.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return emptyList()
        return builtIn.filter { ".$ext" in it.fileTypes }
    }

    /**
     * Pick an adapter. An explicit [explicit] name (or command) always wins — the caller may
     * know something the extension does not, like a Go binary that ends in `.bin`.
     */
    fun select(program: String?, explicit: String?, available: Set<String>): DapAdapterSpec? {
        if (explicit != null) {
            val trimmed = explicit.trim()
            if (trimmed.isEmpty()) return null
            return builtIn.firstOrNull { it.name == trimmed || it.command == trimmed }
                ?: DapAdapterSpec(name = trimmed, command = trimmed, fileTypes = emptyList())
        }
        if (program != null) {
            candidatesFor(program).firstOrNull { it.name in available }?.let { return it }
        }
        return null
    }

    /** Command line for an adapter, quoted for the remote POSIX shell. */
    fun commandLine(spec: DapAdapterSpec): String = spec.command
}
