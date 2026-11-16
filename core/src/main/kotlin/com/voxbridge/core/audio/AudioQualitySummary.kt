package com.voxbridge.core.audio

/** Guidance only: no quality state is permission to reject audio or change ASR/translation. */
enum class AudioQualityState { NO_SPEECH, GOOD, TOO_QUIET, TOO_NOISY, CLIPPING }

/** Immutable scalar observation; it never owns the reusable PCM/VAD frame. */
data class AudioQualitySnapshot(
    val state: AudioQualityState,
    val rmsDbfs: Double,
    val noiseFloorDbfs: Double?,
    /** Speech-frame mixture-to-background contrast proxy, NOT physical source-separated SNR. */
    val snrProxyDb: Double?,
    val peak: Float,
    val clippingRatio: Double,
    val vadProbability: Float?,
    val monitoredDurationMs: Long,
    val noiseReady: Boolean,
)

/**
 * Ratios use instantaneous frame classifications, weighted by observed sample count. The UI's
 * hysteresis is intentionally excluded so short bad intervals remain visible for calibration.
 * Means are sample-weighted frame RMS (linear amplitude) or contrast (dB); null means no evidence.
 * The noise mean/floor exclude clipped frames even if VAD calls them non-speech.
 * Duration covers only samples submitted to observe(), not an unobserved incomplete VAD tail.
 */
data class AudioQualitySummary(
    val monitoredDurationMs: Long,
    val speechRmsMean: Double?,
    val noiseRmsMean: Double?,
    val snrProxyMean: Double?,
    val clippingRatio: Double,
    val goodRatio: Double,
    val quietRatio: Double,
    val noisyRatio: Double,
    val clippingStateRatio: Double,
    val noSpeechRatio: Double,
    val finalAudioQuality: AudioQualityState,
    val vadAvailable: Boolean,
    val analyzedSamples: Long,
)
