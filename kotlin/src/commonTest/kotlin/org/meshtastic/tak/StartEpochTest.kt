package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fixed vectors, including both window-boundary directions.
 *
 * The naive `(now and 0x3FFF.inv()) or lo14` passes the first boundary case and
 * fails the second by 4.55 hours, so a test that only exercises one direction
 * would not distinguish the two implementations.
 */
class StartEpochTest {
    private val b = StartEpoch.MODULUS * 109_000L // a window boundary

    @Test
    fun `round-trips a start equal to now`() {
        val now = b + 1234L
        assertEquals(now, StartEpoch.reconstruct(StartEpoch.truncate(now), now))
    }

    @Test
    fun `round-trips a start a few seconds old`() {
        val now = b + 1234L
        val start = now - 40L
        assertEquals(start, StartEpoch.reconstruct(StartEpoch.truncate(start), now))
    }

    @Test
    fun `at a window boundary with the sender ahead of the receiver`() {
        // start = B, receiver still at B - 1. The naive splice yields B - 16384.
        assertEquals(b, StartEpoch.reconstruct(StartEpoch.truncate(b), b - 1L))
    }

    @Test
    fun `at a window boundary with the packet crossing in flight`() {
        // start = B - 1, receiver already at B. The naive splice yields B + 16383.
        assertEquals(b - 1L, StartEpoch.reconstruct(StartEpoch.truncate(b - 1L), b))
    }

    @Test
    fun `rejects a start implausibly far in the future`() {
        val now = b + 1234L
        val future = now + StartEpoch.FUTURE_TOLERANCE_SECONDS + 10L
        assertNull(StartEpoch.reconstruct(StartEpoch.truncate(future), now))
    }

    @Test
    fun `rejects a start beyond the past limit`() {
        val now = b + 8000L
        val tooOld = now - StartEpoch.PAST_LIMIT_SECONDS - 10L
        assertNull(StartEpoch.reconstruct(StartEpoch.truncate(tooOld), now))
    }

    @Test
    fun `accepts a start just inside the past limit`() {
        val now = b + 8000L
        val old = now - StartEpoch.PAST_LIMIT_SECONDS
        assertEquals(old, StartEpoch.reconstruct(StartEpoch.truncate(old), now))
    }

    @Test
    fun `rejects a value outside the field range`() {
        assertNull(StartEpoch.reconstruct(-1, b))
        assertNull(StartEpoch.reconstruct(StartEpoch.MODULUS.toInt(), b))
    }

    @Test
    fun `truncate is the low 14 bits for any sign`() {
        assertEquals(0, StartEpoch.truncate(b))
        assertEquals(1, StartEpoch.truncate(b + 1L))
        assertEquals((StartEpoch.MODULUS - 1L).toInt(), StartEpoch.truncate(b - 1L))
    }
}
