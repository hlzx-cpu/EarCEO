package com.earceo.app.call

import com.earceo.app.audio.AudioSourcePolicy
import com.earceo.app.rtc.CallMediaTransport
import com.earceo.app.rtc.LiveKitRoomCredentials
import com.earceo.app.telecom.SystemCallController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallSessionCoordinator(
    private val scope: CoroutineScope,
    private val telecom: SystemCallController,
    private val media: CallMediaTransport,
    private val sourcePolicy: AudioSourcePolicy = AudioSourcePolicy(),
    private val monotonicMillis: () -> Long,
    private val onStateChanged: (State) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    enum class State {
        IDLE,
        CONNECTING_MEDIA,
        REGISTERING_TELECOM,
        ACTIVE,
        HELD,
        ENDING,
        FAILED,
    }

    private var callJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastSelectedSource = AudioSourcePolicy.Source.SYSTEM_MIC
    private var viaimSelectedAtMillis = 0L
    private var cleaned = true

    @Synchronized
    fun startOutgoing(credentials: LiveKitRoomCredentials) {
        check(callJob == null) { "A call is already running" }
        cleaned = false
        callJob = scope.launch {
            var failure: Throwable? = null
            runCatching {
                onStateChanged(State.CONNECTING_MEDIA)
                media.connect(credentials)
                startPolicyWatchdog()
                onStateChanged(State.REGISTERING_TELECOM)
                telecom.runOutgoingCall(
                    displayName = "EarCEO",
                    address = "earceo:livekit",
                ) { active ->
                    onStateChanged(if (active) State.ACTIVE else State.HELD)
                }
            }.onFailure {
                if (it !is CancellationException) {
                    failure = it
                    onError(it)
                }
            }
            cleanup(failed = failure != null)
        }
    }

    fun onViaimConnectionChanged(connected: Boolean) {
        applyPolicy(
            sourcePolicy.onViaimConnectionChanged(connected, monotonicMillis()),
        )
    }

    fun onViaimPcm(pcm: ByteArray) {
        media.appendViaimPcm(pcm)
        applyPolicy(sourcePolicy.onViaimPcmFrame(monotonicMillis()))
    }

    fun end() {
        if (callJob == null) return
        onStateChanged(State.ENDING)
        scope.launch {
            if (!telecom.disconnect()) callJob?.cancel()
        }
    }

    fun close() {
        watchdogJob?.cancel()
        callJob?.cancel()
        cleanup(failed = false)
    }

    private fun startPolicyWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                val now = monotonicMillis()
                val snapshot = sourcePolicy.evaluate(now)
                if (
                    snapshot.selectedSource == AudioSourcePolicy.Source.VIAIM &&
                    now - viaimSelectedAtMillis >= VIAIM_INJECTION_GRACE_MILLIS &&
                    (
                        media.lastSuccessfulViaimInjectionMillis() < viaimSelectedAtMillis ||
                            now - media.lastSuccessfulViaimInjectionMillis() >=
                            VIAIM_INJECTION_TIMEOUT_MILLIS
                        )
                ) {
                    applyPolicy(sourcePolicy.onViaimInjectionFailed(now))
                } else {
                    applyPolicy(snapshot)
                }
                delay(POLICY_POLL_MILLIS)
            }
        }
    }

    @Synchronized
    private fun applyPolicy(snapshot: AudioSourcePolicy.Snapshot) {
        if (snapshot.selectedSource == lastSelectedSource) return
        lastSelectedSource = snapshot.selectedSource
        if (lastSelectedSource == AudioSourcePolicy.Source.VIAIM) {
            viaimSelectedAtMillis = monotonicMillis()
        }
        media.selectViaim(snapshot.selectedSource == AudioSourcePolicy.Source.VIAIM)
    }

    @Synchronized
    private fun cleanup(failed: Boolean) {
        if (cleaned) return
        cleaned = true
        watchdogJob?.cancel()
        watchdogJob = null
        media.selectViaim(false)
        media.disconnect()
        callJob = null
        onStateChanged(if (failed) State.FAILED else State.IDLE)
    }

    companion object {
        private const val POLICY_POLL_MILLIS = 250L
        private const val VIAIM_INJECTION_GRACE_MILLIS = 750L
        private const val VIAIM_INJECTION_TIMEOUT_MILLIS = 750L
    }
}
