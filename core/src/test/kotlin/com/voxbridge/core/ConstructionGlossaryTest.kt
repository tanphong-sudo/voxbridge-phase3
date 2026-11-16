package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.translate.ConstructionGlossary
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructionGlossaryTest {
    private fun pin(text: String) = ConstructionGlossary.pinViToEn(text)

    @Test fun tradeTermsArePinnedToEnglish() {
        assertEquals("Chưa được tháo formwork, bê tông mới đổ hôm qua.", pin("Chưa được tháo cốp pha, bê tông mới đổ hôm qua."))
        assertEquals("Lên cao phải móc safety harness vào guardrail.", pin("Lên cao phải móc dây an toàn vào lan can."))
        assertEquals("tower crane đang nâng hàng", pin("Cẩu tháp đang nâng hàng"))
    }

    @Test fun longestTermWinsOverItsPrefix() {
        assertEquals("guardrail bị gãy", pin("lan can an toàn bị gãy"))
        assertEquals("concrete mixer truck đang lùi", pin("xe bồn bê tông đang lùi"))
    }

    @Test fun ordinarySentencesAndAmbiguousWordsAreUntouched() {
        listOf(
            "Anh giải thích lại chậm hơn được không?",
            "Đổ bê tông xong thì dọn thang.",
            "Hàn xong thì cột dây lại.",
            "Đi dầm mưa cả buổi.",
            "Cửa hàng mở lúc tám giờ.",
        ).forEach { assertEquals(it, pin(it)) }
    }

    @Test fun termsMustBeWholeWords() {
        assertEquals("máy đàoxyz", pin("máy đàoxyz"))
    }

    @Test fun decomposedUnicodeFromAsrStillMatches() {
        assertEquals("kiểm tra scaffolding", pin(Normalizer.normalize("kiểm tra giàn giáo", Normalizer.Form.NFD)))
    }

    @Test fun onlyVietnameseToEnglishMtInputIsPinned() {
        val seen = mutableListOf<String>()
        val mt = Translator { text, s, t -> seen += text; TranslationResult(text, "ok", s, t, "test") }
        val audio = AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1500)
        val vi = TranscriptResult(Language.VI, Language.EN, "Kiểm tra cốp pha tầng ba.", 0.9)
        DecisionEngine(mt).decide(audio, vi)
        DecisionEngine(mt, config = DecisionConfig(pinConstructionTerms = false)).decide(audio, vi)
        DecisionEngine(mt).decide(audio, TranscriptResult(Language.EN, Language.VI, "Check the formwork.", 0.9))
        assertEquals(listOf("Kiểm tra formwork tầng ba.", "Kiểm tra cốp pha tầng ba.", "Check the formwork."), seen)
        assertEquals("Kiểm tra cốp pha tầng ba.", vi.text)
    }

    @Test fun asrMisspellingsOfTradeWordsAreCorrectedFirst() {
        assertEquals("tower crane đang nâng rebar lên tầng sáu.", pin("cẩu thác đang nâng cốt thép lên tầng sáu."))
        assertEquals("concrete mixer truck đến lúc hai giờ chiều.", pin("xe bồn bơ tông đến lúc hai giờ chiều."))
        assertEquals("chưa được tháo formwork ở tầng bốn bê tông chưa đủ ngày.", pin("chưa được tháo cốp pha ở tầng bốn bơ công chưa đủ ngày."))
    }

    @Test fun gridAxesBecomeGridLines() {
        assertEquals("còn đổ bê tông sàn từ grid line B đến grid line D khoảng mười hai cubic meters.",
            pin("còn đổ bơ tông sàn từ chục bờ đến chục giờ khoảng mười hai khối."))
        assertEquals("từ grid line B đến grid line D", pin("từ trục B đến trục D"))
        assertEquals("cột ở grid line C bị nứt", pin("cột ở trục xê bị nứt"))
        assertEquals("grid line 3", pin("trục 3"))
        assertEquals("grid column B bị nứt", pin("cột trục B bị nứt"))
    }

    @Test fun ordinaryChucGioAndKhoiAreUntouched() {
        listOf(
            "Làm mấy chục giờ rồi mà chưa xong.",
            "Hai khối nhà này xây cùng lúc.",
            "Trục quay của máy bị kẹt.",
            "Mười chục bờ kè đã xong.",
        ).forEach { assertEquals(it, pin(it)) }
        assertEquals("đổ năm cubic meters bê tông", pin("đổ năm khối bê tông"))
    }

    @Test fun floorMisheardAsTamIsCorrectedOnlyAfterLen() {
        assertEquals("tower crane đang nâng rebar lên tầng sáu.", pin("cẩu thác đang nâng cốt thép lên tầm sáu."))
        assertEquals("Khoảng tầm sáu giờ thì xong.", pin("Khoảng tầm sáu giờ thì xong."))
        assertEquals("Nâng tầm hiểu biết lên.", pin("Nâng tầm hiểu biết lên."))
    }
}
