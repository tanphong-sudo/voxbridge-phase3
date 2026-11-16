package com.voxbridge.core

import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CaptureAnalysisTest {
    @Test
    fun `VAD state resets exactly once before each capture`() {
        val events = mutableListOf<String>()
        val samples = FloatArray(CaptureAnalysis.VAD_CHUNK_SAMPLES * 2)

        val first = CaptureAnalysis.analyzeVad(
            samples = samples,
            reset = { events += "reset" },
            probability = {
                events += "frame"
                0.7f
            },
        )
        val second = CaptureAnalysis.analyzeVad(
            samples = FloatArray(0),
            reset = { events += "reset-empty" },
            probability = { error("empty capture must not evaluate a VAD frame") },
        )

        assertEquals(listOf("reset", "frame", "frame", "reset-empty"), events)
        assertEquals(2, first.speechFrames)
        assertEquals(0, second.speechFrames)
    }

    @Test
    fun `trim retains 300ms pre-roll and 400ms post-roll`() {
        val samples = FloatArray(32_000) { it.toFloat() } // 2 s @16 kHz
        val trimmed = CaptureAnalysis.trimToSpeech(
            samples = samples,
            startMs = 700,
            endMs = 1_200,
        )

        // 700-300 = 400 ms; 1200+400 = 1600 ms => 1.2 s retained.
        assertEquals(19_200, trimmed.size)
        assertEquals(samples[6_400], trimmed.first())
        assertEquals(samples[25_599], trimmed.last())
    }

    @Test
    fun `trim clamps context at capture boundaries`() {
        val samples = FloatArray(16_000) { it.toFloat() }
        val trimmed = CaptureAnalysis.trimToSpeech(samples, startMs = 100, endMs = 900)
        assertContentEquals(samples, trimmed)
    }

    @Test
    fun `clipping requires both count floor and material ratio`() {
        assertEquals(false, CaptureAnalysis.isMeaningfullyClipped(1, 16_000))
        assertEquals(false, CaptureAnalysis.isMeaningfullyClipped(15, 16_000))
        assertEquals(true, CaptureAnalysis.isMeaningfullyClipped(160, 16_000))
        assertEquals(true, CaptureAnalysis.isMeaningfullyClipped(19_070, 207_872))
    }

    @Test
    fun `capture limit remains 30 seconds at recorder sample rate`() {
        assertEquals(480_000, CaptureAnalysis.maxCaptureSamples(16_000))
        assertEquals(1_440_000, CaptureAnalysis.maxCaptureSamples(48_000))
    }

    @Test
    fun `capture classifier characterizes limit clipping noise quality and short speech`() {
        val good = AudioEvidence(
            speechPresent = true,
            audioScore = 0.95,
            durationMs = 1_500,
            validSpeechDurationMs = 900,
        )
        assertIs<CaptureAnalysis.PreAsrGate.AskRepeat>(
            CaptureAnalysis.preAsrGate(good, captureLimitReached = true),
        ).also { assertEquals("speech_too_long", it.reason) }
        assertIs<CaptureAnalysis.PreAsrGate.AskRepeat>(
            CaptureAnalysis.preAsrGate(good.copy(clippingDetected = true)),
        ).also { assertEquals("audio_clipped", it.reason) }
        assertIs<CaptureAnalysis.PreAsrGate.AskRepeat>(
            CaptureAnalysis.preAsrGate(good.copy(tooNoisy = true)),
        ).also { assertEquals("audio_too_noisy", it.reason) }
        assertIs<CaptureAnalysis.PreAsrGate.RunAsr>(
            CaptureAnalysis.preAsrGate(good.copy(audioScore = 0.35)),
        )
        assertIs<CaptureAnalysis.PreAsrGate.AskRepeat>(
            CaptureAnalysis.preAsrGate(good.copy(audioScore = 0.19)),
        ).also { assertEquals("audio_low_quality", it.reason) }
        assertIs<CaptureAnalysis.PreAsrGate.AskRepeat>(
            CaptureAnalysis.preAsrGate(good.copy(validSpeechDurationMs = 224)),
        ).also { assertEquals("speech_too_short", it.reason) }
        assertIs<CaptureAnalysis.PreAsrGate.RunAsr>(CaptureAnalysis.preAsrGate(good))
    }

    @Test
    fun `low gain clear speech is not rejected by absolute RMS`() {
        val chunk = CaptureAnalysis.VAD_CHUNK_SAMPLES
        val samples = FloatArray(chunk * 4)
        // Approximately -63 dBFS speech with much quieter local background, matching the failure
        // shape of the red FLEURS fixture without hard-coding that sample.
        for (i in chunk until chunk * 3) samples[i] = if (i % 2 == 0) 0.001f else -0.001f
        for (i in 0 until chunk) samples[i] = if (i % 2 == 0) 0.00005f else -0.00005f
        for (i in chunk * 3 until chunk * 4) samples[i] = if (i % 2 == 0) 0.00005f else -0.00005f
        val probabilities = ArrayDeque(listOf(0.02f, 0.84f, 0.82f, 0.03f))
        val vad = CaptureAnalysis.analyzeVad(samples, reset = {}, probability = { probabilities.removeFirst() })

        val evidence = CaptureAnalysis.audioQualityEvidence(samples, vad)

        assertTrue(evidence.speechRmsDbfs < -59.0)
        assertTrue(evidence.speechToBackgroundDb!! > 12.0)
        assertTrue(evidence.score >= 0.50, "clear low-gain speech should reach ASR: $evidence")
        assertIs<CaptureAnalysis.PreAsrGate.RunAsr>(
            CaptureAnalysis.preAsrGate(
                AudioEvidence(true, evidence.score, durationMs = 2_000, validSpeechDurationMs = 900),
            ),
        )
    }

    @Test
    fun `weak VAD false positive on low gain stationary noise remains rejected`() {
        val chunk = CaptureAnalysis.VAD_CHUNK_SAMPLES
        val samples = FloatArray(chunk * 4) { i -> if (i % 2 == 0) 0.0007f else -0.0007f }
        val probabilities = ArrayDeque(listOf(0.10f, 0.52f, 0.51f, 0.10f))
        val vad = CaptureAnalysis.analyzeVad(samples, reset = {}, probability = { probabilities.removeFirst() })

        val evidence = CaptureAnalysis.audioQualityEvidence(samples, vad)

        assertTrue(evidence.score < 0.20, "stationary low-gain noise should not pass audio gate: $evidence")
        assertIs<CaptureAnalysis.PreAsrGate.NoSpeech>(
            CaptureAnalysis.preAsrGate(
                AudioEvidence(true, evidence.score, durationMs = 2_000, validSpeechDurationMs = 900),
            ),
        )
    }

    @Test
    fun `no VAD speech always has zero audio quality`() {
        val samples = FloatArray(CaptureAnalysis.VAD_CHUNK_SAMPLES * 2) { 0.2f }
        val vad = CaptureAnalysis.analyzeVad(samples, reset = {}, probability = { 0.1f })
        assertEquals(0.0, CaptureAnalysis.audioQualityScore(samples, vad))
    }
}
