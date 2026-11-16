package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TentativeOutputConfig
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TentativeOutputTest {
    private val goodAudio = AudioEvidence(true, .95, 1600)

    private fun transcript(text: String, score: Double = .2, noSpeech: Double? = null) = TranscriptResult(
        Language.VI, Language.EN, text, score, noSpeechProbability = noSpeech,
    )

    private class RecordingTranslator(private val output: (String) -> String = { "actual translation of:$it" }) : Translator {
        val inputs = mutableListOf<String>()
        override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
            inputs += text
            return TranslationResult(text, output(text), sourceLanguage, targetLanguage, "fixture-offline-mt")
        }
    }

    private fun engine(translator: Translator, config: TentativeOutputConfig = TentativeOutputConfig()) = DecisionEngine(
        translator = translator,
        config = DecisionConfig(tentativeOutputEnabled = true, tentativeOutputConfig = config),
    )

    @Test fun featureIsDefaultOffAndExistingRejectsRemainEqualWithoutMt() {
        val mt = RecordingTranslator()
        val disabled = DecisionEngine(mt)
        val rejection = TranslationDecision.AskRepeat("original_reason")
        assertSame(rejection, disabled.withTentativeOutput(rejection, transcript("dung may")))
        assertEquals(TranslationDecision.AskRepeat("asr_low_confidence"), disabled.decide(goodAudio, transcript("dung may")))
        assertTrue(mt.inputs.isEmpty())
    }

    @Test fun lowConfidenceRejectKeepsItsReasonAndActualSourceAndOneMtResult() {
        val mt = RecordingTranslator { "The weather is nice." }
        val raw = "hôm nay trời đẹp"
        val result = assertIs<TranslationDecision.AskRepeat>(engine(mt).decide(goodAudio, transcript(raw)))
        assertEquals("asr_low_confidence", result.reason)
        val draft = assertNotNull(result.draft)
        assertEquals(raw, draft.sourceText)
        assertEquals(raw, draft.rawSourceText)
        assertEquals("The weather is nice.", draft.translatedText)
        assertTrue(draft.wholeUtteranceUncertain)
        assertFalse(draft.containsUnknownSpans)
        assertFalse(draft.sourceTruncated)
        assertEquals(listOf(raw), mt.inputs)
    }

    @Test fun preAsrAskWithProvidedTranscriptCanCarryDraftWithoutChangingTheGate() {
        val mt = RecordingTranslator { "Observed output" }
        val engine = engine(mt)
        val badAudio = AudioEvidence(true, .19, 1600)
        assertEquals(TranslationDecision.AskRepeat("audio_low_quality"), engine.preAsrDecision(badAudio))
        val result = assertIs<TranslationDecision.AskRepeat>(engine.decide(badAudio, transcript("actual words", .95)))
        assertEquals("audio_low_quality", result.reason)
        assertEquals("Observed output", assertNotNull(result.draft).translatedText)
        assertEquals(listOf("actual words"), mt.inputs)
    }

    @Test fun noSpeechDecisionDoesNotTranslateEvenIfAnUntrustedTranscriptWasProvided() {
        val mt = RecordingTranslator()
        val result = engine(mt).decide(AudioEvidence(false, .05, 1600), transcript("hallucinated words"))
        assertEquals(TranslationDecision.NoSpeech, result)
        assertTrue(mt.inputs.isEmpty())
    }

    @Test fun likelyNoSpeechHypothesisRetainsTheSameAskReasonAndOnlyTentativeOutput() {
        val mt = RecordingTranslator()
        val result = assertIs<TranslationDecision.AskRepeat>(engine(mt).decide(
            goodAudio, transcript("actual ASR text", .95, .9),
        ))
        assertEquals("likely_no_speech_hallucination", result.reason)
        assertTrue(assertNotNull(result.draft).wholeUtteranceUncertain)
        assertEquals(1, mt.inputs.size)
    }

    @Test fun earlyDraftNeverLooksUpTranslationMemory() {
        var memoryCalls = 0
        val mt = RecordingTranslator()
        val engine = DecisionEngine(mt, LocalMemoryRetriever(LearningStore { memoryCalls++; emptyList() }),
            config = DecisionConfig(tentativeOutputEnabled = true))
        assertNotNull(assertIs<TranslationDecision.AskRepeat>(engine.decide(goodAudio, transcript("uncertain text"))).draft)
        assertEquals(0, memoryCalls)
        assertEquals(1, mt.inputs.size)
    }

    @Test fun safetyRejectReusesTheExistingActualModelOutputWithoutSecondMt() {
        val mt = RecordingTranslator { "Start the machine." }
        val result = assertIs<TranslationDecision.AskRepeat>(engine(mt).decide(goodAudio, transcript("dung may", .95)))
        assertTrue(result.reason.startsWith("safety_slot_lost:"))
        assertEquals("Start the machine.", assertNotNull(result.draft).translatedText)
        assertEquals(listOf("dung may"), mt.inputs)
    }

    @Test fun degenerateModelTranslationKeepsSourceOnlyWithoutAnotherMtCall() {
        val repeated = List(8) { "No" }.joinToString(", ")
        val mt = RecordingTranslator { repeated }
        val result = assertIs<TranslationDecision.AskRepeat>(engine(mt).decide(goodAudio, transcript("hôm nay trời đẹp", .95)))
        assertEquals("translation_degenerate", result.reason)
        assertEquals("hôm nay trời đẹp", assertNotNull(result.draft).sourceText)
        assertNull(result.draft?.translatedText)
        assertEquals("translation_unusable", result.draft?.unavailableReason)
        assertEquals(1, mt.inputs.size)
    }

    @Test fun blankOrPunctuationOrMarkerOnlySourceNeverInventsWordsOrInvokesMt() {
        val mt = RecordingTranslator()
        val engine = engine(mt)
        for (text in listOf("", "   ", "...?!", "[mask]", "<unk> [INAUDIBLE] [unclear]")) {
            val reject = TranslationDecision.AskRepeat("keep_me")
            assertSame(reject, engine.withTentativeOutput(reject, transcript(text)))
        }
        assertTrue(mt.inputs.isEmpty())
        assertNull(assertIs<TranslationDecision.AskRepeat>(engine.decide(goodAudio, transcript(""))).draft)
    }

    @Test fun explicitMarkersNormalizeWithoutGuessingNegationNumberOrId() {
        val mt = RecordingTranslator { it }
        val raw = "Do not open MC-07 at [mask] V; valve <UNK>."
        val result = engine(mt).withTentativeOutput(TranslationDecision.AskRepeat("uncertain"), transcript(raw))
        val draft = assertNotNull(result.draft)
        assertEquals("Do not open MC-07 at [unclear] V; valve [unclear].", draft.sourceText)
        assertEquals(raw, draft.rawSourceText)
        assertEquals(draft.sourceText, draft.translatedText)
        assertTrue(draft.containsUnknownSpans)
        assertTrue(draft.wholeUtteranceUncertain)
        assertEquals(listOf(draft.sourceText), mt.inputs)
    }

    @Test fun markerVocabularyIsConfigurableAndNaturalWordsAreNotMaskedByConfidence() {
        val mt = RecordingTranslator { "actual target [missing] span" }
        val engine = engine(mt, TentativeOutputConfig(unknownMarkers = setOf("[missing]")))
        val result = engine.withTentativeOutput(TranslationDecision.AskRepeat("uncertain"), transcript("not 15 B3 [missing]"))
        val draft = assertNotNull(result.draft)
        assertEquals("not 15 B3 [unclear]", draft.sourceText)
        assertEquals("actual target [unclear] span", draft.translatedText)
        assertTrue(draft.containsUnknownSpans)
    }

    @Test fun ordinaryDraftMtFailureKeepsActualSourceAndOriginalAskReason() {
        val mt = RecordingTranslator { throw IllegalStateException("private native diagnostic") }
        val result = engine(mt).withTentativeOutput(TranslationDecision.AskRepeat("audio_clipped"), transcript("actual source"))
        assertEquals("audio_clipped", result.reason)
        val draft = assertNotNull(result.draft)
        assertEquals("actual source", draft.sourceText)
        assertNull(draft.translatedText)
        assertEquals("translation_failed", draft.unavailableReason)
        assertEquals(1, mt.inputs.size)
    }

    @Test fun cancellationIsNotSwallowedByDraftGeneration() {
        val mt = RecordingTranslator { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> {
            engine(mt).withTentativeOutput(TranslationDecision.AskRepeat("uncertain"), transcript("actual source"))
        }
        assertEquals(1, mt.inputs.size)
    }

    @Test fun existingBlankOrUnusableTargetIsNotTranslatedAgain() {
        val mt = RecordingTranslator()
        val engine = engine(mt)
        for (target in listOf("", "...", "[unk]", "x x x x x x")) {
            val result = engine.withTentativeOutput(TranslationDecision.AskRepeat("existing_mt_failed"),
                transcript("actual source"), existingTranslation = target)
            assertNull(assertNotNull(result.draft).translatedText)
            assertEquals("existing_mt_failed", result.reason)
        }
        assertTrue(mt.inputs.isEmpty())
    }

    @Test fun actualSourceAndTargetAreBoundedAndTruncationRemainsExplicit() {
        val mt = RecordingTranslator { "An actual translation that exceeds the configured display limit" }
        val config = TentativeOutputConfig(maxSourceCharacters = 24, maxTranslatedCharacters = 16)
        val raw = "not open valve 15 B3 " + "real " .repeat(30)
        val result = engine(mt, config).withTentativeOutput(TranslationDecision.AskRepeat("audio_limit"), transcript(raw))
        val draft = assertNotNull(result.draft)
        assertTrue(draft.sourceText.length <= 24)
        assertTrue(draft.rawSourceText.length <= 24)
        assertTrue(raw.startsWith(draft.rawSourceText))
        assertTrue(draft.sourceTruncated)
        assertNull(draft.translatedText)
        assertEquals("translation_too_long", draft.unavailableReason)
        assertEquals(listOf(draft.sourceText), mt.inputs)
    }

    @Test fun suppliedTruncationFlagIsPreservedAndDraftAttachmentIsIdempotent() {
        val mt = RecordingTranslator()
        val engine = engine(mt)
        val first = engine.withTentativeOutput(TranslationDecision.AskRepeat("capture_limit"),
            transcript("actual partial source"), sourceTruncated = true)
        assertTrue(assertNotNull(first.draft).sourceTruncated)
        assertSame(first, engine.withTentativeOutput(first, transcript("actual partial source")))
        assertEquals(1, mt.inputs.size)
    }

    @Test fun normalizedTargetExpansionCannotExceedTheDisplayBound() {
        val mt = RecordingTranslator { "a [unk] b" }
        val result = engine(mt, TentativeOutputConfig(maxTranslatedCharacters = 10))
            .withTentativeOutput(TranslationDecision.AskRepeat("uncertain"), transcript("actual source"))
        val draft = assertNotNull(result.draft)
        assertNull(draft.translatedText)
        assertEquals("translation_too_long", draft.unavailableReason)
        assertTrue(draft.containsUnknownSpans)
        assertEquals(1, mt.inputs.size)
    }

    @Test fun acceptedModelAndVerifiedSafetyDecisionsAreUnchangedWhenEnabled() {
        val ordinary = RecordingTranslator { "The weather is nice today." }
        assertEquals(TranslationDecision.ModelTranslation("The weather is nice today."),
            engine(ordinary).decide(goodAudio, transcript("hom nay troi dep", .95)))
        assertEquals(1, ordinary.inputs.size)
        val safety = RecordingTranslator { "Stop conveyor belt number three." }
        assertIs<TranslationDecision.VerifiedTranslation>(engine(safety).decide(goodAudio, transcript("dung bang chuyen so ba", .95)))
        assertEquals(1, safety.inputs.size)
    }

    @Test fun globalUncertaintyCannotBeDisabledOnTheDataObject() {
        val draft = TentativeOutput("actual source", "actual target")
        assertTrue(draft.wholeUtteranceUncertain)
        assertTrue(draft.copy(translatedText = "another actual target").wholeUtteranceUncertain)
    }

    @Test fun markerConfigCannotTreatDomainWordsAsMissingSpans() {
        assertFailsWith<IllegalArgumentException> { TentativeOutputConfig(unknownMarkers = setOf("not")) }
        assertFailsWith<IllegalArgumentException> { TentativeOutputConfig(maxSourceCharacters = 0) }
        assertFailsWith<IllegalArgumentException> { TentativeOutputConfig(maxTranslatedCharacters = 0) }
    }
}
