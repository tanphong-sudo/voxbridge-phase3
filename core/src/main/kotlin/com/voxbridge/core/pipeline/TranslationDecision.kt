package com.voxbridge.core.pipeline

import com.voxbridge.core.safety.SafetyFrame
import com.voxbridge.core.safety.SafetyViolation

/**
 * The single output of the pipeline for one utterance.
 *
 * Model-first: the neural translation is the default. The safety layer only escalates to
 * VERIFIED / REPAIRED / ASK_REPEAT when real safety slots are at stake.
 */
sealed interface TranslationDecision {
    /** No valid speech was detected. */
    data object NoSpeech : TranslationDecision

    /** Unreliable: repeat is still required. A usable draft is spoken as unverified (red) output. */
    data class AskRepeat(val reason: String, val draft: TentativeOutput? = null) : TranslationDecision

    /** A high-confidence approved past translation was reused. */
    data class MemoryTranslation(val text: String, val memoryScore: Double) : TranslationDecision

    /** Plain neural translation; no safety-critical slot was at risk. */
    data class ModelTranslation(val text: String) : TranslationDecision

    /** Neural translation whose safety slots were checked and found preserved. */
    data class VerifiedTranslation(val text: String, val sourceFrame: SafetyFrame) : TranslationDecision

    /** Neural translation that dropped a safety slot and was repaired with high confidence. */
    data class RepairedTranslation(
        val text: String,
        val modelText: String,
        val repaired: List<SafetyViolation>
    ) : TranslationDecision
}
