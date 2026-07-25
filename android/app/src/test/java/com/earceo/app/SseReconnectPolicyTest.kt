package com.earceo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseReconnectPolicyTest {
    @Test
    fun increasesExponentiallyAndStopsAtMaximum() {
        val policy = SseReconnectPolicy(
            initialDelayMs = 1_000,
            maximumDelayMs = 8_000,
            jitterRatio = 0.0,
        )

        assertEquals(1_000, policy.nextDelayMs())
        assertEquals(2_000, policy.nextDelayMs())
        assertEquals(4_000, policy.nextDelayMs())
        assertEquals(8_000, policy.nextDelayMs())
        assertEquals(8_000, policy.nextDelayMs())
    }

    @Test
    fun appliesBoundedJitterAndResetStartsOver() {
        val fractions = ArrayDeque(listOf(0.0, 1.0, 0.5))
        val policy = SseReconnectPolicy(
            initialDelayMs = 1_000,
            maximumDelayMs = 30_000,
            jitterRatio = 0.20,
            randomFraction = { fractions.removeFirst() },
        )

        assertEquals(1_000, policy.nextDelayMs())
        assertEquals(2_400, policy.nextDelayMs())
        policy.reset()
        assertEquals(1_000, policy.nextDelayMs())
        assertTrue(fractions.isEmpty())
    }
}
