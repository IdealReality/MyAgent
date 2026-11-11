package me.rerere.myagent.data.lsp

import me.rerere.myagent.data.ssh.posixShellQuote

/**
 * How to start a language server and which files it owns.
 *
 * Mirrors the parts of omp's `ServerConfig` that matter for a remote-only client: a command, its
 * args, the file types it serves, and the marker files that identify its project root. No
 * settings/initOptions override surface yet — that needs a config file the phone does not have,
 * and inventing one before the basics work would be speculative.
 */
internal data class LspServerSpec(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val fileTypes: List<String>,
    val rootMarkers: List<String> = listOf(".git"),
    val languageId: String? = null,
) {
    /** Full command line, every token quoted for the POSIX shell the remote will run it with. */
    val commandLine: String
        get() = (listOf(command) + args).joinToString(" ") { posixShellQuote(it) }
}

/**
 * Built-in server table plus the pure helpers the tool needs to pick one.
 *
 * Selection order is table order, which is deliberate: `pyright-langserver` before `pylsp`
 * because pyright is the stricter type checker and a code agent benefits more from real type
 * errors than from a linter's lint; `typescript-language-server` before the others for the same
 * reason. The probe decides by what is actually installed, so an uninstalled preference costs
 * nothing.
 */
internal object LspSpecs {

    val builtIn: List<LspServerSpec> = listOf(
        LspServerSpec(
            name = "clangd",
            command = "clangd",
            fileTypes = listOf(".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".hh", ".hxx", ".m", ".mm", ".cu", ".ino"),
            rootMarkers = listOf("compile_commands.json", "compile_flags.txt", ".clangd", "CMakeLists.txt", "meson.build", "Makefile", ".git"),
        ),
        LspServerSpec(
            name = "rust-analyzer",
            command = "rust-analyzer",
            fileTypes = listOf(".rs"),
            rootMarkers = listOf("Cargo.toml", ".git"),
        ),
        LspServerSpec(
            name = "gopls",
            command = "gopls",
            fileTypes = listOf(".go"),
            rootMarkers = listOf("go.mod", "go.work", ".git"),
        ),
        LspServerSpec(
            name = "pyright",
            command = "pyright-langserver",
            args = listOf("--stdio"),
            fileTypes = listOf(".py", ".pyi"),
            rootMarkers = listOf("pyrightconfig.json", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", ".git"),
        ),
        LspServerSpec(
            name = "pylsp",
            command = "pylsp",
            fileTypes = listOf(".py", ".pyi"),
            rootMarkers = listOf("pyproject.toml", "setup.py", "setup.cfg", ".git"),
        ),
        LspServerSpec(
            name = "typescript-language-server",
            command = "typescript-language-server",
            args = listOf("--stdio"),
            fileTypes = listOf(".ts", ".tsx", ".js", ".jsx", ".mts", ".cts", ".mjs", ".cjs"),
            rootMarkers = listOf("tsconfig.json", "jsconfig.json", "package.json", ".git"),
        ),
        LspServerSpec(
            name = "denols",
            command = "deno",
            args = listOf("lsp"),
            fileTypes = listOf(".ts", ".tsx", ".js", ".jsx"),
            rootMarkers = listOf("deno.json", "deno.jsonc", "deno.lock"),
        ),
        LspServerSpec(
            name = "jdtls",
            command = "jdtls",
            fileTypes = listOf(".java"),
            rootMarkers = listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", ".classpath"),
        ),
        LspServerSpec(
            name = "kotlin-lsp",
            command = "kotlin-lsp",
            args = listOf("--stdio"),
            fileTypes = listOf(".kt", ".kts"),
            rootMarkers = listOf("build.gradle.kts", "settings.gradle.kts", "build.gradle", "pom.xml"),
        ),
        LspServerSpec(
            name = "bash-language-server",
            command = "bash-language-server",
            args = listOf("start"),
            fileTypes = listOf(".sh", ".bash", ".zsh", ".ksh"),
            rootMarkers = listOf(".git", "shellcheckrc"),
            languageId = "shellscript",
        ),
        LspServerSpec(
            name = "lua-language-server",
            command = "lua-language-server",
            fileTypes = listOf(".lua"),
            rootMarkers = listOf(".luarc.json", ".luarc.jsonc", ".git"),
        ),
        LspServerSpec(
            name = "zls",
            command = "zls",
            fileTypes = listOf(".zig", ".zon"),
            rootMarkers = listOf("build.zig", "build.zig.zon", ".git"),
        ),
        LspServerSpec(
            name = "yaml-language-server",
            command = "yaml-language-server",
            args = listOf("--stdio"),
            fileTypes = listOf(".yaml", ".yml"),
            rootMarkers = listOf(".git"),
            languageId = "yaml",
        ),
        LspServerSpec(
            name = "marksman",
            command = "marksman",
            args = listOf("server"),
            fileTypes = listOf(".md", ".markdown"),
            rootMarkers = listOf(".marksman.toml", ".git"),
            languageId = "markdown",
        ),
        LspServerSpec(
            name = "docker-langserver",
            command = "docker-langserver",
            args = listOf("--stdio"),
            fileTypes = listOf("Dockerfile", ".dockerfile"),
            rootMarkers = listOf(".git"),
            languageId = "dockerfile",
        ),
        LspServerSpec(
            name = "terraform-ls",
            command = "terraform-ls",
            args = listOf("serve"),
            fileTypes = listOf(".tf", ".tfvars"),
            rootMarkers = listOf(".terraform", ".git"),
        ),
        LspServerSpec(
            name = "nixd",
            command = "nixd",
            fileTypes = listOf(".nix"),
            rootMarkers = listOf("flake.nix", ".git"),
        ),
        LspServerSpec(
            name = "texlab",
            command = "texlab",
            fileTypes = listOf(".tex", ".bib"),
            rootMarkers = listOf(".latexmkrc", ".git"),
        ),
        LspServerSpec(
            name = "solargraph",
            command = "solargraph",
            args = listOf("stdio"),
            fileTypes = listOf(".rb", ".rake", "Gemfile", "Rakefile"),
            rootMarkers = listOf("Gemfile", ".rubocop.yml", ".git"),
            languageId = "ruby",
        ),
        LspServerSpec(
            name = "omnisharp",
            command = "omnisharp",
            args = listOf("-lsp"),
            fileTypes = listOf(".cs"),
            rootMarkers = listOf(".sln", ".csproj", ".git"),
        ),
    )

    private val languageIds: Map<String, String> = mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "pyi" to "python",
        "go" to "go",
        "rs" to "rust",
        "c" to "c",
        "h" to "c",
        "cc" to "cpp",
        "cpp" to "cpp",
        "cxx" to "cpp",
        "hpp" to "cpp",
        "hh" to "cpp",
        "hxx" to "cpp",
        "m" to "objective-c",
        "mm" to "objective-cpp",
        "cu" to "cuda-cpp",
        "ts" to "typescript",
        "tsx" to "typescriptreact",
        "mts" to "typescript",
        "cts" to "typescript",
        "js" to "javascript",
        "jsx" to "javascriptreact",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "sh" to "shellscript",
        "bash" to "shellscript",
        "zsh" to "shellscript",
        "ksh" to "shellscript",
        "lua" to "lua",
        "zig" to "zig",
        "zon" to "zig",
        "php" to "php",
        "cs" to "csharp",
        "rb" to "ruby",
        "rake" to "ruby",
        "yaml" to "yaml",
        "yml" to "yaml",
        "json" to "json",
        "jsonc" to "jsonc",
        "html" to "html",
        "css" to "css",
        "scss" to "scss",
        "md" to "markdown",
        "markdown" to "markdown",
        "sql" to "sql",
        "dart" to "dart",
        "swift" to "swift",
        "scala" to "scala",
        "hs" to "haskell",
        "ex" to "elixir",
        "exs" to "elixir",
        "erl" to "erlang",
        "ml" to "ocaml",
        "mli" to "ocaml",
        "nix" to "nix",
        "tf" to "terraform",
        "tfvars" to "terraform",
        "tex" to "latex",
        "bib" to "bibtex",
        "vim" to "vim",
        "graphql" to "graphql",
        "proto" to "proto",
    )

    /** Lowercase basename of a path; works for POSIX and Windows-style separators. */
    fun baseName(path: String): String {
        val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (cut >= 0) path.substring(cut + 1) else path
    }

    /** Extension without the dot, lowercased; empty when the basename has no extension. */
    fun extensionOf(path: String): String {
        val name = baseName(path)
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * Servers whose `fileTypes` claim this path, in table order.
     *
     * `fileTypes` entries that start with a dot are extension matches; entries without one are
     * exact basenames (`Dockerfile`, `Gemfile`), which is how editors express the same idea.
     */
    fun candidatesFor(path: String): List<LspServerSpec> {
        val name = baseName(path)
        val ext = extensionOf(path)
        return builtIn.filter { spec ->
            spec.fileTypes.any { type ->
                if (type.startsWith(".")) {
                    val bare = type.substring(1).lowercase()
                    ext == bare || name.lowercase() == bare
                } else {
                    name == type
                }
            }
        }
    }

    fun languageIdFor(path: String): String {
        val explicit = candidatesFor(path).firstOrNull { it.languageId != null }?.languageId
        if (explicit != null) return explicit
        val name = baseName(path)
        return when (name) {
            "Dockerfile", "Containerfile" -> "dockerfile"
            "Gemfile", "Rakefile" -> "ruby"
            "Makefile" -> "makefile"
            else -> languageIds[extensionOf(path)] ?: "plaintext"
        }
    }

    /**
     * A shell loop that resolves each candidate to an absolute path on the remote host, checking
     * PATH first and then the project-local bin directories real projects ship (`node_modules`,
     * a venv, Go's `bin/`). `command -v` is the shell's own resolver, so it honours aliases,
     * functions and executable bits exactly like the eventual launch would.
     */
    fun probeScript(rootPath: String, candidates: List<LspServerSpec>): String {
        val names = candidates.map { it.command }.distinct()
        val quoted = names.joinToString(" ") { posixShellQuote(it) }
        val root = posixShellQuote(rootPath)
        return buildString {
            append("cd ").append(root).append(" 2>/dev/null || true\n")
            append("for c in ").append(quoted).append("; do\n")
            append("  for p in \"\$c\" \"\$PWD/node_modules/.bin/\$c\" \"\$PWD/.venv/bin/\$c\" \"\$PWD/venv/bin/\$c\" \"\$PWD/bin/\$c\"; do\n")
            append("    if command -v \"\$p\" >/dev/null 2>&1; then printf '%s\\t%s\\n' \"\$c\" \"\$(command -v \"\$p\")\"; break; fi\n")
            append("  done\n")
            append("done\n")
        }
    }

    /** Parse `name<TAB>resolvedPath` lines from [probeScript]'s output. */
    fun parseProbeOutput(stdout: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = line.substring(0, tab)
            val path = line.substring(tab + 1)
            if (name.isNotEmpty() && path.isNotEmpty()) result.putIfAbsent(name, path)
        }
        return result
    }

    /**
     * `file://` URI for a POSIX path. Percent-encoding is manual rather than URLEncoder because
     * that helper encodes spaces as `+` and mangles the separators; a language server expects
     * `%20` and literal `/`.
     */
    fun pathToUri(path: String): String {
        val absolute = if (path.startsWith("/")) path else "/$path"
        return "file://" + percentEncodePath(absolute)
    }

    fun uriToPath(uri: String): String {
        require(uri.startsWith("file:")) { "not a file URI: $uri" }
        var rest = uri.substring(5)
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            rest = if (slash < 0) "/" else rest.substring(slash)
        }
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        return percentDecodePath(rest)
    }

    private fun percentEncodePath(path: String): String {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size + 16)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            val ch = value.toChar()
            val unreserved = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == '/'
            if (value < 0x80 && unreserved) {
                out.append(ch)
            } else {
                out.append('%')
                out.append(HEX[value shr 4])
                out.append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    private fun percentDecodePath(text: String): String {
        if (!text.contains('%')) return text
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private const val HEX = "0123456789ABCDEF"
}
