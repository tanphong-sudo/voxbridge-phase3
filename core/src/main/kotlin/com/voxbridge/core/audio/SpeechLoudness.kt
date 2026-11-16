package com.voxbridge.core.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Makes synthesized speech carry over site noise: raises the voiced level to a loud, even target and
 * rounds off peaks with a soft limiter instead of letting them clip.
 *
 * The TTS voices come out around -24 dBFS RMS with peaks well below full scale, so on a phone
 * speaker at a construction site most of the available loudness is unused. Gain is measured on
 * voiced samples only (pauses would otherwise pull the average down and over-boost), is capped so
 * near-silent audio is not turned into hiss, and is never below 1: loud input is only limited.
 */
object SpeechLoudness {
    /** About -14 dBFS RMS on voiced samples: loud, with headroom left for the limiter. */
    const val TARGET_RMS = 0.20
    /** +15 dB at most. */
    const val MAX_GAIN = 5.6
    /** Samples above this level are compressed smoothly toward [CEILING]. */
    const val KNEE = 0.55
    const val CEILING = 0.98
    private const val VOICED_FLOOR = 0.01

    fun apply(pcm: PcmAudio): PcmAudio {
        val n = pcm.bytes.size / 2
        if (n == 0) return pcm
        val x = DoubleArray(n) { i -> sample(pcm.bytes, i) / 32768.0 }
        val gain = gainFor(x)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (limit(x[i] * gain) * 32767.0).toInt().coerceIn(-32768, 32767)
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return PcmAudio(out, pcm.sampleRate)
    }

    internal fun gainFor(x: DoubleArray): Double {
        var sum = 0.0
        var voiced = 0
        for (v in x) if (abs(v) > VOICED_FLOOR) { sum += v * v; voiced++ }
        if (voiced == 0) return 1.0
        val rms = sqrt(sum / voiced)
        return (TARGET_RMS / rms).coerceIn(1.0, MAX_GAIN)
    }

    /** Identity below [KNEE]; above it, a tanh curve that approaches but never reaches [CEILING]. */
    internal fun limit(v: Double): Double {
        val a = abs(v)
        if (a <= KNEE) return v
        val span = CEILING - KNEE
        val shaped = KNEE + span * tanh((a - KNEE) / span)
        return if (v < 0) -shaped else shaped
    }

    private fun sample(bytes: ByteArray, i: Int): Int =
        (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
}
