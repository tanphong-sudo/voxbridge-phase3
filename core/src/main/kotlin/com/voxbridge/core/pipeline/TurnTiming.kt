package com.voxbridge.core.pipeline

import java.util.Locale

/**
 * Per-turn latency evidence, measured from end of speech (button release) to the first audio
 * written to the speaker. The Phase 2 commitment is end-of-speech -> first audio <= 2.0 s.
 *
 * A null [firstAudioMs] means nothing has played yet (e.g. a repaired translation waiting for the
 * confirm tap); the budget is then UNKNOWN (null), never reported as passed.
 */
data class TurnTiming(
    val speechEndMs: Long,
    val asrMs: Long?,
    val decideMs: Long?,
    val asrBackend: String? = null,
    val firstAudioMs: Long? = null,
) {
    val withinBudget: Boolean? get() = firstAudioMs?.let { it <= FIRST_AUDIO_BUDGET_MS }

    /** Records the first audio once; later chunks or replays never overwrite it. */
    fun withFirstAudioAt(nowMs: Long): TurnTiming =
        if (firstAudioMs != null) this else copy(firstAudioMs = nowMs - speechEndMs)

    /** Short on-screen line so the demo video shows real processing time per stage. */
    fun summary(): String {
        val parts = ArrayList<String>(3)
        parts += asrMs?.let { ms -> "ASR $ms ms" + (asrBackend?.let { " ($it)" } ?: "") } ?: "ASR bỏ qua"
        decideMs?.let { parts += "Dịch+an toàn $it ms" }
        parts += when (val audio = firstAudioMs) {
            null -> "Âm thanh đầu: chưa phát"
            else -> "Âm thanh đầu $audio ms " + if (audio <= FIRST_AUDIO_BUDGET_MS) "✓" else "✗ >2 s"
        }
        return parts.joinToString(" · ")
    }

    /** Stable key=value line for logcat parsing in device/QDC runs. */
    fun logLine(): String =
        "asrMs=${asrMs ?: "na"} decideMs=${decideMs ?: "na"} firstAudioMs=${firstAudioMs ?: "na"} " +
            "asrBackend=${asrBackend ?: "na"} withinBudget=${withinBudget ?: "na"}"

    /** Short worker-facing status; never presents a pass before AudioTrack has started. */
    fun displaySummary(): String = firstAudioMs?.let { "Phát sau ${String.format(Locale.forLanguageTag("vi"), "%.2f", it / 1000.0)} s" }
        ?: "Chưa phát âm thanh"

    companion object {
        const val FIRST_AUDIO_BUDGET_MS = 2_000L
    }
}
