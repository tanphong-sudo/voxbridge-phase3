package com.voxbridge.runtime

import kotlin.math.abs

/** Overlap protects convolution context. validFrames masks every padded convolution output. */
object PiperTiles {
    const val CHANNELS = 192
    const val FRAMES = 64
    const val HOP = 256
    private const val HALO = 16
    private const val CORE = FRAMES - 2 * HALO

    fun render(latent: FloatArray, infer: (FloatArray, Int) -> FloatArray): FloatArray {
        require(latent.isNotEmpty() && latent.size % CHANNELS == 0 && latent.all { it.isFinite() })
        val frames = latent.size / CHANNELS
        require(frames <= 10000) { "TTS duration budget exceeded" }
        val output = FloatArray(frames * HOP)
        val tile = FloatArray(CHANNELS * FRAMES)
        var offset = 0
        while (offset < frames) {
            val begin = (offset - HALO).coerceIn(0, (frames - FRAMES).coerceAtLeast(0))
            val valid = minOf(FRAMES, frames - begin)
            tile.fill(0f)
            for (channel in 0 until CHANNELS)
                latent.copyInto(tile, channel * FRAMES, channel * frames + begin, channel * frames + begin + valid)
            val wave = infer(tile, valid)
            check(wave.size == FRAMES * HOP && wave.all { it.isFinite() }) { "Invalid QNN vocoder output" }
            val count = minOf(CORE, frames - offset)
            val local = (offset - begin) * HOP
            wave.copyInto(output, offset * HOP, local, local + count * HOP)
            offset += count
        }
        return output
    }

    /** Matches sherpa-onnx 1.13.3 GeneratedAudio::ScaleSilence, including its end comparison. */
    fun scaleSilence(samples: FloatArray, sampleRate: Int, scale: Float = .2f): FloatArray {
        require(sampleRate > 0 && scale in 0f..1f)
        if (scale == 1f) return samples
        val threshold = (sampleRate * .2).toInt()
        val intervals = ArrayList<Pair<Int, Int>>()
        var last = -1
        for (i in samples.indices) {
            if (abs(samples[i]) <= .01f) { if (last == -1) last = i; continue }
            if (last != -1 && i - last < threshold) { last = -1; continue }
            if (last != -1) { intervals += last to i; last = -1 }
        }
        if (last != -1 && samples.size - last > threshold) intervals += last to samples.size
        if (intervals.isEmpty()) return samples
        val out = FloatArray(samples.size)
        var read = 0; var written = 0
        for ((start, end) in intervals) {
            samples.copyInto(out, written, read, start); written += start - read
            val keep = ((end - start) * scale).toInt()
            samples.copyInto(out, written, start, start + keep); written += keep; read = end
        }
        samples.copyInto(out, written, read, samples.size); written += samples.size - read
        return out.copyOf(written)
    }
}
