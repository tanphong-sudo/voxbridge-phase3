package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class RejectedCaptureDraftTest {
    private val speech = AudioEvidence(true, .18, 1000)
    private val rejection = TranslationDecision.AskRepeat("audio_low_quality")
    private fun heard(text: String = "let me go") = TranscriptResult(Language.EN, Language.VI, text, .2)

    @Test fun oneBoundedAttemptKeepsTheOriginalRejection() {
        var decodes = 0
        var translations = 0
        val result = RejectedCaptureDraft.attempt(rejection, speech, 16000, enabled = true,
            decode = { decodes++; heard() },
            attachDraft = { ask, transcript ->
                translations++
                ask.copy(draft = TentativeOutput(transcript.text, "hãy để tôi đi"))
            })
        assertTrue(result.attempted)
        assertEquals(1, decodes)
        assertEquals(1, translations)
        assertEquals(rejection.reason, (result.decision as TranslationDecision.AskRepeat).reason)
        assertEquals("let me go", result.transcript?.text)
        assertEquals("hãy để tôi đi", result.decision.draft?.translatedText)
    }

    @Test fun silenceDisabledAndOversizedCapturesNeverInvokeModels() {
        val cases = listOf(
            Triple(TranslationDecision.NoSpeech, speech, 16000),
            Triple(rejection, AudioEvidence(false, .1, 1000), 16000),
            Triple(rejection, speech, 0), Triple(rejection, speech, 480001),
            Triple(TranslationDecision.AskRepeat("speech_too_long"), speech, 480000),
            Triple(TranslationDecision.AskRepeat("speech_too_long"), speech, 12000),
            Triple(TranslationDecision.ModelTranslation("accepted"), speech, 16000),
        )
        for ((decision, audio, samples) in cases) {
            val result = RejectedCaptureDraft.attempt(decision, audio, samples, enabled = true,
                decode = { error("must not decode") }, attachDraft = { _, _ -> error("must not translate") })
            assertEquals(decision, result.decision)
            assertFalse(result.attempted)
        }
        assertFalse(RejectedCaptureDraft.shouldAttempt(rejection, speech, 16000, false))
    }

    @Test fun aDraftMapperCannotReplaceTheOriginalRejectionReason() {
        val result = RejectedCaptureDraft.attempt(rejection, speech, 16000, true,
            decode = { heard() }, attachDraft = { _, transcript ->
                TranslationDecision.AskRepeat("different_reason", TentativeOutput(transcript.text))
            })
        assertEquals(rejection.reason, (result.decision as TranslationDecision.AskRepeat).reason)
    }

    @Test fun actualCaptureLimitGateCannotBeBypassedByTrimmedPcm() {
        val gate = CaptureAnalysis.preAsrGate(speech, captureLimitReached = true) as CaptureAnalysis.PreAsrGate.AskRepeat
        val decision = TranslationDecision.AskRepeat(gate.reason)
        assertFalse(RejectedCaptureDraft.shouldAttempt(decision, speech, 480000, true))
        assertFalse(RejectedCaptureDraft.shouldAttempt(decision, speech, 12000, true))
    }

    @Test fun inferenceFailurePreservesAskAndNeverUsesAPreviousUtterance() {
        val result = RejectedCaptureDraft.attempt(rejection, speech, 16000, true,
            decode = { throw IllegalStateException("decoder failed") },
            attachDraft = { _, _ -> error("no transcript") })
        assertEquals(rejection, result.decision)
        assertNull(result.transcript)
        assertEquals("IllegalStateException", result.failure)
    }

    @Test fun draftFailureKeepsOnlyTheTranscriptActuallyObtainedThisTurn() {
        val result = RejectedCaptureDraft.attempt(rejection, speech, 16000, true,
            decode = { heard("go") }, attachDraft = { _, _ -> throw IllegalStateException("MT failed") })
        val ask = result.decision as TranslationDecision.AskRepeat
        assertEquals("audio_low_quality", ask.reason)
        assertEquals("go", ask.draft?.sourceText)
        assertNull(ask.draft?.translatedText)
    }

    @Test fun cancellationIsNotTurnedIntoUnreliableText() {
        assertFailsWith<CancellationException> {
            RejectedCaptureDraft.attempt(rejection, speech, 16000, true,
                decode = { throw CancellationException("cancel") }, attachDraft = { a, _ -> a })
        }
        assertFailsWith<CancellationException> {
            RejectedCaptureDraft.attempt(rejection, speech, 16000, true,
                decode = { heard() }, attachDraft = { _, _ -> throw CancellationException("cancel") })
        }
    }

    @Test fun failedDraftUsesBoundedNormalizedSourceAndDoesNotInventMarkerOnlySpeech() {
        val transcript = heard("[mask] go " + "x".repeat(2100))
        val draft = RejectedCaptureDraft.sourceOnly(transcript, "failed")!!
        assertTrue(draft.sourceText.startsWith("[unclear] go"))
        assertTrue(draft.sourceText.length <= 2000)
        assertTrue(draft.sourceTruncated)
        assertTrue(draft.containsUnknownSpans)
        assertEquals("failed", draft.unavailableReason)
        assertNull(RejectedCaptureDraft.sourceOnly(heard("[mask]"), "failed"))
        assertNull(RejectedCaptureDraft.sourceOnly(null, "failed"))
    }
}
