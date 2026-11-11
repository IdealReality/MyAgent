package me.rerere.myagent.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.myagent.data.db.dao.VmInstanceDao
import me.rerere.myagent.data.db.dao.WorkspaceDAO
import me.rerere.myagent.data.db.entity.VmInstanceEntity
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.vm.QemuEngine
import me.rerere.vm.QemuProvisioner
import me.rerere.vm.VmCatalog
import me.rerere.vm.VmCatalogDefaults
import me.rerere.vm.VmCatalogLoader
import me.rerere.vm.VmFileRole
import me.rerere.vm.VmImage
import me.rerere.vm.VmInstallProgress
import me.rerere.vm.VmInstaller
import me.rerere.vm.VmKind
import me.rerere.vm.VmLaunchSpec
import me.rerere.vm.VmRootMode
import me.rerere.vm.VmState
import me.rerere.vm.shellQuote
import me.rerere.vm.toShellCommand
import me.rerere.vm.shellQuote
import java.io.File
import kotlin.uuid.Uuid

/**
 * 虚拟环境的生命周期：镜像下载、QEMU 进程起停、adb 通道。
 *
 * 所有 QEMU/adb 动作都通过 workspace 的 PRoot shell 执行——手机上没有 KVM，引擎只能是
 * rootfs 里的 qemu-system-*；这样做也复用了现成的后台进程管理与取消语义。
 */
class VmRepository(
    private val dao: VmInstanceDao,
    private val workspaceDao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val installer: VmInstaller = VmInstaller(),
) {

    fun listFlow(workspaceId: String): Flow<List<VmInstanceEntity>> = dao.listFlow(workspaceId)

    suspend fun instances(workspaceId: String): List<VmInstanceEntity> = withContext(Dispatchers.IO) {
        dao.listForWorkspace(workspaceId)
    }

    fun catalog(url: String? = null): VmCatalog =
        if (url.isNullOrBlank()) VmCatalogDefaults.catalog() else VmCatalogLoader.load(url)

    fun image(catalog: VmCatalog, imageId: String): VmImage? = catalog.images.firstOrNull { it.id == imageId }

    suspend fun getInstance(id: String): VmInstanceEntity? = dao.getById(id)

    /**
     * 安装镜像。已下载完整的文件会跳过，重复点只补缺失部分。
     * 用 runInterruptible 让 UI 取消真正打断阻塞下载（同 RootfsInstaller 的约定）。
     */
    suspend fun install(
        workspaceId: String,
        catalog: VmCatalog,
        imageId: String,
        onProgress: (VmInstallProgress) -> Unit = {},
    ): VmInstanceEntity = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val image = image(catalog, imageId) ?: error("Unknown image: $imageId")
        val existing = dao.findByImage(workspaceId, imageId)
        val now = System.currentTimeMillis()
        val instance = existing ?: VmInstanceEntity(
            id = Uuid.random().toString(),
            workspaceId = workspaceId,
            imageId = imageId,
            name = image.name,
            imageName = image.name,
            rootMode = image.guest.rootMode.name,
            createdAt = now,
            updatedAt = now,
        )
        // 端口在安装时就定死并落库：启动、adb 转发、VNC 连接三处必须用同一组
        val withPorts = instance.copy(
            state = VmState.INSTALLING.name,
            imageName = image.name,
            rootMode = image.guest.rootMode.name,
            adbPort = instance.adbPort.takeIf { isPortFree(it) } ?: pickPort(image.guest.adbPort),
            vncPort = instance.vncPort.takeIf { isPortFree(it) } ?: pickPort(image.guest.vncPort),
            updatedAt = now,
        )
        dao.upsert(withPorts)

        try {
            val dir = instanceDir(workspace.root, instance.id)
            runInterruptible(Dispatchers.IO) {
                installer.install(image, dir, onProgress)
            }
            extractFromArchives(workspace.root, instance.id, image)
            val ready = withPorts.copy(state = VmState.STOPPED.name, lastError = null, updatedAt = System.currentTimeMillis())
            dao.upsert(ready)
            ready
        } catch (e: Throwable) {
            Log.e(TAG, "vm install failed: $imageId", e)
            dao.upsert(
                withPorts.copy(
                    state = VmState.ERROR.name,
                    lastError = e.message ?: e::class.java.simpleName,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw e
        }
    }

    /** 启动实例。首次启动会自动补装 QEMU / adb 这两个引擎。 */
    suspend fun start(
        workspaceId: String,
        instanceId: String,
        catalog: VmCatalog,
        onLog: (String) -> Unit = {},
    ): VmInstanceEntity = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val instance = dao.getById(instanceId) ?: error("VM instance not found: $instanceId")
        require(workspace.shellStatus == WorkspaceShellStatus.READY.name) {
            "Workdir rootfs is not ready; install the Linux rootfs first"
        }
        val image = image(catalog, instance.imageId) ?: error("Image no longer in catalog: ${instance.imageId}")

        dao.updateState(instanceId, VmState.STARTING.name, null, System.currentTimeMillis())
        try {
            val spec = launchSpec(workspace.root, instance, image)

            // 1) 引擎：qemu。缺失时 apt 装，输出回传给调用方（第一启动会等几分钟）
            ensureTool(workspace.root, "qemu", QemuProvisioner.installCommand(image.arch), onLog)
            // 2) adb：只有需要 adb 通道的镜像才装
            if (image.kind == VmKind.ANDROID || image.guest.rootMode == VmRootMode.ADB_ROOT) {
                ensureTool(workspace.root, "adb", ADB_INSTALL_COMMAND, onLog)
            }
            if (image.guest.bootstrap.isNotEmpty()) {
                ensureTool(workspace.root, "socat", SOCAT_INSTALL_COMMAND, onLog)
            }

            // 3) 进程：同一个 root 下复用后台进程管理器（删除工作区会一起杀掉）
            val engine = QemuEngine(image.arch)
            val command = engine.command(spec).toShellCommand()
            onLog("launch: $command")
            val bg = manager.startBackground(workspace.root, command)
            val running = instance.copy(
                state = VmState.STARTING.name,
                backgroundId = bg.id,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsert(running)

            // 4) guest 引导：串口配网 + 打开 adb，然后等 adb 就绪、切 root、唤醒屏幕。
            //    顺序不能换：adb 通之前只能靠串口，而串口 shell 要等 Android 起到 console。
            if (image.guest.bootstrap.isNotEmpty()) {
                bootstrapGuest(workspace.root, spec, image, onLog)
            }
            if (image.guest.rootMode == VmRootMode.ADB_ROOT) {
                waitForAdb(workspace.root, instance, onLog)
                manager.executeCommand(
                    workspace.root,
                    "${adbPrefix(instance)} root >/dev/null 2>&1; sleep 1; " +
                        "${adbPrefix(instance)} wait-for-device",
                    timeoutMillis = 180_000,
                )
            }
            if (image.guest.postBoot.isNotEmpty()) {
                val commands = image.guest.postBoot.joinToString("; ")
                manager.executeCommand(
                    workspace.root,
                    "${adbPrefix(instance)} wait-for-device && " +
                        "${adbPrefix(instance)} shell ${commands.shellQuote()}",
                    timeoutMillis = 120_000,
                )
            }

            val ready = running.copy(state = VmState.RUNNING.name, updatedAt = System.currentTimeMillis())
            dao.upsert(ready)
            ready
        } catch (e: Throwable) {
            Log.e(TAG, "vm start failed: $instanceId", e)
            dao.upsert(
                instance.copy(
                    state = VmState.ERROR.name,
                    lastError = e.message ?: e::class.java.simpleName,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw e
        }
    }

    suspend fun stop(workspaceId: String, instanceId: String): VmInstanceEntity = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val instance = dao.getById(instanceId) ?: error("VM instance not found: $instanceId")
        instance.backgroundId?.let { manager.killBackground(workspace.root, it) }
        val stopped = instance.copy(
            state = VmState.STOPPED.name,
            backgroundId = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(stopped)
        stopped
    }

    /** 以窗口内进程状态为准刷新实体状态（app 重启后 QEMU 已不在，不应显示 RUNNING）。 */
    suspend fun refreshState(workspaceId: String, instanceId: String): VmState = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId) ?: return@withContext VmState.ERROR
        val instance = dao.getById(instanceId) ?: return@withContext VmState.ERROR
        val running = instance.backgroundId?.let { manager.backgroundStatus(workspace.root, it) }?.running == true
        val state = when {
            running -> VmState.RUNNING
            instance.vmState() == VmState.RUNNING -> VmState.STOPPED
            else -> instance.vmState()
        }
        if (state.name != instance.state) {
            dao.updateState(instanceId, state.name, instance.lastError, System.currentTimeMillis())
        }
        state
    }

    suspend fun delete(workspaceId: String, instanceId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId) ?: return@withContext false
        val instance = dao.getById(instanceId) ?: return@withContext false
        instance.backgroundId?.let { manager.killBackground(workspace.root, it) }
        instanceDir(workspace.root, instanceId).deleteRecursively()
        dao.deleteById(instanceId) > 0
    }

    suspend fun rename(instanceId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = name.trim().ifBlank { return@withContext false }
        dao.rename(instanceId, trimmed, System.currentTimeMillis()) > 0
    }

    // ---------------------------------------------------------------- adb 通道

    /** 在 guest 里执行命令。Android 镜像走 adb（root 优先），其它镜像走串口/直接 shell。 */
    suspend fun shell(
        workspaceId: String,
        instanceId: String,
        command: String,
        timeoutMillis: Long = 120_000,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val (workspace, instance) = contextOf(workspaceId, instanceId)
        val root = workspace.root
        val adb = adbPrefix(instance)
        val script = when (rootModeOf(instance)) {
            VmRootMode.ADB_ROOT -> buildString {
                append("adb start-server >/dev/null 2>&1; ")
                append("$adb wait-for-device && ")
                append("($adb root >/dev/null 2>&1 || true); ")
                append("sleep 1; $adb wait-for-device; ")
                append("$adb shell ${command.shellQuote()}")
            }

            VmRootMode.SU -> "$adb wait-for-device && $adb shell ${"su -c ${command.shellQuote()}".shellQuote()}"

            VmRootMode.DIRECT -> "$adb wait-for-device && $adb shell ${command.shellQuote()}"
        }
        manager.executeCommand(root, script, timeoutMillis = timeoutMillis)
    }

    /** 把 host 侧（工作区 files）里的文件推进 guest。 */
    suspend fun push(
        workspaceId: String,
        instanceId: String,
        hostPath: String,
        guestPath: String,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val (workspace, instance) = contextOf(workspaceId, instanceId)
        val src = manager.resolveFile(workspace.root, hostPath).absolutePath
        manager.executeCommand(
            workspace.root,
            "${adbPrefix(instance)} wait-for-device && ${adbPrefix(instance)} push ${src.shellQuote()} ${guestPath.shellQuote()}",
            timeoutMillis = 300_000,
        )
    }

    /** 从 guest 拉文件到工作区 files 区，返回相对路径。 */
    suspend fun pull(
        workspaceId: String,
        instanceId: String,
        guestPath: String,
        hostPath: String,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val (workspace, instance) = contextOf(workspaceId, instanceId)
        val destination = File(manager.filesDir(workspace.root), hostPath)
        destination.parentFile?.mkdirs()
        manager.executeCommand(
            workspace.root,
            "${adbPrefix(instance)} wait-for-device && ${adbPrefix(instance)} pull ${guestPath.shellQuote()} ${destination.absolutePath.shellQuote()}",
            timeoutMillis = 300_000,
        )
    }

    /** 抓一张 PNG 到工作区，返回 host 侧 File（工具层转成图片给模型）。 */
    suspend fun screencap(workspaceId: String, instanceId: String): File = withContext(Dispatchers.IO) {
        val (workspace, instance) = contextOf(workspaceId, instanceId)
        val target = File(instanceDir(workspace.root, instanceId), "screencap.png")
        target.delete()
        val adb = adbPrefix(instance)
        val result = manager.executeCommand(
            workspace.root,
            "$adb wait-for-device && $adb exec-out screencap -p > ${target.absolutePath.shellQuote()}",
            timeoutMillis = 120_000,
        )
        check(result.exitCode == 0 && target.isFile && target.length() > 0) {
            "screencap failed: ${result.stderr.take(500)}"
        }
        target
    }

    suspend fun installApk(
        workspaceId: String,
        instanceId: String,
        hostPath: String,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val (workspace, instance) = contextOf(workspaceId, instanceId)
        val apk = manager.resolveFile(workspace.root, hostPath).absolutePath
        val adb = adbPrefix(instance)
        manager.executeCommand(
            workspace.root,
            "$adb wait-for-device && $adb install -r -g ${apk.shellQuote()}",
            timeoutMillis = 600_000,
        )
    }

    /** tail 一段 logcat；dumpsys/uiautomator 这类都走 [shell]。 */
    suspend fun logcat(
        workspaceId: String,
        instanceId: String,
        lines: Int = 200,
    ): WorkspaceCommandResult = shell(
        workspaceId,
        instanceId,
        "logcat -d -t ${lines.coerceIn(10, 5000)}",
        timeoutMillis = 120_000,
    )

    // ---------------------------------------------------------------- 内部

    fun instanceDir(root: String, instanceId: String): File =
        File(manager.filesDir(root), "vm/$instanceId")

    private fun adbPrefix(instance: VmInstanceEntity): String =
        "adb -s 127.0.0.1:${instance.adbPort}"

    private suspend fun contextOf(
        workspaceId: String,
        instanceId: String,
    ): Pair<me.rerere.myagent.data.db.entity.WorkspaceEntity, VmInstanceEntity> {
        val workspace = workspaceDao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val instance = dao.getById(instanceId) ?: error("VM instance not found: $instanceId")
        return workspace to instance
    }

    private fun rootModeOf(instance: VmInstanceEntity): VmRootMode =
        runCatching { VmRootMode.valueOf(instance.rootMode) }.getOrDefault(VmRootMode.ADB_ROOT)

    /** host 侧转发端口必须是空闲的：真机上 5555 往往已经被无线调试的 adbd 占着。 */
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: java.io.IOException) {
        false
    }

    private fun pickPort(preferred: Int): Int {
        for (offset in 0..20) {
            val candidate = preferred + offset
            if (isPortFree(candidate)) return candidate
        }
        return preferred
    }

    /**
     * 从已下载的归档里取文件（Android-x86 的 kernel/initrd 就在 ISO 里）。
     *
     * 需要直接 `-kernel` 引导才有串口 console，而串口是 adb 通之前唯一的通道，
     * 所以这一步是「能自动化」的前提，不是可选优化。
     */
    private suspend fun extractFromArchives(root: String, instanceId: String, image: VmImage) {
        val pending = image.files.filter { !it.fromArchive.isNullOrBlank() && it.archivePath != null }
        if (pending.isEmpty()) return
        val dir = instanceDir(root, instanceId)
        if (pending.all { File(dir, it.fileName).isFile }) return
        if (manager.executeCommand(root, "command -v bsdtar", timeoutMillis = 30_000).exitCode != 0) {
            manager.executeCommand(root, LIBARCHIVE_INSTALL_COMMAND, timeoutMillis = 10 * 60_000)
        }
        val script = buildString {
            appendLine("set -e")
            appendLine("cd ${GUEST_VM_DIR}/$instanceId")
            pending.forEach { file ->
                appendLine(
                    "bsdtar -xOf ${file.fromArchive!!.shellQuote()} ${file.archivePath!!.shellQuote()} > ${file.fileName.shellQuote()}"
                )
            }
        }
        val result = manager.executeCommand(root, script, timeoutMillis = 10 * 60_000)
        check(result.exitCode == 0) { "Extracting kernel/initrd failed: ${result.stderr.take(500)}" }
    }

    private suspend fun launchSpec(
        root: String,
        instance: VmInstanceEntity,
        image: VmImage,
    ): VmLaunchSpec {
        val dir = instanceDir(root, instance.id)
        dir.mkdirs()
        val files = image.files
            .groupBy { it.role }
            .mapValues { (_, specs) -> specs.map { File(dir, it.fileName) } }
            .filterValues { list -> list.all { it.isFile } }
        return VmLaunchSpec(
            instanceId = instance.id,
            instanceDir = dir,
            guestDir = "$GUEST_VM_DIR/${instance.id}",
            image = image,
            files = files,
            adbHostPort = instance.adbPort,
            vncHostPort = instance.vncPort,
        )
    }

    /** 引擎缺失就装；安装输出原样回传，避免「点了启动什么也没发生」。 */
    private suspend fun ensureTool(
        root: String,
        tool: String,
        installCommand: String,
        onLog: (String) -> Unit,
    ) {
        val probe = when (tool) {
            "adb" -> "command -v adb >/dev/null 2>&1 && adb version | head -1"
            "qemu" -> QemuProvisioner.checkCommand(QemuEngine.defaultBinary("x86_64"))
            else -> "command -v '$tool'"
        }
        val check = manager.executeCommand(root, probe, timeoutMillis = 30_000)
        if (check.exitCode == 0 && !check.stdout.contains("MISSING")) {
            onLog("$tool: already installed")
            return
        }
        onLog("$tool: installing")
        val install = manager.executeCommand(root, installCommand, timeoutMillis = 20 * 60_000)
        onLog(install.stdout.takeLast(2000))
        check(install.exitCode == 0) {
            "$tool install failed (${install.exitCode}): ${install.stderr.take(1000)}"
        }
    }

    /**
     * adb 还不通的时候，唯一能驱动 guest 的是串口 console。
     *
     * 流程：等 QEMU 建出 socket -> 反复连上发换行、直到读到 console 提示符 -> 一次性发送
     * 镜像声明的引导命令。命令本身属于镜像（不同发行版网络栈完全不同），框架只负责时序。
     */
    private suspend fun bootstrapGuest(
        root: String,
        spec: VmLaunchSpec,
        image: VmImage,
        onLog: (String) -> Unit,
    ) {
        val dir = spec.guestDir.trimEnd('/')
        val sock = "$dir/serial.sock"
        val prompt = image.guest.consolePrompt
        val script = buildString {
            appendLine("SOCK='$sock'")
            appendLine("for i in \$(seq 1 900); do [ -S \"\$SOCK\" ] && break; sleep 1; done")
            appendLine("[ -S \"\$SOCK\" ] || { echo 'serial socket not created' >&2; exit 1; }")
            if (prompt.isNotBlank()) {
                appendLine("for i in \$(seq 1 450); do")
                appendLine("  out=\$(printf '\\n' | timeout 2 socat - \"UNIX-CONNECT:\$SOCK\" 2>/dev/null || true)")
                appendLine("  case \"\$out\" in *'$prompt'*) echo 'console ready'; break;; esac")
                appendLine("  sleep 2")
                appendLine("done")
            }
            appendLine("{")
            image.guest.bootstrap.forEach { appendLine("  echo ${it.shellQuote()}") }
            appendLine("  sleep 3")
            appendLine("} | timeout 120 socat - \"UNIX-CONNECT:\$SOCK\" > '$dir/bootstrap.log' 2>&1")
        }
        val result = manager.executeCommand(root, script, timeoutMillis = 30 * 60_000)
        onLog("bootstrap: exit=${result.exitCode} ${result.stdout.takeLast(800)}")
        if (result.exitCode != 0) {
            throw IllegalStateException("Guest bootstrap failed: ${result.stderr.take(500)}")
        }
    }

    /** 等 adb 把 guest 认成 device（offline 不算）；TCG 下首启可能几分钟。 */
    private suspend fun waitForAdb(
        root: String,
        instance: VmInstanceEntity,
        onLog: (String) -> Unit,
    ) {
        val adb = adbPrefix(instance)
        val script = "adb start-server >/dev/null 2>&1; " +
            "for i in \$(seq 1 300); do " +
            "if $adb get-state 2>/dev/null | grep -q '^device$'; then echo ok; exit 0; fi; " +
            "sleep 2; done; exit 1"
        val result = manager.executeCommand(root, script, timeoutMillis = 12 * 60_000)
        onLog("adb wait: exit=${result.exitCode}")
        check(result.exitCode == 0) { "adb did not become ready: ${result.stderr.take(500)}" }
    }

    private companion object {
        const val TAG = "VmRepository"
        const val GUEST_VM_DIR = "/workspace/vm"
        const val ADB_INSTALL_COMMAND =
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -o Acquire::Retries=3 && " +
                "apt-get install -y --no-install-recommends adb -o APT::Sandbox::User=root"
        const val LIBARCHIVE_INSTALL_COMMAND =
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -o Acquire::Retries=3 && " +
                "apt-get install -y --no-install-recommends libarchive-tools -o APT::Sandbox::User=root"
        const val SOCAT_INSTALL_COMMAND =
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -o Acquire::Retries=3 && " +
                "apt-get install -y --no-install-recommends socat -o APT::Sandbox::User=root"
    }
}
