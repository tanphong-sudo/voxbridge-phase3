package com.voxbridge.core

import com.voxbridge.core.audio.AudioQualityConfig
import com.voxbridge.core.audio.AudioQualityMonitor
import com.voxbridge.core.audio.AudioQualitySnapshot
import com.voxbridge.core.audio.AudioQualityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioQualityMonitorTest {
    private fun frame(amplitude: Float, count: Int = 512) = FloatArray(count) { amplitude }

    private fun feed(
        monitor: AudioQualityMonitor,
        frames: Int,
        amplitude: Float,
        probability: Float?,
    ): AudioQualitySnapshot {
        var latest = monitor.observe(frame(amplitude), vadProbability = probability)
        repeat(frames - 1) { latest = monitor.observe(frame(amplitude), vadProbability = probability) }
        return latest
    }

    @Test fun silenceStaysListeningAndProducesFiniteSilenceMetrics() {
        val monitor = AudioQualityMonitor()
        val current = feed(monitor, 20, 0f, .05f)
        val summary = monitor.summary()
        assertEquals(AudioQualityState.NO_SPEECH, current.state)
        assertEquals(-120.0, current.rmsDbfs)
        assertEquals(640L, summary.monitoredDurationMs)
        assertEquals(0.0, summary.clippingRatio)
        assertEquals(1.0, summary.noSpeechRatio)
        assertNull(summary.speechRmsMean)
        assertEquals(0.0, summary.noiseRmsMean)
        assertTrue(current.noiseReady)
        assertEquals(-120.0, current.noiseFloorDbfs)
    }

    @Test fun weakVadConfirmedSpeechNeedsSustainedWarning() {
        val monitor = AudioQualityMonitor()
        assertEquals(AudioQualityState.NO_SPEECH, feed(monitor, 7, .005f, .9f).state)
        assertEquals(AudioQualityState.TOO_QUIET, monitor.observe(frame(.005f), vadProbability = .9f).state)
        assertEquals(1.0, monitor.summary().quietRatio)
        assertNotNull(monitor.summary().speechRmsMean)
    }

    @Test fun normalSpeechCanBeGoodWithoutInventingAnUnknownNoiseFloor() {
        val monitor = AudioQualityMonitor()
        assertEquals(AudioQualityState.NO_SPEECH, feed(monitor, 4, .08f, .9f).state)
        val current = monitor.observe(frame(.08f), vadProbability = .9f)
        assertEquals(AudioQualityState.GOOD, current.state)
        assertFalse(current.noiseReady)
        assertNull(current.noiseFloorDbfs)
        assertNull(current.snrProxyDb)
        assertEquals(1.0, monitor.summary().goodRatio)
    }

    @Test fun loudBackgroundAndLowSpeechContrastWarnTooNoisy() {
        val monitor = AudioQualityMonitor()
        feed(monitor, 10, .05f, .05f)
        val current = feed(monitor, 8, .06f, .9f)
        assertEquals(AudioQualityState.TOO_NOISY, current.state)
        assertTrue(current.noiseReady)
        assertTrue(assertNotNull(current.snrProxyDb) < 6.0)
        assertNotNull(monitor.summary().snrProxyMean)
        assertTrue(monitor.summary().noisyRatio > 0.0)
    }

    @Test fun loudNonSpeechEventuallyWarnsWithoutCallingItSpeech() {
        val monitor = AudioQualityMonitor()
        val current = feed(monitor, 20, .1f, .05f)
        assertEquals(AudioQualityState.TOO_NOISY, current.state)
        assertNull(monitor.summary().speechRmsMean)
        assertNull(current.snrProxyDb)
    }

    @Test fun strongSpeechAboveANoisyFloorCanStillBeGood() {
        val monitor = AudioQualityMonitor()
        feed(monitor, 20, .05f, .05f)
        val current = feed(monitor, 8, .3f, .9f)
        assertEquals(AudioQualityState.GOOD, current.state)
        assertTrue(assertNotNull(current.snrProxyDb) > 6.0)
    }

    @Test fun clippingHasPriorityAndDoesNotRequireAVadProbability() {
        val monitor = AudioQualityMonitor()
        val current = feed(monitor, 8, 1f, null)
        assertEquals(AudioQualityState.CLIPPING, current.state)
        assertEquals(1f, current.peak)
        assertEquals(1.0, current.clippingRatio)
        assertEquals(1.0, monitor.summary().clippingRatio)
        assertFalse(monitor.summary().vadAvailable)
    }

    @Test fun isolatedPeaksDoNotCountAsSustainedClipping() {
        val samples = frame(.08f).also { it[0] = 1f }
        val monitor = AudioQualityMonitor()
        repeat(10) { monitor.observe(samples, vadProbability = .9f) }
        assertEquals(AudioQualityState.GOOD, monitor.summary().finalAudioQuality)
        assertEquals(1.0 / 512, monitor.summary().clippingRatio)
    }

    @Test fun briefAlternatingWarningsDoNotFlickerOrAccumulateAcrossGoodFrames() {
        val monitor = AudioQualityMonitor()
        feed(monitor, 5, .08f, .9f)
        repeat(8) {
            assertEquals(AudioQualityState.GOOD, feed(monitor, 4, .005f, .9f).state)
            assertEquals(AudioQualityState.GOOD, monitor.observe(frame(.08f), vadProbability = .9f).state)
        }
        assertTrue(monitor.summary().quietRatio > 0.0)
        assertEquals(AudioQualityState.GOOD, monitor.summary().finalAudioQuality)
    }

    @Test fun recoveryRequiresAStableInterval() {
        val monitor = AudioQualityMonitor()
        feed(monitor, 8, .005f, .9f)
        assertEquals(AudioQualityState.TOO_QUIET, feed(monitor, 4, .08f, .9f).state)
        assertEquals(AudioQualityState.TOO_QUIET, monitor.observe(frame(.08f, 256), vadProbability = .9f).state)
        assertEquals(AudioQualityState.GOOD, monitor.observe(frame(.08f, 256), vadProbability = .9f).state)
    }

    @Test fun pausesUseNoSpeechHangoverInsteadOfInstantlyFlashingListening() {
        val monitor = AudioQualityMonitor()
        feed(monitor, 5, .08f, .9f)
        assertEquals(AudioQualityState.GOOD, feed(monitor, 6, 0f, .05f).state)
        assertEquals(AudioQualityState.NO_SPEECH, monitor.observe(frame(0f), vadProbability = .05f).state)
    }

    @Test fun onlyConfidentNonSpeechLearnsNoiseAndSpeechCannotContaminateIt() {
        val monitor = AudioQualityMonitor()
        val floor = feed(monitor, 10, .002f, .05f).noiseFloorDbfs
        assertNotNull(floor)
        assertEquals(floor, feed(monitor, 30, .25f, .9f).noiseFloorDbfs)
        assertEquals(floor, feed(monitor, 30, .25f, .4f).noiseFloorDbfs)
        assertEquals(floor, feed(monitor, 30, .25f, null).noiseFloorDbfs)
    }

    @Test fun noiseWarmupAndEmaAreConservative() {
        val monitor = AudioQualityMonitor()
        val beforeReady = feed(monitor, 9, .01f, .05f)
        assertFalse(beforeReady.noiseReady)
        assertNull(beforeReady.noiseFloorDbfs)
        val ready = monitor.observe(frame(.01f), vadProbability = .05f)
        assertTrue(ready.noiseReady)
        val moved = monitor.observe(frame(.1f), vadProbability = .05f)
        val movedFloor = assertNotNull(moved.noiseFloorDbfs)
        assertTrue(movedFloor > assertNotNull(ready.noiseFloorDbfs))
        assertTrue(movedFloor < -20.0)
    }

    @Test fun clippedNonSpeechDoesNotCalibrateTheAmbientFloor() {
        val monitor = AudioQualityMonitor()
        val clipped = feed(monitor, 20, 1f, .05f)
        assertEquals(AudioQualityState.CLIPPING, clipped.state)
        assertFalse(clipped.noiseReady)
        assertNull(clipped.noiseFloorDbfs)
        assertNull(monitor.summary().noiseRmsMean)
        assertTrue(feed(monitor, 10, .002f, .05f).noiseReady)
    }

    @Test fun unavailableOrAmbiguousVadNeverInventsSpeechOrNoise() {
        for (probability in listOf(null, .4f)) {
            val monitor = AudioQualityMonitor()
            val current = feed(monitor, 25, .1f, probability)
            assertEquals(AudioQualityState.NO_SPEECH, current.state)
            assertFalse(current.noiseReady)
            assertNull(current.noiseFloorDbfs)
            assertNull(current.snrProxyDb)
            assertNull(monitor.summary().speechRmsMean)
            assertNull(monitor.summary().noiseRmsMean)
        }
    }

    @Test fun sampleTimeAndRatiosAreSampleWeightedAndSnapshotsAreImmutable() {
        val monitor = AudioQualityMonitor()
        val first = monitor.observe(frame(.08f, 1024), vadProbability = .9f)
        val firstCopy = first.copy()
        monitor.observe(frame(.005f), vadProbability = .9f)
        val summary = monitor.summary()
        assertEquals(firstCopy, first)
        assertEquals(96L, summary.monitoredDurationMs)
        assertEquals(2.0 / 3, summary.goodRatio)
        assertEquals(1.0 / 3, summary.quietRatio)
        assertEquals(1.0, summary.goodRatio + summary.quietRatio + summary.noisyRatio +
            summary.clippingStateRatio + summary.noSpeechRatio, 1e-12)
    }

    @Test fun observationDoesNotModifyOrRetainTheReusableVadBuffer() {
        val samples = frame(.08f)
        val before = samples.copyOf()
        val monitor = AudioQualityMonitor()
        val snapshot = monitor.observe(samples, vadProbability = .9f)
        val summary = monitor.summary()
        assertContentEquals(before, samples)
        samples.fill(1f)
        assertEquals(.08f, snapshot.peak)
        assertEquals(summary, monitor.summary())
    }

    @Test fun independentCapturesDoNotReuseTheNoiseFloor() {
        val old = AudioQualityMonitor()
        feed(old, 10, .1f, .05f)
        val fresh = AudioQualityMonitor()
        assertFalse(fresh.observe(frame(.08f), vadProbability = .9f).noiseReady)
        assertEquals(32L, fresh.summary().monitoredDurationMs)
        assertEquals(0L, AudioQualityMonitor().summary().monitoredDurationMs)
        assertEquals(AudioQualityState.NO_SPEECH, AudioQualityMonitor().summary().finalAudioQuality)
    }

    @Test fun offsetSlicesAndDifferentSampleRatesUseTheActualObservedDuration() {
        val monitor = AudioQualityMonitor(sampleRate = 48_000)
        val pcm = FloatArray(2048) { if (it in 512 until 1536) .1f else 1f }
        val current = monitor.observe(pcm, offset = 512, count = 1024)
        assertEquals(.1f, current.peak)
        assertEquals(21L, current.monitoredDurationMs)
        assertFalse(current.noiseReady)
        assertEquals(0.0, current.clippingRatio)
    }

    @Test fun prototypeThresholdsCanBeConfiguredWithoutChangingTheUi() {
        val monitor = AudioQualityMonitor(config = AudioQualityConfig(warningHoldMs = 64, goodHoldMs = 64))
        assertEquals(AudioQualityState.TOO_QUIET, feed(monitor, 2, .005f, .9f).state)
        assertEquals(AudioQualityState.GOOD, feed(monitor, 2, .08f, .9f).state)
    }

    @Test fun invalidFrameEvidenceFailsBeforeUpdatingTheMonitor() {
        val monitor = AudioQualityMonitor()
        for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.01f, -1.01f)) {
            assertFailsWith<IllegalArgumentException> { monitor.observe(floatArrayOf(sample), vadProbability = .9f) }
        }
        for (probability in listOf(Float.NaN, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { monitor.observe(frame(.1f), vadProbability = probability) }
        }
        assertFailsWith<IllegalArgumentException> { monitor.observe(FloatArray(0)) }
        assertFailsWith<IllegalArgumentException> { monitor.observe(frame(.1f), offset = -1) }
        assertFailsWith<IllegalArgumentException> { monitor.observe(frame(.1f), count = 513) }
        assertEquals(0L, monitor.summary().monitoredDurationMs)
    }

    @Test fun invalidConfigAndSampleRateAreRejected() {
        assertFailsWith<IllegalArgumentException> { AudioQualityMonitor(sampleRate = 0) }
        assertFailsWith<IllegalArgumentException> { AudioQualityConfig(warningHoldMs = -1) }
        assertFailsWith<IllegalArgumentException> { AudioQualityConfig(noiseEmaTimeConstantMs = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { AudioQualityConfig(nonSpeechVadThreshold = .8f, speechVadThreshold = .6f) }
        assertFailsWith<IllegalArgumentException> { AudioQualityConfig(clippingSampleRatio = 0.0) }
        assertFailsWith<IllegalArgumentException> { AudioQualityConfig(quietSpeechDbfs = 5.0) }
    }
}
