package com.earceo.app.audio

import com.earceo.app.audio.AudioSourcePolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourcePolicyTest {
    @Test
    fun startsWithSystemMicAndDoesNotPromoteOnConnectionAlone() {
        val policy = AudioSourcePolicy()

        assertEquals(Source.SYSTEM_MIC, policy.evaluate(0).selectedSource)
        val connected = policy.onViaimConnectionChanged(true, 100)

        assertEquals(Source.SYSTEM_MIC, connected.selectedSource)
        assertTrue(connected.viaimConnected)
        assertFalse(connected.viaimPcmHealthy)
    }

    @Test
    fun promotesViaimOnlyAfterHealthyPcmHysteresis() {
        val policy = AudioSourcePolicy(
            pcmTimeoutMs = 2_000,
            promotionDelayMs = 1_000,
        )
        policy.onViaimConnectionChanged(true, 0)

        assertEquals(
            Source.SYSTEM_MIC,
            policy.onViaimPcmFrame(100).selectedSource,
        )
        policy.onViaimPcmFrame(900)
        assertEquals(Source.SYSTEM_MIC, policy.evaluate(1_099).selectedSource)

        val promoted = policy.evaluate(1_100)
        assertEquals(Source.VIAIM, promoted.selectedSource)
        assertTrue(promoted.viaimPcmHealthy)
    }

    @Test
    fun disconnectFallsBackImmediately() {
        val policy = selectedViaimPolicy()

        val disconnected = policy.onViaimConnectionChanged(false, 700)

        assertEquals(Source.SYSTEM_MIC, disconnected.selectedSource)
        assertFalse(disconnected.viaimConnected)
        assertFalse(disconnected.viaimPcmHealthy)
    }

    @Test
    fun pcmTimeoutFallsBackAndRequiresFreshHysteresisBeforeRepromotion() {
        val policy = selectedViaimPolicy()

        assertEquals(Source.SYSTEM_MIC, policy.evaluate(1_100).selectedSource)
        assertEquals(
            Source.SYSTEM_MIC,
            policy.onViaimPcmFrame(1_200).selectedSource,
        )
        policy.onViaimPcmFrame(1_600)
        assertEquals(Source.SYSTEM_MIC, policy.evaluate(1_699).selectedSource)
        assertEquals(Source.VIAIM, policy.evaluate(1_700).selectedSource)
    }

    @Test
    fun duplicateConnectionCallbackDoesNotResetHealthyWindow() {
        val policy = AudioSourcePolicy(
            pcmTimeoutMs = 2_000,
            promotionDelayMs = 1_000,
        )
        policy.onViaimConnectionChanged(true, 0)
        policy.onViaimPcmFrame(100)

        policy.onViaimConnectionChanged(true, 900)

        assertEquals(Source.VIAIM, policy.evaluate(1_100).selectedSource)
    }

    @Test
    fun pcmWhileDisconnectedCannotPromoteViaim() {
        val policy = AudioSourcePolicy(promotionDelayMs = 0)

        val snapshot = policy.onViaimPcmFrame(100)

        assertEquals(Source.SYSTEM_MIC, snapshot.selectedSource)
        assertFalse(snapshot.viaimPcmHealthy)
    }

    @Test
    fun injectionFailureFallsBackAndRequiresFreshPcm() {
        val policy = selectedViaimPolicy()

        val failed = policy.onViaimInjectionFailed(650)

        assertEquals(Source.SYSTEM_MIC, failed.selectedSource)
        assertFalse(failed.viaimPcmHealthy)
        assertEquals(Source.SYSTEM_MIC, policy.evaluate(700).selectedSource)
    }

    @Test
    fun aRegressingClockCannotCausePrematurePromotion() {
        val policy = AudioSourcePolicy(
            pcmTimeoutMs = 2_000,
            promotionDelayMs = 1_000,
        )
        policy.onViaimConnectionChanged(true, 1_000)
        policy.onViaimPcmFrame(1_000)

        assertEquals(Source.SYSTEM_MIC, policy.evaluate(500).selectedSource)
        assertEquals(Source.VIAIM, policy.evaluate(2_000).selectedSource)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidTimeout() {
        AudioSourcePolicy(pcmTimeoutMs = 0)
    }

    private fun selectedViaimPolicy(): AudioSourcePolicy {
        return AudioSourcePolicy(
            pcmTimeoutMs = 500,
            promotionDelayMs = 500,
        ).also {
            it.onViaimConnectionChanged(true, 0)
            it.onViaimPcmFrame(100)
            it.onViaimPcmFrame(400)
            it.onViaimPcmFrame(600)
            assertEquals(Source.VIAIM, it.evaluate(600).selectedSource)
        }
    }
}
