package me.rerere.myagent.plugin

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import me.rerere.myagent.skills.js.decodeBase64ImageToCacheFile
import java.io.File

private const val TAG = "PluginSamples"

/**
 * Turns a plugin-returned base64 image into a `file://` URL the chat renderer already
 * knows how to display (same pipeline as the JS skill runner).
 */
fun pluginImageUrl(context: Context, base64: String): String? =
    decodeBase64ImageToCacheFile(context, base64)?.toUri()?.toString()
private const val SAMPLE_ROOT = "plugins"
private const val LEGACY_SEEDED_MARKER = ".seeded"

/**
 * Installs the plugins bundled in the APK's `assets/plugins/` that are not on disk yet.
 *
 * The previous rule seeded only when the whole plugin dir was empty, which made every
 * plugin added to the APK after the first launch dead weight: the dir was already
 * populated by the sample, so a shipped kernel integration (e.g. `trpg`) could never
 * reach an existing install. The rule is now per plugin, and asymmetric on purpose:
 *
 *  - a directory that exists is user/model-owned state → never overwritten;
 *  - a bundled plugin that is missing is an app capability that was not installed yet.
 *
 * Consequence: deleting a bundled plugin re-installs it on the next launch. Disable it
 * instead — [PluginStore.setEnabled] persists that, and the Plugins page has the switch.
 */
object PluginSamples {
    fun seedBundled(context: Context, store: PluginStore) {
        store.ensure()
        val installed = runCatching {
            installMissingBundled(
                root = store.root,
                bundledIds = context.assets.list(SAMPLE_ROOT).orEmpty().toList(),
                copy = { id -> copyAssetTree(context, "$SAMPLE_ROOT/$id", File(store.root, id)) },
            )
        }.onFailure { Log.w(TAG, "bundled plugin install failed", it) }.getOrDefault(emptyList())
        if (installed.isNotEmpty()) {
            Log.i(TAG, "installed bundled plugin(s): ${installed.joinToString(",")}")
        }
        // Marker from the old "seed only into an empty dir" rule. Removing it keeps a
        // rollback to the previous build from treating a populated dir as unseeded.
        File(store.root, LEGACY_SEEDED_MARKER).delete()
    }

    /**
     * The whole install policy, without Android: for each bundled id, install only when its
     * directory is absent. Split out so the "never overwrite what is already there" rule is
     * covered by a plain JVM test instead of only by hand on a device.
     */
    internal fun installMissingBundled(
        root: File,
        bundledIds: List<String>,
        copy: (String) -> Unit,
    ): List<String> {
        val installed = ArrayList<String>()
        bundledIds.forEach { id ->
            val target = File(root, id)
            if (target.exists()) return@forEach
            copy(id)
            installed += id
        }
        return installed
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree(context, "$assetPath/$child", File(target, child)) }
    }
}
