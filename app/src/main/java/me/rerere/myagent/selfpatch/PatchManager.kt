package me.rerere.myagent.selfpatch

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Self-Patch 补丁生命周期管理。
 *
 * 目录布局（全部位于 App 私有 filesDir 下）：
 *   patches/enabled/NAME.dex   启动时注入，按文件名排序
 *   patches/staging/NAME.dex   已生成、待启用
 *   patches/disabled/NAME.dex  已回滚
 *   patches/boot.json       看门狗状态
 *
 * 看门狗：每次启动 attempts+1；Application.onCreate 完整走完置 0。
 * 连续 MAX_BOOT_FAILURES 次没走完，就把全部补丁下架，避免"坏补丁 -> 起不来 -> 更没法关"的死循环。
 */
object PatchManager {
    private const val TAG = "SelfPatch"
    private const val MAX_BOOT_FAILURES = 3

    fun root(ctx: Context): File = File(ctx.filesDir, "patches").apply { mkdirs() }

    fun enabledDir(ctx: Context): File = File(root(ctx), "enabled").apply { mkdirs() }

    fun stagingDir(ctx: Context): File = File(root(ctx), "staging").apply { mkdirs() }

    fun disabledDir(ctx: Context): File = File(root(ctx), "disabled").apply { mkdirs() }

    private fun bootFile(ctx: Context): File = File(root(ctx), "boot.json")

    data class BootState(
        val attempts: Int = 0,
        val lastOkPatch: String? = null,
    )

    fun readBootState(ctx: Context): BootState = runCatching {
        val f = bootFile(ctx)
        if (!f.isFile) return@runCatching BootState()
        val o = JSONObject(f.readText(Charsets.UTF_8))
        BootState(
            attempts = o.optInt("attempts", 0),
            lastOkPatch = o.optString("lastOkPatch", "").ifEmpty { null },
        )
    }.getOrElse {
        Log.w(TAG, "readBootState failed", it)
        BootState()
    }

    private fun writeBootState(ctx: Context, state: BootState) {
        runCatching {
            bootFile(ctx).writeText(
                JSONObject()
                    .put("attempts", state.attempts)
                    .put("lastOkPatch", state.lastOkPatch ?: "")
                    .toString(),
                Charsets.UTF_8,
            )
        }.onFailure { Log.w(TAG, "writeBootState failed", it) }
    }

    fun enabledDex(ctx: Context): List<File> =
        enabledDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 启动时调用。返回 false 表示本轮不应注入（补丁已全部下架）。 */
    fun onBootBegin(ctx: Context): Boolean {
        val state = readBootState(ctx)
        if (state.attempts >= MAX_BOOT_FAILURES) {
            Log.w(TAG, "boot failures=${state.attempts}, disabling all patches")
            disableAll(ctx)
            writeBootState(ctx, BootState())
            return false
        }
        writeBootState(ctx, state.copy(attempts = state.attempts + 1))
        return true
    }

    /** Application.onCreate 走完调用：本轮启动判定成功。 */
    fun onBootOk(ctx: Context) {
        val active = enabledDex(ctx).firstOrNull()?.name
        writeBootState(ctx, BootState(attempts = 0, lastOkPatch = active))
    }

    fun disableAll(ctx: Context) {
        enabledDex(ctx).forEach { f ->
            runCatching { f.renameTo(File(disabledDir(ctx), f.name)) }
                .onFailure { Log.w(TAG, "disable ${f.name} failed", it) }
        }
    }

    /** 把 staging 里的补丁挪进 enabled（下次冷启动生效）。返回移动的文件数。 */
    fun promoteStaging(ctx: Context): Int {
        var n = 0
        stagingDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                if (f.renameTo(File(enabledDir(ctx), f.name))) n++
            }
        return n
    }
}
