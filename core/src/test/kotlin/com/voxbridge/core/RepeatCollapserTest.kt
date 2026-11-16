package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.RepeatCollapser
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import kotlin.test.Test
import kotlin.test.assertEquals

class RepeatCollapserTest {
    private fun en(text: String) = RepeatCollapser.collapse(text, Language.EN)
    private fun vi(text: String) = RepeatCollapser.collapse(text, Language.VI)

    @Test fun englishStutterAndRepeatedPhraseCollapse() {
        assertEquals(
            "yeah i want some quality time with my friends outside like it's really nice",
            en("yeah i want some quality time quality time with my friends outside like it's it's really nice"),
        )
        assertEquals("I need the ladder", en("I need the the ladder"))
        assertEquals("I need the ladder", en("I need the the the ladder"))
        assertEquals("Stop, the crane", en("Stop, stop. the crane"))
    }

    @Test fun legitimateDoublesAndNumbersAreKept() {
        assertEquals("I know that that is wrong", en("I know that that is wrong"))
        assertEquals("he had had enough", en("he had had enough"))
        assertEquals("gate 2 2 2", en("gate 2 2 2"))
        assertEquals("level 5 meters 5 meters", en("level 5 meters 5 meters"))
    }

    @Test fun vietnameseReduplicationSurvivesButStutterDoesNot() {
        assertEquals("đi từ từ thôi", vi("đi từ từ thôi"))
        assertEquals("đi từ thôi", vi("đi từ từ từ thôi"))
        assertEquals("nhanh lên mang dây ra", vi("nhanh lên nhanh lên mang dây ra"))
        assertEquals("cẩu số 3 số 3", vi("cẩu số 3 số 3"))
    }

    @Test fun untouchedTextIsReturnedAsIs() {
        val text = "  Close valve three.  "
        assertEquals(text, en(text))
        assertEquals("", en(""))
    }

    @Test fun onlyMtInputIsCollapsedTheTranscriptStaysAsHeard() {
        val seen = mutableListOf<String>()
        val mt = Translator { text, s, t -> seen += text; TranslationResult(text, "Đó thật sự rất vui.", s, t, "test") }
        val transcript = TranscriptResult(Language.EN, Language.VI, "it's it's it's really nice", 0.9)
        DecisionEngine(mt).decide(AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1500), transcript)
        assertEquals(listOf("it's really nice"), seen)
        assertEquals("it's it's it's really nice", transcript.text)
    }
}
