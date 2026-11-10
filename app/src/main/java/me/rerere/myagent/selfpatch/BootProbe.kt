package me.rerere.myagent.selfpatch

/**
 * 注入链路探针。
 *
 * 故意做成最简单的一句话方法：补丁 dex 里放一个同名类，把 value() 改成别的字符串，
 * 启动日志就会从 "host" 变成 "patched"。这样"注入到底有没有生效"不需要猜，
 * 也不需要依赖任何 UI。
 */
object BootProbe {
    fun value(): String = "host"
}
