package me.rerere.rikkahub.data.agentsanywhere

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.agentsAnywhereSessionDataStore by preferencesDataStore(name = "agents_anywhere_sessions")

/**
 * 本地会话 id → 远端 AA session id 的持久映射。
 *
 * 为什么必须持久：AA 的会话 id 由客户端持有，服务端不会替你记住「刚才那次聊天」。手机进程被
 * 系统回收后重开，如果映射丢了，那一轮就会在远端新建一个会话 —— 用户看到的是「它忘了前面说过的
 * 一切」。存在 DataStore 里（而不是只存内存）才能跨进程续上。
 *
 * 只存 id，不存任何消息内容；远端会话本身在 AA 服务端。
 */
class AgentsAnywhereSessionStore(private val context: Context) {

    suspend fun remoteSessionId(localConversationId: String): String? {
        if (localConversationId.isBlank()) return null
        val prefs = context.agentsAnywhereSessionDataStore.data.first()
        return prefs[stringPreferencesKey(localConversationId)]
    }

    suspend fun remember(localConversationId: String, remoteSessionId: String) {
        if (localConversationId.isBlank() || remoteSessionId.isBlank()) return
        context.agentsAnywhereSessionDataStore.edit { prefs ->
            prefs[stringPreferencesKey(localConversationId)] = remoteSessionId
        }
    }

    /** 清空全部映射。换 connector / 换机器后用，避免旧映射指向不存在的远端会话。 */
    suspend fun clearAll() {
        context.agentsAnywhereSessionDataStore.edit { prefs -> prefs.clear() }
    }

    suspend fun forget(localConversationId: String) {
        if (localConversationId.isBlank()) return
        context.agentsAnywhereSessionDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(localConversationId))
        }
    }
}
