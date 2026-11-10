package me.rerere.myagent.data.ai.tools.local

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate exists because the per-connection test limit multiplies by the number of open sockets.
 * These cases pin the two behaviours the socket relies on: a slot is refused after the wait budget
 * (so a spammer gets `busy`, not an unbounded queue of 30s dials) and a returned slot is reused.
 */
class ProbeGateTest {

    @Test
    fun `a slot is refused once capacity is taken`() = runBlocking {
        val gate = ProbeGate(capacity = 1)
        assertTrue(gate.enter(50))
        try {
            assertFalse(gate.enter(50))
        } finally {
            gate.exit()
        }
        assertTrue("a returned slot must be reusable", gate.enter(50))
        gate.exit()
    }

    @Test
    fun `never more than capacity run at once`() = runBlocking {
        val gate = ProbeGate(capacity = 2)
        var running = 0
        var peak = 0
        val jobs = (1..12).map {
            async {
                if (!gate.enter(2_000)) return@async false
                try {
                    running += 1
                    peak = maxOf(peak, running)
                    delay(10)
                } finally {
                    running -= 1
                    gate.exit()
                }
                true
            }
        }
        assertTrue(jobs.awaitAll().all { it })
        assertEquals(2, peak)
    }
}
