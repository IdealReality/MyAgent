package me.rerere.myagent.data.memory

import me.rerere.myagent.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆作用域解析：私有与共用同时成立，旧 useGlobalMemory 保持兼容。 */
class MemoryScopeResolverTest {

    private val shared = AssistantMemoryStore.GLOBAL_SCOPE_ID

    @Test
    fun `default assistant reads private then shared`() {
        val a = Assistant(name = "A")
        val scopes = MemoryScopeResolver.scopesOf(a)
        assertEquals("a-${a.id}", scopes.write)
        assertEquals(listOf("a-${a.id}", shared), scopes.read)
        assertTrue(scopes.readsShared)
        assertFalse(scopes.writesShared)
    }

    @Test
    fun `shared memory can be switched off`() {
        val a = Assistant(name = "A", useSharedMemory = false)
        val scopes = MemoryScopeResolver.scopesOf(a)
        assertEquals(listOf("a-${a.id}"), scopes.read)
        assertFalse(scopes.readsShared)
    }

    @Test
    fun `legacy global mode writes and reads only the shared scope`() {
        val a = Assistant(name = "A", useGlobalMemory = true, useSharedMemory = false)
        val scopes = MemoryScopeResolver.scopesOf(a)
        assertEquals(shared, scopes.write)
        assertEquals(listOf(shared), scopes.read)
        assertTrue(scopes.writesShared)
    }

    @Test
    fun `scope labels distinguish shared from self`() {
        assertEquals("shared", MemoryScopeResolver.scopeLabel(shared))
        assertEquals("self", MemoryScopeResolver.scopeLabel("a-anything"))
    }

    @Test
    fun `two assistants never share a private scope`() {
        val a = Assistant(name = "A")
        val b = Assistant(name = "B")
        assertTrue(
            MemoryScopeResolver.privateScopeIdOf(a) != MemoryScopeResolver.privateScopeIdOf(b)
        )
    }
}
