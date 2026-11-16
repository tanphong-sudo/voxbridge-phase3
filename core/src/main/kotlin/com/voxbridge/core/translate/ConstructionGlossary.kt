package com.voxbridge.core.translate

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Construction-site terms the on-device MT model does not know, pinned before translation.
 *
 * EnViT5 mistranslates Vietnamese trade nouns ("tháo cốp pha" -> "unpacked", "dây an toàn" ->
 * "seat belt", "vận thăng" -> "chopper"). Writing the English term into the Vietnamese source makes
 * the model carry it through as a code-switched word, which it does reliably. Measured on held-out
 * site sentences in experiments/construction_vocab.
 *
 * Only VI->EN: the same trick hurts EN->VI ("cốp pha" became "cốp xe"), where the model already
 * gets trade terms right. Deliberately excluded: words the model already translates correctly
 * ("bê tông", "thang"), and ambiguous single syllables ("hàn" weld/Korea, "cột" column/tie,
 * "dầm" beam/soaked), which would corrupt ordinary sentences.
 */
object ConstructionGlossary {
    /** Vietnamese surface (with diacritics) to the English term written into the MT input. */
    val VI_TO_EN: List<Pair<String, String>> = listOf(
        "giàn giáo" to "scaffolding",
        "dàn giáo" to "scaffolding",
        "giàn dáo" to "scaffolding",
        "giáo chống" to "scaffold shoring",
        "cốp pha" to "formwork",
        "cốp-pha" to "formwork",
        "côp pha" to "formwork",
        "coppha" to "formwork",
        "ván khuôn" to "formwork",
        "ván cốp pha" to "formwork panel",
        "cẩu tháp" to "tower crane",
        "cần trục tháp" to "tower crane",
        "xe cẩu" to "mobile crane",
        "cẩu bánh xích" to "crawler crane",
        "cẩu bánh lốp" to "mobile crane",
        "móc cẩu" to "crane hook",
        "cáp cẩu" to "lifting sling",
        "dây cáp cẩu" to "lifting sling",
        "dây an toàn" to "safety harness",
        "dây đai an toàn" to "safety harness",
        "đai an toàn" to "safety harness",
        "dây cứu sinh" to "lifeline",
        "móc an toàn" to "safety hook",
        "cốt thép" to "rebar",
        "thép chủ" to "main rebar",
        "thép đai" to "stirrup",
        "đai thép" to "stirrup",
        "lưới thép" to "steel mesh",
        "thép hình" to "structural steel",
        "mũ bảo hộ" to "hard hat",
        "nón bảo hộ" to "hard hat",
        "giày bảo hộ" to "safety boots",
        "ủng bảo hộ" to "safety boots",
        "kính bảo hộ" to "safety goggles",
        "găng tay bảo hộ" to "safety gloves",
        "áo phản quang" to "high-visibility vest",
        "mặt nạ hàn" to "welding mask",
        "hố móng" to "foundation pit",
        "móng cọc" to "pile foundation",
        "cọc khoan nhồi" to "bored pile",
        "máy ép cọc" to "pile driver",
        "máy đóng cọc" to "pile driver",
        "máy xúc" to "excavator",
        "máy đào" to "excavator",
        "xe ben" to "dump truck",
        "xe lu" to "road roller",
        "máy ủi" to "bulldozer",
        "xe bồn bê tông" to "concrete mixer truck",
        "xe bơm bê tông" to "concrete pump truck",
        "máy bơm bê tông" to "concrete pump",
        "máy trộn bê tông" to "concrete mixer",
        "máy trộn vữa" to "mortar mixer",
        "máy đầm dùi" to "poker vibrator",
        "đầm dùi" to "poker vibrator",
        "máy đầm cóc" to "jumping jack compactor",
        "đầm cóc" to "jumping jack compactor",
        "máy đầm bàn" to "plate compactor",
        "máy khoan bê tông" to "hammer drill",
        "máy khoan phá" to "jackhammer",
        "máy cắt sắt" to "rebar cutter",
        "máy uốn sắt" to "rebar bender",
        "máy hàn" to "welding machine",
        "mối hàn" to "weld",
        "máy mài" to "angle grinder",
        "máy cắt cầm tay" to "angle grinder",
        "vận thăng" to "construction hoist",
        "pa lăng" to "chain hoist",
        "palăng" to "chain hoist",
        "ròng rọc" to "pulley",
        "tời điện" to "electric winch",
        "lưới an toàn" to "safety net",
        "lưới chống rơi" to "safety net",
        "lan can an toàn" to "guardrail",
        "lan can" to "guardrail",
        "sàn thao tác" to "work platform",
        "sàn công tác" to "work platform",
        "rào chắn" to "barrier",
        "dải phân cách" to "barrier",
        "biển báo" to "warning sign",
        "băng cảnh báo" to "warning tape",
        "khu vực thi công" to "work zone",
        "dầm bê tông" to "concrete beam",
        "dầm thép" to "steel beam",
        "dầm sàn" to "floor beam",
        "xà gồ" to "purlin",
        "cột trục" to "grid column",
        "cột bê tông" to "concrete column",
        "cột thép" to "steel column",
        "cột chống" to "shoring prop",
        "cây chống" to "shoring prop",
        "sàn bê tông" to "concrete slab",
        "bê tông tươi" to "ready-mix concrete",
        "bê tông lót" to "blinding concrete",
        "tường chắn" to "retaining wall",
        "bu lông neo" to "anchor bolt",
        "bạt che" to "tarp",
        "chỉ huy trưởng" to "site manager",
        "giám sát công trình" to "site supervisor",
    )

    private val patterns: List<Pair<Pattern, String>> = VI_TO_EN
        .sortedByDescending { it.first.length }
        .map { (vi, en) ->
            Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(vi) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            ) to en
        }

    /**
     * Misspellings the Vietnamese recognizer produces for trade words with some (northern) voices,
     * corrected to the Vietnamese word before pinning. None of them is an ordinary word.
     */
    val ASR_FIXES: List<Pair<String, String>> = listOf(
        "bơ tông" to "bê tông",
        "bơ công" to "bê tông",
        "cẩu thác" to "cẩu tháp",
    )
    // Also: "lên tầm <number>" -> "lên tầng <number>" (floorAfterUp below).

    /** Spoken Vietnamese names of drawing-grid letters ("trục bê" = grid line B). */
    private val LETTER_NAMES: Map<String, String> = mapOf(
        "bê" to "B", "bờ" to "B", "xê" to "C", "cê" to "C", "cờ" to "C", "dê" to "D", "dờ" to "D",
        "đê" to "D", "đờ" to "D", "gờ" to "G", "hát" to "H", "lờ" to "L", "mờ" to "M", "nờ" to "N",
        "pê" to "P", "pờ" to "P", "rờ" to "R", "sờ" to "S", "tê" to "T", "tờ" to "T", "vê" to "V",
        "vờ" to "V", "ích" to "X",
    )
    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    private val NAMES = LETTER_NAMES.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"
    private val asrFixes = ASR_FIXES.map { (bad, good) ->
        Pattern.compile(NOT_WORD_BEFORE + Pattern.quote(bad) + NOT_WORD_AFTER, FLAGS) to good
    }
    /** "từ trục B đến trục D"; the recognizer often hears "trục" as "chục" and "dờ" as "giờ" here. */
    private val gridRange = Pattern.compile(
        NOT_WORD_BEFORE + "từ\\s+(?:trục|chục)\\s+([a-z]|$NAMES|giờ)\\s+đến\\s+(?:trục|chục)\\s+([a-z]|$NAMES|giờ)" + NOT_WORD_AFTER, FLAGS)
    private val gridLine = Pattern.compile(NOT_WORD_BEFORE + "trục\\s+([a-z]|\\d{1,2}|$NAMES)" + NOT_WORD_AFTER, FLAGS)
    /** "mười hai khối" on site is a volume: twelve cubic meters. Only before a material or the end. */
    private val cubic = Pattern.compile(
        NOT_WORD_BEFORE + "(\\d+(?:[.,]\\d+)?|một|hai|ba|bốn|tư|năm|lăm|sáu|bảy|tám|chín|mười|mươi|mốt|trăm|rưỡi)\\s+khối" +
            "(?=\\s*(?:$|[.,;:!?]|bê tông|đất|cát|đá|vữa|sỏi))", FLAGS)

    /** Under noise "lên tầng sáu" is often heard as "lên tầm sáu" ("about six"); a floor is meant. */
    private val floorAfterUp = Pattern.compile(
        NOT_WORD_BEFORE + "lên\\s+tầm\\s+(?=(\\d+|một|hai|ba|bốn|năm|sáu|bảy|tám|chín|mười)" + NOT_WORD_AFTER + ")", FLAGS)

    private fun letter(spoken: String): String =
        if (spoken.equals("giờ", ignoreCase = true)) "D" else LETTER_NAMES[spoken.lowercase()] ?: spoken.uppercase()

    private fun replace(text: String, pattern: Pattern, render: (java.util.regex.Matcher) -> String): String {
        val m = pattern.matcher(text); val out = StringBuffer()
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(render(m)))
        m.appendTail(out); return out.toString()
    }

    /** Replace every known Vietnamese trade term in [text] with its English term. */
    fun pinViToEn(text: String): String {
        var out = Normalizer.normalize(text, Normalizer.Form.NFC)
        for ((pattern, good) in asrFixes) out = pattern.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(good))
        out = floorAfterUp.matcher(out).replaceAll("lên tầng ")
        out = replace(out, gridRange) { "từ grid line ${letter(it.group(1))} đến grid line ${letter(it.group(2))}" }
        for ((pattern, en) in patterns) out = pattern.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(en))
        out = replace(out, gridLine) { "grid line ${letter(it.group(1))}" }
        out = replace(out, cubic) { "${it.group(1)} cubic meters" }
        return out
    }
}
