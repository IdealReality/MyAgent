package me.rerere.myagent.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class PluginState(
    val enabled: Boolean = true,
    val failures: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = 0L,
)

data class PluginSource(
    val manifest: PluginManifest,
    val dir: File,
    val validation: PluginValidation,
    val state: PluginState,
)

/**
 * P2-40 — plugin kernel, part 2: the disk layout.
 *
 * ```
 * files/plugins/
 *   <id>/manifest.json + main.js + assets…      working copy (what runs)
 *   .state.json                                 enable flags + failure counters
 *   .history/<id>/<epoch>/…                     rollback snapshots (5 deep)
 *   .kv/<id>.json                               plugin-scoped key/value store
 * ```
 *
 * Pure JVM (File + kotlinx.serialization only) so the whole store is unit-testable without
 * an Android context. Every write is atomic (temp file + rename) and every mutating call
 * snapshots first, which is what makes "let the model rewrite itself" recoverable.
 */
class PluginStore(
    rootDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true },
) {
    val root: File = rootDir
    private val stateFile: File get() = File(root, STATE_FILE)
    private val historyDir: File get() = File(root, HISTORY_DIR)
    private val kvDir: File get() = File(root, KV_DIR)
    private val lock = Any()

    companion object {
        const val STATE_FILE = ".state.json"
        const val HISTORY_DIR = ".history"
        const val KV_DIR = ".kv"
        const val MAX_SNAPSHOTS = 5
        /** Consecutive runtime failures before a plugin is auto-disabled. */
        const val MAX_FAILURES = 3
        private const val MANIFEST = "manifest.json"
    }

    fun ensure() {
        synchronized(lock) {
            if (!root.exists()) root.mkdirs()
            if (!stateFile.exists()) writeAtomically(stateFile, "{}")
        }
    }

    fun dir(id: String): File = File(root, id)

    fun listIds(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun hasManifest(id: String): Boolean = File(dir(id), MANIFEST).isFile

    // ---------------------------------------------------------------- manifests

    fun readManifest(id: String): PluginSource? {
        val dir = dir(id)
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        val manifest = runCatching {
            json.decodeFromString(PluginManifest.serializer(), file.readText())
        }.getOrNull() ?: return null
        val validation = PluginValidationRules.validate(manifest, dir)
        // The directory name IS the plugin id (tools dispatch by id, state is keyed by id).
        // A hand-edited directory can disagree with its manifest; that is a load error, not
        // something to paper over.
        val effective = if (manifest.id != id) {
            validation.copy(
                errors = validation.errors + "manifest id '${manifest.id}' does not match directory '$id'",
            )
        } else {
            validation
        }
        return PluginSource(
            manifest = manifest,
            dir = dir,
            validation = effective,
            state = stateOf(id),
        )
    }

    fun readManifestRaw(id: String): String? =
        File(dir(id), MANIFEST).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    // ------------------------------------------------------------------- state

    fun states(): Map<String, PluginState> = synchronized(lock) { readStateFile() }

    fun stateOf(id: String): PluginState = states()[id] ?: PluginState()

    fun setEnabled(id: String, enabled: Boolean) = updateState(id) {
        it.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
    }

    fun recordFailure(id: String, message: String): PluginState = updateState(id) {
        val failures = it.failures + 1
        it.copy(
            failures = failures,
            lastError = message.take(2_000),
            // Self-healing: after MAX_FAILURES consecutive failures the plugin stops being
            // loaded at all. Without this a plugin that throws during load would be retried
            // on every single turn and spam the tool surface with the same error.
            enabled = if (failures >= MAX_FAILURES) false else it.enabled,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun clearFailures(id: String) = updateState(id) {
        if (it.failures == 0 && it.lastError == null) it
        else it.copy(failures = 0, lastError = null, updatedAt = System.currentTimeMillis())
    }

    private val stateSerializer = MapSerializer(String.serializer(), PluginState.serializer())
    private val kvSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun updateState(id: String, transform: (PluginState) -> PluginState): PluginState {
        synchronized(lock) {
            val all = readStateFile().toMutableMap()
            val next = transform(all[id] ?: PluginState())
            all[id] = next
            writeAtomically(stateFile, json.encodeToString(stateSerializer, all))
            return next
        }
    }

    private fun readStateFile(): Map<String, PluginState> = runCatching {
        if (!stateFile.isFile) return emptyMap()
        json.decodeFromString(stateSerializer, stateFile.readText())
    }.getOrElse { emptyMap() }

    // ------------------------------------------------------------------- files

    /** Resolves [relPath] inside plugin [id], refusing anything that escapes the directory. */
    fun resolveFile(id: String, relPath: String): File? {
        if (PluginValidationRules.unsafeRelativePath(relPath) != null) return null
        val base = dir(id)
        val file = File(base, relPath)
        return runCatching {
            val basePath = base.canonicalFile.toPath()
            val target = file.canonicalFile.toPath()
            if (target.startsWith(basePath)) file.canonicalFile else null
        }.getOrNull()
    }

    fun readFile(id: String, relPath: String): String? =
        resolveFile(id, relPath)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun listFiles(id: String, maxEntries: Int = 200): List<String> {
        val base = dir(id)
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown()
            .onEnter { it == base || !it.name.startsWith(".") }
            .filter { it.isFile }
            .take(maxEntries)
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()
    }

    /**
     * Writes one file inside plugin [id] after snapshotting the previous state. Returns the
     * resulting validation (manifest re-parsed when the write touched `manifest.json`).
     */
    fun writeFile(id: String, relPath: String, content: String, snapshot: Boolean = true): PluginValidation {
        val target = resolveFile(id, relPath)
            ?: return PluginValidation(listOf("unsafe path '$relPath'"))
        if (content.toByteArray().size > PluginValidationRules.MAX_WRITE_BYTES) {
            return PluginValidation(listOf("content exceeds ${PluginValidationRules.MAX_WRITE_BYTES} bytes"))
        }
        synchronized(lock) {
            if (snapshot) snapshotLocked(id)
            target.parentFile?.mkdirs()
            writeAtomically(target, content)
        }
        return validateNow(id, relPath)
    }

    private fun validateNow(id: String, touched: String): PluginValidation {
        if (touched != MANIFEST) {
            return readManifest(id)?.validation ?: PluginValidation(emptyList())
        }
        val raw = readManifestRaw(id) ?: return PluginValidation(listOf("manifest.json missing"))
        val parsed = runCatching {
            json.decodeFromString(PluginManifest.serializer(), raw)
        }.getOrElse { e ->
            return PluginValidation(listOf("manifest.json does not parse: ${e.message?.take(300)}"))
        }
        if (parsed.id.isNotBlank() && parsed.id != id) {
            // The directory name IS the plugin id; a mismatch would make the registry load
            // this plugin under one id and dispatch under another.
            return PluginValidation(listOf("manifest id '${parsed.id}' does not match directory '$id'"))
        }
        return PluginValidationRules.validate(parsed, dir(id))
    }

    fun createPlugin(
        id: String,
        manifestJson: String,
        entryContent: String?,
    ): PluginValidation {
        if (!Regex("^[a-z0-9][a-z0-9._-]{0,63}$").matches(id)) {
            return PluginValidation(listOf("invalid plugin id '$id'"))
        }
        // The directory name IS the plugin id. Refuse a mismatch before touching disk so a
        // model cannot create a plugin whose tools dispatch under another id.
        val declaredId = runCatching {
            json.decodeFromString(PluginManifest.serializer(), manifestJson).id
        }.getOrNull()
        if (declaredId != id) {
            return PluginValidation(
                listOf("manifest id '${declaredId ?: "?"}' does not match plugin_id '$id'")
            )
        }
        val target = dir(id)
        synchronized(lock) {
            if (target.exists()) {
                snapshotLocked(id)
            } else {
                target.mkdirs()
            }
            writeAtomically(File(target, MANIFEST), manifestJson)
            entryContent?.let { writeAtomically(File(target, "main.js"), it) }
        }
        return validateNow(id, MANIFEST)
    }

    fun deletePlugin(id: String): Boolean {
        synchronized(lock) {
            val target = dir(id)
            if (!target.exists()) return false
            snapshotLocked(id)
            val ok = target.deleteRecursively()
            if (ok) {
                updateState(id) { it.copy(enabled = false, failures = 0, lastError = null) }
            }
            return ok
        }
    }

    // -------------------------------------------------------------- snapshots

    fun snapshot(id: String): File? = synchronized(lock) { snapshotLocked(id) }

    private fun snapshotLocked(id: String): File? {
        val src = dir(id)
        if (!src.isDirectory) return null
        val stamp = System.currentTimeMillis()
        val target = File(File(historyDir, id), "s$stamp-${UUID.randomUUID().toString().take(6)}")
        target.mkdirs()
        if (!copyTree(src, target)) return null
        pruneSnapshots(id)
        return target
    }

    fun snapshots(id: String): List<File> =
        File(historyDir, id).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.reversed() ?: emptyList()

    /** Restores the newest snapshot over the live directory. */
    fun rollback(id: String): Boolean {
        synchronized(lock) {
            val latest = snapshots(id).firstOrNull() ?: return false
            val live = dir(id)
            if (!live.deleteRecursively()) return false
            live.mkdirs()
            if (!copyTree(latest, live)) return false
            updateState(id) { it.copy(failures = 0, lastError = null, enabled = true, updatedAt = System.currentTimeMillis()) }
            return true
        }
    }

    private fun pruneSnapshots(id: String) {
        snapshots(id).drop(MAX_SNAPSHOTS).forEach { it.deleteRecursively() }
    }

    // ---------------------------------------------------------------- key/value

    fun kvGet(pluginId: String, key: String): String? = synchronized(lock) {
        readKv(pluginId)[key]
    }

    fun kvSet(pluginId: String, key: String, value: String) = synchronized(lock) {
        val all = readKv(pluginId).toMutableMap()
        all[key] = value
        kvDir.mkdirs()
        writeAtomically(File(kvDir, "$pluginId.json"), json.encodeToString(kvSerializer, all))
    }

    private fun readKv(pluginId: String): Map<String, String> = runCatching {
        val f = File(kvDir, "$pluginId.json")
        if (!f.isFile) return emptyMap()
        json.decodeFromString(kvSerializer, f.readText())
    }.getOrElse { emptyMap() }

    // --------------------------------------------------------------- freshness

    /**
     * Cheap change token: the id set, each `manifest.json` + entry file's mtime/size, and
     * the state file. The registry reparses only when this token differs, so per-turn
     * reload costs one directory listing, not a full parse.
     */
    fun signature(): String {
        val sb = StringBuilder()
        listIds().forEach { id ->
            val d = dir(id)
            sb.append(id).append(':')
            runCatching {
                d.walkTopDown()
                    .onEnter { it == d || !it.name.startsWith(".") }
                    .filter { it.isFile }
                    .take(64)
                    .sortedBy { it.path }
                    .forEach { f ->
                        sb.append(f.relativeTo(d).path).append('=')
                            .append(f.lastModified()).append('/').append(f.length()).append(';')
                    }
            }
            sb.append('\n')
        }
        sb.append("state=").append(stateFile.lastModified()).append('/').append(stateFile.length())
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun copyTree(src: File, dst: File): Boolean = runCatching {
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src)
            val out = File(dst, rel.path)
            if (f.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                Files.copy(f.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        true
    }.getOrElse { false }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
