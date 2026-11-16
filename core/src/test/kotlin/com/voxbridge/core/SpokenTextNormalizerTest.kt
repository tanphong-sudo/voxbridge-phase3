package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer
import com.voxbridge.core.safety.SafetyFrameExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpokenTextNormalizerTest {
    @Test
    fun `Vietnamese proposal measurements have explicit spoken forms`() {
        assertEquals("Cắt ống dài mười lăm mi li mét.", SpokenTextNormalizer.normalize("Cắt ống dài 15 mm.", Language.VI))
        assertEquals("Điện áp hai trăm hai mươi vôn.", SpokenTextNormalizer.normalize("Điện áp 220 V.", Language.VI))
        assertEquals("Áp suất ba phẩy năm bar.", SpokenTextNormalizer.normalize("Áp suất 3.5 bar.", Language.VI))
    }

    @Test
    fun `English proposal measurements have explicit spoken forms`() {
        assertEquals("Set the gap to fifteen millimeters.", SpokenTextNormalizer.normalize("Set the gap to 15 mm.", Language.EN))
        assertEquals("Voltage is two hundred twenty volts.", SpokenTextNormalizer.normalize("Voltage is 220 V.", Language.EN))
        assertEquals("Pressure is three point five bar.", SpokenTextNormalizer.normalize("Pressure is 3.5 bar.", Language.EN))
    }

    @Test
    fun `machine identifiers are never rewritten as quantities`() {
        val vi = "Dừng máy A-12 ngay lập tức."
        val en = "Stop machine A-12 immediately."
        assertEquals(vi, SpokenTextNormalizer.normalize(vi, Language.VI))
        assertEquals(en, SpokenTextNormalizer.normalize(en, Language.EN))
        assertTrue("A-12" in SpokenTextNormalizer.normalize(vi, Language.VI))
        assertTrue("A-12" in SpokenTextNormalizer.normalize(en, Language.EN))
    }

    @Test
    fun `negation and safety phrases stay lexically intact`() {
        val vi = "Không mở van. Dừng máy ngay."
        val en = "Do not open the valve. Stop the machine immediately."
        assertEquals(vi, SpokenTextNormalizer.normalize(vi, Language.VI))
        assertEquals(en, SpokenTextNormalizer.normalize(en, Language.EN))
    }

    @Test
    fun `safety slots unrelated to verbalized measurement stay identical`() {
        val extractor = SafetyFrameExtractor()
        val viRaw = "Không mở van máy số 3 ở 220 V."
        val enRaw = "Do not open valve number 3 at 220 V."
        val viSpoken = SpokenTextNormalizer.normalize(viRaw, Language.VI)
        val enSpoken = SpokenTextNormalizer.normalize(enRaw, Language.EN)

        val viBefore = extractor.extract(viRaw, Language.VI)
        val viAfter = extractor.extract(viSpoken, Language.VI)
        assertEquals(viBefore.negation, viAfter.negation)
        assertEquals(viBefore.action, viAfter.action)
        assertEquals(viBefore.objectType, viAfter.objectType)
        assertEquals(viBefore.objectId, viAfter.objectId)

        val enBefore = extractor.extract(enRaw, Language.EN)
        val enAfter = extractor.extract(enSpoken, Language.EN)
        assertEquals(enBefore.negation, enAfter.negation)
        assertEquals(enBefore.action, enAfter.action)
        assertEquals(enBefore.objectType, enAfter.objectType)
        assertEquals(enBefore.objectId, enAfter.objectId)
    }

    @Test
    fun `unsupported product languages pass through until separately validated`() {
        val text = "220 V"
        assertEquals(text, SpokenTextNormalizer.normalize(text, Language.ZH))
        assertEquals(text, SpokenTextNormalizer.normalize(text, Language.KO))
    }
}
