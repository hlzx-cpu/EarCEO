package com.earceo.app.audio

import android.media.AudioFormat
import android.os.SystemClock
import io.livekit.android.audio.AudioBufferCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Replaces LiveKit's microphone buffer with Viaim PCM while Viaim is selected.
 *
 * When Viaim is not selected the original WebRTC AudioRecord buffer is left
 * untouched, providing the normal system-microphone fallback.
 */
class ViaimAudioBufferCallback(
    private val pcmBuffer: Pcm16FrameBuffer,
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) : AudioBufferCallback {
    private val viaimSelected = AtomicBoolean(false)
    private val lastSuccessfulReadMillis = AtomicLong(0L)

    fun selectViaim(selected: Boolean) {
        if (viaimSelected.getAndSet(selected) == selected) return
        pcmBuffer.clear()
    }

    fun appendViaimPcm(pcm: ByteArray) {
        if (viaimSelected.get()) pcmBuffer.offerLittleEndian(pcm)
    }

    fun lastSuccessfulReadMillis(): Long = lastSuccessfulReadMillis.get()

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        if (!viaimSelected.get()) return captureTimeNs
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT &&
            audioFormat != AudioFormat.ENCODING_DEFAULT
        ) {
            silence(buffer, bytesRead)
            return captureTimeNs
        }

        val bytesPerFrame = Short.SIZE_BYTES * channelCount
        val frames = bytesRead / bytesPerFrame
        val replaced = pcmBuffer.readInto(buffer, frames, sampleRate, channelCount)
        if (replaced) {
            lastSuccessfulReadMillis.set(clockMillis())
        } else {
            // Never leak the phone microphone into a call that claims Viaim is
            // active. The policy watchdog can subsequently select fallback.
            silence(buffer, bytesRead)
        }
        return captureTimeNs
    }

    private fun silence(buffer: ByteBuffer, bytesRead: Int) {
        buffer.order(ByteOrder.nativeOrder())
        buffer.position(0)
        repeat(bytesRead.coerceAtMost(buffer.capacity())) { buffer.put(0) }
        buffer.position(0)
    }
}
