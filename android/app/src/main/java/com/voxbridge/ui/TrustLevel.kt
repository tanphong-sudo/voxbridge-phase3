package com.voxbridge.ui

/**
 * Pure, Android-free trust mapping for the worker-facing HMI.
 *
 * This file has NO Compose / Android imports on purpose: it is the one piece of the UI whose logic
 * carries a safety meaning (which decision is "act on it" vs "say again" vs "nothing heard"), so it
 * is unit-tested off-device with plain `kotlinc` (tools/ui-verify) exactly like the decision core.
 * The Compose layer is a thin renderer over these three functions.
 *
 * The `decision` strings are produced verbatim by [com.voxbridge.ui.PushToTalkViewModel.render]
 * from the verified core's six states:
 *   NO_SPEECH | "ASK_REPEAT (<reason>)" | MEMORY | TRANSLATED | VERIFIED | REPAIRED
 * where <reason> is one of the DecisionEngine reasons: audio_clipped, audio_too_noisy,
 * audio_low_quality, speech_too_short, empty_transcript, asr_low_confidence,
 * likely_no_speech_hallucination, safety_slot_lost:<...>.
 *
 * STATUS: pure logic, unit-tested in tools/ui-verify (no device needed).
 */
enum class TrustLevel { GREEN, AMBER, GREY }

/** Worker-independent retry guidance: WHAT to physically change, not "you spoke wrong" (UX-23). */
enum class RetryHint { MIC_CLOSER, SOFTER, SLOWER, GENERIC }

/**
 * Map a decision string to a glanceable trust level.
 *  - ASK_REPEAT* -> AMBER (say it again)
 *  - NO_SPEECH   -> GREY  (nothing heard; neutral, not an error)
 *  - everything else (MEMORY/TRANSLATED/VERIFIED/REPAIRED) -> GREEN (a usable translation is present)
 *
 * REPAIRED is GREEN because by the time it is shown the safety slots have been restored; the confirm
 * gate (handled in the screen, FR-17) governs *speaking* it, not its trust color. A blank decision
 * (idle, before first utterance) is GREY.
 */
fun trustOf(decision: String): TrustLevel = when {
    decision.isBlank() -> TrustLevel.GREY
    decision.startsWith("ASK_REPEAT") -> TrustLevel.AMBER
    decision.startsWith("NO_SPEECH") -> TrustLevel.GREY
    else -> TrustLevel.GREEN
}

/** A retry hint + bilingual caption, framed as the device's limitation (never blaming the worker). */
data class RetryGuidance(val hint: RetryHint, val captionVi: String, val captionEn: String)

/**
 * Parse the parenthesised reason out of `"ASK_REPEAT (<reason>)"` and map it to a physical action.
 * Substring-matched so a compound reason (e.g. `safety_slot_lost:OBJECT_DROPPED`) still classifies.
 */
fun retryGuidance(decision: String): RetryGuidance {
    val reason = decision.substringAfter('(', "").substringBeforeLast(')').trim().lowercase()
    val hint = when {
        "clip" in reason -> RetryHint.SOFTER
        "noisy" in reason || "low_quality" in reason || "low_audio" in reason -> RetryHint.MIC_CLOSER
        "short" in reason || "asr" in reason || "confidence" in reason || "slot_lost" in reason -> RetryHint.SLOWER
        else -> RetryHint.GENERIC
    }
    return when (hint) {
        RetryHint.MIC_CLOSER -> RetryGuidance(hint, "Đưa máy lại gần miệng hơn", "Hold the device closer")
        RetryHint.SOFTER -> RetryGuidance(hint, "Nói nhỏ lại một chút", "Speak a little softer")
        RetryHint.SLOWER -> RetryGuidance(hint, "Nói lại chậm và rõ", "Say it again, slower")
        RetryHint.GENERIC -> RetryGuidance(hint, "Tôi nghe chưa rõ — nói lại nhé", "I didn't catch that — say again")
    }
}

// --- Hazard cue scan -------------------------------------------------------------------------------
// Drives the extra-emphatic HAZARD haptic + an accent stripe when a usable (GREEN) translation also
// carries a prohibition / urgency / danger word. This is a DISPLAY heuristic over the rendered text,
// not a safety decision: a false positive is only an extra buzz on an already-safe message, and a
// false negative still shows GREEN + the readable sentence. The clean alternative (a derived
// `hasHazard` flag on UiState, set by the core) is deliberately Phase-3 so the verified pipeline is
// not touched here. Cues are drawn from the core's own safety lexicon + doc 14's warning vocabulary.

private val hazardTokens = setOf(
    // VI prohibition / urgency / danger
    "không", "đừng", "cấm", "ngay", "khẩn", "dừng", "nguy",
    // EN prohibition / urgency / danger
    "never", "danger", "hazard", "immediately", "emergency", "stop", "evacuate",
)
private val hazardPhrases = listOf("do not", "nguy hiểm", "sơ tán", "high voltage", "điện cao")

/**
 * True if `text` (typically `output` + `repairedSlots`) contains a hazard / prohibition / urgency
 * cue. Single-word cues match on whole tokens (so they don't fire inside unrelated words); phrases
 * match as substrings. Vietnamese diacritics are preserved by the tokenizer, so `ngay` (immediately)
 * does not collide with `ngày` (day).
 */
fun containsHazardCue(text: String): Boolean {
    if (text.isBlank()) return false
    val lower = text.lowercase()
    if (hazardPhrases.any { it in lower }) return true
    val tokens = lower.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return tokens.any { it in hazardTokens }
}
