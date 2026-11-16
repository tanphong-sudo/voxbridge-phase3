package com.voxbridge.core.asr

import com.voxbridge.core.model.Language

/**
 * One actual ASR observation, optionally accompanied by bounded runtime evidence.
 * Raw decoder scores are diagnostics unless benchmark-calibrated. An alternative must retain
 * its own configuration/input provenance; agreement is a heuristic rather than calibration.
 */
data class TranscriptResult(
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val text: String,
    val asrScore: Double,
    val noSpeechProbability: Double? = null,
    /** False when [asrScore] is a raw decoder-derived diagnostic rather than benchmark-calibrated. */
    val asrScoreCalibrated: Boolean = true,
    /** Actual alternative/native word evidence. Absence never means that missing words are safe. */
    val reliability: AsrReliabilityEvidence? = null,
) {
    init {
        require(asrScore in 0.0..1.0) { "asrScore must be between 0 and 1" }
        noSpeechProbability?.let {
            require(it in 0.0..1.0) { "noSpeechProbability must be between 0 and 1" }
        }
    }

    val isBlank: Boolean get() = text.isBlank()
}
