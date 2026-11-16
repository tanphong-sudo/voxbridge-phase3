package com.voxbridge.core

import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.audio.SpeechLoudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechLoudnessTest {
    private fun tone(amplitude: Double, samples: Int = 1600, silenceAfter: Int = 0): PcmAudio {
        val bytes = ByteArray((samples + silenceAfter) * 2)
        for (i in 0 until samples) {
            val v = (amplitude * sin(2 * PI * 220 * i / 16000) * 32767).toInt()
            bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
        }
        return PcmAudio(bytes, 16000)
    }

    private fun samples(p: PcmAudio) = IntArray(p.bytes.size / 2) { i ->
        (p.bytes[2 * i].toInt() and 0xff) or (p.bytes[2 * i + 1].toInt() shl 8)
    }

    private fun rms(p: PcmAudio, count: Int = samples(p).size) =
        sqrt(samples(p).take(count).sumOf { (it / 32768.0).let { v -> v * v } } / count)

    @Test fun quietSpeechIsRaisedTowardTheTarget() {
        val out = SpeechLoudness.apply(tone(0.08))
        assertTrue(rms(out) > 0.15, "rms ${rms(out)}")
        assertEquals(16000, out.sampleRate)
    }

    @Test fun loudSpeechIsNeverClippedOrMadeQuieter() {
        val input = tone(0.99)
        val out = SpeechLoudness.apply(input)
        val peak = samples(out).maxOf { abs(it) } / 32768.0
        assertTrue(peak < SpeechLoudness.CEILING + 0.001, "peak $peak")
        assertTrue(rms(out) > rms(input) * 0.85)
    }

    @Test fun pausesDoNotCauseOverBoost() {
        val withPause = SpeechLoudness.apply(tone(0.1, silenceAfter = 16000))
        val without = SpeechLoudness.apply(tone(0.1))
        assertEquals(rms(without, 1600), rms(withPause, 1600), 0.002)
    }

    @Test fun silenceAndEmptyAudioPassThrough() {
        val silent = PcmAudio(ByteArray(3200), 22050)
        assertTrue(samples(SpeechLoudness.apply(silent)).all { it == 0 })
        val empty = PcmAudio(ByteArray(0), 22050)
        assertEquals(0, SpeechLoudness.apply(empty).bytes.size)
    }

    @Test fun limiterIsContinuousAtTheKnee() {
        val k = SpeechLoudness.KNEE
        assertEquals(k, SpeechLoudness.limit(k), 1e-9)
        assertTrue(SpeechLoudness.limit(k + 1e-4) - k < 2e-4)
        assertTrue(SpeechLoudness.limit(-3.0) > -SpeechLoudness.CEILING)
    }
}
