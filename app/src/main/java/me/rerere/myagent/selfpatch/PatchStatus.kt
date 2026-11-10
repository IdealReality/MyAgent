package me.rerere.myagent.selfpatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 给 LLM 会话读的补丁状态快照。只读，不改任何东西。
 *
 * 有了它，模型才能自己判断"上一次改的东西生效了没有、是不是被看门狗下了架"，
 * 而不是靠猜。
 */
object PatchStatus {

    fun snapshot(ctx: Context): String {
        val boot = PatchManager.readBootState(ctx)
        val enabled = PatchManager.enabledDex(ctx)
        val staging = PatchManager.stagingDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val disabled = PatchManager.disabledDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?: emptyList()

        val root = JSONObject()
        root.put("injectedElements", SelfPatch.injectedElements)
        root.put("lastInjectError", SelfPatch.lastError ?: "")
        root.put("bootAttempts", boot.attempts)
        root.put("lastOkPatch", boot.lastOkPatch ?: "")
        root.put("enabled", enabled.toJson())
        root.put("staging", staging.toJson())
        root.put("disabled", disabled.toJson())
        return root.toString(2)
    }

    private fun List<File>.toJson(): JSONArray {
        val arr = JSONArray()
        forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("bytes", f.length())
            )
        }
        return arr
    }
}
