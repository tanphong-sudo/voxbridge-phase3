package com.voxbridge.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordSourceTest {
    @Test
    fun `capture limit tracks the recorder sample rate for a 30 second Whisper window`() {
        assertEquals(480_000, AudioRecordSource.maxCaptureSamples(16_000))
        assertEquals(1_440_000, AudioRecordSource.maxCaptureSamples(48_000))
    }
}
