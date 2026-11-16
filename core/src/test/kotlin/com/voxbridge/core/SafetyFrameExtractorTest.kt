package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.safety.HazardType
import com.voxbridge.core.safety.SafetyAction
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.safety.SafetyObject
import com.voxbridge.core.safety.Urgency
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyFrameExtractorTest {
    private val extractor = SafetyFrameExtractor()

    @Test
    fun vietnameseFullGasLeakPhrasePreservesHazard() {
        for (text in listOf("Rò rỉ khí!", "Ro ri khi!",
                            "Khẩn cấp, dừng máy ép số sáu vì rò rỉ khí.")) {
            val frame = extractor.extract(text, Language.VI)
            assertTrue(frame.hazard == HazardType.GAS_LEAK, "gas leak missing from $frame for '$text'")
            assertTrue(frame.hasSafetySignal, "gas alert inactive for '$text'")
        }
    }

    @Test
    fun gasLeakMentionInLongNarrativeDoesNotBecomeCommand() {
        val frame = extractor.extract(
            "Bài viết so sánh rò rỉ khí và rò rỉ nước trong một mô hình nghiên cứu.", Language.VI)
        assertTrue(frame.hazard == HazardType.GAS_LEAK, "lexical gas leak missing: $frame")
        assertFalse(frame.hasSafetySignal, "descriptive narrative became command: $frame")
        assertNull(extractor.extract("Rò rỉ nước ở đường ống.", Language.VI).hazard)
    }

    @Test
    fun negativeGasLeakStatementKeepsNegationInsteadOfDiscardingHazard() {
        val frame = extractor.extract("Không có rò rỉ khí.", Language.VI)
        assertTrue(frame.hazard == HazardType.GAS_LEAK, "gas leak missing: $frame")
        assertTrue(frame.negation == true, "negative assertion lost: $frame")
    }

    @Test
    fun ordinaryVietnameseCultureSentenceDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Văn hóa và bộ lạc cổ xưa đã bắt đầu giữ những con vật này để dễ lấy sữa, tóc, thịt, và da.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "ordinary FLEURS sentence produced $frame")
    }

    @Test
    fun ordinaryVietnameseInternetSentenceDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Mạng Internet kết hợp những yếu tố của cả truyền thông đại chúng và giao tiếp cá nhân.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "ordinary FLEURS sentence produced $frame")
    }

    @Test
    fun fleursShopNarrativeDoesNotCreateDoorObject() {
        val frame = extractor.extract(
            "Bishkek được một người quan sát giải thích là rơi vào tình trạng \"vô chính phủ\", khi các băng nhóm tràn ra đường và cướp phá các cửa hàng nhu yếu phẩm.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "cửa hàng must not become a DOOR safety target: $frame")
    }

    @Test
    fun fleursFiscalYearNarrativeDoesNotCreateLockObject() {
        val frame = extractor.extract(
            "Quốc hội bắt đầu cấp kinh phí cho đề xướng chống khiêu dâm trong năm tài khóa 2005 và quy định rằng FBI phải dành 10 đặc vụ cho nội dung khiêu dâm người lớn.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "tài khóa must not become a LOCK safety target: $frame")
    }

    @Test
    fun ordinaryVietnameseSoundAndLightSentenceDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Buổi trình diễn âm thanh và ánh sáng là một điểm thu hút khách du lịch nổi tiếng.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "ordinary FLEURS sentence produced $frame")
    }

    @Test
    fun ordinaryEnglishEmergencyReportDoesNotActivateSafety() {
        val frame = extractor.extract(
            "The Northern Marianas emergency management office said that there were no damages reported in the nation.",
            Language.EN,
        )
        assertFalse(frame.hasSafetySignal, "ordinary FLEURS sentence produced $frame")
    }

    @Test
    fun vietnameseNarrativeBuildAgainDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Vương quốc Ma-rốc đã xây dựng lại thành phố này giống như Daru l-Badya.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "dựng lại must not become DỪNG LẠI: $frame")
    }

    @Test
    fun vietnameseSportsScoreDoesNotCreateObjectId() {
        val frame = extractor.extract(
            "45 trong tổng số 117 vận động viên không giành được vị trí trong bảng xếp hạng.",
            Language.VI,
        )
        assertNull(frame.objectId, "generic count must not become equipment id: $frame")
        assertFalse(frame.hasSafetySignal, "generic sports count produced $frame")
    }

    @Test
    fun richterDecimalDoesNotCreateTemperatureQuantity() {
        val frame = extractor.extract(
            "Cơn địa chấn chưa đạt tới tiêu chuẩn 6.5 độ richter.",
            Language.VI,
        )
        assertNull(frame.quantity, "Richter decimal must not become Celsius: $frame")
        assertFalse(frame.hasSafetySignal, "Richter sentence produced $frame")
    }

    @Test
    fun wifiVersionDoesNotCreateAmpQuantity() {
        val frame = extractor.extract(
            "This is backwards compatible with 802.11a, 802.11b and 802.11g.",
            Language.EN,
        )
        assertNull(frame.quantity, "802.11a must not become 11 amps: $frame")
        assertFalse(frame.hasSafetySignal, "Wi-Fi version sentence produced $frame")
    }

    @Test
    fun vietnameseResearchDoesNotCreateCrushHazard() {
        val frame = extractor.extract(
            "Theo báo cáo của các nhà nghiên cứu, kết quả này cần được kiểm tra thêm.",
            Language.VI,
        )
        assertNull(frame.hazard, "nghiên cứu must not become nghiền/crush: $frame")
        assertFalse(frame.hasSafetySignal, "research sentence produced $frame")
    }

    @Test
    fun vietnameseRunwayPhraseDoesNotCreateConveyor() {
        val frame = extractor.extract(
            "Máy bay chạy quá đường băng tại Mashhad và đâm vào một bức tường.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "băng tại must not become băng tải: $frame")
    }

    @Test
    fun vietnameseSourceNounDoesNotCreatePowerObject() {
        val frame = extractor.extract(
            "Hai mươi phần trăm lượng nước bắt nguồn từ Amazon.",
            Language.VI,
        )
        assertNull(frame.objectType, "nguồn in bắt nguồn must not become electrical POWER: $frame")
        assertFalse(frame.hasSafetySignal, "nguồn in bắt nguồn must not mean electrical power: $frame")
    }

    @Test
    fun amazonPercentageNarrativeDoesNotActivateSafety() {
        val frame = extractor.extract(
            "20% lượng nước đổ từ các con sông trên hành tinh này ra đại dương đều bắt nguồn từ Amazon.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "Amazon percentage narrative produced $frame")
    }

    @Test
    fun longBridgeClearanceNarrativeDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Chiều cao tĩnh không dưới cầu là 15 mét. Việc xây dựng được hoàn thành vào tháng 8 năm 2011 nhưng đến tháng 3 năm 2017 cầu mới được khai thông.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "long measurement narrative produced $frame")
    }

    @Test
    fun vietnameseEmergencyManagementReportDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Phòng quản lý tình trạng khẩn cấp cho biết không có báo cáo nào về tổn thất.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "management report produced $frame")
    }

    @Test
    fun narrativeStopDoesNotActivateButBareStopDoes() {
        val narrative = extractor.extract(
            "Xe buýt số 403 thường xuyên chạy đến Sintra, dừng ở Cabo da Roca.",
            Language.VI,
        )
        assertFalse(narrative.hasSafetySignal, "narrative stop produced $narrative")

        val alert = extractor.extract("Dừng!", Language.VI)
        assertTrue(alert.hasSafetySignal, "bare stop must remain guarded: $alert")
    }

    @Test
    fun narrativeIsolationDoesNotActivateSafety() {
        val frame = extractor.extract(
            "Quan điểm phổ biến trong thập niên 1920 là chủ nghĩa hòa bình và cô lập.",
            Language.VI,
        )
        assertFalse(frame.hasSafetySignal, "narrative isolation produced $frame")
    }

    @Test
    fun narrativeFireDoesNotActivateButBareFireDoes() {
        val narrative = extractor.extract(
            "Between ten and eleven at night, a fire was started by the inmates in the yard.",
            Language.EN,
        )
        assertFalse(narrative.hasSafetySignal, "narrative fire produced $narrative")

        val alert = extractor.extract("Fire!", Language.EN)
        assertTrue(alert.hazard == HazardType.FIRE && alert.hasSafetySignal, "bare fire alert lost: $alert")
    }

    @Test
    fun collapsedShutdownPressCommandKeepsCriticalSlots() {
        val frame = extractor.extract(
            "Emergency, shutdown press number one because of a gas leak.",
            Language.EN,
        )

        assertTrue(frame.action == SafetyAction.STOP, "shutdown must canonicalize to STOP: $frame")
        assertTrue(frame.objectType == SafetyObject.MACHINE, "industrial press must canonicalize to MACHINE: $frame")
        assertTrue(frame.objectId == "1", "press number must retain equipment id: $frame")
        assertTrue(frame.urgency == Urgency.HIGH, "emergency urgency lost: $frame")
        assertTrue(frame.hazard == HazardType.GAS_LEAK, "gas-leak hazard lost: $frame")
        assertTrue(frame.hasSafetySignal, "urgent shutdown command must activate safety: $frame")
    }
}
