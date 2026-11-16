package com.voxbridge.core.audio

import com.voxbridge.core.model.Language

/** Backend-neutral mono signed PCM16 little-endian output. */
data class PcmAudio(val bytes: ByteArray, val sampleRate: Int) {
    init { require(sampleRate > 0); require(bytes.size % 2 == 0) }
}

interface TtsEngine : AutoCloseable {
    fun synthesize(text: String, language: Language): PcmAudio
}

/** Fails on zero-progress/errors instead of silently dropping output or spinning forever. */
fun writePcmFully(bytes: ByteArray, isActive: () -> Boolean, write: (ByteArray, Int, Int) -> Int) {
    var offset = 0
    while (offset < bytes.size && isActive()) {
        val count = write(bytes, offset, minOf(8192, bytes.size - offset))
        if (!isActive()) return
        check(count > 0 && count <= minOf(8192, bytes.size - offset)) { "AudioTrack write failed: $count" }
        offset += count
    }
}

/** Primitive bounded capture storage; grows in blocks, never boxes a sample. */
class FloatCaptureBuffer(private val limit: Int) {
    init { require(limit > 0) }
    private var values = FloatArray(minOf(limit, 16000))
    var size = 0
        private set
    fun append(samples: ShortArray, count: Int): Int {
        require(count in 0..samples.size)
        val accepted = minOf(count, limit - size)
        if (size + accepted > values.size) values = values.copyOf(minOf(limit, maxOf(size + accepted, values.size * 2)))
        for (i in 0 until accepted) values[size + i] = samples[i] / 32768f
        size += accepted
        return accepted
    }
    fun visitTail(count: Int, consume: (FloatArray, Int, Int) -> Unit) = consume(values, size - count, count)
    fun finish(): FloatArray = if (size == values.size) values else values.copyOf(size)
}
