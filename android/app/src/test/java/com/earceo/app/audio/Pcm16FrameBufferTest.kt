package com.earceo.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16FrameBufferTest {
    @Test
    fun `upsamples mono 16k to stereo 48k`() {
        val buffer = Pcm16FrameBuffer(maxBufferedMillis = 100)
        buffer.offerLittleEndian(
            byteArrayOf(
                1, 0,
                2, 0,
            ),
        )
        val output = ByteBuffer.allocate(6 * 2 * 2).order(ByteOrder.nativeOrder())

        assertTrue(buffer.readInto(output, frames = 6, targetSampleRate = 48_000, targetChannels = 2))

        val values = ShortArray(12) { output.getShort(it * 2) }
        assertEquals(listOf<Short>(1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2), values.toList())
    }

    @Test
    fun `does not partially consume on underrun`() {
        val buffer = Pcm16FrameBuffer(maxBufferedMillis = 100)
        buffer.offerLittleEndian(byteArrayOf(7, 0))
        val output = ByteBuffer.allocate(8)

        assertFalse(buffer.readInto(output, frames = 4, targetSampleRate = 16_000, targetChannels = 1))
        assertEquals(1, buffer.bufferedSamples())
    }
}
