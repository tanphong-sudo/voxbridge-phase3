package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.translate.Translator
import com.voxbridge.core.translate.TranslationResult
import kotlin.test.Test
import kotlin.test.assertEquals

class PunctuationPipelineTest {
    private fun input(text: String, source: Language) = TranscriptResult(
        source, if (source == Language.VI) Language.EN else Language.VI,
        text, 0.9,
    )

    @Test fun onlyMtInputChangesWhenEnabledForVietnamese() {
        val seen = mutableListOf<String>()
        val mt = Translator { text, s, t ->
            seen += text
            TranslationResult(text, "Do you need more wire?", s, t, "test")
        }
        val transcript = input("anh cần thêm dây không.", Language.VI)
        DecisionEngine(mt, config = DecisionConfig(restoreViPunctuation = true))
            .decide(AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1200), transcript)
        assertEquals(listOf("Anh cần thêm dây không?"), seen)
        assertEquals("anh cần thêm dây không.", transcript.text)
    }

    @Test fun featureDefaultsOffAndEnglishIsUntouched() {
        val seen = mutableListOf<String>()
        val mt = Translator { text, s, t ->
            seen += text
            TranslationResult(text, "Hello.", s, t, "test")
        }
        val audio = AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1200)
        DecisionEngine(mt).decide(audio, input("anh cần thêm dây không.", Language.VI))
        DecisionEngine(mt, config = DecisionConfig(restoreViPunctuation = true))
            .decide(audio, input("have you checked it yet.", Language.EN))
        assertEquals(listOf("anh cần thêm dây không.", "have you checked it yet."), seen)
    }
}
