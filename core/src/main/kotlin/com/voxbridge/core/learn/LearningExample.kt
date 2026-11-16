package com.voxbridge.core.learn

import com.voxbridge.core.model.Language
import com.voxbridge.core.safety.SafetyFrame

/** How the user responded to an output. */
enum class FeedbackType { APPROVED, EDITED_SOURCE, EDITED_TRANSLATION, EDITED_BOTH }

/**
 * One piece of approve/edit feedback captured after an output. Stored in the on-device
 * database only — never uploaded in the competition build.
 */
data class UserFeedback(
    val sourceText: String,
    val targetText: String,
    val approved: Boolean,
    val correctedSourceText: String? = null,
    val correctedTargetText: String? = null,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val decisionBeforeFeedback: String,
    val createdAtMs: Long
) {
    val feedbackType: FeedbackType
        get() = when {
            correctedSourceText != null && correctedTargetText != null -> FeedbackType.EDITED_BOTH
            correctedSourceText != null -> FeedbackType.EDITED_SOURCE
            correctedTargetText != null -> FeedbackType.EDITED_TRANSLATION
            else -> FeedbackType.APPROVED
        }
}

/**
 * A promoted, reusable piece of local knowledge derived from feedback. Lives in the
 * on-device store and is consulted before the neural model on each utterance.
 */
data class LearningExample(
    val normalizedSource: String,
    val approvedTarget: String,
    val correctedSource: String? = null,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val sourceFrame: SafetyFrame? = null,
    val targetFrame: SafetyFrame? = null,
    val feedbackType: FeedbackType,
    val approvalCount: Int = 1,
    val rejectionCount: Int = 0,
    val lastUsedAtMs: Long = 0L
) {
    /** Whether this example is trusted enough to be reused for safety-critical sentences. */
    val safetyTrusted: Boolean
        get() = approvalCount >= SAFETY_APPROVAL_THRESHOLD && rejectionCount == 0

    /** Whether this example is trusted enough to be reused for ordinary low-risk sentences. */
    val lowRiskTrusted: Boolean
        get() = approvalCount >= LOW_RISK_APPROVAL_THRESHOLD && rejectionCount == 0

    companion object {
        const val LOW_RISK_APPROVAL_THRESHOLD = 2
        const val SAFETY_APPROVAL_THRESHOLD = 3
    }
}

/** A scored retrieval hit from the local memory. */
data class MemoryMatch(
    val example: LearningExample,
    val score: Double
)
