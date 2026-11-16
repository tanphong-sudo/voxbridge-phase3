package com.voxbridge.core.safety

import java.text.Normalizer
import java.math.BigDecimal

/** Numeric measurements only. Preserve decimals, signs, every value, and its limit direction.
 * A single equality keeps the historical `30bar` representation; lists use `|`, limits `lt:` etc.
 * Word-form numbers, unit conversion, and entity binding are outside this bounded parser.
 */
internal object SafetyMeasurements {
    private val measurement = Regex(
        "(?<![\\p{L}\\p{N}.])([+-]?\\d{1,6}(?:[.,]\\d+)?)[ \\t]*(bar|psi|kpa|mpa|kg|tons?|tan|rpm|volts?|amps?|celsius|degrees?\\s*c|do\\s*c|metres?|meters?|met|[cvam])\\b"
    )
    private val limit = Regex(
        "(?:khong\\s+(?:duoc\\s+)?vuot\\s+qua|at most|no more than|<=|≤)|" +
        "(?:it nhat|at least|no less than|>=|≥)|" +
        "(?:duoi|below|under|less than|<)|(?:tren|above|over|greater than|>)"
    )
    fun extract(raw: String): String? {
        val text = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ','d').replace("℃", "c")
        val values = mutableListOf<String>()
        var previousEnd = 0
        for (match in measurement.findAll(text)) {
            val number = match.groupValues[1]
            val rawUnit = match.groupValues[2]
            // Wireless-standard suffixes and prose time abbreviations are not physical units.
            if (number.startsWith("802.11") ||
                (rawUnit.length == 1 && match.value.contains(Regex("\\s")) && rawUnit in listOf("a", "v", "m"))) continue
            val unit = when (rawUnit) {
                "celsius", "degree c", "degrees c", "do c" -> "c"
                "volt", "volts" -> "v"
                "amp", "amps" -> "a"
                "ton", "tons", "tan" -> "t"
                "metre", "metres", "meter", "meters", "met" -> "m"
                else -> rawUnit
            }
            val value = BigDecimal(number.replace(',', '.')).stripTrailingZeros().toPlainString()
            val prefix = text.substring(previousEnd.coerceAtMost(match.range.first),match.range.first)
                .takeLast(60).substringAfterLast(';').substringAfterLast('\n')
            val cue = limit.findAll(prefix).lastOrNull()?.value
            val relation = when {
                cue == null -> ""
                cue.startsWith("khong") || cue in listOf("at most","no more than","<=","≤") -> "le:"
                cue in listOf("it nhat","at least","no less than",">=","≥") -> "ge:"
                cue in listOf("duoi","below","under","less than","<") -> "lt:"
                else -> "gt:"
            }
            values += "$relation$value$unit"
            previousEnd = match.range.last + 1
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString("|")
    }
}
