package com.voxbridge.core

import com.voxbridge.core.normalize.ViPunctuationRestorer
import kotlin.test.Test
import kotlin.test.assertEquals

class ViPunctuationRestorerTest {
    @Test fun conditionalAndOpenQuestionsRemainQuestions() {
        listOf("Nếu chậm thêm thì buổi trình bày có ảnh hưởng không?", "Ai đang điều khiển cần cẩu?", "Tại sao máy số 2 dừng?", "Khoảng cách an toàn là mấy mét?").forEach { expected ->
            assertEquals(expected, ViPunctuationRestorer.restore(expected.lowercase().replace('?', '.')))
        }
    }

    @Test fun reportedQuestionsRemainStatements() {
        listOf("Tôi hỏi anh ấy máy có điện không.", "Tôi muốn xác nhận máy có điện không.").forEach { expected ->
            assertEquals(expected, ViPunctuationRestorer.restore(expected.lowercase()))
        }
    }

    @Test fun broaderQuestionFormsAndIndirectRequests() {
        listOf("Làm sao để tắt máy?", "Vì sao đèn báo đỏ?", "Anh mang mấy cái mũ?", "Cần những dụng cụ gì?", "Van nào đang mở?").forEach { expected ->
            assertEquals(expected, ViPunctuationRestorer.restore(expected.lowercase().replace('?', '.')))
        }
        listOf("Tôi cần biết van ở đâu.", "Kiểm tra xem máy đã tắt chưa.", "Tôi đang tìm hiểu khi nào được vào.", "Chúng tôi chưa rõ máy có điện không.", "Máy nào cũng phải ngắt điện trước khi sửa.", "Tôi không rõ van nào đã khóa.").forEach { expected ->
            assertEquals(expected, ViPunctuationRestorer.restore(expected.lowercase()))
        }
    }

    @Test fun questionsAreRestored() {
        val cases = mapOf(
            "anh cần thêm dây không." to "Anh cần thêm dây không?",
            "chị đã kiểm tra van chưa." to "Chị đã kiểm tra van chưa?",
            "bạn đang ở phòng nào để tôi đến." to "Bạn đang ở phòng nào để tôi đến?",
            "bao lâu nữa thì máy dừng." to "Bao lâu nữa thì máy dừng?",
            "áp suất hiện tại là bao nhiêu." to "Áp suất hiện tại là bao nhiêu?",
            "tôi nên gửi thông tin cho ai." to "Tôi nên gửi thông tin cho ai?",
            "anh muốn trà hay cà phê." to "Anh muốn trà hay cà phê?",
            "đây là lần đầu à." to "Đây là lần đầu à?",
            "vị trí này đúng không." to "Vị trí này đúng không?",
            "anh đã dùng loại nào." to "Anh đã dùng loại nào?",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, ViPunctuationRestorer.restore(input), input) }
    }

    @Test fun prohibitionsAndStatementsAreNotQuestions() {
        val inputs = listOf(
            "không mở van số ba.", "đừng đi đâu.", "cấm chạm vào tủ điện.",
            "hãy kiểm tra xem van đã khóa chưa.", "vui lòng hỏi người phụ trách xem có điện không.",
            "tôi chưa biết.", "tôi không biết anh ấy có đến không.", "tôi muốn biết máy ở đâu.",
            "tôi đã hỏi họ kiểm tra xong chưa.", "tôi trả lời không.", "câu trả lời là không.",
            "anh hay đi bộ.", "ai cũng phải đội mũ.", "loại nào cũng được.",
            "anh chọn trà hay cà phê đều được.", "nếu chưa rõ thì đừng mở van.",
        )
        inputs.forEach { input ->
            assertEquals(input.replaceFirstChar { it.uppercaseChar() }, ViPunctuationRestorer.restore(input), input)
        }
    }

    @Test fun numbersIdsAndExistingPunctuationArePreserved() {
        assertEquals("Áp suất là 3.5 bar.", ViPunctuationRestorer.restore("áp suất là 3.5 bar."))
        assertEquals("Dừng máy A-12 ngay!", ViPunctuationRestorer.restore("dừng máy A-12 ngay!"))
        assertEquals("Đừng đi đâu?", ViPunctuationRestorer.restore("đừng đi đâu?"))
        assertEquals("Không biết...", ViPunctuationRestorer.restore("không biết..."))
        assertEquals("Mã ABC-12 có đúng không?", ViPunctuationRestorer.restore("mã ABC-12 có đúng không."))
        assertEquals("\"Anh kiểm tra xong chưa?\"", ViPunctuationRestorer.restore("\"anh kiểm tra xong chưa.\""))
    }

    @Test fun sentenceHandlingDoesNotSplitAbbreviationsOrDecimals() {
        assertEquals("Tôi chưa rõ. Anh kiểm tra lại được không?",
            ViPunctuationRestorer.restore("tôi chưa rõ. anh kiểm tra lại được không."))
        assertEquals("Gửi cho BS. An. Anh ấy ở đâu?",
            ViPunctuationRestorer.restore("gửi cho BS. An. anh ấy ở đâu."))
        assertEquals("Van A-12 ở mức 3.5 bar. Có đúng không?",
            ViPunctuationRestorer.restore("van A-12 ở mức 3.5 bar. có đúng không."))
    }

    @Test fun blankAndUnpunctuatedStatementsAreHandled() {
        assertEquals("", ViPunctuationRestorer.restore("   "))
        assertEquals("Tôi chờ thêm một phút.", ViPunctuationRestorer.restore("tôi chờ thêm một phút"))
    }

    @Test fun transformationIsIdempotentAndPreservesContentTokens() {
        val inputs = listOf("anh cần thêm dây không.", "không mở van A-12.", "áp suất 3.5 bar.")
        fun tokens(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toList()
        inputs.forEach { input ->
            val restored = ViPunctuationRestorer.restore(input)
            assertEquals(tokens(input), tokens(restored))
            assertEquals(restored, ViPunctuationRestorer.restore(restored))
        }
    }
}
