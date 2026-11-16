package com.voxbridge.core.asr

/** Agreement is a heuristic; native probabilities are usable here only after calibration. */
enum class AsrEvidenceKind { DECODE_COMPARISON, SIGNAL_VARIANT_COMPARISON, NATIVE_WORD_CONFIDENCE }

/** Offsets reference the exact primary transcript, never a normalized or reference transcript. */
data class AsrWordConfidence(val start: Int, val endExclusive: Int, val confidence: Double) {
    init {
        require(start >= 0 && endExclusive > start) { "Invalid word evidence range" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid word confidence" }
    }
}

/** Runtime provenance binds two actual observations; IDs are provenance, not calibrated scores. */
data class AsrReliabilityEvidence(
    val primaryText: String,
    val primaryConfigurationId: String,
    val originalAudioId: String,
    val primaryInputId: String,
    val kind: AsrEvidenceKind,
    val alternativeText: String? = null,
    val alternativeConfigurationId: String? = null,
    val alternativeInputId: String? = null,
    val alternativeOriginalAudioId: String? = null,
    val words: List<AsrWordConfidence> = emptyList(),
    val wordConfidencesCalibrated: Boolean = false,
    val provider: String? = null,
) {
    init {
        require(primaryText.length <= MAX_CHARACTERS && (alternativeText?.length ?: 0) <= MAX_CHARACTERS)
        require(words.size <= MAX_WORDS)
        require(listOf(primaryConfigurationId, originalAudioId, primaryInputId).all(::validId))
        require(listOfNotNull(alternativeConfigurationId, alternativeInputId, alternativeOriginalAudioId, provider).all(::validId))
    }

    companion object {
        private const val MAX_CHARACTERS = 50_000
        private const val MAX_WORDS = 4_096
        private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }
    }
}
