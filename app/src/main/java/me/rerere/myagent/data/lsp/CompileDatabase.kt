package me.rerere.myagent.data.lsp

import com.jcraft.jsch.Session
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.myagent.data.ssh.RemoteExec
import me.rerere.myagent.data.ssh.RemoteFileClient
import me.rerere.myagent.data.ssh.posixShellQuote

/**
 * Compile database bootstrap for clangd on projects that do not ship one.
 *
 * clangd only answers **cross-file** questions (references, rename across a header and its users)
 * for a file it has compilation flags for. With no `compile_commands.json` / `compile_flags.txt`
 * anywhere up the tree it degrades silently: `definition` still works (the include is parsed), but
 * `references` returns only the file the symbol is declared in, forever — waiting for the
 * background index does not help, because the file is never considered part of a project at all.
 *
 * Measured on clangd 23.1.4 with a two-file C project:
 *
 * | setup                                   | references for `add` in util.h |
 * |-----------------------------------------|--------------------------------|
 * | nothing                                 | `[util.h]`                     |
 * | `--fallback-style=file --background-index` | `[util.h]`                  |
 * | `compile_flags.txt` with `-I<root>`     | `[main.c, util.h]`             |
 * | external CDB + `--compile-commands-dir` | `[main.c, util.h]`             |
 *
 * The last row is the one used here: a throwaway `compile_commands.json` in `/tmp` keeps the
 * user's repository untouched (no file added to a directory we do not own, nothing for `git
 * status` to notice), while `--compile-commands-dir` points clangd at it.
 *
 * Only the file the caller is about to open gets an entry. That is enough: clangd resolves
 * includes from that translation unit, which is exactly the set of files a reference query spans.
 * Other files keep the same fallback behaviour they had before, so nothing regresses.
 */
internal object CompileDatabase {

    private const val DIR_PREFIX = "/tmp/myagent-lsp-cdb-"

    /** Files clangd discovers on its own, walking up from each source file. */
    private val DISCOVERED_FILES = listOf("compile_commands.json", "compile_flags.txt")

    /** True when clangd would find a database without our help. */
    fun alreadyPresent(files: RemoteFileClient, rootPath: String): Boolean =
        DISCOVERED_FILES.any { name -> runCatching { files.exists("$rootPath/$name") }.getOrDefault(false) }

    /**
     * Create the throwaway database for [filePath] and return its directory, or `null` when the
     * host refuses (no write access to `/tmp`, unsupported layout) — in that case the caller
     * simply runs clangd the plain way and gets the pre-existing behaviour.
     */
    fun create(
        session: Session,
        files: RemoteFileClient,
        rootPath: String,
        filePath: String,
    ): String? {
        val dir = DIR_PREFIX + System.nanoTime()
        val made = runCatching { RemoteExec.run(session, "mkdir -p " + posixShellQuote(dir)) }.getOrNull()
        if (made == null || made.exitCode != 0) return null

        val database = buildJsonArray {
            addJsonObject {
                put("directory", rootPath)
                put("file", filePath)
                putJsonArray("arguments") {
                    add("cc")
                    add("-I$rootPath")
                    add("-c")
                    add(filePath)
                }
            }
        }.toString()

        return runCatching {
            files.writeText("$dir/compile_commands.json", database + "\n")
            dir
        }.getOrNull()
    }

    /** Best-effort cleanup; a leftover `/tmp/myagent-lsp-cdb-*` directory is harmless. */
    fun remove(session: Session, dir: String?) {
        if (dir == null) return
        runCatching { RemoteExec.run(session, "rm -rf " + posixShellQuote(dir), timeoutMs = 10_000) }
    }
}
