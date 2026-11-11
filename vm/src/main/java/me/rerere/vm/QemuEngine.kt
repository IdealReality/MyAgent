package me.rerere.vm

import java.io.File

/**
 * 把一份 [VmLaunchSpec] 翻译成一条 QEMU 命令行。
 *
 * 这里刻意不做任何 IO，也不启动进程：命令构造是纯函数，才能在 JVM 单测里断言。
 * 真正的执行（proot 内后台进程）由 app 侧用 `WorkspaceShellRunner.start` 负责。
 */
interface VmEngine {
    /** 引擎可执行文件在 rootfs 内的绝对路径 */
    val binary: String

    fun command(spec: VmLaunchSpec): List<String>

    /** 判断实例看起来是否还在跑（供 shell 侧探活，不依赖 QEMU 的 pidfile 语义） */
    fun probeCommand(spec: VmLaunchSpec): String
}

/**
 * QEMU 引擎。默认走 TCG 软件模拟：Android 应用进程没有 /dev/kvm，这是唯一可用路径。
 * arch 是 guest 架构（决定用哪个 qemu-system-* 二进制）。
 */
class QemuEngine(
    guestArch: String = "x86_64",
    override val binary: String = defaultBinary(guestArch),
) : VmEngine {

    override fun command(spec: VmLaunchSpec): List<String> {
        val image = spec.image
        val boot = image.boot
        val guest = image.guest
        val dir = spec.guestDir.trimEnd('/')
        val cmd = mutableListOf(binary)

        if (boot.machine.isNotBlank()) {
            cmd += listOf("-machine", boot.machine)
        }
        // 无 KVM：TCG 多线程翻译，尽量榨出多核
        cmd += listOf("-accel", "tcg,thread=multi")
        cmd += listOf("-m", boot.memoryMb.coerceAtLeast(512).toString())
        cmd += listOf("-smp", boot.cpus.coerceIn(1, 16).toString())
        if (boot.cpu.isNotBlank()) {
            cmd += listOf("-cpu", boot.cpu)
        }
        if (boot.vga.isNotBlank()) {
            cmd += listOf("-vga", boot.vga)
        }

        // 直接引导：内核 + initrd + 命令行
        val kernel = spec.files[VmFileRole.KERNEL].orEmpty().firstOrNull()
        val initrd = spec.files[VmFileRole.INITRD].orEmpty().firstOrNull()
        if (kernel != null) {
            cmd += listOf("-kernel", "${dir}/${kernel.name}")
            initrd?.let { cmd += listOf("-initrd", "${dir}/${it.name}") }
            val args = boot.kernelArgs.joinToString(" ")
            if (args.isNotBlank()) {
                cmd += listOf("-append", args)
            }
        }

        // 启动介质
        spec.files[VmFileRole.CDROM].orEmpty().forEach { iso ->
            cmd += listOf("-cdrom", "${dir}/${iso.name}")
            cmd += listOf("-boot", "d")
        }
        spec.files[VmFileRole.DISK].orEmpty().forEachIndexed { index, disk ->
            cmd += listOf(
                "-drive",
                "file=${dir}/${disk.name},format=${diskFormat(disk.name)},if=virtio,index=$index",
            )
        }

        // 持久数据盘：安装时创建，存在才挂
        if (boot.dataDiskMb > 0) {
            val dataName = "data.img"
            if (File(spec.instanceDir, dataName).isFile) {
                cmd += listOf("-drive", "file=$dir/$dataName,format=raw,if=virtio")
            }
        }

        // 网络：user 模式 + adb 端口转发。VNC 不走 hostfwd——QEMU 自己监听 5900，
        // 再转发一次会撞同一个宿端口。
        val forwards = "hostfwd=tcp:${spec.bindAddress}:${spec.adbHostPort}-:${guest.adbPort}"
        cmd += listOf("-netdev", "user,id=net0,$forwards")
        cmd += listOf("-device", "virtio-net-pci,netdev=net0")

        when (boot.graphics) {
            VmGraphics.VNC -> {
                // -vnc 的 display 号 = 端口 - 5900，绑定回环避免局域网可见
                val display = (spec.vncHostPort - VNC_PORT_BASE).coerceAtLeast(0)
                cmd += listOf("-vnc", "${spec.bindAddress}:$display")
            }

            VmGraphics.NONE -> cmd += listOf("-display", "none")
        }

        // 串口是 adb 通之前唯一的交互通道：guest 内的 console shell 从这里驱动
        // （配网、开 adb）。unix socket 让 proot 里的 nc/socat 能直接连。
        cmd += listOf("-serial", "unix:${dir}/serial.sock,server,nowait")
        cmd += listOf("-pidfile", "${dir}/qemu.pid")
        cmd += listOf("-name", "myagent-${spec.instanceId}")
        cmd += listOf("-rtc", "base=localtime")

        cmd += boot.extraArgs
        return cmd
    }

    override fun probeCommand(spec: VmLaunchSpec): String {
        val dir = spec.guestDir.trimEnd('/')
        // pidfile 里的 pid 在 proot 内是宿主机 pid；kill -0 探活最省事
        return "pid=\$(cat '$dir/qemu.pid' 2>/dev/null); " +
            "if [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null; then echo running; else echo stopped; fi"
    }

    companion object {
        const val VNC_PORT_BASE = 5900

        fun defaultBinary(guestArch: String): String = when (guestArch.lowercase()) {
            "aarch64", "arm64" -> "/usr/bin/qemu-system-aarch64"
            else -> "/usr/bin/qemu-system-x86_64"
        }

        /** ISO 必须按 raw 挂载，其余按扩展名猜 */
        fun diskFormat(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "qcow2" -> "qcow2"
            "vmdk" -> "vmdk"
            "vdi" -> "vdi"
            else -> "raw"
        }
    }
}
