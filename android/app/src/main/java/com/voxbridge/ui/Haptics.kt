package com.voxbridge.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Three-pattern haptic vocabulary — the co-primary output channel for the safety HMI (doc 14 §6,
 * UX-9..UX-12). In a loud factory the worker may not hear the spoken translation and may only
 * glance at the screen; a distinct buzz tells them, eyes-free, which of three things happened:
 *
 *   SAFE      one firm pulse           -> a usable translation is ready / "Understood" receipt
 *   SAY_AGAIN two short pulses         -> ASK_REPEAT, please repeat
 *   HAZARD    one long + two sharp     -> the message itself is a prohibition / danger / urgency
 *
 * The three patterns are deliberately discriminable by length and rhythm alone, not intensity, so
 * they survive a gloved hand and a phone in a chest pocket. If the device has no vibrator the class
 * degrades to a silent no-op (the visual traffic-light field is always the primary signal).
 * A separate optional 25 ms microphone-guidance pulse is advisory and never means ASK_REPEAT.
 *
 * minSdk 29: `VibrationEffect.createWaveform` is API 26+. `VibratorManager` is API 31+, so we branch
 * on Build.VERSION and fall back to the (deprecated-on-31) `VIBRATOR_SERVICE` lookup on 29–30.
 *
 * Manifest: requires `android.permission.VIBRATE` (normal permission, granted at install).
 *
 * STATUS: Android Vibrator wrapper, written against the platform API; not run on a device here.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    /** One firm pulse — "safe, act on it" / receipt of an Understood tap. */
    fun safe() = play(SAFE)

    /** Two short pulses — "I didn't catch that, say it again." */
    fun sayAgain() = play(SAY_AGAIN)

    /** Long + two sharp — the translated message is a prohibition / danger / urgent stop. */
    fun hazard() = play(HAZARD)

    /** A short tick the moment capture really starts, so a gloved thumb knows it is recording. */
    fun captureStarted() = play(CAPTURE_START)

    /** Optional live microphone guidance; distinct from the terminal SAY_AGAIN pattern. */
    fun audioQualityWarning() = play(AUDIO_QUALITY)

    private fun play(timings: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // repeat = -1: play once, do not loop.
        v.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private fun resolveVibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    companion object {
        // Timing arrays are [offDelay, on, off, on, ...] ms — index 0 is the initial wait.
        val SAFE = longArrayOf(0, 60)
        val SAY_AGAIN = longArrayOf(0, 40, 80, 40)
        val HAZARD = longArrayOf(0, 140, 60, 40, 60, 40)
        private val AUDIO_QUALITY = longArrayOf(0, 25)
        private val CAPTURE_START = longArrayOf(0, 18)
    }
}
