package me.rerere.myagent.data.repository

import me.rerere.myagent.data.db.dao.SshHostDao
import me.rerere.myagent.data.db.entity.SshHostEntity

class SshHostRepository(private val dao: SshHostDao) {
    suspend fun getAll(): List<SshHostEntity> = dao.getAll()
    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)
    suspend fun upsert(host: SshHostEntity) = dao.upsert(host)
    suspend fun deleteByName(name: String): Int = dao.deleteByName(name)
    suspend fun rename(from: String, host: SshHostEntity) = dao.rename(from, host)
}
