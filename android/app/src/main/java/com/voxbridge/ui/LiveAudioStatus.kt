package com.voxbridge.ui

import com.voxbridge.core.audio.AudioQualityState
import java.util.Locale

/** Presentation only: these colors never become translation or ASR decisions. */
enum class AudioQualityTone(val argb: Long) {
    NEUTRAL(0xFF9AA6B2),
    GOOD(0xFF36C98F),
    WARNING(0xFFFFCE73),
    DANGER(0xFFFF8A80),
}

data class LiveAudioStatusPresentation(
    val label: String,
    val hint: String?,
    val symbol: String,
    val tone: AudioQualityTone,
) {
    val accessibilityLabel: String
        get() = listOfNotNull(label, hint).joinToString(". ")
}

fun liveAudioStatus(state: AudioQualityState?): LiveAudioStatusPresentation = when (state) {
    AudioQualityState.GOOD -> LiveAudioStatusPresentation("Good", "Release to translate", "●", AudioQualityTone.GOOD)
    AudioQualityState.TOO_QUIET -> LiveAudioStatusPresentation("Move closer", null, "⚠", AudioQualityTone.WARNING)
    AudioQualityState.TOO_NOISY -> LiveAudioStatusPresentation("Too noisy", "Move phone closer", "⚠", AudioQualityTone.WARNING)
    AudioQualityState.CLIPPING -> LiveAudioStatusPresentation("Too loud", "Move phone away", "⚠", AudioQualityTone.DANGER)
    AudioQualityState.NO_SPEECH, null -> LiveAudioStatusPresentation("Listening...", "Start speaking", "●", AudioQualityTone.NEUTRAL)
}

/**
 * Receives already-debounced monitor states, not raw frame classifications.
 * Own in the ViewModel and reset per PTT; allow at most one optional pulse per utterance.
 */
class LiveAudioHapticPolicy {
    private data class State(val previous: AudioQualityState? = null, val alerted: Boolean = false)
    private var state = State()

    fun reset() { state = State() }

    fun onState(listening: Boolean, quality: AudioQualityState?, enabled: Boolean = false): Boolean {
        if (!listening) {
            reset()
            return false
        }
        val warning = quality == AudioQualityState.TOO_QUIET ||
            quality == AudioQualityState.TOO_NOISY || quality == AudioQualityState.CLIPPING
        val emit = enabled && !state.alerted && state.previous == AudioQualityState.GOOD && warning
        state = State(previous = quality, alerted = state.alerted || emit)
        return emit
    }
}

fun formatAudioQualityValue(value: Double?): String =
    if (value == null || !value.isFinite()) "unavailable" else String.format(Locale.US, "%.1f", value)
