import com.voxbridge.ui.RetryHint
import com.voxbridge.ui.TrustLevel
import com.voxbridge.ui.containsHazardCue
import com.voxbridge.ui.retryGuidance
import com.voxbridge.ui.trustOf

/**
 * Off-device verification of the ONLY safety-meaningful logic in the UI layer: the decision→trust
 * mapping, the retry guidance parser, and the hazard-cue scan (TrustLevel.kt). That file has no
 * Android/Compose imports, so it compiles and runs under plain `kotlinc` — exactly like the
 * decision-core harnesses. The Compose screens themselves need the Android SDK + a device and are
 * Phase-3 render work; this harness pins the part whose correctness carries a worker-safety meaning.
 *
 * Every `decision` string below is one the verified core actually emits (DecisionEngine reasons +
 * the six ViewModel states) — not invented inputs.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("  PASS  $name") }
    else { failed++; println("  FAIL  $name  $detail") }
}

fun main() {
    println("== VoxBridge UI trust-logic verification ==\n")

    println("[trustOf — decision -> traffic light]")
    // GREEN: any usable translation is present.
    check("VERIFIED -> GREEN", trustOf("VERIFIED") == TrustLevel.GREEN)
    check("TRANSLATED -> GREEN", trustOf("TRANSLATED") == TrustLevel.GREEN)
    check("MEMORY -> GREEN", trustOf("MEMORY") == TrustLevel.GREEN)
    check("REPAIRED -> GREEN", trustOf("REPAIRED") == TrustLevel.GREEN)
    // AMBER: every ASK_REPEAT reason the core can emit.
    check("ASK_REPEAT (audio_clipped) -> AMBER", trustOf("ASK_REPEAT (audio_clipped)") == TrustLevel.AMBER)
    check("ASK_REPEAT (audio_too_noisy) -> AMBER", trustOf("ASK_REPEAT (audio_too_noisy)") == TrustLevel.AMBER)
    check("ASK_REPEAT (audio_low_quality) -> AMBER", trustOf("ASK_REPEAT (audio_low_quality)") == TrustLevel.AMBER)
    check("ASK_REPEAT (speech_too_short) -> AMBER", trustOf("ASK_REPEAT (speech_too_short)") == TrustLevel.AMBER)
    check("ASK_REPEAT (empty_transcript) -> AMBER", trustOf("ASK_REPEAT (empty_transcript)") == TrustLevel.AMBER)
    check("ASK_REPEAT (asr_low_confidence) -> AMBER", trustOf("ASK_REPEAT (asr_low_confidence)") == TrustLevel.AMBER)
    check("ASK_REPEAT (likely_no_speech_hallucination) -> AMBER", trustOf("ASK_REPEAT (likely_no_speech_hallucination)") == TrustLevel.AMBER)
    check("ASK_REPEAT (safety_slot_lost:...) -> AMBER", trustOf("ASK_REPEAT (safety_slot_lost:OBJECT_DROPPED|NEGATION_FLIPPED)") == TrustLevel.AMBER)
    // GREY: nothing heard, or idle.
    check("NO_SPEECH -> GREY", trustOf("NO_SPEECH") == TrustLevel.GREY)
    check("blank (idle) -> GREY", trustOf("") == TrustLevel.GREY)

    println("\n[retryGuidance — reason -> physical action]")
    check("audio_clipped -> SOFTER", retryGuidance("ASK_REPEAT (audio_clipped)").hint == RetryHint.SOFTER)
    check("audio_too_noisy -> MIC_CLOSER", retryGuidance("ASK_REPEAT (audio_too_noisy)").hint == RetryHint.MIC_CLOSER)
    check("audio_low_quality -> MIC_CLOSER", retryGuidance("ASK_REPEAT (audio_low_quality)").hint == RetryHint.MIC_CLOSER)
    check("speech_too_short -> SLOWER", retryGuidance("ASK_REPEAT (speech_too_short)").hint == RetryHint.SLOWER)
    check("asr_low_confidence -> SLOWER", retryGuidance("ASK_REPEAT (asr_low_confidence)").hint == RetryHint.SLOWER)
    check("safety_slot_lost -> SLOWER", retryGuidance("ASK_REPEAT (safety_slot_lost:NEGATION_FLIPPED)").hint == RetryHint.SLOWER)
    check("empty_transcript -> GENERIC", retryGuidance("ASK_REPEAT (empty_transcript)").hint == RetryHint.GENERIC)
    // Captions are always bilingual and non-blaming (device's limitation, not the worker's).
    val g = retryGuidance("ASK_REPEAT (audio_too_noisy)")
    check("guidance caption is bilingual + non-empty", g.captionVi.isNotBlank() && g.captionEn.isNotBlank())

    println("\n[containsHazardCue — prohibition/urgency/danger scan]")
    check("VI negation 'không vào khu vực nguy hiểm' -> hazard", containsHazardCue("không vào khu vực nguy hiểm"))
    check("EN 'do not enter' -> hazard", containsHazardCue("do not enter"))
    check("EN 'STOP the machine immediately' -> hazard", containsHazardCue("STOP the machine immediately"))
    check("VI 'dừng máy ngay' -> hazard", containsHazardCue("dừng máy ngay"))
    check("EN 'high voltage' -> hazard", containsHazardCue("high voltage panel"))
    // Negatives: a benign instruction must NOT trip the hazard channel.
    check("benign 'bring the box to dock B' -> no hazard", !containsHazardCue("bring the box to dock B"))
    check("benign VI 'mang hộp đến bến B' -> no hazard", !containsHazardCue("mang hộp đến bến B"))
    check("blank -> no hazard", !containsHazardCue(""))
    // 'ngay' (immediately) must trip, but it must match as a whole token, not inside another word.
    check("'ngay' fires as a token", containsHazardCue("đóng van ngay"))
    check("substring-only 'ngayonemore' does not fire", !containsHazardCue("ngayonemore filler text"))

    println("\n================  UI trust-logic: $passed passed, $failed failed  ================")
    if (failed > 0) kotlin.system.exitProcess(1)
}
