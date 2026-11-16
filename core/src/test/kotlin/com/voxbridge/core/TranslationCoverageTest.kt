package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationCoverageTest {
    @Test fun emptyAndDecoderLoopsAreClearFailures() {
        assertTrue(TranslationCoverage.evaluate("Dừng máy.", "...", Language.VI).hardFailure)
        assertTrue(TranslationCoverage.evaluate("Dừng máy.", "Stop the machine stop the machine stop the machine.", Language.VI).hardFailure)
        assertTrue(TranslationCoverage.evaluate("Dừng máy.", "stop stop stop stop stop stop", Language.VI).hardFailure)
    }
    @Test fun legitimateSourceRepetitionIsNotAutomaticallyCollapse() {
        assertFalse(TranslationCoverage.evaluate("Go now go now go now.", "Đi ngay đi ngay đi ngay.", Language.EN).hardFailure)
        assertFalse(TranslationCoverage.evaluate("No, no. Please wait.", "Không, không. Xin chờ.", Language.EN).hardFailure)
        assertFalse(TranslationCoverage.evaluate("Đi! Đi! Đi!", "Go now go now go now!", Language.VI).hardFailure)
    }
    @Test fun checksMissingActionsEvenWhenTargetIsLong() {
        val verdict = TranslationCoverage.evaluate("Dừng máy. Chờ tôi đến.", "Stop the machine. Thank you for your patience and understanding.", Language.VI)
        assertTrue("wait" in verdict.missingUnits)
        assertFalse(verdict.hardFailure)
        assertEquals(listOf("Dừng máy.", "Chờ tôi đến."), verdict.retrySentences)
    }
    @Test fun shortCompleteParaphrasesAndSentenceMergesAreAllowed() {
        val v = TranslationCoverage.evaluate("Stop the machine. Wait for me.", "Dừng máy và đợi tôi.", Language.EN)
        assertTrue(v.missingUnits.isEmpty())
        assertFalse(v.hardFailure)
        assertTrue(v.retrySentences.isEmpty())
    }
    @Test fun splitterProtectsDecimalsIdsAndAbbreviations() {
        assertEquals(listOf("Áp suất 3.5 bar.", "Gọi BS. An ở phòng A-12."), TranslationCoverage.splitSentences("Áp suất 3.5 bar. Gọi BS. An ở phòng A-12."))
        assertEquals(listOf("Arrive at 9 a.m.", "Call Dr. Smith."), TranslationCoverage.splitSentences("Arrive at 9 a.m. Call Dr. Smith."))
        assertEquals(listOf("Use e.g. this valve.", "Call J. Smith."), TranslationCoverage.splitSentences("Use e.g. this valve. Call J. Smith."))
    }
    @Test fun retryIsBoundedAndDoesNotGuessCommaBoundaries() {
        val long = TranslationCoverage.evaluate("Stop. Wait. Call me. Wear a mask.", "", Language.EN)
        assertTrue(long.retrySentences.isEmpty())
        assertTrue(TranslationCoverage.evaluate("Stop and wait.", "Stop.", Language.EN).retrySentences.isEmpty())
    }
    @Test fun actionCuesHandleInflectionAndDoNotTreatRescueTeamAsWear() {
        assertTrue(TranslationCoverage.evaluate("Xác nhận máy đã dừng. Gọi tôi.", "Confirm the machine has stopped. Call me.", Language.VI).missingUnits.isEmpty())
        assertTrue(TranslationCoverage.evaluate("Gọi đội cứu hộ. Chờ ở cổng chính.", "Call the rescue team. Wait at the main gate.", Language.VI).missingUnits.isEmpty())
        assertTrue(TranslationCoverage.evaluate("Tôi vừa chuyển xong, anh kiểm tra nhé.", "I just transferred it. Check it out.", Language.VI).missingUnits.isEmpty())
        assertTrue(TranslationCoverage.evaluate("Evacuate this area. Wait outside.", "Di tản khu vực này, chờ bên ngoài.", Language.EN).missingUnits.isEmpty())
    }
    @Test fun numberDifferenceBelongsToSafetyAndCannotAloneTriggerCoverageRetry() {
        val v=TranslationCoverage.evaluate("Open valve 3.", "Mở van 2.", Language.EN)
        assertTrue(v.missingUnits.isEmpty())
        assertTrue(v.retrySentences.isEmpty())
    }
}
