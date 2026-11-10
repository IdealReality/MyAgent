package me.rerere.myagent.selfpatch

import android.content.Context
import android.util.Log
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import java.io.File

/**
 * L1 热补丁注入。
 *
 * 必须在 Application.attachBaseContext 里调用：补丁 dex 的 Element 会被插到宿主
 * PathClassLoader 的 dexElements 数组最前面，于是宿主自己的 loadClass 会优先命中
 * 补丁类。晚于这个时机注入，早期类已经解析完了，补丁就再也进不去。
 *
 * 走的是反射（BaseDexClassLoader.pathList / DexPathList.dexElements）。这两个字段名
 * 在 AOSP 里长期稳定，但不是公开 API：失败时自动下架全部补丁，绝不阻断启动。
 *
 * 已知上限：已经被加载的类无法替换。Application 自身、以及 attachBaseContext 之前
 * 被 touched 的类都在此列。这是 JVM 的物理边界，不是工程问题。
 */
object SelfPatch {
    private const val TAG = "SelfPatch"

    @Volatile
    var injectedElements: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun install(ctx: Context): Boolean {
        val dexList = PatchManager.enabledDex(ctx)
        if (dexList.isEmpty()) {
            lastError = null
            return false
        }
        if (!PatchManager.onBootBegin(ctx)) {
            lastError = "watchdog: boot failure threshold reached, patches disabled"
            return false
        }
        return try {
            injectedElements = inject(ctx.classLoader, dexList)
            lastError = null
            Log.i(TAG, "injected ${injectedElements} element(s) from ${dexList.size} file(s)")
            true
        } catch (t: Throwable) {
            lastError = t.toString()
            Log.e(TAG, "inject failed, disabling all patches", t)
            PatchManager.disableAll(ctx)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(host: ClassLoader, dexFiles: List<File>): Int {
        val base = host as? BaseDexClassLoader
            ?: throw IllegalStateException("host loader is ${host.javaClass.name}")

        val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
        pathListField.isAccessible = true
        val hostPathList = pathListField.get(base)
            ?: throw IllegalStateException("host pathList is null")

        val dexElementsField = hostPathList.javaClass.getDeclaredField("dexElements")
        dexElementsField.isAccessible = true
        val hostElements = dexElementsField.get(hostPathList) as Array<Any?>

        val patchPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        val patchLoader = DexClassLoader(patchPath, null, null, host)
        val patchPathList = pathListField.get(patchLoader)
            ?: throw IllegalStateException("patch pathList is null")
        val patchElements = dexElementsField.get(patchPathList) as Array<Any?>

        if (patchElements.isEmpty()) return 0

        val component = hostElements.javaClass.componentType
        val combined = java.lang.reflect.Array
            .newInstance(component, hostElements.size + patchElements.size) as Array<Any?>
        patchElements.copyInto(combined, 0)
        hostElements.copyInto(combined, patchElements.size)

        dexElementsField.set(hostPathList, combined)
        return patchElements.size
    }
}
