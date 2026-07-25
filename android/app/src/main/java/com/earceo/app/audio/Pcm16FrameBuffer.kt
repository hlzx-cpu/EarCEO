package com.earceo.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Bounded, thread-safe buffer for Viaim's 16 kHz mono PCM.
 *
 * LiveKit/WebRTC normally requests 10 ms PCM16 buffers at its native sample
 * rate. This class performs the small deterministic rate/channel conversion
 * needed to replace those buffers without doing work on the Viaim callback.
 */
class Pcm16FrameBuffer(
    private val sourceSampleRate: Int = 16_000,
    maxBufferedMillis: Int = 2_000,
) {
    private val capacity = sourceSampleRate * maxBufferedMillis / 1_000
    private val samples = ArrayDeque<Short>(capacity)

    @Synchronized
    fun offerLittleEndian(pcm: ByteArray) {
        var index = 0
        while (index + 1 < pcm.size) {
            if (samples.size == capacity) samples.removeFirst()
            val value = (pcm[index].toInt() and 0xff) or
                (pcm[index + 1].toInt() shl 8)
            samples.addLast(value.toShort())
            index += 2
        }
    }

    @Synchronized
    fun readInto(
        destination: ByteBuffer,
        frames: Int,
        targetSampleRate: Int,
        targetChannels: Int,
    ): Boolean {
        require(targetSampleRate > 0)
        require(targetChannels > 0)
        val sourceFramesNeeded = ceil(
            frames.toDouble() * sourceSampleRate.toDouble() / targetSampleRate.toDouble(),
        ).toInt().coerceAtLeast(1)
        if (samples.size < sourceFramesNeeded) return false

        val source = ShortArray(sourceFramesNeeded) { samples.removeFirst() }
        destination.order(ByteOrder.nativeOrder())
        destination.position(0)
        repeat(frames) { frame ->
            val sourceIndex = (
                frame.toLong() * sourceSampleRate.toLong() / targetSampleRate.toLong()
                ).toInt().coerceAtMost(source.lastIndex)
            repeat(targetChannels) {
                destination.putShort(source[sourceIndex])
            }
        }
        destination.position(0)
        return true
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun bufferedSamples(): Int = samples.size
}
