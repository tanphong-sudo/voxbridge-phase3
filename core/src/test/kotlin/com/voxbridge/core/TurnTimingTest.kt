package com.voxbridge.core

import com.voxbridge.core.pipeline.TurnTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnTimingTest {
    @Test fun `worker display is compact and cannot claim audio before playback starts`() {
        val initial = TurnTiming(speechEndMs = 1000, asrMs = 300, decideMs = 100)
        assertEquals("Chưa phát âm thanh", initial.displaySummary())
        assertEquals("Phát sau 1,25 s", initial.withFirstAudioAt(2250).displaySummary())
    }

    @Test
    fun `first audio latency is measured from end of speech`() {
        val timing = TurnTiming(speechEndMs = 1_000, asrMs = 300, decideMs = 120)
            .withFirstAudioAt(2_250)
        assertEquals(1_250, timing.firstAudioMs)
        assertTrue(timing.withinBudget == true)
    }

    @Test
    fun `budget is exceeded above two seconds`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 1_500, decideMs = 400).withFirstAudioAt(2_001)
        assertFalse(timing.withinBudget == true)
    }

    @Test
    fun `no audio yet means budget is unknown, not passed`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 300, decideMs = 100)
        assertNull(timing.firstAudioMs)
        assertNull(timing.withinBudget)
    }

    @Test
    fun `first audio is recorded only once`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 300, decideMs = 100)
            .withFirstAudioAt(900)
            .withFirstAudioAt(5_000)
        assertEquals(900, timing.firstAudioMs)
    }

    @Test
    fun `summary shows every stage that ran`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 312, decideMs = 85, asrBackend = "NPU")
            .withFirstAudioAt(640)
        assertEquals("ASR 312 ms (NPU) · Dịch+an toàn 85 ms · Âm thanh đầu 640 ms ✓", timing.summary())
    }

    @Test
    fun `summary marks skipped ASR and waiting audio honestly`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = null, decideMs = null)
        assertEquals("ASR bỏ qua · Âm thanh đầu: chưa phát", timing.summary())
    }

    @Test
    fun `summary flags an over-budget turn`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 1_800, decideMs = 300).withFirstAudioAt(2_600)
        assertEquals("ASR 1800 ms · Dịch+an toàn 300 ms · Âm thanh đầu 2600 ms ✗ >2 s", timing.summary())
    }

    @Test
    fun `log line is machine parseable`() {
        val timing = TurnTiming(speechEndMs = 0, asrMs = 312, decideMs = 85, asrBackend = "CPU")
            .withFirstAudioAt(640)
        assertEquals("asrMs=312 decideMs=85 firstAudioMs=640 asrBackend=CPU withinBudget=true", timing.logLine())
    }
}
