package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpeechOutputPolicyTest {
    private val policy = SpeechOutputPolicy()

    @Test
    fun usableDraftIsSpokenAsUnverifiedButCannotBeReplayedOrConfirmed() {
        val decision = TranslationDecision.AskRepeat("uncertain_audio", TentativeOutput(
            sourceText = "not open valve [unclear]",
            translatedText = "Do not open valve 15.",
            containsUnknownSpans = true,
        ))
        val session = policy.session(decision, Language.VI, Language.EN)
        assertTrue(session.replayEvents().isEmpty())
        assertTrue(session.confirmRepaired().isEmpty())
        assertEquals(
            listOf(SpeechOutputPolicy.Event.SpeakTranslation("Do not open valve 15.", Language.EN, unverified = true)),
            session.initialEvents(),
        )
        assertTrue(session.initialEvents().isEmpty())
        assertEquals(
            listOf(SpeechOutputPolicy.Event.SpeakTranslation("Do not open valve 15.", Language.EN, unverified = true)),
            session.replayEvents(),
        )
        assertTrue(session.confirmRepaired().isEmpty())
    }

    @Test
    fun noSpeechAndRejectUseSourceLanguagePromptAndNeverSpeakCandidateTranslation() {
        val noSpeech = policy.session(TranslationDecision.NoSpeech, Language.VI, Language.EN)
        val noSpeechEvents = noSpeech.initialEvents()
        assertEquals(1, noSpeechEvents.size)
        assertEquals(
            SpeechOutputPolicy.Event.Prompt(SpeechOutputPolicy.PromptKind.NO_SPEECH, Language.VI),
            noSpeechEvents.single(),
        )
        assertTrue(noSpeechEvents.none { it is SpeechOutputPolicy.Event.SpeakTranslation })

        val reject = policy.session(TranslationDecision.AskRepeat("audio_clipped"), Language.EN, Language.VI)
        val rejectEvents = reject.initialEvents()
        assertEquals(
            SpeechOutputPolicy.Event.Prompt(SpeechOutputPolicy.PromptKind.ASK_REPEAT, Language.EN),
            rejectEvents.single(),
        )
        assertTrue(rejectEvents.none { it is SpeechOutputPolicy.Event.SpeakTranslation })
    }

    @Test
    fun acceptedTranslationSpeaksTargetExactlyOnceOnInitialRender() {
        val session = policy.session(
            TranslationDecision.ModelTranslation("Hello."),
            Language.VI,
            Language.EN,
        )

        val event = assertIs<SpeechOutputPolicy.Event.SpeakTranslation>(session.initialEvents().single())
        assertEquals("Hello.", event.text)
        assertEquals(Language.EN, event.language)
        assertTrue(session.initialEvents().isEmpty())
        assertTrue(session.confirmRepaired().isEmpty())
    }

    @Test
    fun repairedTranslationIsSilentUntilConfirmationAndConfirmationIsIdempotent() {
        val decision = TranslationDecision.RepairedTranslation(
            text = "Stop conveyor number three.",
            modelText = "Stop the conveyor.",
            repaired = listOf(SafetyViolation.LOST_OBJECT_ID),
        )
        val session = policy.session(decision, Language.VI, Language.EN)

        assertTrue(session.initialEvents().isEmpty())
        val event = assertIs<SpeechOutputPolicy.Event.SpeakTranslation>(session.confirmRepaired().single())
        assertEquals(decision.text, event.text)
        assertEquals(Language.EN, event.language)
        assertTrue(session.confirmRepaired().isEmpty())
    }

    @Test
    fun replayCannotBypassRepairConfirmationOrSpeakRejectedInput() {
        val repaired = policy.session(TranslationDecision.RepairedTranslation(
            "Stop machine three.", "Start machine.", listOf(SafetyViolation.LOST_NEGATION)), Language.VI, Language.EN)
        assertTrue(repaired.replayEvents().isEmpty())
        repaired.initialEvents()
        assertTrue(repaired.replayEvents().isEmpty())
        repaired.confirmRepaired()
        assertEquals(listOf(SpeechOutputPolicy.Event.SpeakTranslation("Stop machine three.", Language.EN)), repaired.replayEvents())
        assertTrue(repaired.confirmRepaired().isEmpty())
        for (decision in listOf(TranslationDecision.NoSpeech, TranslationDecision.AskRepeat("unsafe"))) {
            val rejected = policy.session(decision, Language.VI, Language.EN)
            rejected.initialEvents()
            assertTrue(rejected.replayEvents().isEmpty())
        }
    }

    @Test
    fun replayUsesTheRenderedDecisionAndItsOriginalLanguage() {
        val session = policy.session(TranslationDecision.ModelTranslation("Hello."), Language.VI, Language.EN)
        assertTrue(session.replayEvents().isEmpty())
        session.initialEvents()
        repeat(2) {
            assertEquals(listOf(SpeechOutputPolicy.Event.SpeakTranslation("Hello.", Language.EN)), session.replayEvents())
        }
    }
}
