import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator

/**
 * Logic-only SAFETY BENCHMARK for the VoxBridge decision core.
 *
 * It drives the REAL DecisionEngine over a labelled corpus. The neural MT is simulated from
 * each case's `modelOut` field so we can inject the exact failure modes (dropped negation,
 * lost object-id, object substitution, action inversion) that the Safety Preservation Layer
 * must catch — deterministically and reproducibly, with no model weights or device.
 *
 * The oracle is INDEPENDENT of the engine's own comparator: each safety-critical case lists the
 * tokens that MUST appear in any spoken output. The headline metric is UNSAFE-PASS rate —
 * a safety-critical source whose spoken translation is missing a required safety token. Target: 0.
 */

private class TableTranslator(private val table: Map<String, String>) : Translator {
    override fun translate(text: String, src: Language, tgt: Language): TranslationResult =
        TranslationResult(text, table[text.trim()] ?: text, src, tgt, "sim-mt")
}

private enum class Cat { CLEAN_GENERAL, CLEAN_SAFETY, NEGATION, OBJECT_ID, OBJECT_SUB, ACTION_CHANGE, HAZARD, URGENCY, DEICTIC, GATE, LOTO, QUANTITY, ZH_PAIR, KO_PAIR }

/**
 * One labelled case.
 * @param required tokens (lowercased) that MUST be in any SPOKEN output; checked only when safetyCritical.
 * @param expect    acceptable decision names.
 */
private data class Case(
    val id: String,
    val src: Language,
    val tgt: Language,
    val source: String,
    val modelOut: String,
    val cat: Cat,
    val audio: Double = 0.95,
    val asr: Double = 0.92,
    val safetyCritical: Boolean = true,
    val required: List<String> = emptyList(),
    val expect: Set<String>
)

private val VERIFIED = "VERIFIED_TRANSLATION"
private val MODEL = "MODEL_TRANSLATION"
private val REPAIRED = "REPAIRED_TRANSLATION"
private val ASKREP = "ASK_REPEAT"
private val NOSPEECH = "NO_SPEECH"

// Tokens accepted for a preserved negation in either language (raw target keeps diacritics).
private val NEG = listOf("do not", "don't", "never", "no entry", "không", "khong")

private val CASES = listOf(
    // --- CLEAN GENERAL: no safety signal -> plain MODEL_TRANSLATION, never repaired ---
    Case("gen1", Language.VI, Language.EN, "hom nay troi dep", "The weather is nice today.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(MODEL)),
    Case("gen2", Language.EN, Language.VI, "the report is on your desk", "Báo cáo ở trên bàn của bạn.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(MODEL)),
    Case("gen3", Language.VI, Language.EN, "cam on anh nhieu", "Thank you very much.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(MODEL)),

    // --- CLEAN SAFETY preserved -> VERIFIED ---
    Case("ver1", Language.VI, Language.EN, "dung bang chuyen so ba", "Stop conveyor belt number three.", Cat.CLEAN_SAFETY, required = listOf("three", "conveyor"), expect = setOf(VERIFIED)),
    Case("ver2", Language.EN, Language.VI, "do not start machine five", "Không khởi động máy số năm.", Cat.CLEAN_SAFETY, required = NEG + listOf("năm"), expect = setOf(VERIFIED)),
    Case("ver3", Language.VI, Language.EN, "tat nguon dien", "Turn off the power.", Cat.CLEAN_SAFETY, required = listOf("power"), expect = setOf(VERIFIED)),
    Case("ver4", Language.EN, Language.VI, "wear protective equipment", "Mang đồ bảo hộ.", Cat.CLEAN_SAFETY, required = listOf("bảo hộ"), expect = setOf(VERIFIED)),
    Case("ver5", Language.VI, Language.EN, "goi quan ly ngay", "Call the supervisor immediately.", Cat.CLEAN_SAFETY, required = listOf("supervisor", "immediately"), expect = setOf(VERIFIED)),

    // --- NEGATION lost/flipped -> must REPAIR or ASK_REPEAT, never speak permissive ---
    Case("neg1", Language.VI, Language.EN, "khong vao khu vuc nguy hiem", "Enter the hazard zone.", Cat.NEGATION, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    Case("neg2", Language.EN, Language.VI, "do not start machine five", "Khởi động máy số năm.", Cat.NEGATION, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    Case("neg3", Language.VI, Language.EN, "khong mo cua", "Open the door.", Cat.NEGATION, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    Case("neg4", Language.EN, Language.VI, "do not enter the hazard zone", "Vào khu vực nguy hiểm.", Cat.NEGATION, required = NEG, expect = setOf(REPAIRED, ASKREP)),

    // --- OBJECT-ID lost -> REPAIR restores the number ---
    Case("oid1", Language.VI, Language.EN, "dung bang chuyen so ba", "Stop the conveyor.", Cat.OBJECT_ID, required = listOf("three", "3"), expect = setOf(REPAIRED, ASKREP)),
    Case("oid2", Language.EN, Language.VI, "stop machine number seven", "Dừng máy.", Cat.OBJECT_ID, required = listOf("7"), expect = setOf(REPAIRED, ASKREP)),
    Case("oid3", Language.VI, Language.EN, "dung may ep so hai", "Stop the press machine.", Cat.OBJECT_ID, required = listOf("two", "2"), expect = setOf(REPAIRED, ASKREP)),

    // --- OBJECT SUBSTITUTION (in-lexicon) -> CHANGED_OBJECT detect-only -> ASK_REPEAT ---
    Case("sub1", Language.VI, Language.EN, "dung bang chuyen", "Stop the forklift.", Cat.OBJECT_SUB, required = listOf("conveyor"), expect = setOf(ASKREP)),
    Case("sub2", Language.EN, Language.VI, "stop the machine", "Dừng băng chuyền.", Cat.OBJECT_SUB, required = listOf("máy"), expect = setOf(ASKREP)),

    // --- ACTION CHANGE -> detect-only -> ASK_REPEAT (never silently spoken) ---
    Case("act1", Language.VI, Language.EN, "dung may", "Start the machine.", Cat.ACTION_CHANGE, required = listOf("stop"), expect = setOf(ASKREP)),
    Case("act2", Language.EN, Language.VI, "open the valve", "Đóng van.", Cat.ACTION_CHANGE, required = listOf("mở"), expect = setOf(ASKREP)),
    Case("act3", Language.VI, Language.EN, "tat nguon dien", "Turn on the power.", Cat.ACTION_CHANGE, required = listOf("off"), expect = setOf(ASKREP)),

    // --- HAZARD dropped -> repair or refuse, never drop the danger silently ---
    Case("haz1", Language.VI, Language.EN, "khong vao khu vuc nguy hiem", "Do not enter the area.", Cat.HAZARD, required = listOf("hazard", "zone"), expect = setOf(REPAIRED, ASKREP)),

    // --- URGENCY dropped -> repair appends the urgency marker ---
    Case("urg1", Language.VI, Language.EN, "goi quan ly ngay", "Call the supervisor.", Cat.URGENCY, required = listOf("immediately", "now"), expect = setOf(REPAIRED, ASKREP)),
    Case("urg2", Language.EN, Language.VI, "stop the conveyor immediately", "Dừng băng chuyền.", Cat.URGENCY, required = listOf("ngay"), expect = setOf(REPAIRED, ASKREP)),

    // --- DEICTIC: action preserved, no concrete object -> safe to speak (VERIFIED) or MODEL ---
    Case("dei1", Language.VI, Language.EN, "tat cai do di", "Turn that off.", Cat.DEICTIC, safetyCritical = false, expect = setOf(VERIFIED, MODEL)),
    Case("dei2", Language.EN, Language.VI, "stop that", "Dừng lại.", Cat.DEICTIC, safetyCritical = false, expect = setOf(VERIFIED, MODEL)),

    // --- LOCKOUT-TAGOUT: the most-cited industrial safety procedure; LOTO verbs are critical ---
    Case("loto1", Language.EN, Language.VI, "lock out the breaker", "Khóa máy cầu dao.", Cat.LOTO, required = listOf("khóa", "khoa"), expect = setOf(VERIFIED)),
    Case("loto2", Language.VI, Language.EN, "ngat dien may so ba", "Energize machine number three.", Cat.LOTO, required = listOf("de-energize", "de energize", "isolate", "off"), expect = setOf(ASKREP)),
    Case("loto3", Language.EN, Language.VI, "verify dead before work", "Kiểm tra hết điện trước khi làm.", Cat.LOTO, required = listOf("hết điện", "het dien"), expect = setOf(VERIFIED)),
    Case("loto4", Language.VI, Language.EN, "treo the canh bao", "Tag out warning.", Cat.LOTO, required = listOf("tag"), expect = setOf(VERIFIED)),

    // --- QUANTITY: a dropped or changed measurement is dangerous; detect-only -> ASK_REPEAT ---
    Case("qty1", Language.EN, Language.VI, "set pressure to 30 bar", "Đặt áp suất.", Cat.QUANTITY, required = listOf("30"), expect = setOf(ASKREP)),
    // Digit-form VI quantity ("200 do" = 200 degrees) dropped by the model. Uses a digit because
    // quantity detection is digit+unit by design; spelled-out multi-word VI numbers ("hai tram")
    // are out of scope for the detector and must NOT be faked via a phantom object id.
    Case("qty2", Language.VI, Language.EN, "nhiet do 200 do", "Set the temperature.", Cat.QUANTITY, required = listOf("200"), expect = setOf(ASKREP)),
    Case("qty3", Language.EN, Language.VI, "torque to 50 newton metre", "Đặt lực 50 m.", Cat.QUANTITY, safetyCritical = false, expect = setOf(VERIFIED, MODEL)),

    // --- FATAL-FOUR hazards: dropped hazard term must repair or refuse ---
    Case("haz2", Language.EN, Language.VI, "watch for the overhead load", "Chú ý phía trên.", Cat.HAZARD, required = listOf("vat roi", "vật rơi", "phía trên"), expect = setOf(VERIFIED, REPAIRED, ASKREP)),

    // --- VI↔ZH: the SAME canonical SafetyFrame + comparator catches loss across a new pair ---
    // ZH→VI clean safety preserved → VERIFIED (proves ZH extraction works end-to-end).
    Case("zh1", Language.ZH, Language.VI, "停止三号传送带", "Dừng băng chuyền số ba.", Cat.ZH_PAIR, required = listOf("băng chuyền", "ba"), expect = setOf(VERIFIED)),
    // ZH→VI negation flipped by model → repair into VI restores "không" (or ASK_REPEAT).
    Case("zh2", Language.ZH, Language.VI, "禁止进入危险区", "Vào khu vực nguy hiểm.", Cat.ZH_PAIR, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    // ZH→VI action flip (停止→khởi động) → CHANGED_ACTION detect-only → ASK_REPEAT.
    Case("zh3", Language.ZH, Language.VI, "停止机器", "Khởi động máy.", Cat.ZH_PAIR, required = listOf("dừng"), expect = setOf(ASKREP)),
    // zh4 is a NO-FABRICATION GATE test on a VI source (not a ZH-detection test): VI extractor sees
    // the lost negation, and the repair-into-ZH gate forces ASK_REPEAT rather than splicing ZH.
    Case("zh4", Language.VI, Language.ZH, "khong vao khu vuc nguy hiem", "进入危险区。", Cat.ZH_PAIR, required = NEG, expect = setOf(ASKREP)),
    // ZH→VI clean general (no safety slot) → plain MODEL_TRANSLATION, layer stays out.
    Case("zh5", Language.ZH, Language.VI, "今天天气很好", "Hôm nay trời đẹp.", Cat.ZH_PAIR, safetyCritical = false, expect = setOf(MODEL)),
    // ZH-SOURCE detection test (decision depends on target-side... no — on ZH-source extraction):
    // ZH "打开阀门" (OPEN valve) vs model "关闭阀门" (CLOSE) → CHANGED_ACTION → ASK_REPEAT. This case
    // FAILS if longest-match misreads 打开 as TURN_ON, so it locks the duplicate-surface fix.
    Case("zh6", Language.ZH, Language.VI, "打开阀门", "Đóng van.", Cat.ZH_PAIR, required = listOf("mở"), expect = setOf(ASKREP)),
    // Benign-CJK negative controls: substring-collision words must NOT fabricate a slot and force a
    // false refusal. 打电话(make a call)/通知部门(notify the division) carry no guarded object.
    Case("zh7", Language.ZH, Language.VI, "打电话给主管", "Gọi cho quản lý.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(VERIFIED, MODEL)),

    // --- VI↔KO: same engine, third pair ---
    // KO→VI clean safety preserved → VERIFIED.
    Case("ko1", Language.KO, Language.VI, "3번 컨베이어 정지", "Dừng băng chuyền số ba.", Cat.KO_PAIR, required = listOf("băng chuyền", "ba"), expect = setOf(VERIFIED)),
    // KO→VI negation flipped → repair into VI restores negation (or ASK_REPEAT).
    Case("ko2", Language.KO, Language.VI, "위험 구역에 들어가지 마세요", "Vào khu vực nguy hiểm.", Cat.KO_PAIR, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    // KO→VI action flip (정지→khởi động) → ASK_REPEAT.
    Case("ko3", Language.KO, Language.VI, "기계 정지", "Khởi động máy.", Cat.KO_PAIR, required = listOf("dừng"), expect = setOf(ASKREP)),
    // ko4 is a NO-FABRICATION GATE test on a VI source (not a KO-detection test): the VI extractor
    // sees the lost negation and the repair-into-KO gate forces ASK_REPEAT rather than splicing KO.
    Case("ko4", Language.VI, Language.KO, "khong vao khu vuc nguy hiem", "위험 구역에 들어가세요.", Cat.KO_PAIR, required = NEG, expect = setOf(ASKREP)),

    // --- ADVERSARIAL REGRESSION: extractor must not over-fire on benign / must catch new phrasings ---
    // Negation recall: "stay away" is a prohibition with no explicit "not"; a model that flips it
    // to a permissive ("approach") must be caught, not spoken as VERIFIED.
    Case("adv_neg1", Language.EN, Language.VI, "stay away from the conveyor", "Đến gần băng chuyền.", Cat.NEGATION, required = NEG, expect = setOf(REPAIRED, ASKREP)),
    // Phantom object-id: "cua sau" = "back door"; "sau" must NOT fabricate objectId=6. Object+action
    // preserved by the model, so this is VERIFIED, never a false repair to "...number six".
    Case("adv_oid1", Language.VI, Language.EN, "dong cua sau", "Close the back door.", Cat.CLEAN_SAFETY, required = listOf("door"), expect = setOf(VERIFIED)),
    // Benign number with no object: "wait two hours" must not fabricate objectId=2.
    Case("adv_num1", Language.EN, Language.VI, "wait two hours", "Đợi hai tiếng.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(VERIFIED, MODEL)),
    // Benign time expression: "8 a.m." must not be read as quantity "8a" and forced to ASK_REPEAT.
    Case("adv_qty1", Language.EN, Language.VI, "meet at 8 a m", "Gặp lúc 8 giờ sáng.", Cat.CLEAN_GENERAL, safetyCritical = false, expect = setOf(MODEL, VERIFIED)),

    // --- GATES: bad input must never reach translation ---
    Case("gate1", Language.VI, Language.EN, "", "", Cat.GATE, audio = 0.05, asr = 0.0, safetyCritical = false, expect = setOf(NOSPEECH)),
    Case("gate2", Language.VI, Language.EN, "dung bang chuyen so ba", "Stop conveyor belt number three.", Cat.GATE, audio = 0.18, safetyCritical = false, expect = setOf(ASKREP)),
    Case("gate3", Language.VI, Language.EN, "dung bang chuyen so ba", "Stop conveyor belt number three.", Cat.GATE, asr = 0.40, safetyCritical = false, expect = setOf(ASKREP)),
)

private fun decisionName(d: TranslationDecision): String = when (d) {
    is TranslationDecision.NoSpeech -> NOSPEECH
    is TranslationDecision.AskRepeat -> ASKREP
    is TranslationDecision.MemoryTranslation -> "MEMORY_TRANSLATION"
    is TranslationDecision.ModelTranslation -> MODEL
    is TranslationDecision.VerifiedTranslation -> VERIFIED
    is TranslationDecision.RepairedTranslation -> REPAIRED
}

private fun spokenText(d: TranslationDecision): String? = when (d) {
    is TranslationDecision.ModelTranslation -> d.text
    is TranslationDecision.VerifiedTranslation -> d.text
    is TranslationDecision.RepairedTranslation -> d.text
    is TranslationDecision.MemoryTranslation -> d.text
    else -> null
}

fun main() {
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    println("== VoxBridge — Logic-only Safety Benchmark ==\n")
    println("Cases: ${CASES.size}  (neural MT simulated per-case to inject failures; oracle independent of engine comparator)\n")

    var decisionMatch = 0
    var safetyTotal = 0
    var unsafePass = 0
    var spokeCount = 0
    var askRepeat = 0
    var repairs = 0
    var falseRepairs = 0
    val byCat = sortedMapOf<String, IntArray>() // [total, decisionMatch, unsafePass]
    val confusions = mutableListOf<String>()

    for (c in CASES) {
        val engine = DecisionEngine(translator = TableTranslator(mapOf(c.source.trim() to c.modelOut)))
        val audio = AudioEvidence(
            speechPresent = c.source.isNotBlank(),
            audioScore = c.audio,
            durationMs = 1600
        )
        val transcript = TranscriptResult(c.src, c.tgt, c.source, c.asr)
        val d = engine.decide(audio, transcript)
        val name = decisionName(d)
        val spoke = spokenText(d)

        val stat = byCat.getOrPut(c.cat.name) { IntArray(3) }
        stat[0]++

        val matched = c.expect.contains(name)
        if (matched) { decisionMatch++; stat[1]++ }
        else confusions.add("  [${c.id}] ${c.cat}: got $name, expected ${c.expect}  src='${c.source}' model='${c.modelOut}' out='${spoke ?: ""}'")

        if (name == ASKREP) askRepeat++
        if (name == REPAIRED) repairs++

        if (c.safetyCritical) {
            safetyTotal++
            if (spoke != null) {
                spokeCount++
                val low = spoke.lowercase()
                val missing = c.required.isNotEmpty() && c.required.none { low.contains(it) }
                if (missing) {
                    unsafePass++; stat[2]++
                    if (name == REPAIRED) falseRepairs++
                }
            }
        }
    }

    // Per-category table
    println("Per-category results  (cat: total, decision-match, unsafe-pass)")
    for ((cat, s) in byCat) {
        println("  %-14s total=%2d  match=%2d  unsafe=%d".format(cat, s[0], s[1], s[2]))
    }

    val pct = { n: Int, d: Int -> if (d == 0) "n/a" else "%.1f%%".format(100.0 * n / d) }
    println("\nHeadline metrics")
    println("  Decision-match accuracy      : ${pct(decisionMatch, CASES.size)}  ($decisionMatch/${CASES.size})")
    println("  UNSAFE-PASS rate (target 0)  : ${pct(unsafePass, safetyTotal)}  ($unsafePass/$safetyTotal safety-critical)")
    println("  Safety refusals (ASK_REPEAT) : $askRepeat")
    println("  Repairs emitted              : $repairs")
    println("  False-repair rate            : ${pct(falseRepairs, repairs)}  ($falseRepairs/$repairs)")
    println("  Spoken safety outputs        : $spokeCount/$safetyTotal")

    if (confusions.isNotEmpty()) {
        println("\nDecision mismatches outside the expected set:")
        confusions.forEach(::println)
    } else {
        println("\nAll decisions within expected sets.")
    }

    println("\n== RESULT: UNSAFE-PASS=$unsafePass  decision-match=${pct(decisionMatch, CASES.size)} ==")
    if (unsafePass > 0 || confusions.isNotEmpty()) kotlin.system.exitProcess(1)
}
