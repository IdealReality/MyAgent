package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dropped channel is recovered by the backoff in [reconnectDelayMs]: fast for the first
 * couple of tries, then slow enough that a dead host is not hammered all day.
 */
class TerminalReconnectTest {

    @Test
    fun `backoff grows then caps`() {
        val delays = (0 until MAX_AUTO_RECONNECT).map(::reconnectDelayMs)

        assertEquals(1_000L, delays.first())
        assertEquals(delays.sorted(), delays)
        // Fast enough to feel automatic at first, slow enough to survive a train ride.
        assertTrue(delays.last() <= 60_000L)
        assertTrue(delays.take(3).all { it <= 4_000L })
    }

    @Test
    fun `a negative attempt is treated as the first one`() {
        assertEquals(reconnectDelayMs(0), reconnectDelayMs(-5))
    }

    @Test
    fun `only a shell that ended on a live link counts as a user exit`() {
        // Ctrl-D / `exit`: status 0 with the transport still up.
        assertTrue(isUserInitiatedExit(0, linkAlive = true))
        // A dropped link can also surface status 0 — that is a drop, not the user leaving, and
        // treating it as one is what used to disable autoConnect for good.
        assertFalse(isUserInitiatedExit(0, linkAlive = false))
        // Killed from the other side (`kill -9 $$`, host shutdown).
        assertFalse(isUserInitiatedExit(137, linkAlive = true))
        assertFalse(isUserInitiatedExit(-1, linkAlive = false))
    }
}
