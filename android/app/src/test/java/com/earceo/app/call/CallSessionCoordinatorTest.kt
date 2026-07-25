package com.earceo.app.call

import com.earceo.app.audio.AudioSourcePolicy
import com.earceo.app.rtc.CallMediaTransport
import com.earceo.app.rtc.LiveKitRoomCredentials
import com.earceo.app.telecom.SystemCallController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionCoordinatorTest {
    @Test
    fun `connects media before telecom and cleans up on end`() = runBlocking {
        val operations = mutableListOf<String>()
        val media = FakeMedia(operations)
        val telecom = FakeTelecom(operations)
        val states = mutableListOf<CallSessionCoordinator.State>()
        val coordinator = CallSessionCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            telecom = telecom,
            media = media,
            monotonicMillis = { 0L },
            onStateChanged = states::add,
        )

        coordinator.startOutgoing(LiveKitRoomCredentials("wss://example", "token"))

        assertEquals(listOf("media.connect", "telecom.run"), operations)
        assertTrue(states.contains(CallSessionCoordinator.State.ACTIVE))

        coordinator.end()
        telecom.finished.await()

        assertTrue(media.disconnected)
        assertEquals(CallSessionCoordinator.State.IDLE, states.last())
    }

    @Test
    fun `promotes healthy Viaim and falls back on disconnect`() {
        var now = 0L
        val media = FakeMedia(mutableListOf())
        val coordinator = CallSessionCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            telecom = FakeTelecom(mutableListOf()),
            media = media,
            sourcePolicy = AudioSourcePolicy(promotionDelayMs = 100L),
            monotonicMillis = { now },
        )

        coordinator.onViaimConnectionChanged(true)
        coordinator.onViaimPcm(byteArrayOf(1, 0))
        assertFalse(media.viaimSelected)

        now = 100L
        coordinator.onViaimPcm(byteArrayOf(2, 0))
        assertTrue(media.viaimSelected)

        coordinator.onViaimConnectionChanged(false)
        assertFalse(media.viaimSelected)
    }

    private class FakeMedia(
        private val operations: MutableList<String>,
    ) : CallMediaTransport {
        var viaimSelected = false
        var disconnected = false

        override suspend fun connect(credentials: LiveKitRoomCredentials) {
            operations += "media.connect"
        }

        override fun selectViaim(selected: Boolean) {
            viaimSelected = selected
        }

        override fun appendViaimPcm(pcm: ByteArray) = Unit

        override fun lastSuccessfulViaimInjectionMillis(): Long = Long.MAX_VALUE

        override fun disconnect() {
            disconnected = true
        }
    }

    private class FakeTelecom(
        private val operations: MutableList<String>,
    ) : SystemCallController {
        val finished = CompletableDeferred<Unit>()
        private var onActiveChanged: (suspend (Boolean) -> Unit)? = null

        override suspend fun runOutgoingCall(
            displayName: String,
            address: String,
            onActiveChanged: suspend (Boolean) -> Unit,
        ) {
            operations += "telecom.run"
            this.onActiveChanged = onActiveChanged
            onActiveChanged(true)
            finished.await()
            onActiveChanged(false)
        }

        override suspend fun disconnect(): Boolean {
            finished.complete(Unit)
            return true
        }
    }
}
