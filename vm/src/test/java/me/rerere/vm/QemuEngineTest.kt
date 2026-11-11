package me.rerere.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QemuEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun image(
        boot: VmBootSpec = VmBootSpec(),
        guest: VmGuestSpec = VmGuestSpec(),
        files: List<VmImageFile> = emptyList(),
    ) = VmImage(id = "t", name = "t", files = files, boot = boot, guest = guest)

    private fun spec(
        img: VmImage,
        files: Map<VmFileRole, List<File>> = emptyMap(),
        adbPort: Int = 5555,
        vncPort: Int = 5900,
    ) = VmLaunchSpec(
        instanceId = "inst1",
        instanceDir = tmp.root,
        guestDir = "/workspace/.vm/inst1",
        image = img,
        files = files,
        adbHostPort = adbPort,
        vncHostPort = vncPort,
    )

    @Test
    fun `runs on tcg because app has no kvm`() {
        val cmd = QemuEngine("x86_64").command(spec(image()))
        val accel = cmd.indexOf("-accel")
        assertTrue(accel >= 0)
        assertEquals("tcg,thread=multi", cmd[accel + 1])
        assertTrue(cmd.none { it.contains("kvm") })
    }

    @Test
    fun `cdrom image boots from cdrom and exposes vnc`() {
        val iso = File(tmp.root, "android.iso").apply { writeBytes(ByteArray(1)) }
        val img = image(files = listOf(VmImageFile("u", "android.iso", role = VmFileRole.CDROM)))
        val cmd = QemuEngine("x86_64").command(spec(img, mapOf(VmFileRole.CDROM to listOf(iso))))

        val cdrom = cmd.indexOf("-cdrom")
        assertEquals("/workspace/.vm/inst1/android.iso", cmd[cdrom + 1])
        assertTrue(cmd.containsAll(listOf("-boot", "d")))
        val vnc = cmd.indexOf("-vnc")
        assertEquals("127.0.0.1:0", cmd[vnc + 1])
    }

    @Test
    fun `non default vnc port maps to display number`() {
        val cmd = QemuEngine("x86_64").command(spec(image(), vncPort = 5903))
        assertEquals("127.0.0.1:3", cmd[cmd.indexOf("-vnc") + 1])
    }

    @Test
    fun `forwards adb and vnc to loopback only`() {
        val cmd = QemuEngine("x86_64").command(spec(image(), adbPort = 5555, vncPort = 5900))
        val netdev = cmd[cmd.indexOf("-netdev") + 1]
        assertTrue(netdev.contains("hostfwd=tcp:127.0.0.1:5555-:5555"))
        assertTrue(netdev.contains("user,id=net0"))
        // VNC 由 QEMU 自己监听，不能再转发一次（会和 -vnc 抢同一个宿端口）
        assertTrue(!netdev.contains("5900"))
        assertEquals("127.0.0.1:0", cmd[cmd.indexOf("-vnc") + 1])
    }

    @Test
    fun `graphics none disables vnc and display`() {
        val img = image(boot = VmBootSpec(graphics = VmGraphics.NONE))
        val cmd = QemuEngine("x86_64").command(spec(img))
        assertTrue(cmd.containsAll(listOf("-display", "none")))
        assertTrue(cmd.none { it == "-vnc" })
        // 纯命令行时不需要转发 vnc 端口
        assertTrue(cmd[cmd.indexOf("-netdev") + 1].let { !it.contains("5900") })
    }

    @Test
    fun `direct kernel boot uses kernel initrd and append`() {
        val kernel = File(tmp.root, "vmlinuz").apply { writeBytes(ByteArray(1)) }
        val initrd = File(tmp.root, "initrd.img").apply { writeBytes(ByteArray(1)) }
        val img = image(
            boot = VmBootSpec(kernelArgs = listOf("console=ttyS0", "root=/dev/sr0")),
            files = listOf(
                VmImageFile("u", "vmlinuz", role = VmFileRole.KERNEL),
                VmImageFile("u", "initrd.img", role = VmFileRole.INITRD),
            ),
        )
        val cmd = QemuEngine("x86_64").command(
            spec(img, mapOf(VmFileRole.KERNEL to listOf(kernel), VmFileRole.INITRD to listOf(initrd)))
        )
        assertEquals("/workspace/.vm/inst1/vmlinuz", cmd[cmd.indexOf("-kernel") + 1])
        assertEquals("/workspace/.vm/inst1/initrd.img", cmd[cmd.indexOf("-initrd") + 1])
        assertEquals("console=ttyS0 root=/dev/sr0", cmd[cmd.indexOf("-append") + 1])
    }

    @Test
    fun `data disk is attached only when it exists`() {
        val img = image(boot = VmBootSpec(dataDiskMb = 4096))
        val without = QemuEngine("x86_64").command(spec(img))
        assertTrue(without.none { it.contains("data.img") })

        File(tmp.root, VmInstaller.DATA_DISK_NAME).writeBytes(ByteArray(1))
        val with = QemuEngine("x86_64").command(spec(img))
        assertTrue(with.any { it.contains("file=/workspace/.vm/inst1/data.img,format=raw") })
    }

    @Test
    fun `qcow2 disk keeps its format`() {
        val disk = File(tmp.root, "root.qcow2").apply { writeBytes(ByteArray(1)) }
        val img = image(files = listOf(VmImageFile("u", "root.qcow2", role = VmFileRole.DISK)))
        val cmd = QemuEngine("x86_64").command(spec(img, mapOf(VmFileRole.DISK to listOf(disk))))
        assertTrue(cmd.any { it.contains("format=qcow2") })
    }

    @Test
    fun `arch picks the matching qemu binary`() {
        assertEquals("/usr/bin/qemu-system-aarch64", QemuEngine.defaultBinary("arm64"))
        assertEquals("/usr/bin/qemu-system-x86_64", QemuEngine.defaultBinary("x86_64"))
        assertEquals("qemu-system-x86", QemuProvisioner.packageName("x86_64"))
        assertEquals("qemu-system-arm", QemuProvisioner.packageName("aarch64"))
    }

    @Test
    fun `catalog defaults parse and every image is launchable`() {
        val catalog = VmCatalogDefaults.catalog()
        assertTrue(catalog.images.isNotEmpty())
        catalog.images.forEach { img ->
            assertTrue("image ${img.id} has files", img.files.isNotEmpty())
            val spec = VmLaunchSpec(
                instanceId = img.id,
                instanceDir = tmp.root,
                guestDir = "/workspace/.vm/${img.id}",
                image = img,
                files = emptyMap(),
            )
            val cmd = QemuEngine(img.arch).command(spec)
            assertEquals(0, cmd.size % 1)
            assertTrue(cmd.first().startsWith("/usr/bin/qemu-system-"))
        }
    }
}
