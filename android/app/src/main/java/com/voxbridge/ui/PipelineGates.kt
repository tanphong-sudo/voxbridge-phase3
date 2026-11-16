package com.voxbridge.ui

import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision

/**
 * Product-level pre-ASR gate. Silero VAD is authoritative for whether ASR is invoked at all.
 * Keeping this predicate explicit prevents a regression where a no-speech capture is still sent
 * to PhoWhisper, whose fine-tuned no-speech head can hallucinate text on digital silence.
 */
internal fun preAsrDecision(
    engine: DecisionEngine,
    audio: AudioEvidence,
    captureLimitReached: Boolean = false,
): TranslationDecision? = engine.preAsrDecision(audio, captureLimitReached)

/** A new PTT turn may start only when no earlier turn is still being processed. */
internal fun canStartCapture(pipelineStage: String, listening: Boolean): Boolean =
    pipelineStage == "READY" && !listening

/**
 * Treat clipping as a sustained signal-quality failure, not a single hot transient.
 *
 * PCM speech can legitimately touch the near-full-scale threshold for a handful of samples.
 * Rejecting an entire utterance on `clippingCount > 0` made the audio gate hypersensitive to
 * normal microphone/AGC peaks. A clipped utterance must contain both a minimum number of hot
 * samples and a material fraction of the capture.
 */
internal fun isMeaningfullyClipped(clippingCount: Int, sampleCount: Int): Boolean {
    return CaptureAnalysis.isMeaningfullyClipped(clippingCount, sampleCount)
}
