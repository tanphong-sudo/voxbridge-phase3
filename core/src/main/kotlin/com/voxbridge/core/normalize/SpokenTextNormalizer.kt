package com.voxbridge.core.normalize

import com.voxbridge.core.model.Language

/**
 * TTS-only normalization for values that are easy for a voice engine to mispronounce.
 *
 * This runs after translation/safety verification and immediately before speech synthesis. It is
 * deliberately narrow for English: only a numeric value directly attached to one of the frozen
 * measurement units below is verbalized. Vietnamese additionally gets times, dates, numbers,
 * acronyms and common loanwords spelled out ([VietnameseSpokenForms]), because eSpeak-vi reads
 * raw MT output like "5: 30" or "UTI" badly. Hyphenated machine identifiers ("A-12"), negation
 * and hazard language are retained verbatim in both languages.
 *
 * Examples required by the Phase-2 proposal:
 *  - VI: 15 mm -> mười lăm mi li mét; 220 V -> hai trăm hai mươi vôn;
 *        3.5 bar -> ba phẩy năm bar
 *  - EN: 15 mm -> fifteen millimeters; 220 V -> two hundred twenty volts;
 *        3.5 bar -> three point five bar
 *
 * Important: do not feed this spoken rendering back into SafetyFrameExtractor. The safety layer
 * has already verified the machine-readable text; this class only prepares the final utterance.
 */
object SpokenTextNormalizer {
    private val MEASUREMENT = Regex(
        pattern = "(?i)(?<![\\p{L}\\p{N}-])(\\d{1,4}(?:[.,]\\d+)?)\\s*(mm|v|bar)\\b",
    )

    fun normalize(text: String, language: Language): String = when (language) {
        // Measurements first (they own "3.5 bar"), then times/dates/numbers/acronyms for eSpeak-vi.
        Language.VI -> VietnameseSpokenForms.normalize(normalizeMeasurements(text, language))
        Language.EN -> normalizeMeasurements(text, language)
        // Task-4 acceptance covers VI/EN only. Other product languages must not be silently
        // rewritten by an unvalidated number grammar.
        Language.ZH, Language.KO -> text
    }

    private fun normalizeMeasurements(text: String, language: Language): String =
        MEASUREMENT.replace(text) { match ->
            val numeric = match.groupValues[1]
            val unit = match.groupValues[2].lowercase()
            val spokenValue = when (language) {
                Language.VI -> numberVi(numeric)
                Language.EN -> numberEn(numeric)
                else -> numeric
            }
            val spokenUnit = when (language) {
                Language.VI -> when (unit) {
                    "mm" -> "mi li mét"
                    "v" -> "vôn"
                    "bar" -> "bar"
                    else -> unit
                }
                Language.EN -> when (unit) {
                    "mm" -> "millimeters"
                    "v" -> "volts"
                    "bar" -> "bar"
                    else -> unit
                }
                else -> unit
            }
            "$spokenValue $spokenUnit"
        }

    private fun numberEn(raw: String): String {
        val parts = raw.replace(',', '.').split('.', limit = 2)
        val whole = parts[0].toIntOrNull() ?: return raw
        val head = integerEn(whole)
        if (parts.size == 1) return head
        val decimal = parts[1].mapNotNull { ch -> DIGIT_EN[ch] }.joinToString(" ")
        return if (decimal.isBlank()) head else "$head point $decimal"
    }

    private fun integerEn(value: Int): String {
        require(value in 0..9999) { "English spoken number out of supported range: $value" }
        if (value < 20) return EN_SMALL[value]
        if (value < 100) {
            val tens = EN_TENS[value / 10]
            val ones = value % 10
            return if (ones == 0) tens else "$tens ${EN_SMALL[ones]}"
        }
        if (value < 1000) {
            val hundreds = "${EN_SMALL[value / 100]} hundred"
            val rest = value % 100
            return if (rest == 0) hundreds else "$hundreds ${integerEn(rest)}"
        }
        val thousands = "${EN_SMALL[value / 1000]} thousand"
        val rest = value % 1000
        return if (rest == 0) thousands else "$thousands ${integerEn(rest)}"
    }

    private fun numberVi(raw: String): String {
        val parts = raw.replace(',', '.').split('.', limit = 2)
        val whole = parts[0].toIntOrNull() ?: return raw
        val head = VietnameseSpokenForms.integer(whole.toLong())
        if (parts.size == 1) return head
        val decimal = parts[1].mapNotNull { ch -> DIGIT_VI[ch] }.joinToString(" ")
        return if (decimal.isBlank()) head else "$head phẩy $decimal"
    }

    private val EN_SMALL = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val EN_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val VI_DIGITS = listOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
    private val DIGIT_EN = EN_SMALL.take(10).mapIndexed { index, word -> ('0' + index) to word }.toMap()
    private val DIGIT_VI = VI_DIGITS.mapIndexed { index, word -> ('0' + index) to word }.toMap()
}
