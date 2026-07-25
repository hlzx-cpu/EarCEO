package com.earceo.app

import kotlin.math.pow
import kotlin.math.roundToLong

class SseReconnectPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
    private val jitterRatio: Double = 0.20,
    private val randomFraction: () -> Double = Math::random,
) {
    private var attempt = 0

    init {
        require(initialDelayMs > 0)
        require(maximumDelayMs >= initialDelayMs)
        require(jitterRatio in 0.0..1.0)
    }

    @Synchronized
    fun nextDelayMs(): Long {
        val exponential = initialDelayMs.toDouble() * 2.0.pow(attempt.coerceAtMost(30))
        val capped = exponential.coerceAtMost(maximumDelayMs.toDouble())
        val centeredRandom = randomFraction().coerceIn(0.0, 1.0) * 2.0 - 1.0
        val jittered = capped * (1.0 + centeredRandom * jitterRatio)
        attempt = (attempt + 1).coerceAtMost(31)
        return jittered.roundToLong().coerceIn(initialDelayMs, maximumDelayMs)
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}
