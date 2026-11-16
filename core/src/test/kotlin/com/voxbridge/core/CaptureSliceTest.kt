package com.voxbridge.core

import com.voxbridge.core.audio.CaptureAnalysis
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureSliceTest {
    @Test fun sliceUsesOriginalBufferAndMatchesLegacySummary() {
        val samples = FloatArray(512 * 4 + 15) { it.toFloat() / 2048 }
        val legacy = CaptureAnalysis.analyzeVad(samples, reset = {}, probability = { it[0] })
        var resets = 0
        var calls = 0
        val actual = CaptureAnalysis.analyzeVadSlice(samples, reset = { resets++ }) { pcm, offset, length ->
            assertSame(samples, pcm)
            assertEquals(512, length)
            assertEquals(calls++ * 512, offset)
            pcm[offset]
        }
        assertEquals(legacy, actual)
        assertEquals(1, resets)
        assertEquals(4, calls)
    }
}
