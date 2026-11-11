package me.rerere.vm

/**
 * 引擎本身的安装：QEMU 不在 APK 里，也不在镜像包里，而是由 rootfs 的包管理器装。
 *
 * 这是「框架内置、内容下载」的边界：二进制来源是发行版仓库（可换镜像），
 * 框架只负责判断装没装、装哪个包、怎么验活。
 */
object QemuProvisioner {

    fun packageName(guestArch: String): String = when (guestArch.lowercase()) {
        "aarch64", "arm64" -> "qemu-system-arm"
        else -> "qemu-system-x86"
    }

    /** 0 = 已安装；非 0 = 缺失（配合 shell 的退出码使用） */
    fun checkCommand(binary: String): String =
        "command -v '$binary' >/dev/null 2>&1 && '$binary' --version | head -1 || echo MISSING"

    /**
     * 安装命令。--no-install-recommends 砍掉 3D/音频/文档这些手机上永远用不到的东西，
     * 能少下几百 MB；apt 的进度输出保持原样，进度由调用方从 stdout 解析。
     *
     * APT::Sandbox::User=root 是实测必需：apt 默认把下载/解包临时目录交给 _apt 用户，
     * 而 PRoot 里 /tmp 的属主与 uid 映射对不上，dpkg 直接报
     * "mkdtemp of /tmp/apt-dpkg-install-* failed"（Android 17 真机复现）。
     */
    fun installCommand(guestArch: String): String {
        val pkg = packageName(guestArch)
        return buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("apt-get update -o Acquire::Retries=3 && ")
            append("apt-get install -y --no-install-recommends $pkg ")
            append("-o Dpkg::Options::=--force-confold -o APT::Sandbox::User=root")
        }
    }

    /** 装完之后必须能跑起来；apt 报成功但缺库时在这里暴露 */
    fun verifyCommand(binary: String): String =
        "set -e; '$binary' --version | head -1; test -x '$binary'"
}
