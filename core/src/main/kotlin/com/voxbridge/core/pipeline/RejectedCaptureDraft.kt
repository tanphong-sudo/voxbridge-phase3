package com.voxbridge.core.pipeline

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import java.util.concurrent.CancellationException

/** A bounded salvage pass may attach a draft, but can never accept a rejected capture. */
object RejectedCaptureDraft {
    /** Runtime failure fallback uses only this turn's source and the same bounded marker policy. */
    fun sourceOnly(transcript: TranscriptResult?, reason: String): TentativeOutput? {
        val source = transcript?.let { TentativeText(TentativeOutputConfig()).source(it.text) } ?: return null
        return TentativeOutput(source.text, rawSourceText = source.raw,
            containsUnknownSpans = source.unknown, sourceTruncated = source.truncated,
            unavailableReason = reason)
    }

    data class Result(
        val decision: TranslationDecision,
        val transcript: TranscriptResult? = null,
        val attempted: Boolean = false,
        val failure: String? = null,
    )

    fun shouldAttempt(
        decision: TranslationDecision, audio: AudioEvidence, sampleCount: Int, enabled: Boolean,
    ): Boolean = enabled && decision is TranslationDecision.AskRepeat &&
        decision.reason != "speech_too_long" && audio.speechPresent &&
        sampleCount in 1..CaptureAnalysis.maxCaptureSamples(16_000)

    fun attempt(
        decision: TranslationDecision,
        audio: AudioEvidence,
        sampleCount: Int,
        enabled: Boolean,
        decode: () -> TranscriptResult,
        attachDraft: (TranslationDecision.AskRepeat, TranscriptResult) -> TranslationDecision.AskRepeat,
    ): Result {
        if (!shouldAttempt(decision, audio, sampleCount, enabled)) return Result(decision)
        val rejection = decision as TranslationDecision.AskRepeat
        var transcript: TranscriptResult? = null
        return try {
            val heard = decode()
            transcript = heard
            val candidate = attachDraft(rejection, heard)
            Result(rejection.copy(draft = candidate.draft), heard, attempted = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val draft = sourceOnly(transcript, "draft_generation_failed")
            Result(rejection.copy(draft = draft), transcript, attempted = true,
                failure = failure.javaClass.simpleName)
        }
    }
}
