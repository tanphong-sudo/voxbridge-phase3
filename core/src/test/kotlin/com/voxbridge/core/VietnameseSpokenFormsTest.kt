package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer
import com.voxbridge.core.pipeline.SpeechAudioPlan
import com.voxbridge.core.audio.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VietnameseSpokenFormsTest {
    private fun vi(text: String) = SpokenTextNormalizer.normalize(text, Language.VI)

    @Test
    fun `clock times from EnViT5 output are read as hours and minutes`() {
        assertEquals(
            "Gọi tắc xi cho tôi để đưa tôi ra sân bay vào thứ sáu lúc năm giờ ba mươi sáng.",
            vi("Gọi taxi cho tôi để đưa tôi ra sân bay vào thứ sáu lúc 5: 30 sáng."),
        )
        assertEquals("Vào thứ Tư lúc tám giờ ba mươi sáng.", vi("Vào thứ Tư lúc 8h30 sáng."))
        assertEquals("Lúc tám giờ sáng.", vi("Lúc 8h sáng."))
        assertEquals("Lúc tám giờ năm phút.", vi("Lúc 8:05."))
        assertEquals("Khởi hành lúc mười sáu giờ bốn mươi lăm phút.", vi("Khởi hành lúc 16 giờ 45 phút."))
    }

    @Test
    fun `dates keep month names and years`() {
        assertEquals(
            "Ngày sinh của tôi là hai mươi tháng tư năm một nghìn chín trăm tám mươi.",
            vi("Ngày sinh của tôi là 20 tháng 4,1980."),
        )
        assertEquals("Ngày mười lăm tháng mười hai năm hai nghìn không trăm hai mươi sáu.", vi("Ngày 15/12/2026."))
        assertEquals("Hẹn vào tháng tư.", vi("Hẹn vào tháng 4."))
        assertEquals("ba tháng nữa", vi("3 tháng nữa"))
    }

    @Test
    fun `numbers use standard Vietnamese reading`() {
        assertEquals("Khoảng mười đô la.", vi("Khoảng \$10."))
        assertEquals("Tôi có chín trăm đô tiền mặt.", vi("Tôi có 900 đô tiền mặt."))
        assertEquals("một nghìn năm trăm người", vi("1.500 người"))
        assertEquals("một triệu không trăm lẻ năm", vi("1.000.005"))
        assertEquals("ba mươi mốt tuổi", vi("31 tuổi"))
        assertEquals("hai phẩy năm lít", vi("2,5 lít"))
        assertEquals("năm mươi phần trăm", vi("50%"))
        assertEquals("số không chín một hai ba bốn năm", vi("số 0912345"))
    }

    @Test
    fun `acronyms codes and loanwords are spelled the Vietnamese way`() {
        assertEquals("gọi là u tê i đơn giản", vi("gọi là UTI đơn giản"))
        assertEquals("Số hiệu chuyến bay là en nờ đắp bờ liu a bốn bảy sáu.", vi("Số hiệu chuyến bay là NWA 476."))
        assertEquals("Nhắn qua phây búc hoặc da lô nhé, ô kê?", vi("Nhắn qua Facebook hoặc Zalo nhé, OK?"))
        assertEquals("giờ G cộng bốn", vi("giờ G + 4"))
    }

    @Test
    fun `machine ids and plain Vietnamese stay untouched`() {
        assertEquals("Dừng máy A-12 ngay lập tức.", vi("Dừng máy A-12 ngay lập tức."))
        assertEquals("DỪNG LẠI NGAY.", vi("DỪNG LẠI NGAY."))
        assertEquals("DUNG LAI NGAY, KHONG MO VAN.", vi("DUNG LAI NGAY, KHONG MO VAN."))
        assertEquals("đến phòng i xê u", vi("đến phòng ICU"))
        assertEquals("Không mở van.", vi("Không mở van."))
    }

    @Test
    fun `normalization is idempotent`() {
        val samples = listOf(
            "Ngày sinh của tôi là 20 tháng 4,1980.",
            "Lúc 5: 30 sáng, chuyến NWA 476, 50% và \$10.",
            "Dừng máy A-12; áp suất 3.5 bar.",
        )
        for (text in samples) assertEquals(vi(text), vi(vi(text)), text)
    }

    @Test
    fun `audio plan normalizes before chunking so a date comma is not a clause split`() {
        val spoken = ArrayList<String>()
        val text = "Tên tôi là Sarah Smith và ngày sinh của tôi là 20 tháng 4, 1980."
        SpeechAudioPlan.producers(text, Language.VI, cache = { null }) { chunk, _ ->
            spoken += chunk; PcmAudio(ByteArray(2), 16_000)
        }.forEach { it() }
        assertFalse(spoken.any { it.any(Char::isDigit) }, spoken.toString())
        assertEquals(vi(text), spoken.joinToString(" "))
    }
}
