package com.voxbridge.ui

import com.voxbridge.core.audio.AudioQualityState
import com.voxbridge.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPresentationTest {
    @Test fun `listening wins over any previous result`() {
        assertEquals(TurnMode.LISTENING, turnModeOf("LISTENING", true, "TRANSLATED", "Hello"))
    }

    @Test fun `processing stages show the working panel`() {
        assertEquals(TurnMode.WORKING, turnModeOf("UNDERSTANDING", false, "", ""))
        assertEquals(TurnMode.WORKING, turnModeOf("TRANSLATING", false, "", ""))
        assertEquals(1, workingStepOf("TRANSLATING"))
    }

    @Test fun `a repeat prompt being spoken still shows the repeat panel`() {
        assertEquals(TurnMode.REPEAT, turnModeOf("SPEAKING", false, "ASK_REPEAT (audio_too_noisy)", ""))
        assertEquals(TurnMode.NO_SPEECH, turnModeOf("SPEAKING", false, "NO_SPEECH", ""))
    }

    @Test fun `accepted translation shows result while it is spoken and after`() {
        assertEquals(TurnMode.RESULT, turnModeOf("SPEAKING", false, "VERIFIED", "Stop the machine."))
        assertEquals(TurnMode.RESULT, turnModeOf("READY", false, "REPAIRED", "Close valve 3."))
        assertEquals(TurnMode.IDLE, turnModeOf("READY", false, "", ""))
    }

    @Test fun `direction preparation states are distinct from idle`() {
        assertEquals(TurnMode.PREPARING_DIRECTION, turnModeOf("PREPARING_ASR", false, "", ""))
        assertEquals(TurnMode.PREPARATION_FAILED, turnModeOf("PREPARATION_FAILED", false, "", ""))
    }

    @Test fun `every ask repeat reason gets a specific physical action in the speaker language`() {
        val reasons = listOf(
            "audio_clipped", "audio_too_noisy", "audio_low_quality", "speech_too_short", "speech_too_long",
            "safety_slot_lost:NUMBER", "translation_token_limit", "runtime error",
        )
        val vi = reasons.map { repeatGuidance("ASK_REPEAT ($it)", Language.VI) }
        assertEquals("every reason has its own advice", reasons.size, vi.map { it.action }.toSet().size)
        assertEquals("Câu bị cắt ngắn", repeatGuidance("ASK_REPEAT (speech_too_short)", Language.VI).title)
        assertEquals(
            "Hold the mic to your mouth, cup it, and say it again.",
            repeatGuidance("ASK_REPEAT (audio_too_noisy)", Language.EN).action,
        )
        assertEquals("Máy chưa nghe rõ", repeatGuidance("ASK_REPEAT (asr_low_confidence)", Language.VI).title)
    }

    @Test fun `live guidance warns with an action and never blames the speaker`() {
        val noisy = liveGuidance(AudioQualityState.TOO_NOISY, Language.VI)
        assertEquals(GuidanceTone.WARNING, noisy.tone)
        assertEquals("Đưa micro sát miệng, lấy tay che bớt", noisy.action)
        assertEquals(GuidanceTone.DANGER, liveGuidance(AudioQualityState.CLIPPING, Language.EN).tone)
        assertEquals(liveGuidance(null, Language.VI), liveGuidance(AudioQualityState.NO_SPEECH, Language.VI))
        assertNotEquals(
            liveGuidance(AudioQualityState.GOOD, Language.VI).title,
            liveGuidance(AudioQualityState.GOOD, Language.EN).title,
        )
    }

    @Test fun `hold timer counts up then down before the capture cap`() {
        assertEquals("3 giây", holdTimerText(3_400, 30_000, Language.VI))
        assertEquals("Còn 5 giây", holdTimerText(25_000, 30_000, Language.VI))
        assertEquals("1 s left", holdTimerText(29_500, 30_000, Language.EN))
        assertEquals("Còn 0 giây", holdTimerText(31_000, 30_000, Language.VI))
        assertFalse(isHoldNearLimit(10_000, 30_000))
        assertTrue(isHoldNearLimit(26_000, 30_000))
    }

    @Test fun `idle copy names the language to speak and the language that will be read out`() {
        val vi = idleCopy(Language.VI, Language.EN)
        assertEquals("Giữ nút và nói tiếng Việt", vi.title)
        assertTrue(vi.detail.endsWith("bản dịch tiếng Anh."))
        assertTrue(idleCopy(Language.EN, Language.VI).detail.endsWith("in Vietnamese."))
    }

    @Test fun `playback failure and low volume detection`() {
        assertTrue(isPlaybackFailure("TTS FAILED: AudioTrack"))
        assertTrue(isPlaybackFailure("TTS cache FAILED: io"))
        assertFalse(isPlaybackFailure("TTS: playback complete (420 ms)"))
        assertTrue(isMediaVolumeLow(3, 15))
        assertFalse(isMediaVolumeLow(8, 15))
        assertFalse(isMediaVolumeLow(0, 0))
    }
}
