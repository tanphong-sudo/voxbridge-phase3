package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.learn.FeedbackPromoter
import com.voxbridge.core.learn.FeedbackType
import com.voxbridge.core.learn.LearningExample
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.learn.UserFeedback
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DecisionEngineMemoryTest {
    private val extractor = SafetyFrameExtractor()
    private val goodAudio = AudioEvidence(
        speechPresent = true,
        audioScore = 0.95,
        durationMs = 1_200,
        validSpeechDurationMs = 900,
    )

    private fun transcript(text: String) = TranscriptResult(
        sourceLanguage = Language.VI,
        targetLanguage = Language.EN,
        text = text,
        asrScore = 0.95,
    )

    private class CountingStore(
        var examples: List<LearningExample> = emptyList(),
        private val fail: Boolean = false,
    ) : LearningStore {
        var calls = 0
        override fun all(): List<LearningExample> {
            calls++
            if (fail) error("simulated store failure")
            return examples
        }
    }

    private class RecordingTranslator(
        private val outputs: Map<String, String>,
    ) : Translator {
        var calls = 0
        override fun translate(
            text: String,
            sourceLanguage: Language,
            targetLanguage: Language,
        ): TranslationResult {
            calls++
            return TranslationResult(
                sourceText = text,
                translatedText = outputs[text] ?: "fallback:$text",
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                modelName = "recording-test-mt",
            )
        }
    }

    private fun example(
        source: String,
        target: String,
        approvals: Int,
    ): LearningExample = LearningExample(
        normalizedSource = source,
        approvedTarget = target,
        sourceLanguage = Language.VI,
        targetLanguage = Language.EN,
        sourceFrame = extractor.extract(source, Language.VI),
        targetFrame = extractor.extract(target, Language.EN),
        feedbackType = FeedbackType.APPROVED,
        approvalCount = approvals,
    )

    @Test
    fun emptyMemoryBaselineStillPerformsLookupThenUsesMt() {
        val store = CountingStore()
        val translator = RecordingTranslator(mapOf("hom nay troi dep" to "The weather is nice today."))
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val decision = engine.decide(goodAudio, transcript("hom nay troi dep"))

        assertEquals(1, store.calls)
        assertEquals(1, translator.calls)
        assertIs<TranslationDecision.ModelTranslation>(decision)
    }

    @Test
    fun trustedMemoryHitBypassesMtAfterFreshSafetyCheck() {
        val source = "hom nay troi dep"
        val target = "The weather is nice today."
        val store = CountingStore(listOf(example(source, target, approvals = 2)))
        val translator = RecordingTranslator(emptyMap())
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val decision = engine.decide(goodAudio, transcript(source))

        assertEquals(1, store.calls)
        assertEquals(0, translator.calls)
        assertEquals(target, assertIs<TranslationDecision.MemoryTranslation>(decision).text)
    }

    @Test
    fun untrustedSafetyHitFallsThroughToMt() {
        val source = "dung bang chuyen so ba"
        val safeTarget = "Stop conveyor number three."
        // Two approvals can score above DIRECT_REUSE_THRESHOLD, but safety reuse requires three.
        val store = CountingStore(listOf(example(source, safeTarget, approvals = 2)))
        val translator = RecordingTranslator(mapOf(source to safeTarget))
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val decision = engine.decide(goodAudio, transcript(source))

        assertEquals(1, store.calls)
        assertEquals(1, translator.calls)
        assertIs<TranslationDecision.VerifiedTranslation>(decision)
    }

    @Test
    fun safetyConflictingTrustedHitFallsThroughToMt() {
        val source = "dung bang chuyen so ba"
        val store = CountingStore(
            listOf(example(source, "Start conveyor number three.", approvals = 3)),
        )
        val translator = RecordingTranslator(mapOf(source to "Stop conveyor number three."))
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val decision = engine.decide(goodAudio, transcript(source))

        assertEquals(1, store.calls)
        assertEquals(1, translator.calls)
        assertIs<TranslationDecision.VerifiedTranslation>(decision)
    }

    @Test
    fun storeFailureFallsThroughToMt() {
        val source = "hom nay troi dep"
        val store = CountingStore(fail = true)
        val translator = RecordingTranslator(mapOf(source to "The weather is nice today."))
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val decision = engine.decide(goodAudio, transcript(source))

        assertEquals(1, store.calls)
        assertEquals(1, translator.calls)
        assertIs<TranslationDecision.ModelTranslation>(decision)
    }

    @Test
    fun userApprovedCorrectionBecomesReusableMemoryAfterRequiredApprovals() {
        val source = "hom nay troi dep"
        val corrected = "The weather is lovely today."
        val sourceFrame = extractor.extract(source, Language.VI)
        val correctedFrame = extractor.extract(corrected, Language.EN)
        val promoter = FeedbackPromoter()

        fun feedback(at: Long) = UserFeedback(
            sourceText = source,
            targetText = "The weather is nice today.",
            approved = true,
            correctedTargetText = corrected,
            sourceLanguage = Language.VI,
            targetLanguage = Language.EN,
            decisionBeforeFeedback = "MODEL_TRANSLATION",
            createdAtMs = at,
        )

        val first = promoter.evaluate(feedback(1), sourceFrame, correctedFrame, existing = null)
        val hint = assertIs<FeedbackPromoter.PromotionDecision.HintOnly>(first).example
        val second = promoter.evaluate(feedback(2), sourceFrame, correctedFrame, existing = hint)
        val promoted = assertIs<FeedbackPromoter.PromotionDecision.Promote>(second).example
        assertTrue(promoted.lowRiskTrusted)

        val store = CountingStore(listOf(promoted))
        val translator = RecordingTranslator(mapOf(source to "should not run"))
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))
        val decision = engine.decide(goodAudio, transcript(source))

        assertEquals(1, store.calls)
        assertEquals(0, translator.calls)
        assertEquals(corrected, assertIs<TranslationDecision.MemoryTranslation>(decision).text)
    }

    @Test
    fun alternatingLanguagesSilenceAndRepeatedInputDoNotLeakDecisionState() {
        val vi = "hom nay troi dep"
        val en = "please wait here"
        val store = CountingStore()
        val translator = RecordingTranslator(
            mapOf(
                vi to "The weather is nice today.",
                en to "Vui lòng chờ ở đây.",
            ),
        )
        val engine = DecisionEngine(translator, LocalMemoryRetriever(store))

        val viTranscript = transcript(vi)
        val enTranscript = TranscriptResult(
            sourceLanguage = Language.EN,
            targetLanguage = Language.VI,
            text = en,
            asrScore = 0.95,
        )

        val firstVi = assertIs<TranslationDecision.ModelTranslation>(engine.decide(goodAudio, viTranscript))
        val silence = engine.decide(
            AudioEvidence(speechPresent = false, audioScore = 0.0, durationMs = 800),
            TranscriptResult(Language.EN, Language.VI, "", 0.95),
        )
        val middleEn = assertIs<TranslationDecision.ModelTranslation>(engine.decide(goodAudio, enTranscript))
        val repeatedVi = assertIs<TranslationDecision.ModelTranslation>(engine.decide(goodAudio, viTranscript))

        assertEquals("The weather is nice today.", firstVi.text)
        assertEquals(TranslationDecision.NoSpeech, silence)
        assertEquals("Vui lòng chờ ở đây.", middleEn.text)
        assertEquals(firstVi.text, repeatedVi.text)
        assertEquals(3, store.calls, "silence must terminate before memory lookup")
        assertEquals(3, translator.calls, "silence must terminate before MT")
    }
}
