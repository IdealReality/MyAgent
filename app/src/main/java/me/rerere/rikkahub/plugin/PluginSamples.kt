package me.rerere.rikkahub.plugin

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import me.rerere.rikkahub.skills.js.decodeBase64ImageToCacheFile
import java.io.File

private const val TAG = "PluginSamples"

/**
 * Turns a plugin-returned base64 image into a `file://` URL the chat renderer already
 * knows how to display (same pipeline as the JS skill runner).
 */
fun pluginImageUrl(context: Context, base64: String): String? =
    decodeBase64ImageToCacheFile(context, base64)?.toUri()?.toString()
private const val SAMPLE_ROOT = "plugins"
private const val SEEDED_MARKER = ".seeded"

/**
 * Copies the bundled reference plugin into the writable plugin dir on first run only.
 *
 * Deliberately one sample, and only when the directory has never been seeded: the plugin
 * dir is user/model-owned state, so re-seeding after a user deletes everything would be
 * the app fighting its own operator. The marker records "we already offered it".
 */
object PluginSamples {
    fun seedIfEmpty(context: Context, store: PluginStore) {
        store.ensure()
        val marker = File(store.root, SEEDED_MARKER)
        if (marker.exists()) return
        val existing = store.listIds()
        if (existing.isNotEmpty()) {
            marker.writeText("already populated: ${existing.joinToString(",")}")
            return
        }
        runCatching {
            val assets = context.assets.list(SAMPLE_ROOT).orEmpty()
            assets.forEach { name ->
                copyAssetTree(context, "$SAMPLE_ROOT/$name", File(store.root, name))
            }
            marker.writeText("seeded=${assets.joinToString(",")} at ${System.currentTimeMillis()}")
            Log.i(TAG, "seeded ${assets.size} sample plugin(s)")
        }.onFailure { Log.w(TAG, "sample seeding failed", it) }
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
