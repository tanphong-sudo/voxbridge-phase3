package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.*
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import kotlin.test.*

class ServerTentativeMergeTest {
    private val audio = AudioEvidence(true, .95, 1600)
    private fun transcript(text: String, score: Double = .95, lang: Language = Language.EN) =
        TranscriptResult(lang, if (lang == Language.EN) Language.VI else Language.EN, text, score)

    @Test fun decoderLimitStillRejectsAndReusesOneActualMtResultWithFeatureOnOrOff() {
        for (enabled in listOf(false, true)) {
            var calls = 0
            val mt = Translator { text, s, t ->
                calls++
                TranslationResult(text, "Vui lòng kiểm tra van.", s, t, "capped", hitTokenLimit = true)
            }
            val decision = assertIs<TranslationDecision.AskRepeat>(DecisionEngine(mt,
                config = DecisionConfig(tentativeOutputEnabled = enabled)).decide(audio,
                transcript("Please check the valve and then close it.")))
            assertEquals("translation_token_limit", decision.reason)
            assertEquals(1, calls)
            if (enabled) assertNotNull(decision.draft) else assertNull(decision.draft)
            val events = SpeechOutputPolicy().session(decision, Language.EN, Language.VI).initialEvents()
            assertTrue(events.all { it is SpeechOutputPolicy.Event.Prompt })
        }
    }

    @Test fun addedSafetyMeaningRemainsAskAndDisplayOnlyEvenWithDraftEnabled() {
        var calls = 0
        val mt = Translator { text, s, t ->
            calls++
            TranslationResult(text, "Dừng máy ngay lập tức.", s, t, "test")
        }
        val decision = assertIs<TranslationDecision.AskRepeat>(DecisionEngine(mt,
            config = DecisionConfig(tentativeOutputEnabled = true)).decide(audio, transcript("Hello there.")))
        assertEquals("translation_added_safety_signal", decision.reason)
        assertEquals(1, calls)
        assertTrue(assertNotNull(decision.draft).wholeUtteranceUncertain)
    }

    @Test fun phraseCollapseUsesLatestServerCoverageAndNeverGeneratesSecondMt() {
        var calls = 0
        val mt = Translator { text, s, t -> calls++; TranslationResult(text,
            "dừng máy ngay dừng máy ngay dừng máy ngay", s, t, "test") }
        val result = assertIs<TranslationDecision.AskRepeat>(DecisionEngine(mt,
            config = DecisionConfig(tentativeOutputEnabled = true)).decide(audio, transcript("Hello there.")))
        assertEquals("translation_degenerate", result.reason)
        assertNull(assertNotNull(result.draft).translatedText)
        assertEquals(1, calls)
    }

    @Test fun punctuationOptInSurvivesForAcceptedAndEarlyDraftMtWithoutChangingRawSource() {
        for (score in listOf(.95, .2)) {
            val seen = mutableListOf<String>()
            val mt = Translator { text, s, t -> seen += text; TranslationResult(text,
                "Do you need more wire?", s, t, "test") }
            val input = transcript("anh cần thêm dây không.", score, Language.VI)
            val decision = DecisionEngine(mt, config = DecisionConfig(
                restoreViPunctuation = true, tentativeOutputEnabled = true)).decide(audio, input)
            assertEquals(listOf("Anh cần thêm dây không?"), seen)
            assertEquals("anh cần thêm dây không.", input.text)
            if (score < .6) assertEquals(input.text, assertIs<TranslationDecision.AskRepeat>(decision).draft?.rawSourceText)
        }
    }
}
