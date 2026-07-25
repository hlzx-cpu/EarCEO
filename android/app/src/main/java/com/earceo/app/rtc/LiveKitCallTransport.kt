package com.earceo.app.rtc

import android.content.Context
import com.earceo.app.audio.ViaimAudioBufferCallback
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack

data class LiveKitRoomCredentials(
    val url: String,
    val token: String,
) {
    val configured: Boolean
        get() = url.isNotBlank() && token.isNotBlank()
}

interface CallMediaTransport {
    suspend fun connect(credentials: LiveKitRoomCredentials)
    fun selectViaim(selected: Boolean)
    fun appendViaimPcm(pcm: ByteArray)
    fun lastSuccessfulViaimInjectionMillis(): Long
    fun disconnect()
}

/**
 * Bidirectional WebRTC media transport.
 *
 * Remote audio playback is handled by LiveKit. Local capture normally comes
 * from WebRTC AudioRecord; while Viaim is healthy, [audioBufferCallback]
 * replaces those samples with the headset's PCM.
 */
class LiveKitCallTransport(
    context: Context,
    private val audioBufferCallback: ViaimAudioBufferCallback,
) : CallMediaTransport {
    private val room: Room = LiveKit.create(
        appContext = context.applicationContext,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioOutputType = AudioType.CallAudioType(),
            ),
        ),
    )
    private var localAudioTrack: LocalAudioTrack? = null

    override suspend fun connect(credentials: LiveKitRoomCredentials) {
        require(credentials.configured) { "LiveKit room credentials are missing" }
        room.connect(credentials.url, credentials.token)
        val track = room.localParticipant.getOrCreateDefaultAudioTrack()
        track.setAudioBufferCallback(audioBufferCallback)
        check(room.localParticipant.publishAudioTrack(track)) {
            "LiveKit failed to publish the local audio track"
        }
        localAudioTrack = track
    }

    override fun selectViaim(selected: Boolean) {
        audioBufferCallback.selectViaim(selected)
    }

    override fun appendViaimPcm(pcm: ByteArray) {
        audioBufferCallback.appendViaimPcm(pcm)
    }

    override fun lastSuccessfulViaimInjectionMillis(): Long {
        return audioBufferCallback.lastSuccessfulReadMillis()
    }

    override fun disconnect() {
        localAudioTrack?.setAudioBufferCallback(null)
        localAudioTrack = null
        room.disconnect()
    }
}
