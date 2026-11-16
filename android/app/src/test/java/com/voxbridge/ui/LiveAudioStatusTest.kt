package com.voxbridge.ui

import com.voxbridge.core.audio.AudioQualityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioStatusTest {
    @Test fun `status labels stay short and warnings include an action`() {
        assertEquals("Good", liveAudioStatus(AudioQualityState.GOOD).label)
        assertEquals("Move closer", liveAudioStatus(AudioQualityState.TOO_QUIET).label)
        assertEquals("Too noisy", liveAudioStatus(AudioQualityState.TOO_NOISY).label)
        assertEquals("Move phone closer", liveAudioStatus(AudioQualityState.TOO_NOISY).hint)
        assertEquals("Too loud", liveAudioStatus(AudioQualityState.CLIPPING).label)
        assertEquals("Move phone away", liveAudioStatus(AudioQualityState.CLIPPING).hint)
        assertEquals("Listening...", liveAudioStatus(AudioQualityState.NO_SPEECH).label)
        assertEquals(liveAudioStatus(AudioQualityState.NO_SPEECH), liveAudioStatus(null))
    }

    @Test fun `guidance has a text and icon signal as well as distinct tones`() {
        val good = liveAudioStatus(AudioQualityState.GOOD)
        val quiet = liveAudioStatus(AudioQualityState.TOO_QUIET)
        val noisy = liveAudioStatus(AudioQualityState.TOO_NOISY)
        val clipping = liveAudioStatus(AudioQualityState.CLIPPING)
        assertEquals(AudioQualityTone.GOOD, good.tone)
        assertEquals(AudioQualityTone.WARNING, quiet.tone)
        assertEquals(AudioQualityTone.WARNING, noisy.tone)
        assertEquals(AudioQualityTone.DANGER, clipping.tone)
        assertTrue(good.symbol.isNotBlank())
        assertTrue(noisy.symbol.isNotBlank())
        assertEquals("Too noisy. Move phone closer", noisy.accessibilityLabel)
        assertEquals("Move closer", quiet.accessibilityLabel)
        assertFalse(good.tone.argb == clipping.tone.argb)
    }

    @Test fun `quality haptic is disabled by default and is not a repeat request`() {
        val policy = LiveAudioHapticPolicy()
        assertFalse(policy.onState(listening = true, AudioQualityState.GOOD))
        assertFalse(policy.onState(listening = true, AudioQualityState.TOO_NOISY))
        assertFalse(policy.onState(listening = true, AudioQualityState.TOO_NOISY, enabled = true))
    }

    @Test fun `a stable good to fault transition emits once per utterance`() {
        val policy = LiveAudioHapticPolicy()
        assertFalse(policy.onState(true, AudioQualityState.NO_SPEECH, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.GOOD, enabled = true))
        assertTrue(policy.onState(true, AudioQualityState.TOO_QUIET, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.TOO_QUIET, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.GOOD, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.CLIPPING, enabled = true))
        assertFalse(policy.onState(false, null, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.GOOD, enabled = true))
        assertTrue(policy.onState(true, AudioQualityState.TOO_NOISY, enabled = true))
    }

    @Test fun `initial fault and enabling a setting mid-fault do not vibrate`() {
        val policy = LiveAudioHapticPolicy()
        assertFalse(policy.onState(true, AudioQualityState.CLIPPING, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.GOOD, enabled = false))
        assertFalse(policy.onState(true, AudioQualityState.TOO_NOISY, enabled = false))
        assertFalse(policy.onState(true, AudioQualityState.TOO_NOISY, enabled = true))
    }

    @Test fun `missing or no speech evidence does not emit a warning haptic`() {
        val policy = LiveAudioHapticPolicy()
        assertFalse(policy.onState(true, AudioQualityState.GOOD, enabled = true))
        assertFalse(policy.onState(true, null, enabled = true))
        assertFalse(policy.onState(true, AudioQualityState.NO_SPEECH, enabled = true))
        assertFalse(policy.onState(false, AudioQualityState.CLIPPING, enabled = true))
    }

    @Test fun `unavailable metrics are never presented as a measured zero`() {
        assertEquals("unavailable", formatAudioQualityValue(null))
        assertEquals("unavailable", formatAudioQualityValue(Double.NaN))
        assertEquals("unavailable", formatAudioQualityValue(Double.POSITIVE_INFINITY))
        assertEquals("-21.3", formatAudioQualityValue(-21.25))
    }
}
