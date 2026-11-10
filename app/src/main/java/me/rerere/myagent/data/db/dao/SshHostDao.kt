package me.rerere.myagent.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.myagent.data.db.entity.SshHostEntity

@Dao
interface SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    suspend fun getAll(): List<SshHostEntity>

    @Query("SELECT * FROM ssh_hosts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: SshHostEntity)

    @Delete
    suspend fun delete(host: SshHostEntity)

    /** Returns the number of rows removed, so a caller can tell "deleted" from "never existed". */
    @Query("DELETE FROM ssh_hosts WHERE name = :name")
    suspend fun deleteByName(name: String): Int

    /**
     * Rename in one transaction. `name` is the primary key, so a rename is delete-then-insert;
     * as two separate calls a crash (or a concurrent reader) in between could see the row vanish.
     */
    @Transaction
    suspend fun rename(from: String, host: SshHostEntity) {
        deleteByName(from)
        upsert(host)
    }
}
