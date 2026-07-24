package com.earceo.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executor

/**
 * Serializes headset PCM writes away from the SDK audio callback thread.
 *
 * Input is signed 16-bit little-endian, 16 kHz, mono PCM. A placeholder WAV
 * header is written first and patched after the final PCM frame is flushed.
 */
class WavRecorder(
    val outputFile: File,
    private val executor: Executor,
) {
    data class Result(
        val file: File,
        val pcmBytes: Long,
        val error: Throwable?,
    )

    private val stateLock = Any()
    private var acceptingPcm = true
    private var output: BufferedOutputStream? = null
    private var pcmBytes = 0L
    private var failure: Throwable? = null

    init {
        executor.execute {
            runCatching {
                outputFile.parentFile?.mkdirs()
                output = BufferedOutputStream(FileOutputStream(outputFile, false)).also {
                    writeHeader(it, pcmDataSize = 0L)
                }
            }.onFailure(::rememberFailure)
        }
    }

    fun append(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val copy = pcm.copyOf()
        synchronized(stateLock) {
            if (!acceptingPcm) return
            executor.execute {
                if (failure != null) return@execute
                runCatching {
                    output?.write(copy)
                        ?: error("WAV output stream was not initialized")
                    pcmBytes += copy.size
                }.onFailure(::rememberFailure)
            }
        }
    }

    fun finish(onFinished: (Result) -> Unit) {
        synchronized(stateLock) {
            if (!acceptingPcm) return
            acceptingPcm = false
            executor.execute {
                runCatching {
                    output?.flush()
                    output?.close()
                    output = null
                    if (failure == null) patchHeader(outputFile, pcmBytes)
                }.onFailure(::rememberFailure)

                onFinished(
                    Result(
                        file = outputFile,
                        pcmBytes = pcmBytes,
                        error = failure,
                    ),
                )
            }
        }
    }

    private fun rememberFailure(error: Throwable) {
        if (failure == null) failure = error
    }

    private fun patchHeader(file: File, pcmDataSize: Long) {
        RandomAccessFile(file, "rw").use {
            it.seek(4)
            it.writeLittleEndianInt(36L + pcmDataSize)
            it.seek(40)
            it.writeLittleEndianInt(pcmDataSize)
        }
    }

    private fun writeHeader(output: OutputStream, pcmDataSize: Long) {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(36L + pcmDataSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(CHANNEL_COUNT)
        output.writeLittleEndianInt(SAMPLE_RATE.toLong())
        output.writeLittleEndianInt(
            (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE).toLong(),
        )
        output.writeLittleEndianShort(CHANNEL_COUNT * BYTES_PER_SAMPLE)
        output.writeLittleEndianShort(BITS_PER_SAMPLE)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(pcmDataSize)
    }

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun OutputStream.writeLittleEndianInt(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
    }
}
