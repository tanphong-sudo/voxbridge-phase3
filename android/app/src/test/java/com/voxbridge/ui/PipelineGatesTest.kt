package com.voxbridge.ui

import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineGatesTest {
    private val engine = DecisionEngine(
        translator = Translator { text, sourceLanguage, targetLanguage ->
            TranslationResult(
                sourceText = text,
                translatedText = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                modelName = "unused-pre-asr-test",
            )
        },
    )

    private fun healthyAudio() = AudioEvidence(
        speechPresent = true,
        audioScore = 0.95,
        durationMs = 1_500,
        validSpeechDurationMs = 900,
        clippingDetected = false,
        silenceRatio = 0.2,
    )

    @Test
    fun `VAD no-speech skips ASR even when other audio fields look healthy`() {
        val audio = AudioEvidence(
            speechPresent = false,
            audioScore = 0.99,
            durationMs = 3_000,
            validSpeechDurationMs = 0,
            clippingDetected = false,
            silenceRatio = 1.0,
        )

        assertEquals(TranslationDecision.NoSpeech, preAsrDecision(engine, audio))
    }

    @Test
    fun `VAD speech allows ASR`() {
        assertEquals(null, preAsrDecision(engine, healthyAudio()))
    }

    @Test
    fun `clipped low quality noisy and short captures are terminal before ASR`() {
        val clipped = preAsrDecision(engine, healthyAudio().copy(clippingDetected = true))
        val lowQuality = preAsrDecision(engine, healthyAudio().copy(audioScore = 0.10))
        val borderline = preAsrDecision(engine, healthyAudio().copy(audioScore = 0.18))
        val noisy = preAsrDecision(engine, healthyAudio().copy(tooNoisy = true))
        val short = preAsrDecision(engine, healthyAudio().copy(validSpeechDurationMs = 200))

        assertEquals(TranslationDecision.AskRepeat("audio_clipped"), clipped)
        assertEquals(TranslationDecision.NoSpeech, lowQuality)
        assertEquals(TranslationDecision.AskRepeat("audio_low_quality"), borderline)
        assertEquals(TranslationDecision.AskRepeat("audio_too_noisy"), noisy)
        assertEquals(TranslationDecision.AskRepeat("speech_too_short"), short)
    }

    @Test
    fun `isolated near full-scale samples do not mark an utterance clipped`() {
        assertFalse(isMeaningfullyClipped(clippingCount = 1, sampleCount = 16_000))
        assertFalse(isMeaningfullyClipped(clippingCount = 15, sampleCount = 16_000))
    }

    @Test
    fun `sustained near full-scale samples mark an utterance clipped`() {
        assertTrue(isMeaningfullyClipped(clippingCount = 160, sampleCount = 16_000))
        assertTrue(isMeaningfullyClipped(clippingCount = 19_070, sampleCount = 207_872))
    }

    @Test
    fun `capture starts only from ready idle state`() {
        assertTrue(canStartCapture(pipelineStage = "READY", listening = false))
        assertFalse(canStartCapture(pipelineStage = "LISTENING", listening = true))
        assertFalse(canStartCapture(pipelineStage = "UNDERSTANDING", listening = false))
        assertFalse(canStartCapture(pipelineStage = "TRANSLATING", listening = false))
        assertFalse(canStartCapture(pipelineStage = "SPEAKING", listening = false))
    }

    @Test
    fun `capture limit fails closed before ASR`() {
        assertEquals(null, preAsrDecision(engine, healthyAudio(), captureLimitReached = false))
        assertEquals(
            TranslationDecision.AskRepeat("speech_too_long"),
            preAsrDecision(engine, healthyAudio(), captureLimitReached = true),
        )
    }
}
