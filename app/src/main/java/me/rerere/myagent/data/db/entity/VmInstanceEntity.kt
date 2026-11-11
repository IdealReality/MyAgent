package me.rerere.myagent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.vm.VmState

/**
 * 一个工作区里安装的虚拟环境。
 *
 * 与 workspace 是「一个工作区可以有多个 VM」的关系：Ubuntu rootfs 是工作区自带的执行
 * 环境，VM 是在它之上额外挂的整机环境（QEMU 跑在 rootfs 里，镜像从镜像库下载）。
 */
@Entity(
    tableName = "vm_instances",
    indices = [
        Index(value = ["workspace_id"]),
        Index(value = ["workspace_id", "image_id"], unique = true),
    ],
)
data class VmInstanceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** 镜像库条目的 id */
    @ColumnInfo("image_id")
    val imageId: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("state")
    val state: String = VmState.NOT_INSTALLED.name,
    @ColumnInfo("image_name")
    val imageName: String = "",
    /** host 侧转发的 adb 端口 */
    /** 获得 guest root 的方式（ADB_ROOT / SU / DIRECT），随镜像记录，避免依赖 catalog 才能连 */
    @ColumnInfo("root_mode", defaultValue = "ADB_ROOT")
    val rootMode: String = "ADB_ROOT",
    @ColumnInfo("adb_port", defaultValue = "5555")
    val adbPort: Int = 5555,
    /** host 侧 VNC 端口 */
    @ColumnInfo("vnc_port", defaultValue = "5900")
    val vncPort: Int = 5900,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    /** WorkspaceBackgroundProcesses 里承载 QEMU 的后台进程 id */
    @ColumnInfo("background_id")
    val backgroundId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    fun vmState(): VmState = runCatching { VmState.valueOf(state) }.getOrDefault(VmState.NOT_INSTALLED)
}
