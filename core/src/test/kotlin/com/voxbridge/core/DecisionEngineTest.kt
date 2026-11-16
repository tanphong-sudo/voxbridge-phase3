package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionEngineTest {

    private fun goodAudio() = AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1600)

    private fun vi2en(text: String, asrScore: Double = 0.92) = TranscriptResult(
        sourceLanguage = Language.VI,
        targetLanguage = Language.EN,
        text = text,
        asrScore = asrScore
    )

    @Test
    fun noSpeechDoesNotTranslate() {
        val engine = DecisionEngine(translator = FakeTranslator())
        val decision = engine.decide(
            audio = AudioEvidence(speechPresent = false, audioScore = 0.05, durationMs = 2000),
            transcript = vi2en("")
        )
        assertTrue(decision is TranslationDecision.NoSpeech)
    }

    @Test
    fun benchmarkCalibratedAudioAboveMinimumReachesAsr() {
        val engine = DecisionEngine(translator = FakeTranslator())
        val decision = engine.preAsrDecision(
            AudioEvidence(speechPresent = true, audioScore = 0.35, durationMs = 1200),
        )
        assertEquals(null, decision)
    }

    @Test
    fun benchmarkMarginalSpeechAsksRepeatInsteadOfNoSpeech() {
        val engine = DecisionEngine(translator = FakeTranslator())
        val decision = engine.preAsrDecision(
            AudioEvidence(
                speechPresent = true,
                audioScore = 0.19,
                durationMs = 2_000,
                validSpeechDurationMs = 1_408,
            ),
        )
        assertEquals(TranslationDecision.AskRepeat("audio_low_quality"), decision)
    }

    @Test
    fun preAsrDecisionUsesTheEngineDecisionConfig() {
        val engine = DecisionEngine(
            translator = FakeTranslator(),
            config = DecisionConfig(
                minAudioScore = 0.10,
                askRepeatAudioScore = 0.80,
                minSpeechDurationMs = 600,
            ),
        )

        val lowQuality = engine.preAsrDecision(
            AudioEvidence(speechPresent = true, audioScore = 0.70, durationMs = 1_000),
        )
        assertEquals(TranslationDecision.AskRepeat("audio_low_quality"), lowQuality)

        val tooShort = engine.preAsrDecision(
            AudioEvidence(
                speechPresent = true,
                audioScore = 0.95,
                durationMs = 1_000,
                validSpeechDurationMs = 500,
            ),
        )
        assertEquals(TranslationDecision.AskRepeat("speech_too_short"), tooShort)
    }

    @Test
    fun lowAsrConfidenceAsksRepeat() {
        val engine = DecisionEngine(translator = FakeTranslator())
        val decision = engine.decide(
            audio = goodAudio(),
            transcript = vi2en("dung bang chuyen so ba", asrScore = 0.40)
        )
        assertTrue(decision is TranslationDecision.AskRepeat)
    }

    @Test
    fun uncalibratedRawDecoderScoreIsNotComparedWithCalibratedThreshold() {
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("hom nay troi dep" to "The weather is nice today."))
        )
        val transcript = TranscriptResult(
            sourceLanguage = Language.VI,
            targetLanguage = Language.EN,
            text = "hom nay troi dep",
            asrScore = 0.40,
            asrScoreCalibrated = false,
        )
        val decision = engine.decide(goodAudio(), transcript)
        assertTrue(decision is TranslationDecision.ModelTranslation)
    }

    @Test
    fun ordinarySentenceShipsModelTranslation() {
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("hom nay troi dep" to "The weather is nice today."))
        )
        val decision = engine.decide(goodAudio(), vi2en("hom nay troi dep"))
        assertTrue(decision is TranslationDecision.ModelTranslation)
        assertEquals("The weather is nice today.", decision.text)
    }

    @Test
    fun degenerateRepeatedModelOutputAsksRepeat() {
        val repeated = List(32) { "No" }.joinToString(", ") + ","
        val transcript = "không vào khu vực núi giềm số ba."
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf(transcript to repeated))
        )

        val decision = engine.decide(goodAudio(), vi2en(transcript))

        assertEquals(TranslationDecision.AskRepeat("translation_degenerate"), decision)
    }

    @Test
    fun preservedSafetySlotsAreVerified() {
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("dung bang chuyen so ba" to "Stop conveyor belt number three."))
        )
        val decision = engine.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        assertTrue(decision is TranslationDecision.VerifiedTranslation)
        assertEquals("Stop conveyor belt number three.", decision.text)
    }

    @Test
    fun lostObjectIdIsRepaired() {
        // The model drops "number three"; the safety layer must restore it.
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("dung bang chuyen so ba" to "Stop the conveyor."))
        )
        val decision = engine.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        assertTrue(decision is TranslationDecision.RepairedTranslation)
        val text = (decision as TranslationDecision.RepairedTranslation).text.lowercase()
        assertTrue("three" in text || "3" in text, "repaired text must restore the object id: $text")
        assertTrue("conveyor" in text, "repaired text must keep the object: $text")
    }

    @Test
    fun lostNegationIsRepaired() {
        // The model flips a prohibition into a permission; the safety layer must restore "do not".
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("khong vao khu vuc nguy hiem" to "Enter the hazard zone."))
        )
        val decision = engine.decide(goodAudio(), vi2en("khong vao khu vuc nguy hiem"))
        assertTrue(decision is TranslationDecision.RepairedTranslation)
        val text = (decision as TranslationDecision.RepairedTranslation).text.lowercase()
        assertTrue("do not" in text || "don't" in text, "repaired text must restore negation: $text")
    }

    @Test
    fun unrepairableActionChangeAsksRepeat() {
        // The model inverts the action (stop -> start). Insertion cannot fix this, so the
        // engine must refuse to speak and ask for a repeat.
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf("dung may" to "Start the machine."))
        )
        val decision = engine.decide(goodAudio(), vi2en("dung may"))
        assertTrue(decision is TranslationDecision.AskRepeat)
    }

    @Test
    fun droppedGenericActionAsksRepeatEvenWhenObjectSurvives() {
        val source = "mo cua"
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf(source to "The door."))
        )

        val decision = engine.decide(goodAudio(), vi2en(source))

        assertEquals(TranslationDecision.AskRepeat("safety_slot_lost:LOST_ACTION"), decision)
    }

    @Test
    fun translationMustNotInventSafetyCommandForOrdinarySource() {
        val source = "hom nay troi dep"
        val engine = DecisionEngine(
            translator = FakeTranslator(mapOf(source to "Stop the machine."))
        )

        val decision = engine.decide(goodAudio(), vi2en(source))

        assertEquals(TranslationDecision.AskRepeat("translation_added_safety_signal"), decision)
    }
}
