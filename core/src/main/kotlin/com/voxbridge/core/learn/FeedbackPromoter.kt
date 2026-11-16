package com.voxbridge.core.learn

import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.UtteranceKey
import com.voxbridge.core.safety.SafetyFrame
import com.voxbridge.core.safety.SafetyFrameComparator
import com.voxbridge.core.translate.TranslationCoverage

/**
 * Decides whether a piece of feedback may be promoted into reusable local knowledge, and
 * guards against learning a correction that destroys safety meaning.
 *
 * Promotion policy:
 *  - 1 approval        -> stored as a hint only.
 *  - 2 approvals       -> usable as translation memory for low-risk sentences.
 *  - 3 approvals / admin review -> usable for safety-related sentences.
 *  - a rejection       -> increments rejectionCount and demotes (trust is reversible).
 *  - slot conflict     -> never promoted (would teach an unsafe correction).
 */
class FeedbackPromoter(
    private val comparator: SafetyFrameComparator = SafetyFrameComparator(),
    private val extractor: com.voxbridge.core.safety.SafetyFrameExtractor = com.voxbridge.core.safety.SafetyFrameExtractor()
) {
    sealed interface PromotionDecision {
        data class Promote(val example: LearningExample) : PromotionDecision
        data class HintOnly(val example: LearningExample) : PromotionDecision
        data class Demoted(val example: LearningExample) : PromotionDecision
        data class Blocked(val reason: String) : PromotionDecision
    }

    /**
     * Canonicalize the source so the stored `normalizedSource` is comparable to a live query.
     * Delegates to [UtteranceKey] — the ONE normalizer shared by the write side, the read side
     * and the ViewModel lookup; any divergence here would silently no-op the learning loop
     * (the Round-4 HIGH: the old inline `[^a-z0-9 ]` rule erased every CJK glyph, so ZH/KO
     * examples could never be matched again).
     */
    private fun normalizeSource(text: String, language: Language): String =
        UtteranceKey.normalize(text, language)

    fun evaluate(
        feedback: UserFeedback,
        sourceFrame: SafetyFrame?,
        correctedTargetFrame: SafetyFrame?,
        existing: LearningExample?
    ): PromotionDecision {
        val rawSource = feedback.correctedSourceText ?: feedback.sourceText
        val normalizedSource = normalizeSource(rawSource, feedback.sourceLanguage)

        // A rejection (not approved, no correction supplied) lowers confidence in any existing
        // example for this utterance. Trust is reversible: rejectionCount > 0 disqualifies an
        // example from both low-risk and safety reuse (see LearningExample.*Trusted).
        val isRejection = !feedback.approved &&
            feedback.correctedSourceText == null &&
            feedback.correctedTargetText == null
        if (isRejection) {
            val demoted = (existing ?: LearningExample(
                normalizedSource = normalizeSource(feedback.sourceText, feedback.sourceLanguage),
                approvedTarget = feedback.targetText,
                sourceLanguage = feedback.sourceLanguage,
                targetLanguage = feedback.targetLanguage,
                sourceFrame = sourceFrame,
                targetFrame = correctedTargetFrame,
                feedbackType = feedback.feedbackType,
                approvalCount = 0,
                rejectionCount = 0,
                lastUsedAtMs = feedback.createdAtMs
            )).copy(
                rejectionCount = (existing?.rejectionCount ?: 0) + 1,
                lastUsedAtMs = feedback.createdAtMs
            )
            return PromotionDecision.Demoted(demoted)
        }

        // Block a correction that drops or flips safety meaning relative to the source.
        val verifiedSourceFrame = (feedback.correctedSourceText ?: feedback.sourceText).let {
            extractor.extract(it, feedback.sourceLanguage)
        }
        val targetText = feedback.correctedTargetText ?: feedback.targetText
        // Caller frames may describe the old output. Re-extract from the exact text being saved.
        val verifiedCorrectedFrame = extractor.extract(targetText, feedback.targetLanguage)
        if (TranslationCoverage.isCollapsed(rawSource, targetText)) {
            return PromotionDecision.Blocked("collapsed_target")
        }
        if (verifiedSourceFrame.hasSafetySignal) {
            val comparison = comparator.compare(verifiedSourceFrame, verifiedCorrectedFrame)
            if (comparison.criticalViolations.isNotEmpty()) {
                return PromotionDecision.Blocked("slot_conflict:${comparison.criticalViolations.joinToString("|")}")
            }
        }
        if (comparator.addsSafetyMeaning(verifiedSourceFrame, verifiedCorrectedFrame)) {
            return PromotionDecision.Blocked("target_added_safety_signal")
        }

        val sameExample = existing != null && existing.normalizedSource == normalizedSource &&
            existing.approvedTarget == targetText && existing.sourceLanguage == feedback.sourceLanguage &&
            existing.targetLanguage == feedback.targetLanguage
        val priorApprovals = if (sameExample) existing!!.approvalCount else 0
        val approvalCount = priorApprovals + if (feedback.approved || feedback.correctedTargetText != null) 1 else 0
        val example = LearningExample(
            normalizedSource = normalizedSource,
            approvedTarget = targetText,
            correctedSource = feedback.correctedSourceText,
            sourceLanguage = feedback.sourceLanguage,
            targetLanguage = feedback.targetLanguage,
            sourceFrame = verifiedSourceFrame,
            targetFrame = verifiedCorrectedFrame,
            feedbackType = feedback.feedbackType,
            approvalCount = approvalCount,
            rejectionCount = if (sameExample) existing!!.rejectionCount else 0,
            lastUsedAtMs = feedback.createdAtMs
        )

        return if (approvalCount >= LearningExample.LOW_RISK_APPROVAL_THRESHOLD) {
            PromotionDecision.Promote(example)
        } else {
            PromotionDecision.HintOnly(example)
        }
    }
}
