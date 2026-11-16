package com.voxbridge.core.translate

import com.voxbridge.core.model.Language

/** Collapse gate plus experimental diagnostics. Missing lexical cues are not proof of semantic omission. */
object TranslationCoverage {
    data class Verdict(
        val hardFailure: Boolean,
        val reasons: List<String>,
        val missingUnits: List<String>,
        /** One proposed fallback pass, with at most three explicit source sentences. */
        val retrySentences: List<String>,
    )
    private val tokens = Regex("[\\p{L}\\p{N}']+")
    private val abbreviations = Regex("(?:^|\\s)(?:bs|ts|ths|pgs|gs|mr|mrs|ms|dr|prof|e\\.g|i\\.e|[A-Z])$", RegexOption.IGNORE_CASE)
    private val units = linkedMapOf(
        "stop" to Pair("(?:dừng|ngừng)(?: lại)?", "(?:stop(?:s|ped|ping)?|halt(?:s|ed|ing)?|shut down|shutdown)"),
        "wait" to Pair("(?:chờ|đợi)", "(?:wait|hold on|stand by)"),
        "call" to Pair("(?:gọi|liên lạc)", "(?:call|phone|contact)"),
        "wear" to Pair("(?:đeo|mặc|đội (?:(?:cái|chiếc) )?(?:mũ|nón))", "(?:wear|put on)"),
        "open" to Pair("mở", "open"),
        "close" to Pair("(?:đóng|khóa)", "(?:close|shut|lock)"),
        "disconnect" to Pair("(?:ngắt|cắt|tắt) (?:nguồn |cầu dao |nguồn điện|điện)", "(?:disconnect|cut|switch off|turn off|isolate) (?:the )?(?:power|electricity|supply|breaker)"),
        "send" to Pair("(?:gửi|chuyển)", "(?:send|sent|forward(?:ed)?|submit(?:ted)?|transfer(?:s|red|ring)?)"),
        "check" to Pair("(?:kiểm tra|kiểm chứng)", "(?:check|inspect|verify)"),
        "repeat" to Pair("(?:nhắc lại|nói lại|lặp lại)", "(?:repeat|say .* again)"),
        "evacuate" to Pair("(?:sơ tán|di tản|thoát ra)", "(?:evacuate|leave the area)"),
        "confirm" to Pair("xác nhận", "confirm"),
    ).mapValues { (_, p) -> Pair(Regex("(?<![\\p{L}])${p.first}(?![\\p{L}])"), Regex("(?<![\\p{L}])${p.second}(?![\\p{L}])")) }

    private fun loop(text: String): Boolean {
        val t = tokens.findAll(text.lowercase()).map { it.value }.toList()
        // Actual QNN collapse can repeat a whole long clause (e.g. EV-9462: eleven words).
        // Three repeats covering >=60% remains required, preserving ordinary short repetition.
        for (size in 1..minOf(64, t.size / 3)) {
            for (start in 0..t.size - size * 3) {
                var end = start + size
                while (end + size <= t.size && (0 until size).all { t[start + it] == t[end + it] }) end += size
                val repetitions = (end - start) / size
                if ((size == 1 && repetitions >= 6 || size >= 2 && repetitions >= 3) &&
                    (end - start).toDouble() / t.size >= 0.6) return true
            }
        }
        return false
    }

    fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] !in ".?!") continue
            if (text[i] == '.' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()) continue
            if (text[i] == '.' && abbreviations.containsMatchIn(text.substring(start, i))) continue
            if (i + 1 == text.length || text[i + 1].isWhitespace()) {
                val piece = text.substring(start, i + 1).trim()
                if (piece.isNotEmpty()) result += piece
                start = i + 1
            }
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { result += it }
        return result
    }

    /** Production gate only: preserves the existing blank/single-token guard and adds dominant phrase loops.
     * Does not inspect action cues, sentence count or length and never calls a translator.
     */
    fun isCollapsed(source: String, target: String): Boolean {
        val targetTokens = tokens.findAll(target.lowercase()).map { it.value }.toList()
        if (targetTokens.isEmpty()) return true
        var previous = ""
        var run = 0
        for (token in targetTokens) {
            run = if (token == previous) run + 1 else 1
            if (run >= 6) return true // Preserve the pre-existing guard even in a longer output.
            previous = token
        }
        if (!loop(target)) return false
        val sourceTokens = tokens.findAll(source.lowercase()).map { it.value }.toList()
        val sourceEcho = sourceTokens.size >= 3 &&
            sourceTokens.groupingBy { it }.eachCount().values.maxOrNull()!!.toDouble() / sourceTokens.size >= 0.6
        return !loop(source) && !sourceEcho
    }

    fun evaluate(source: String, target: String, sourceLanguage: Language): Verdict {
        val reasons = mutableListOf<String>()
        val blank = !tokens.containsMatchIn(target)
        val repetition = !blank && isCollapsed(source, target)
        if (blank) reasons += "blank"
        if (repetition) reasons += "repeated_phrase"
        fun recognized(text: String, vi: Boolean): Set<String> = units.filterValues {
            (if (vi) it.first else it.second).containsMatchIn(text.lowercase())
        }.keys
        val sourceUnits = recognized(source, sourceLanguage == Language.VI)
        val targetUnits = recognized(target, sourceLanguage != Language.VI)
        // Only a multi-cue source can raise omission suspicion; one unknown synonym is too weak.
        val missing = if (sourceUnits.size >= 2) (sourceUnits - targetUnits).toList() else emptyList()
        if (missing.isNotEmpty()) reasons += "missing_action_cue"
        val sourceCount = tokens.findAll(source).count()
        val targetCount = tokens.findAll(target).count()
        if (sourceCount >= 12 && targetCount <= 3) reasons += "very_short_output"
        val sentences = splitSentences(source)
        if (sentences.size >= 2 && splitSentences(target).size < sentences.size) reasons += "sentence_merge_or_loss"
        val retry = if ((blank || repetition || missing.isNotEmpty()) && sentences.size in 2..3 && source.length <= 800) sentences else emptyList()
        return Verdict(blank || repetition, reasons, missing, retry)
    }
}
