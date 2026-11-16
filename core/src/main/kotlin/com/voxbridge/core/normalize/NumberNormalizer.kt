package com.voxbridge.core.normalize

/**
 * Extracts and renders object-id references in both Vietnamese and English.
 *
 * An object id is the machine / line / zone / dock identifier whose loss after translation is
 * dangerous ("conveyor belt number three", "loading dock B"). It is returned as a CANONICAL
 * STRING so that numeric ids ("ba" / "three" / "number 3" → "3") and alphanumeric ids
 * ("dock B" → "B") are both representable and comparable across languages. Operates on
 * normalized (diacritic-stripped, lowercased) text.
 */
class NumberNormalizer {
    private val viNumbers = mapOf(
        "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4,
        "nam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9, "muoi" to 10
    )

    private val enNumbers = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    // Chinese (一..九, 十) and Sino-Korean (일..구, 십) numeral characters for "<n>号" / "<n>번" ids.
    private val zhDigits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val koDigits = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    // Location/equipment words after which a single letter is an identifier ("dock b", "zone a").
    private val idAnchors = listOf("dock", "zone", "khu", "line", "bay", "cua", "door", "ben")

    /**
     * Find an object id and return it as a canonical string, or null.
     *
     * Resolution order prefers an explicit marker ("so N" / "number N" / "<n>号" / "<n>번") and an
     * anchored letter ("dock B") over a bare number. A BARE number (no marker, no letter anchor) is
     * only trusted when [allowBareNumber] is set — the caller passes `true` only when a guarded
     * object/action is also present, so an incidental number does not become a phantom machine id.
     *
     * The bare *Vietnamese* number-word fallback is intentionally absent: VI number words are
     * homographs of common words (sau=after/back, ba=three/father, nam=five/year, hai=two/harm),
     * so "đóng cửa sau" ("close the back door") must NOT yield objectId="6". Vietnamese ids are
     * recognised only via the explicit "so N" marker above.
     */
    fun extractObjectId(
        normalizedText: String,
        allowBareNumber: Boolean = true,
        allowNumericMarker: Boolean = true,
    ): String? {
        if (allowNumericMarker) {
            // Explicit Vietnamese numeric marker: "so 3" / "so ba". `\d+` (not `\d{1,3}`) so a full
            // machine number like "so 1000" yields "1000", never a truncated "100".
            Regex("\\bso\\s+(\\d+)\\b").find(normalizedText)?.let { return it.groupValues[1] }
            Regex("\\bso\\s+([a-z]+)\\b").find(normalizedText)?.let { m -> viNumbers[m.groupValues[1]]?.let { return it.toString() } }

            // Explicit English numeric marker: "number 3" / "number three"
            Regex("\\bnumber\\s+(\\d+)\\b").find(normalizedText)?.let { return it.groupValues[1] }
            Regex("\\bnumber\\s+([a-z]+)\\b").find(normalizedText)?.let { m -> enNumbers[m.groupValues[1]]?.let { return it.toString() } }

            // Explicit Chinese marker "<n>号" (e.g. "三号" / "5号机" / "1000号").
            Regex("(\\d+)\\s*号").find(normalizedText)?.let { return it.groupValues[1] }
            Regex("([一二三四五六七八九十]+)\\s*号").find(normalizedText)?.let { m -> cjkToInt(m.groupValues[1], zhDigits)?.let { return it.toString() } }

            // Explicit Korean marker "<n>번" (e.g. "3번" / "삼번 기계").
            Regex("(\\d+)\\s*번").find(normalizedText)?.let { return it.groupValues[1] }
            Regex("([일이삼사오육칠팔구십]+)\\s*번").find(normalizedText)?.let { m -> cjkToInt(m.groupValues[1], koDigits)?.let { return it.toString() } }
        }

        // Anchored single-letter id: "dock b", "zone a" → "B" / "A"
        idAnchors.forEach { anchor ->
            Regex("\\b$anchor\\s+([a-z])\\b").find(normalizedText)?.let { return it.groupValues[1].uppercase() }
        }

        if (!allowBareNumber) return null

        // Bare standalone number, 1–3 digits, NOT part of a longer digit run. The lookarounds make
        // "2024" yield null (rather than a truncated "202" that could be SPOKEN as a wrong machine
        // number) while still matching a real id "3" or a CJK-adjacent digit "3" in "停止3传送带".
        // A 4+ digit number is treated as not-an-id here; the explicit markers above handle large ids.
        Regex("(?<!\\d)(\\d{1,3})(?!\\d)").find(normalizedText)?.let { return it.groupValues[1] }

        // Bare English number words are accepted only immediately after a concrete equipment
        // anchor. Otherwise "a professional one" or "the one next door" becomes a fake ID.
        return enNumbers.entries.firstOrNull { (surface, _) ->
            Regex("\\b(?:machine|conveyor|press|line|zone|forklift|door)\\s+$surface\\b")
                .containsMatchIn(normalizedText)
        }?.value?.toString()
    }

    /**
     * Convert a short CJK/Sino numeral (1–99) to an Int. Handles single digits, 十/십 = 10,
     * 十X (10+X), X十 (X*10), and X十Y (X*10+Y). Covers the machine/line-number range; returns
     * null for anything outside this simple grammar rather than guessing.
     */
    private fun cjkToInt(s: String, digits: Map<Char, Int>, tenChar: Char = if (digits === zhDigits) '十' else '십'): Int? {
        if (s.isEmpty()) return null
        if (s.length == 1) return if (s[0] == tenChar) 10 else digits[s[0]]
        val ten = s.indexOf(tenChar)
        if (ten < 0) return null
        val tens = if (ten == 0) 1 else digits[s[0]] ?: return null
        val ones = if (ten == s.length - 1) 0 else digits[s[ten + 1]] ?: return null
        return tens * 10 + ones
    }

    /** Render a numeric value as its English word; pass-through for non-numeric ids. */
    fun numberToEnglish(value: Int): String = when (value) {
        1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
        6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"; 10 -> "ten"
        else -> value.toString()
    }
}
