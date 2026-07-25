package com.earceo.app.audio

/**
 * Selects the preferred call audio source without owning either source.
 *
 * Viaim is promoted only after it has remained connected and delivered fresh
 * PCM for [promotionDelayMs]. A disconnect or PCM timeout falls back to the
 * system microphone immediately. Callers should pass a monotonic timestamp,
 * such as Android's elapsed realtime.
 */
class AudioSourcePolicy(
    private val pcmTimeoutMs: Long = 1_500L,
    private val promotionDelayMs: Long = 1_000L,
) {
    enum class Source {
        VIAIM,
        SYSTEM_MIC,
    }

    data class Snapshot(
        val selectedSource: Source,
        val viaimConnected: Boolean,
        val viaimPcmHealthy: Boolean,
    )

    private var selectedSource = Source.SYSTEM_MIC
    private var viaimConnected = false
    private var lastPcmAtMs: Long? = null
    private var healthySinceMs: Long? = null
    private var lastObservedAtMs = 0L

    init {
        require(pcmTimeoutMs > 0L)
        require(promotionDelayMs >= 0L)
    }

    @Synchronized
    fun onViaimConnectionChanged(connected: Boolean, nowMs: Long): Snapshot {
        val now = normalizeTimestamp(nowMs)
        if (viaimConnected == connected) return evaluateAt(now)

        viaimConnected = connected
        lastPcmAtMs = null
        healthySinceMs = null
        if (!connected) selectedSource = Source.SYSTEM_MIC
        return evaluateAt(now)
    }

    @Synchronized
    fun onViaimPcmFrame(nowMs: Long): Snapshot {
        val now = normalizeTimestamp(nowMs)
        if (!viaimConnected) return evaluateAt(now)

        val wasHealthy = isPcmHealthy(now)
        lastPcmAtMs = now
        if (!wasHealthy) healthySinceMs = now
        return evaluateAt(now)
    }

    @Synchronized
    fun onViaimInjectionFailed(nowMs: Long): Snapshot {
        val now = normalizeTimestamp(nowMs)
        lastPcmAtMs = null
        healthySinceMs = null
        selectedSource = Source.SYSTEM_MIC
        return evaluateAt(now)
    }

    @Synchronized
    fun evaluate(nowMs: Long): Snapshot = evaluateAt(normalizeTimestamp(nowMs))

    private fun evaluateAt(nowMs: Long): Snapshot {
        val pcmHealthy = isPcmHealthy(nowMs)
        if (!viaimConnected || !pcmHealthy) {
            selectedSource = Source.SYSTEM_MIC
            healthySinceMs = null
        } else if (
            nowMs - checkNotNull(healthySinceMs) >= promotionDelayMs
        ) {
            selectedSource = Source.VIAIM
        }

        return Snapshot(
            selectedSource = selectedSource,
            viaimConnected = viaimConnected,
            viaimPcmHealthy = pcmHealthy,
        )
    }

    private fun isPcmHealthy(nowMs: Long): Boolean {
        val lastPcm = lastPcmAtMs ?: return false
        return viaimConnected && nowMs - lastPcm < pcmTimeoutMs
    }

    private fun normalizeTimestamp(nowMs: Long): Long {
        require(nowMs >= 0L)
        lastObservedAtMs = maxOf(lastObservedAtMs, nowMs)
        return lastObservedAtMs
    }
}
