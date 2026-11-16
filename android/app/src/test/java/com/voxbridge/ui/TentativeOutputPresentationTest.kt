package com.voxbridge.ui

import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TranslationDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TentativeOutputPresentationTest {
    @Test fun `draft appears only for enabled ASK_REPEAT and remains wholly uncertain`() {
        val draft = TentativeOutput("Open valve three", "Mở van ba")
        val ui = tentativeOutputPresentation("ASK_REPEAT (audio_low_quality)", Language.EN, draft, enabled = true)
        assertNotNull(ui)
        assertEquals("Unreliable draft · Please repeat", ui!!.warningLabel)
        assertEquals("!", ui.warningSymbol)
        assertTrue(ui.wholeUtteranceUncertain)
        assertEquals("Possibly heard", ui.sourceLabel)
        assertEquals("Open valve three", ui.sourceText)
        assertEquals("Possible translation", ui.translationLabel)
        assertEquals("Mở van ba", ui.translatedText)
        assertFalse(shouldShowAcceptedOutput("ASK_REPEAT (audio_low_quality)", "stale accepted translation"))
    }

    @Test fun `missing disabled or unrelated drafts do not create a card`() {
        val draft = TentativeOutput("Open valve", "Mở van")
        assertNull(tentativeOutputPresentation("ASK_REPEAT", Language.EN, null, enabled = true))
        assertNull(tentativeOutputPresentation("ASK_REPEAT", Language.EN, draft, enabled = false))
        for (decision in listOf("", "NO_SPEECH", "TRANSLATED", "VERIFIED_TRANSLATION", "REPAIRED_TRANSLATION")) {
            assertNull(tentativeOutputPresentation(decision, Language.EN, draft, enabled = true))
        }
    }

    @Test fun `source-only fallback never fabricates or labels an accepted translation`() {
        val draft = TentativeOutput(
            sourceText = "Possibly open valve",
            translatedText = null,
            unavailableReason = "internal MT diagnostic should stay out of user text",
        )
        val ui = tentativeOutputPresentation("ASK_REPEAT", Language.EN, draft, enabled = true)!!
        assertEquals(draft.sourceText, ui.sourceText)
        assertNull(ui.translatedText)
        assertNull(ui.translationLabel)
        assertTrue(ui.hints.any { it.contains("Translation unavailable") })
        assertFalse(ui.hints.joinToString().contains("internal MT diagnostic"))
        assertFalse(shouldShowAcceptedOutput("ASK_REPEAT", ui.sourceText))
    }

    @Test fun `masked and truncated text is preserved and raw source is never displayed`() {
        val draft = TentativeOutput(
            sourceText = "Open [?] valve",
            translatedText = "Mở van [?]",
            rawSourceText = "Open unrecognized-raw-token valve",
            containsUnknownSpans = true,
            sourceTruncated = true,
        )
        val ui = tentativeOutputPresentation("ASK_REPEAT", Language.EN, draft, enabled = true)!!
        assertEquals("Open [?] valve", ui.sourceText)
        assertEquals("Mở van [?]", ui.translatedText)
        assertTrue(ui.hints.any { it.contains("Some words") })
        assertTrue(ui.hints.any { it.contains("shortened") })
        assertFalse(ui.toString().contains("unrecognized-raw-token"))
        assertTrue(ui.wholeUtteranceUncertain)
    }

    @Test fun `Vietnamese direction uses Vietnamese warnings labels and fallback hints`() {
        val draft = TentativeOutput(
            sourceText = "Mở van [?]",
            translatedText = null,
            containsUnknownSpans = true,
            sourceTruncated = true,
        )
        val ui = tentativeOutputPresentation("ASK_REPEAT", Language.VI, draft, enabled = true)!!
        assertEquals("Bản nháp chưa tin cậy · Xin nói lại", ui.warningLabel)
        assertEquals("Có thể đã nghe", ui.sourceLabel)
        assertTrue(ui.hints.any { it.contains("Một số từ") })
        assertTrue(ui.hints.any { it.contains("rút gọn") })
        assertTrue(ui.hints.any { it.contains("Chưa có bản dịch") })
    }

    @Test fun `accepted output affordances remain separate from repeat and no speech decisions`() {
        assertFalse(shouldShowAcceptedOutput("ASK_REPEAT", "candidate"))
        assertFalse(shouldShowAcceptedOutput("NO_SPEECH", "stale text"))
        assertFalse(shouldShowAcceptedOutput("TRANSLATED", ""))
        assertTrue(shouldShowAcceptedOutput("TRANSLATED", "accepted text"))
        assertTrue(shouldShowAcceptedOutput("REPAIRED_TRANSLATION", "review text"))
    }

    @Test fun `candidate extraction clears drafts for all non-repeat decision types`() {
        val draft = TentativeOutput("open valve", "mở van")
        assertEquals(draft, tentativeForDecision(TranslationDecision.AskRepeat("uncertain", draft)))
        assertNull(tentativeForDecision(TranslationDecision.AskRepeat("uncertain")))
        assertNull(tentativeForDecision(TranslationDecision.AskRepeat("uncertain", draft), enabled = false))
        val otherDecisions = listOf(
            TranslationDecision.NoSpeech,
            TranslationDecision.ModelTranslation("accepted"),
            TranslationDecision.MemoryTranslation("memory", .99),
            TranslationDecision.RepairedTranslation("repaired", "model", emptyList()),
        )
        assertTrue(otherDecisions.all { tentativeForDecision(it) == null })
    }
}
