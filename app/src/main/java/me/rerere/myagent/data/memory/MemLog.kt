package me.rerere.myagent.data.memory

/**
 * 记忆引擎 v1 · 日志。
 *
 * 存储层四文件（Store/Writer/Segmenter/Prompts）会被 JVM 单测直接执行，
 * 不能碰 android.util.Log（单测环境未 mock 会抛 "not mocked"）。统一走这里：
 * println 在 logcat 里以 System.out 出现，JVM 测试里进 stdout，两边都安全。
 */
object MemLog {
    fun d(tag: String, msg: String) {
        println("D/$tag: $msg")
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        println("W/$tag: $msg ${t?.let { it.javaClass.simpleName + ": " + it.message } ?: ""}")
    }
}
