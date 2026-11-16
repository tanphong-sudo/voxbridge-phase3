package com.voxbridge.core

import com.voxbridge.core.audio.*
import kotlin.test.*

class LiveAudioQualityCaptureTest {
    @Test fun publishesBoundedScalarsAndDoesNotRetainOrChangePcm() {
        val snapshots = mutableListOf<AudioQualitySnapshot>()
        val capture = LiveAudioQualityCapture(onUpdate = { snapshots += requireNotNull(it) })
        val frame = FloatArray(512) { .1f }
        repeat(32) { capture.observe(frame, vadProbability = .9f) }
        assertEquals(AudioQualityState.GOOD, capture.snapshot?.state)
        assertTrue(snapshots.size in 8..12, "Publication must be near 10Hz, not every VAD frame")
        assertTrue(frame.all { it == .1f })
        val before = capture.summary()
        frame.fill(0f)
        assertEquals(before, capture.summary(), "Reusable PCM must not escape into telemetry")
        assertNull(capture.failure)
    }

    @Test fun stableWarningPublishesImmediatelyDespiteLongMeterInterval() {
        val snapshots = mutableListOf<AudioQualitySnapshot>()
        val capture = LiveAudioQualityCapture(onUpdate = { snapshots += requireNotNull(it) }, publishIntervalMs = 5_000)
        repeat(6) { capture.observe(FloatArray(512) { .1f }, vadProbability = .9f) }
        repeat(8) { capture.observe(FloatArray(512) { .001f }, vadProbability = .9f) }
        assertEquals(AudioQualityState.TOO_QUIET, snapshots.last().state)
        assertEquals(448L, snapshots.last().monitoredDurationMs)
    }

    @Test fun guidanceCallbackFailureCannotChangeExistingVadOrAbortCapture() {
        val capture = LiveAudioQualityCapture(onUpdate = { error("consumer unavailable") })
        var vadCalls = 0
        val stream = StreamingVadAnalysis({}, { vadCalls++; .9f },
            onFrame = { frame, p -> capture.observe(frame, vadProbability = p) })
        stream.append(FloatArray(1024) { .1f }, 0, 1024)
        assertEquals(2, vadCalls)
        assertEquals(2, stream.summary().speechFrames)
        assertEquals("IllegalStateException", capture.failure)
        assertNull(capture.snapshot)
        assertNull(capture.summary())
    }

    @Test fun absentLiveVadUsesConservativeStateAndDoesNotLearnNoise() {
        val capture = LiveAudioQualityCapture(sampleRate = 48_000, onUpdate = {})
        repeat(30) { capture.observe(FloatArray(1024) { .2f }) }
        assertEquals(AudioQualityState.NO_SPEECH, capture.snapshot?.state)
        assertNull(capture.snapshot?.noiseFloorDbfs)
        assertNull(capture.snapshot?.snrProxyDb)
        assertFalse(capture.summary()!!.vadAvailable)
    }

    @Test fun disabledGuidanceDoesNotEvaluateFrames() {
        val capture = LiveAudioQualityCapture()
        capture.observe(floatArrayOf(Float.NaN), vadProbability = Float.NaN)
        assertNull(capture.snapshot)
        assertNull(capture.summary())
        assertNull(capture.failure)
    }
}
