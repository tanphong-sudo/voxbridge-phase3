package com.voxbridge.core.pipeline

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.safety.SafetyFrameComparator
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.safety.SafetyRepairer
import com.voxbridge.core.safety.RepairOutcome
import com.voxbridge.core.normalize.RepeatCollapser
import com.voxbridge.core.translate.ConstructionGlossary
import com.voxbridge.core.normalize.ViPunctuationRestorer
import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationCoverage
import com.voxbridge.core.translate.Translator
import java.util.concurrent.CancellationException

/**
 * Tunable gates for the pipeline. Defaults match the design's initial thresholds and are
 * meant to be re-calibrated from the benchmark set.
 */
data class DecisionConfig(
    val minAudioScore: Double = 0.15,
    val askRepeatAudioScore: Double = 0.20,
    val minSpeechDurationMs: Long = 250,
    val minAsrScore: Double = 0.60,
    val noSpeechProbabilityMax: Double = 0.60,
    /** Experimental: VI MT input only; enable after real-ASR semantic review. */
    val restoreViPunctuation: Boolean = false,
    /** Drop speaker stutter before MT. */
    val collapseRepeats: Boolean = true,
    /** Pin construction trade terms in VI->EN MT input. */
    val pinConstructionTerms: Boolean = true,
    val tentativeOutputEnabled: Boolean = false,
    val tentativeOutputConfig: TentativeOutputConfig = TentativeOutputConfig(),
    val wordImportanceEnabled: Boolean = false,
    val wordImportanceConfig: WordImportanceConfig = WordImportanceConfig(),
    val bestEffortRecoveryEnabled: Boolean = false,
    val draftRecoveryConfig: DraftRecoveryConfig = DraftRecoveryConfig(),
)

/**
 * Model-first decision engine.
 *
 * The neural [Translator] owns translation. The safety layer only verifies the result and
 * repairs it when a safety-critical slot was lost and the repair clears strict gates. When
 * neither verification nor repair can be trusted, the engine asks the speaker to repeat
 * rather than speak a possibly-dangerous translation.
 *
 * Order: no-speech -> audio gate -> ASR reliability gate -> local memory -> neural MT
 *        -> output collapse gate -> safety frame compare -> verified | repaired | ask-repeat | model.
 */
class DecisionEngine(
    private val translator: Translator,
    private val memory: LocalMemoryRetriever? = null,
    private val extractor: SafetyFrameExtractor = SafetyFrameExtractor(),
    private val comparator: SafetyFrameComparator = SafetyFrameComparator(),
    private val repairer: SafetyRepairer = SafetyRepairer(),
    private val config: DecisionConfig = DecisionConfig(),
    private val fallbackTranslator: Translator? = null
) {
    private val tentativeText = TentativeText(config.tentativeOutputConfig)
    private val wordImportance = WordImportancePolicy(config.wordImportanceConfig)
    private val draftRecovery by lazy {
        BestEffortDraftRecovery(translator, fallbackTranslator,
            config.draftRecoveryConfig.copy(textConfig = config.tentativeOutputConfig))
    }


    /**
     * Attach an unverified display-only draft to an existing rejection. It never changes its
     * reason, consults memory, repairs content, accepts output, or emits speech side effects.
     * A supplied MT result (even blank) is reused so a rejected translation is not generated twice.
     */
    fun withTentativeOutput(
        rejection: TranslationDecision.AskRepeat,
        transcript: TranscriptResult,
        existingTranslation: String? = null,
        sourceTruncated: Boolean = false,
        priorTranslationCalls: Int = if (existingTranslation == null) 0 else 1,
        existingPrimaryHitTokenLimit: Boolean = false,
        checkActive: () -> Unit = {},
    ): TranslationDecision.AskRepeat {
        val attached = attachDraft(rejection, transcript, existingTranslation, sourceTruncated,
            priorTranslationCalls, existingPrimaryHitTokenLimit, checkActive)
        val draft = attached.draft ?: return attached
        val marked = withUncertainWords(draft, transcript)
        return if (marked === draft) attached else attached.copy(draft = marked)
    }

    /** Marks only words two actual observations disagreed on; never guesses which other words are right. */

    private fun observedEvidence(
        primary: TranscriptResult,
        observe: ((TranscriptResult) -> TranscriptResult)?,
        checkActive: () -> Unit,
    ): TranscriptResult {
        if (observe == null || !(config.wordImportanceEnabled || config.bestEffortRecoveryEnabled)) return primary
        return try {
            checkActive()
            observe(primary).takeIf { it.text == primary.text && it.sourceLanguage == primary.sourceLanguage &&
                it.targetLanguage == primary.targetLanguage } ?: primary
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            primary // A failed optional observation adds no evidence and never changes the decision.
        }
    }

    private fun withUncertainWords(draft: TentativeOutput, transcript: TranscriptResult): TentativeOutput {
        if (!config.wordImportanceEnabled || transcript.reliability == null) return draft
        val side = when (draft.sourceOrigin) {
            "asr_primary" -> WordEvidenceSide.PRIMARY
            "asr_secondary" -> WordEvidenceSide.ALTERNATIVE
            else -> return draft
        }
        val words = wordImportance.assess(transcript).spans
            .filter { it.side == side && it.uncertain == true && it.category != WordImportance.LOW }
            .map { it.text }.distinct().take(MAX_UNCERTAIN_WORDS)
        return if (words.isEmpty()) draft else draft.copy(uncertainWords = words)
    }

    private fun attachDraft(
        rejection: TranslationDecision.AskRepeat,
        transcript: TranscriptResult,
        existingTranslation: String?,
        sourceTruncated: Boolean,
        priorTranslationCalls: Int,
        existingPrimaryHitTokenLimit: Boolean,
        checkActive: () -> Unit,
    ): TranslationDecision.AskRepeat {
        if (!config.tentativeOutputEnabled) return rejection
        checkActive()
        if (config.bestEffortRecoveryEnabled) {
            val alternatives = wordImportance.validatedAlternativeText(transcript)?.let {
                listOf(DraftSourceCandidate(it, "asr_secondary"))
            }.orEmpty()
            val recovered = draftRecovery.recover(transcript, existingTranslation, alternatives,
                sourceTruncated, priorTranslationCalls, existingPrimaryHitTokenLimit, checkActive)
            return rejection.copy(draft = recovered.draft ?: rejection.draft)
        }
        rejection.draft?.let { draft ->
            return if (sourceTruncated && !draft.sourceTruncated)
                rejection.copy(draft = draft.copy(sourceTruncated = true)) else rejection
        }
        val source = tentativeText.source(transcript.text) ?: return rejection
        val originalDraft = TentativeOutput(source.text, rawSourceText = source.raw,
            containsUnknownSpans = source.unknown, sourceTruncated = sourceTruncated || source.truncated)
        val modelText = try {
            existingTranslation ?: translator.translate(source.text, transcript.sourceLanguage, transcript.targetLanguage).translatedText
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejection.copy(draft = originalDraft.copy(unavailableReason = "translation_failed"))
        }
        if (modelText.length > config.tentativeOutputConfig.maxTranslatedCharacters) {
            return rejection.copy(draft = originalDraft.copy(unavailableReason = "translation_too_long"))
        }
        val normalized = tentativeText.normalize(modelText)
        if (normalized.text.length > config.tentativeOutputConfig.maxTranslatedCharacters) {
            return rejection.copy(draft = originalDraft.copy(
                containsUnknownSpans = source.unknown || normalized.unknown,
                unavailableReason = "translation_too_long"))
        }
        val usable = tentativeText.hasWords(normalized.text) &&
            !TranslationCoverage.isCollapsed(source.text, normalized.text)
        val draft = originalDraft.copy(
            translatedText = normalized.text.takeIf { usable },
            containsUnknownSpans = source.unknown || normalized.unknown,
            unavailableReason = if (usable) null else "translation_unusable",
        )
        return rejection.copy(draft = draft)
    }

    /**
     * Classify capture evidence before ASR using this engine's exact [DecisionConfig]. A null
     * result permits the accepted translation path; a non-null result keeps that path rejected.
     * An explicitly enabled, bounded salvage pass may still decode only to attach an unverified
     * draft to AskRepeat. Android and host harnesses share this gate so thresholds cannot drift.
     */
    fun preAsrDecision(
        audio: AudioEvidence,
        captureLimitReached: Boolean = false,
    ): TranslationDecision? = when (
        val gate = CaptureAnalysis.preAsrGate(
            audio = audio,
            captureLimitReached = captureLimitReached,
            minAudioScore = config.minAudioScore,
            askRepeatAudioScore = config.askRepeatAudioScore,
            minSpeechDurationMs = config.minSpeechDurationMs,
        )
    ) {
        CaptureAnalysis.PreAsrGate.RunAsr -> null
        CaptureAnalysis.PreAsrGate.NoSpeech -> TranslationDecision.NoSpeech
        is CaptureAnalysis.PreAsrGate.AskRepeat -> TranslationDecision.AskRepeat(gate.reason)
    }

    fun decide(
        audio: AudioEvidence,
        transcript: TranscriptResult,
        checkActive: () -> Unit = {},
        /**
         * Optional second ASR observation, invoked at most once and only when this turn is about
         * to be rejected. It must return the same primary text with attached evidence; accepted
         * turns never pay for it.
         */
        observeAlternative: ((TranscriptResult) -> TranscriptResult)? = null,
    ): TranslationDecision {
        checkActive()
        val evidence by lazy { observedEvidence(transcript, observeAlternative, checkActive) }
        // 1-2. Shared no-speech/audio-quality gate. Android runs the same predicate before ASR;
        // DecisionEngine repeats it defensively for direct callers and tests.
        preAsrDecision(audio)?.let {
            return if (it is TranslationDecision.AskRepeat) withTentativeOutput(it, evidence, checkActive = checkActive) else it
        }

        // 3. ASR reliability gate, unchanged from the baseline. Word importance and the second ASR
        //    observation run only where the baseline would already ask to repeat.
        if (transcript.isBlank) return TranslationDecision.AskRepeat("empty_transcript")
        // minAsrScore is a threshold in the benchmark-CALIBRATED 0..1 score space. Comparing a
        // raw exp(mean token log-probability) against 0.60 is dimensionally wrong and caused real
        // speech to be rejected as "low confidence". Until a calibration mapping is installed,
        // keep the raw value as diagnostics and rely on the independent audio/no-speech gates.
        if (transcript.asrScoreCalibrated && transcript.asrScore < config.minAsrScore) {
            // Every baseline rejection stays a rejection; the second ASR observation and word
            // importance only help build and annotate its spoken red draft.
            return withTentativeOutput(TranslationDecision.AskRepeat("asr_low_confidence"), evidence,
                checkActive = checkActive)
        }
        if ((transcript.noSpeechProbability ?: 0.0) > config.noSpeechProbabilityMax) {
            return withTentativeOutput(TranslationDecision.AskRepeat("likely_no_speech_hallucination"),
                evidence, checkActive = checkActive)
        }

        val sourceFrame = extractor.extract(transcript.text, transcript.sourceLanguage)

        // 4. Local memory: reuse an approved past translation only if the match is strong, the
        //    example is TRUSTED at the right level (low-risk needs LOW_RISK_APPROVAL_THRESHOLD,
        //    safety needs SAFETY_APPROVAL_THRESHOLD + no rejection), AND the replayed target still
        //    passes the live safety frame check. A store fault must never crash decide(): on any
        //    failure we log nothing and fall through to neural MT (model-first is the safe default).
        val best = runCatching {
            memory?.retrieve(transcript.text, sourceFrame, transcript.sourceLanguage, transcript.targetLanguage)
                ?.firstOrNull()
        }.getOrNull()
        if (best != null && best.score >= LocalMemoryRetriever.DIRECT_REUSE_THRESHOLD) {
            val trusted = if (sourceFrame.hasSafetySignal) best.example.safetyTrusted else best.example.lowRiskTrusted
            if (trusted) {
                // Submission_Final requires every memory replay to receive a fresh safety check.
                // Run it even for low-risk source frames: today that comparison is normally empty,
                // but keeping the check unconditional prevents a future extractor/comparator rule
                // from silently creating a bypass on the memory branch.
                val replayedFrame = extractor.extract(best.example.approvedTarget, transcript.targetLanguage)
                val reuseOk = comparator.compare(sourceFrame, replayedFrame).preserved
                if (reuseOk && !TranslationCoverage.isCollapsed(transcript.text, best.example.approvedTarget) &&
                    !comparator.addsSafetyMeaning(sourceFrame, replayedFrame)) {
                    return TranslationDecision.MemoryTranslation(best.example.approvedTarget, best.score)
                }
                // Memory hit failed the live safety check -> fall through to MT + verify.
            }
        }

        // 5. Neural translation (the default path).
        val fluent = if (config.collapseRepeats) RepeatCollapser.collapse(transcript.text, transcript.sourceLanguage)
            else transcript.text
        val punctuated = if (config.restoreViPunctuation && transcript.sourceLanguage == Language.VI) {
            ViPunctuationRestorer.restore(fluent)
        } else fluent
        val mtInput = if (config.pinConstructionTerms && transcript.sourceLanguage == Language.VI &&
            transcript.targetLanguage == Language.EN) ConstructionGlossary.pinViToEn(punctuated) else punctuated
        val translation = try {
            checkActive()
            translator.translate(mtInput, transcript.sourceLanguage, transcript.targetLanguage)
                .also { checkActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!config.bestEffortRecoveryEnabled) throw failure
            return withTentativeOutput(TranslationDecision.AskRepeat("translation_failed"), evidence,
                priorTranslationCalls = 1, checkActive = checkActive)
        }
        val modelText = translation.translatedText

        // A fluent prefix can still be semantically incomplete when the fixed decoder budget is
        // exhausted. Treat that signal as terminal before safety checks or TTS can expose it.
        // The capped output is a decoder loop, never reused as a draft; only bounded recovery
        // (another model / actual alternative ASR) may supply one.
        if (translation.hitTokenLimit) {
            return if (config.bestEffortRecoveryEnabled) withTentativeOutput(
                TranslationDecision.AskRepeat("translation_token_limit"), evidence,
                priorTranslationCalls = 1, checkActive = checkActive,
            ) else TranslationDecision.AskRepeat("translation_token_limit")
        }

        // Fail closed on decoder collapse before any output branch can speak. This gate only
        // checks blank/single-token/dominant phrase loops. Omission heuristics and retry remain
        // diagnostic-only; use the original transcript to preserve genuine source repetition.
        if (TranslationCoverage.isCollapsed(transcript.text, modelText)) {
            return withTentativeOutput(
                TranslationDecision.AskRepeat("translation_degenerate"), evidence, modelText,
                checkActive = checkActive,
            )
        }

        val targetFrame = extractor.extract(modelText, transcript.targetLanguage)
        if (comparator.addsSafetyMeaning(sourceFrame, targetFrame)) {
            return if (config.bestEffortRecoveryEnabled) withTentativeOutput(
                TranslationDecision.AskRepeat("translation_added_safety_signal"), evidence,
                priorTranslationCalls = 1, checkActive = checkActive,
            ) else TranslationDecision.AskRepeat("translation_added_safety_signal")
        }

        // 7. If the source has no safety signal, ship the neural translation as-is.
        if (!sourceFrame.hasSafetySignal) {
            return TranslationDecision.ModelTranslation(modelText)
        }

        // 8. Verify the translation preserved the safety slots.
        val comparison = comparator.compare(sourceFrame, targetFrame)
        if (comparison.preserved) {
            return TranslationDecision.VerifiedTranslation(modelText, sourceFrame)
        }

        // 9. A slot was lost. Attempt constrained repair.
        val outcome = repairer.repair(
            modelTranslation = modelText,
            sourceFrame = sourceFrame,
            comparison = comparison,
            targetLanguage = transcript.targetLanguage
        )
        return when (outcome) {
            is RepairOutcome.Repaired ->
                TranslationDecision.RepairedTranslation(outcome.text, modelText, comparison.violations)
            is RepairOutcome.Rejected ->
                // 10. Could not repair a safety-critical loss -> ask to repeat rather than risk it.
                withTentativeOutput(TranslationDecision.AskRepeat(
                    "safety_slot_lost:${comparison.criticalViolations.joinToString("|")}"), evidence, modelText,
                    checkActive = checkActive)
        }
    }

}

private const val MAX_UNCERTAIN_WORDS = 32
