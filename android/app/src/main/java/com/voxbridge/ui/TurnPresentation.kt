package com.voxbridge.ui

import com.voxbridge.core.audio.AudioQualityState
import com.voxbridge.core.model.Language

/**
 * Pure, Android-free mapping from ViewModel state to what the worker sees for the current turn.
 *
 * Every worker-facing sentence lives here so it can be unit-tested off-device and kept in one
 * voice. Guidance is written in the SPEAKER's language (the person holding the button), tells them
 * one physical thing to change, and treats a failed turn as the device's limitation, not theirs.
 */
enum class TurnMode {
    IDLE, LISTENING, WORKING, RESULT, REPEAT, NO_SPEECH, PREPARING_DIRECTION, PREPARATION_FAILED,
}

fun turnModeOf(stage: String, listening: Boolean, decision: String, output: String): TurnMode = when {
    listening -> TurnMode.LISTENING
    stage == "PREPARATION_FAILED" -> TurnMode.PREPARATION_FAILED
    stage == "PREPARING_ASR" -> TurnMode.PREPARING_DIRECTION
    stage == "UNDERSTANDING" || stage == "TRANSLATING" -> TurnMode.WORKING
    decision.startsWith("ASK_REPEAT") -> TurnMode.REPEAT
    decision.startsWith("NO_SPEECH") -> TurnMode.NO_SPEECH
    shouldShowAcceptedOutput(decision, output) -> TurnMode.RESULT
    else -> TurnMode.IDLE
}

/** The three steps between release and first audio; the index of the one running now. */
fun workingStepOf(stage: String): Int = when (stage) {
    "UNDERSTANDING" -> 0
    "TRANSLATING" -> 1
    "SPEAKING" -> 2
    else -> 0
}

fun workingSteps(source: Language): List<String> =
    if (source == Language.VI) listOf("Nghe", "Dịch", "Đọc") else listOf("Listen", "Translate", "Speak")

fun languageName(language: Language, inLanguage: Language): String =
    if (inLanguage == Language.VI) {
        if (language == Language.VI) "Tiếng Việt" else "Tiếng Anh"
    } else {
        if (language == Language.VI) "Vietnamese" else "English"
    }

/** Name a direction pill by its own language, so each speaker finds theirs at a glance. */
fun ownLanguageName(language: Language): String = if (language == Language.VI) "Tiếng Việt" else "English"

data class IdleCopy(val title: String, val detail: String)

fun idleCopy(source: Language, target: Language): IdleCopy =
    if (source == Language.VI) IdleCopy(
        "Giữ nút và nói tiếng Việt",
        "Nói xong thì thả nút. Máy sẽ đọc to bản dịch ${languageName(target, Language.VI).replaceFirstChar { it.lowercase() }}.",
    ) else IdleCopy(
        "Hold the button and speak English",
        "Let go when you finish. The phone reads the translation aloud in ${languageName(target, Language.EN)}.",
    )

enum class GuidanceTone { NEUTRAL, GOOD, WARNING, DANGER }

data class LiveGuidance(val title: String, val action: String, val tone: GuidanceTone)

/** What to show above the button while it is held, from the debounced live quality state. */
fun liveGuidance(state: AudioQualityState?, source: Language): LiveGuidance {
    val vi = source == Language.VI
    return when (state) {
        AudioQualityState.GOOD -> LiveGuidance(
            if (vi) "Nghe rõ" else "Coming through clearly",
            if (vi) "Nói xong thì thả nút" else "Let go when you finish",
            GuidanceTone.GOOD,
        )
        AudioQualityState.TOO_QUIET -> LiveGuidance(
            if (vi) "Tiếng nhỏ quá" else "Too quiet",
            if (vi) "Nói to hơn hoặc đưa máy lại gần miệng" else "Speak up or bring the phone closer",
            GuidanceTone.WARNING,
        )
        AudioQualityState.TOO_NOISY -> LiveGuidance(
            if (vi) "Xung quanh ồn quá" else "Too much background noise",
            if (vi) "Đưa micro sát miệng, lấy tay che bớt" else "Hold the mic to your mouth and cup it",
            GuidanceTone.WARNING,
        )
        AudioQualityState.CLIPPING -> LiveGuidance(
            if (vi) "To quá, tiếng bị rè" else "Too loud, sound is distorting",
            if (vi) "Đưa máy ra xa miệng một chút" else "Move the phone a little away",
            GuidanceTone.DANGER,
        )
        AudioQualityState.NO_SPEECH, null -> LiveGuidance(
            if (vi) "Đang nghe" else "Listening",
            if (vi) "Cứ nói bình thường" else "Speak normally",
            GuidanceTone.NEUTRAL,
        )
    }
}

const val HOLD_WARNING_REMAINING_MS = 5_000L

/** Elapsed seconds while holding; switches to a countdown in the last seconds before the cap. */
fun holdTimerText(elapsedMs: Long, limitMs: Long, source: Language): String {
    val remaining = (limitMs - elapsedMs).coerceAtLeast(0)
    val vi = source == Language.VI
    return if (remaining <= HOLD_WARNING_REMAINING_MS) {
        val s = (remaining + 999) / 1000
        if (vi) "Còn $s giây" else "$s s left"
    } else {
        val s = elapsedMs.coerceAtLeast(0) / 1000
        if (vi) "$s giây" else "$s s"
    }
}

fun isHoldNearLimit(elapsedMs: Long, limitMs: Long): Boolean = limitMs - elapsedMs <= HOLD_WARNING_REMAINING_MS

/** Why the turn did not produce a translation, and the one thing to do differently. */
data class RepeatGuidance(val title: String, val action: String)

fun repeatGuidance(decision: String, source: Language): RepeatGuidance {
    val reason = decision.substringAfter('(', "").substringBeforeLast(')').trim().lowercase()
    val vi = source == Language.VI
    fun g(viTitle: String, viAction: String, enTitle: String, enAction: String) =
        if (vi) RepeatGuidance(viTitle, viAction) else RepeatGuidance(enTitle, enAction)
    return when {
        "clip" in reason -> g(
            "Tiếng bị rè vì nói quá sát", "Cầm máy cách miệng một gang tay rồi nói lại.",
            "The sound distorted", "Hold the phone a hand's width from your mouth and say it again.",
        )
        "noisy" in reason -> g(
            "Xung quanh ồn quá", "Đưa micro sát miệng, lấy tay che bớt rồi nói lại.",
            "Too much background noise", "Hold the mic to your mouth, cup it, and say it again.",
        )
        "low_quality" in reason -> g(
            "Tiếng thu được chưa rõ", "Đưa máy lại gần miệng và nói to hơn.",
            "The recording was unclear", "Bring the phone closer and speak up.",
        )
        "too_short" in reason -> g(
            "Câu bị cắt ngắn", "Giữ nút suốt lúc nói, nói hết câu rồi mới thả.",
            "The sentence was cut off", "Keep holding while you speak and let go only at the end.",
        )
        "too_long" in reason -> g(
            "Câu dài quá 30 giây", "Chia thành từng câu ngắn, mỗi lần giữ nút một câu.",
            "Longer than 30 seconds", "Split it into short sentences, one per press.",
        )
        "slot_lost" in reason || "safety_signal" in reason -> g(
            "Bản dịch có thể sai số hoặc tên", "Nói lại chậm, đọc rõ từng con số và mã máy.",
            "Numbers or names may be wrong", "Say it again slowly, reading each number and code clearly.",
        )
        "token_limit" in reason || "degenerate" in reason -> g(
            "Chưa dịch được câu này", "Nói lại ngắn gọn hơn, mỗi lần một ý.",
            "Could not translate that", "Say it again more simply, one point at a time.",
        )
        "runtime" in reason -> g(
            "Máy xử lý bị lỗi, đã sẵn sàng lại", "Giữ nút và nói lại câu vừa rồi.",
            "Processing failed and has recovered", "Hold the button and say it again.",
        )
        else -> g(
            "Máy chưa nghe rõ", "Nói lại chậm và rõ từng chữ.",
            "Didn't catch that", "Say it again, slowly and clearly.",
        )
    }
}

fun noSpeechGuidance(source: Language): RepeatGuidance =
    if (source == Language.VI) RepeatGuidance("Chưa nghe thấy tiếng nói", "Giữ nút rồi nói ngay, nói xong mới thả.")
    else RepeatGuidance("No speech heard", "Hold the button, start speaking right away, and let go at the end.")

/** TTS status strings look like "TTS FAILED: …" or "TTS cache FAILED: …". */
fun isPlaybackFailure(ttsStatus: String): Boolean = ttsStatus.contains(" FAILED")

/** Below this share of max media volume the translation is easy to miss on a loud floor. */
const val LOW_VOLUME_FRACTION = 0.5f

fun isMediaVolumeLow(current: Int, max: Int): Boolean = max > 0 && current.toFloat() / max < LOW_VOLUME_FRACTION
