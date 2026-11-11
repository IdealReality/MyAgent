package me.rerere.myagent.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.files.FilesManager
import me.rerere.myagent.data.repository.VmRepository
import me.rerere.vm.VmState
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 虚拟环境工具面。
 *
 * 只有工作区里已经装过（或正在装）至少一个 VM 时才注册——没有任何镜像时这些工具只会
 * 让模型误以为有机器可操作。
 */
val VmToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "vm_list" to false,
    "vm_status" to false,
    "vm_start" to true,
    "vm_stop" to true,
    "vm_shell" to true,
    "vm_install_image" to true,
    "vm_push" to false,
    "vm_pull" to false,
    "vm_screencap" to false,
    "vm_install_apk" to true,
    "vm_input" to false,
    "vm_ui_dump" to false,
    "vm_logcat" to false,
)

suspend fun createVmTools(
    workspaceId: String,
    vmRepository: VmRepository,
    needsApproval: (String) -> Boolean = { VmToolDefaultApprovals[it] ?: false },
): List<Tool> {
    val instances = vmRepository.listFlow(workspaceId)
    // 只要存在实例（哪怕正在安装）就暴露工具面
    val hasAny = instances.first()
    if (hasAny.isNullOrEmpty()) return emptyList()

    return listOf(
        createVmListTool(workspaceId, vmRepository, needsApproval),
        createVmStatusTool(workspaceId, vmRepository, needsApproval),
        createVmStartTool(workspaceId, vmRepository, needsApproval),
        createVmStopTool(workspaceId, vmRepository, needsApproval),
        createVmShellTool(workspaceId, vmRepository, needsApproval),
        createVmInstallImageTool(workspaceId, vmRepository, needsApproval),
        createVmPushTool(workspaceId, vmRepository, needsApproval),
        createVmPullTool(workspaceId, vmRepository, needsApproval),
        createVmScreencapTool(workspaceId, vmRepository, needsApproval),
        createVmInstallApkTool(workspaceId, vmRepository, needsApproval),
        createVmInputTool(workspaceId, vmRepository, needsApproval),
        createVmUiDumpTool(workspaceId, vmRepository, needsApproval),
        createVmLogcatTool(workspaceId, vmRepository, needsApproval),
    )
}

private fun instanceParam(required: Boolean = true) = buildJsonObject {
    put("type", "string")
    put("description", "VM instance id (from vm_list).")
    if (!required) put("description", "VM instance id (from vm_list). Optional when the workspace has exactly one VM.")
}

private fun commandResult(result: me.rerere.workspace.WorkspaceCommandResult) =
    UIMessagePart.Text(
        buildJsonObject {
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            if (result.timedOut) put("timedOut", true)
            if (result.truncated) put("truncated", true)
        }.toString()
    )

private suspend fun resolveInstance(
    repository: VmRepository,
    workspaceId: String,
    explicit: String?,
): String {
    if (!explicit.isNullOrBlank()) return explicit
    val all = repository.listFlow(workspaceId).first()
    return when (all.size) {
        1 -> all.first().id
        0 -> error("No VM in this workspace")
        else -> error("Multiple VMs; pass instance explicitly: ${all.joinToString { it.name }}")
    }
}

private fun createVmListTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_list",
    description = "List virtual machines installed in this workspace (Android VM / Linux VM) and the images available in the catalog.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = { needsApproval("vm_list") },
    execute = {
        val installed = repository.listFlow(workspaceId).first()
        val catalog = repository.catalog()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("instances", kotlinx.serialization.json.buildJsonArray {
                        installed.forEach { inst ->
                            add(buildJsonObject {
                                put("id", inst.id)
                                put("name", inst.name)
                                put("imageId", inst.imageId)
                                put("state", inst.vmState().name)
                                put("adbPort", inst.adbPort)
                                put("vncPort", inst.vncPort)
                                inst.lastError?.let { put("lastError", it) }
                            })
                        }
                    })
                    put("catalog", kotlinx.serialization.json.buildJsonArray {
                        catalog.images.forEach { img ->
                            add(buildJsonObject {
                                put("id", img.id)
                                put("name", img.name)
                                put("kind", img.kind.name)
                                put("arch", img.arch)
                                img.sizeBytes?.let { put("sizeBytes", it) }
                            })
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createVmStatusTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_status",
    description = "Refresh and report the state of one VM (RUNNING/STOPPED/ERROR).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("instance", instanceParam()) }, required = listOf("instance"))
    },
    needsApproval = { needsApproval("vm_status") },
    execute = {
        val id = it.jsonObject["instance"]?.jsonPrimitive?.contentOrNull
            ?: error("instance is required")
        val state = repository.refreshState(workspaceId, id)
        val inst = repository.getInstance(id)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("instance", id)
                    put("state", state.name)
                    inst?.lastError?.let { put("lastError", it) }
                }.toString()
            )
        )
    },
)

private fun createVmStartTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_start",
    description = "Boot one VM. The first start may install QEMU/adb inside the workspace rootfs (minutes). Returns when QEMU has been launched, not when Android finished booting; poll vm_status or retry vm_shell.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("instance", instanceParam()) }, required = listOf("instance"))
    },
    needsApproval = { needsApproval("vm_start") },
    execute = {
        val id = resolveInstance(repository, workspaceId, it.jsonObject["instance"]?.jsonPrimitive?.contentOrNull)
        val log = StringBuilder()
        val entity = repository.start(workspaceId, id, repository.catalog()) { line -> log.appendLine(line) }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("instance", entity.id)
                    put("state", entity.vmState().name)
                    put("log", log.toString().takeLast(4000))
                }.toString()
            )
        )
    },
)

private fun createVmStopTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_stop",
    description = "Power off one VM (kills the QEMU process).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("instance", instanceParam()) }, required = listOf("instance"))
    },
    needsApproval = { needsApproval("vm_stop") },
    execute = {
        val id = resolveInstance(repository, workspaceId, it.jsonObject["instance"]?.jsonPrimitive?.contentOrNull)
        val entity = repository.stop(workspaceId, id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("instance", entity.id)
            put("state", entity.vmState().name)
        }.toString()))
    },
)

private fun createVmShellTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_shell",
    description = "Run a shell command inside the VM. Android images run as root via adb (adb root). Use it for anything inside the guest: files, packages, android commands, /system inspection.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance", instanceParam(false))
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run inside the guest.")
                })
                put("timeout", buildJsonObject {
                    put("type", "number")
                    put("description", "Timeout in seconds (default 120, max 900).")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("vm_shell") },
    execute = {
        val params = it.jsonObject
        val id = resolveInstance(repository, workspaceId, params["instance"]?.jsonPrimitive?.contentOrNull)
        val command = params["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val timeout = (params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 120L)
            .coerceIn(1L, 900L) * 1000
        val result = repository.shell(workspaceId, id, command, timeout)
        listOf(commandResult(result))
    },
)

private fun createVmInstallImageTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_install_image",
    description = "Download an image from the VM catalog and create a VM instance for it. Large (Android ISOs are ~1 GB); the call blocks until the download finishes.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("image", buildJsonObject {
                    put("type", "string")
                    put("description", "Catalog image id (see vm_list).")
                })
            },
            required = listOf("image"),
        )
    },
    needsApproval = { needsApproval("vm_install_image") },
    execute = {
        val imageId = it.jsonObject["image"]?.jsonPrimitive?.contentOrNull ?: error("image is required")
        val catalog = repository.catalog()
        val entity = repository.install(workspaceId, catalog, imageId)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("instance", entity.id)
            put("image", imageId)
            put("state", entity.vmState().name)
        }.toString()))
    },
)

private fun createVmPushTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_push",
    description = "Copy a file from the workspace files area (/workspace/...) into the running VM.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance", instanceParam(false))
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace-relative source path, e.g. build/app.apk")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path inside the guest, e.g. /data/local/tmp/app.apk")
                })
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { needsApproval("vm_push") },
    execute = {
        val p = it.jsonObject
        val id = resolveInstance(repository, workspaceId, p["instance"]?.jsonPrimitive?.contentOrNull)
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: error("source is required")
        val target = p["target"]?.jsonPrimitive?.contentOrNull ?: error("target is required")
        listOf(commandResult(repository.push(workspaceId, id, source, target)))
    },
)

private fun createVmPullTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_pull",
    description = "Copy a file out of the VM into the workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance", instanceParam(false))
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path inside the guest.")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace-relative destination, e.g. dump/log.txt")
                })
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { needsApproval("vm_pull") },
    execute = {
        val p = it.jsonObject
        val id = resolveInstance(repository, workspaceId, p["instance"]?.jsonPrimitive?.contentOrNull)
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: error("source is required")
        val target = p["target"]?.jsonPrimitive?.contentOrNull ?: error("target is required")
        listOf(commandResult(repository.pull(workspaceId, id, source, target)))
    },
)

private fun createVmScreencapTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_screencap",
    description = "Take a screenshot of the running VM screen and return it as an image.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("instance", instanceParam(false)) })
    },
    needsApproval = { needsApproval("vm_screencap") },
    execute = {
        val id = resolveInstance(repository, workspaceId, it.jsonObject["instance"]?.jsonPrimitive?.contentOrNull)
        val file = repository.screencap(workspaceId, id)
        val bytes = file.readBytes()
        val filesManager = getKoin().get<FilesManager>()
        val uri = filesManager.createChatFilesByByteArrays(listOf(bytes)).first()
        listOf(
            UIMessagePart.Image(url = uri.toString()),
            UIMessagePart.Text(buildJsonObject {
                put("instance", id)
                put("bytes", bytes.size)
            }.toString()),
        )
    },
)

private fun createVmInstallApkTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_install_apk",
    description = "Install an APK from the workspace files area into the running Android VM (adb install -r -g).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance", instanceParam(false))
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace-relative APK path, e.g. build/app-debug.apk")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("vm_install_apk") },
    execute = {
        val p = it.jsonObject
        val id = resolveInstance(repository, workspaceId, p["instance"]?.jsonPrimitive?.contentOrNull)
        val path = p["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        listOf(commandResult(repository.installApk(workspaceId, id, path)))
    },
)

private fun createVmInputTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_input",
    description = "Inject input into the Android VM: tap x y | swipe x1 y1 x2 y2 [ms] | text <string> | key <KEYCODE>. Coordinates are guest screen pixels.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance", instanceParam(false))
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "tap | swipe | text | key")
                })
                put("x", buildJsonObject { put("type", "number") })
                put("y", buildJsonObject { put("type", "number") })
                put("x2", buildJsonObject { put("type", "number") })
                put("y2", buildJsonObject { put("type", "number") })
                put("text", buildJsonObject { put("type", "string") })
                put("keycode", buildJsonObject { put("type", "string") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { needsApproval("vm_input") },
    execute = {
        val p = it.jsonObject
        val id = resolveInstance(repository, workspaceId, p["instance"]?.jsonPrimitive?.contentOrNull)
        fun int(key: String): Int? = p[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val command = when (val action = p["action"]?.jsonPrimitive?.contentOrNull) {
            "tap" -> "input tap ${int("x") ?: error("x required")} ${int("y") ?: error("y required")}"
            "swipe" -> "input swipe ${int("x") ?: error("x required")} ${int("y") ?: error("y required")} " +
                "${int("x2") ?: error("x2 required")} ${int("y2") ?: error("y2 required")}"
            "text" -> "input text ${(p["text"]?.jsonPrimitive?.contentOrNull ?: error("text required")).replace(" ", "%s")}"
            "key" -> "input keyevent ${p["keycode"]?.jsonPrimitive?.contentOrNull ?: error("keycode required")}"
            else -> error("unknown action: $action")
        }
        listOf(commandResult(repository.shell(workspaceId, id, command, 60_000)))
    },
)

private fun createVmUiDumpTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_ui_dump",
    description = "Dump the Android view hierarchy (uiautomator) of the running VM for UI automation.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("instance", instanceParam(false)) })
    },
    needsApproval = { needsApproval("vm_ui_dump") },
    execute = {
        val id = resolveInstance(repository, workspaceId, it.jsonObject["instance"]?.jsonPrimitive?.contentOrNull)
        val result = repository.shell(
            workspaceId, id,
            "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml",
            120_000,
        )
        listOf(commandResult(result))
    },
)

private fun createVmLogcatTool(
    workspaceId: String,
    repository: VmRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "vm_logcat",
    description = "Read the last N lines of the Android VM logcat.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("instance", instanceParam(false))
            put("lines", buildJsonObject {
                put("type", "number")
                put("description", "Line count, default 200.")
            })
        })
    },
    needsApproval = { needsApproval("vm_logcat") },
    execute = {
        val p = it.jsonObject
        val id = resolveInstance(repository, workspaceId, p["instance"]?.jsonPrimitive?.contentOrNull)
        val lines = p["lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200
        listOf(commandResult(repository.logcat(workspaceId, id, lines)))
    },
)
