package me.rerere.vm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一台虚拟机的「镜像库条目」。
 *
 * 框架只认识这里的字段：怎么下载（[files]）、怎么启动（[boot]）、启动之后怎么连
 * （[guest]）。具体是什么系统完全由条目决定，所以新增一种系统只是往目录里加一条
 * JSON，不需要改引擎代码。
 */
@Serializable
data class VmCatalog(
    val version: Int = 1,
    val images: List<VmImage> = emptyList(),
)

@Serializable
data class VmImage(
    val id: String,
    val name: String,
    val description: String = "",
    val kind: VmKind = VmKind.ANDROID,
    /** QEMU 目标架构，决定用 qemu-system-<arch> 还是 qemu-system-x86_64 */
    val arch: String = "x86_64",
    val files: List<VmImageFile> = emptyList(),
    val boot: VmBootSpec = VmBootSpec(),
    val guest: VmGuestSpec = VmGuestSpec(),
    /** 全部文件之和，展示用；缺失时按实际下载累计 */
    val sizeBytes: Long? = null,
)

@Serializable
enum class VmKind {
    @SerialName("android")
    ANDROID,

    @SerialName("linux")
    LINUX,
}

/** 镜像包内单个文件在 VM 启动时的用途 */
@Serializable
enum class VmFileRole {
    /** 可启动介质（ISO / qcow2 / raw），默认作为第一块盘 */
    @SerialName("disk")
    DISK,

    /** 光驱 */
    @SerialName("cdrom")
    CDROM,

    /** 直接引导的内核 */
    @SerialName("kernel")
    KERNEL,

    /** 直接引导用的 initrd */
    @SerialName("initrd")
    INITRD,

    /** 可写持久盘（/data），安装时按 [VmBootSpec.dataDiskMb] 生成而不是下载 */
    @SerialName("data")
    DATA,

    @SerialName("aux")
    AUX,
}

@Serializable
data class VmImageFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val role: VmFileRole = VmFileRole.CDROM,
    /** 主地址不通时按顺序回退 */
    val mirrors: List<String> = emptyList(),
    /**
     * 从另一个已下载的文件里提取（如从 ISO 里取出 kernel/initrd）。
     * 设为非空时 [url] 不参与下载，安装器只负责占位，提取由调用方执行。
     */
    val fromArchive: String? = null,
    /** [fromArchive] 内的成员路径 */
    val archivePath: String? = null,
)

@Serializable
data class VmBootSpec(
    /** QEMU -machine，默认 pc（x86 BIOS） */
    val machine: String = "pc",
    val memoryMb: Int = 2048,
    val cpus: Int = 4,
    /** QEMU -cpu，Android-x86 需要 SSE4.2，max 最稳 */
    val cpu: String = "max",
    val vga: String = "std",
    /** 图形输出方式；VNC 由 app 内的 RFB 客户端接管，NONE 表示纯命令行 */
    val graphics: VmGraphics = VmGraphics.VNC,
    /** 直接引导时的内核命令行（仅当有 KERNEL 文件时生效） */
    val kernelArgs: List<String> = emptyList(),
    /** 自动生成的持久数据盘大小，0 表示不建 */
    val dataDiskMb: Int = 4096,
    /** 追加到 QEMU 命令行的原始参数，给目录作者留逃生口 */
    val extraArgs: List<String> = emptyList(),
)

@Serializable
enum class VmGraphics {
    @SerialName("vnc")
    VNC,

    @SerialName("none")
    NONE,
}

@Serializable
data class VmGuestSpec(
    val adbPort: Int = 5555,
    val vncPort: Int = 5900,
    /** 获得 guest root 的方式 */
    val rootMode: VmRootMode = VmRootMode.ADB_ROOT,
    /** 非 adb 方式时登录用的用户名 */
    val user: String = "root",
    /**
     * adb 尚不可用时，通过串口 console 执行的引导命令（配 guest 网卡、打开 adb TCP）。
     * 这是「镜像内容」的一部分：不同发行版的网络栈完全不同，框架不猜。
     */
    val bootstrap: List<String> = emptyList(),
    /** 串口 console 出现前等待的提示符标记；空表示不等待、按固定延时发送 */
    val consolePrompt: String = "",
    /** adb 通了之后立刻执行的命令（唤醒屏幕、关屏保等） */
    val postBoot: List<String> = emptyList(),
)

@Serializable
enum class VmRootMode {
    /** `adb root` 把 adbd 切到 root（Android-x86 / userdebug 镜像） */
    @SerialName("adb_root")
    ADB_ROOT,

    /** 通过 `su -c` 提权 */
    @SerialName("su")
    SU,

    /** guest 本身就是 root 终端（如 Alpine/Ubuntu cloud image） */
    @SerialName("direct")
    DIRECT,
}

/** 安装进度，与 RootfsInstallProgress 同构，便于 UI 复用同一套进度组件 */
@Serializable
data class VmInstallProgress(
    val fileName: String = "",
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val extracting: Boolean = false,
)

enum class VmState {
    /** 未安装任何镜像 */
    NOT_INSTALLED,
    INSTALLING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

/** 一个已安装实例的启动计划：引擎只需要这个，不关心 UI/数据库 */
data class VmLaunchSpec(
    val instanceId: String,
    val instanceDir: java.io.File,
    /** 同一目录在 guest rootfs 内的绝对路径（proot 下的 /workspace/...） */
    val guestDir: String,
    val image: VmImage,
    /** role -> 已下载到本地的文件（DATA 由引擎自己生成） */
    val files: Map<VmFileRole, List<java.io.File>>,
    /** host 侧 adb/vnc 端口，默认与 guest 相同，可被实例覆盖 */
    val adbHostPort: Int = image.guest.adbPort,
    val vncHostPort: Int = image.guest.vncPort,
    /** 只绑定回环，VM 只对 app 自己可见 */
    val bindAddress: String = "127.0.0.1",
)
