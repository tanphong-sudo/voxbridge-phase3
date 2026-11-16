package com.voxbridge.core.audio

/**
 * Evidence about a captured push-to-talk utterance, produced before ASR.
 *
 * The pipeline uses this to gate out silence, noise, clipping, and utterances
 * that are too short to be a reliable safety command. Gating before ASR is the
 * first defence against Whisper no-speech hallucination.
 */
data class AudioEvidence(
    val speechPresent: Boolean,
    val audioScore: Double,
    val durationMs: Long,
    val tooNoisy: Boolean = false,
    val clippingDetected: Boolean = false,
    val validSpeechDurationMs: Long? = null,
    val silenceRatio: Double? = null
) {
    init {
        require(audioScore in 0.0..1.0) { "audioScore must be between 0 and 1" }
        require(durationMs >= 0) { "durationMs must be non-negative" }
    }
}
