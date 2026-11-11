package me.rerere.myagent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.myagent.data.db.entity.VmInstanceEntity

@Dao
interface VmInstanceDao {
    @Query("SELECT * FROM vm_instances WHERE workspace_id = :workspaceId ORDER BY created_at ASC")
    fun listFlow(workspaceId: String): Flow<List<VmInstanceEntity>>

    @Query("SELECT * FROM vm_instances WHERE workspace_id = :workspaceId ORDER BY created_at ASC")
    suspend fun listForWorkspace(workspaceId: String): List<VmInstanceEntity>

    @Query("SELECT * FROM vm_instances WHERE id = :id")
    suspend fun getById(id: String): VmInstanceEntity?

    @Query("SELECT * FROM vm_instances WHERE workspace_id = :workspaceId AND image_id = :imageId")
    suspend fun findByImage(workspaceId: String, imageId: String): VmInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: VmInstanceEntity)

    @Query("UPDATE vm_instances SET state = :state, last_error = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, error: String?, updatedAt: Long): Int

    @Query("UPDATE vm_instances SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long): Int

    @Query("DELETE FROM vm_instances WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM vm_instances WHERE workspace_id = :workspaceId")
    suspend fun deleteByWorkspace(workspaceId: String): Int

    @Query("SELECT * FROM vm_instances")
    suspend fun getAll(): List<VmInstanceEntity>
}
