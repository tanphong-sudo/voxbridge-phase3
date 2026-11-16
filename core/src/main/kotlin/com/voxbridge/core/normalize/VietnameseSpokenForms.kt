package com.voxbridge.core.normalize

/**
 * Vietnamese spoken-form rewriting for TTS (Piper + eSpeak-ng `vi` frontend).
 *
 * eSpeak reads MT output such as "5: 30 sáng", "8h30", "20 tháng 4,1980", "NWA 476" or "UTI"
 * badly (stray pauses, English letter names, "tháng bốn"). This turns times, dates, numbers,
 * acronyms and a few common loanwords into plain Vietnamese words. The rules are applied in a
 * fixed order (dates before times before plain numbers) and the output contains no digits, so
 * running the normalizer twice changes nothing.
 *
 * Safety: this runs only on already-verified text, right before synthesis. Hyphenated machine
 * ids ("A-12") are left verbatim, and a lone capital letter is never spelled out.
 */
internal object VietnameseSpokenForms {
    fun normalize(text: String): String {
        var s = text
        s = NUMERIC_DATE.replace(s, ::numericDate)
        s = MONTH_YEAR.replace(s) { m ->
            val day = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { integer(it.toLong()) + " " }.orEmpty()
            "${day}tháng ${month(m.groupValues[2])} năm ${integer(m.groupValues[3].toLong())}"
        }
        s = MONTH.replace(s) { m -> "tháng ${month(m.groupValues[1])}" }
        s = CLOCK.replace(s, ::clock)
        s = HOUR_ONLY.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            if (h > 24) m.value else "${integer(h.toLong())} giờ"
        }
        s = DOLLAR.replace(s) { m -> "${number(m.groupValues[1])} đô la" }
        s = LEXICON_PATTERN.replace(s) { m -> LEXICON.getValue(m.value.lowercase()) }
        s = CODE_NUMBER.replace(s) { m -> "${spell(m.groupValues[1])} ${digits(m.groupValues[2])}" }
        s = PERCENT.replace(s) { m -> "${number(m.groupValues[1])} phần trăm" }
        s = NUMBER.replace(s) { m -> number(m.value) }
        // Shouted Vietnamese typed without diacritics ("DUNG LAI NGAY") is still a syllable; spell
        // only capitals that cannot be one ("UTI", "ATM", "NWA").
        s = ACRONYM.replace(s) { m -> if (VI_SYLLABLE.matches(m.value.lowercase())) m.value else spell(m.value) }
        s = SYMBOLS.replace(s) { m -> " ${SYMBOL_WORDS.getValue(m.value)} " }
        return s.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()
    }

    /** Reads a non-negative integer the standard Vietnamese way ("hai nghìn không trăm hai mươi bốn"). */
    fun integer(value: Long): String {
        require(value >= 0) { "negative values are not spoken here: $value" }
        if (value == 0L) return "không"
        if (value >= 1_000_000_000_000L) return digits(value.toString())
        val groups = ArrayList<Int>()
        var rest = value
        while (rest > 0) { groups += (rest % 1000).toInt(); rest /= 1000 }
        val words = ArrayList<String>()
        for (i in groups.indices.reversed()) {
            val group = groups[i]
            if (group == 0) continue
            words += triple(group, full = i != groups.lastIndex)
            if (i > 0) words += SCALES[i]
        }
        return words.joinToString(" ")
    }

    /** Integer, thousands-grouped integer ("1.500", "1,500") or decimal ("3,5", "3.25"). */
    fun number(raw: String): String {
        if (GROUPED.matches(raw)) {
            val plain = raw.filter(Char::isDigit)
            return if (plain.length > MAX_SPOKEN_DIGITS) digits(plain) else integer(plain.toLong())
        }
        val sep = raw.indexOfFirst { it == '.' || it == ',' }
        if (sep < 0) {
            val longId = raw.length >= DIGIT_BY_DIGIT_LENGTH || (raw.length > 1 && raw[0] == '0')
            return if (longId) digits(raw) else integer(raw.toLong())
        }
        val whole = raw.substring(0, sep)
        val fraction = raw.substring(sep + 1)
        return "${number(whole)} phẩy ${digits(fraction)}"
    }

    fun digits(raw: String): String = raw.filter(Char::isDigit).map { DIGITS[it - '0'] }.joinToString(" ")

    private fun triple(n: Int, full: Boolean): String {
        val hundreds = n / 100
        val rest = n % 100
        val parts = ArrayList<String>(3)
        val saysHundreds = full || hundreds > 0
        if (saysHundreds) parts += "${DIGITS[hundreds]} trăm"
        when {
            rest == 0 -> Unit
            rest < 10 -> parts += if (saysHundreds) "lẻ ${DIGITS[rest]}" else DIGITS[rest]
            else -> parts += twoDigits(rest)
        }
        return parts.joinToString(" ")
    }

    private fun twoDigits(value: Int): String {
        val tens = value / 10
        val ones = value % 10
        val head = if (tens == 1) "mười" else "${DIGITS[tens]} mươi"
        val tail = when {
            ones == 0 -> return head
            ones == 5 -> "lăm"
            ones == 1 && tens > 1 -> "mốt"
            else -> DIGITS[ones]
        }
        return "$head $tail"
    }

    private fun month(raw: String): String = when (val m = raw.toInt()) {
        4 -> "tư"
        else -> integer(m.toLong())
    }

    private fun numericDate(m: MatchResult): String {
        val day = m.groupValues[1].toInt()
        val mon = m.groupValues[2].toInt()
        if (day !in 1..31 || mon !in 1..12) return m.value
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { " năm ${integer(it.toLong())}" }.orEmpty()
        return "${integer(day.toLong())} tháng ${month(mon.toString())}$year"
    }

    private fun clock(m: MatchResult): String {
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 24 || min > 59) return m.value
        val hour = "${integer(h.toLong())} giờ"
        return when {
            min == 0 -> hour
            min < 10 -> "$hour ${integer(min.toLong())} phút"
            else -> "$hour ${integer(min.toLong())}"
        }
    }

    private fun spell(letters: String): String = letters.map { LETTERS.getValue(it.uppercaseChar()) }.joinToString(" ")

    private const val DIGIT_BY_DIGIT_LENGTH = 7
    private const val MAX_SPOKEN_DIGITS = 12
    private val DIGITS = listOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
    private val SCALES = listOf("", "nghìn", "triệu", "tỷ")

    // Vietnamese letter names as used when reading acronyms aloud.
    private val LETTERS = mapOf(
        'A' to "a", 'B' to "bê", 'C' to "xê", 'D' to "đê", 'E' to "e", 'F' to "ép", 'G' to "giê",
        'H' to "hát", 'I' to "i", 'J' to "giây", 'K' to "ca", 'L' to "e lờ", 'M' to "em mờ",
        'N' to "en nờ", 'O' to "o", 'P' to "pê", 'Q' to "quy", 'R' to "e rờ", 'S' to "ét",
        'T' to "tê", 'U' to "u", 'V' to "vê", 'W' to "đắp bờ liu", 'X' to "ích", 'Y' to "i",
        'Z' to "dét",
    )

    // Loanwords MT keeps in Latin script; spelled the way Vietnamese speakers say them.
    private val LEXICON = mapOf(
        "facebook" to "phây búc", "google" to "gu gồ", "youtube" to "diu túp", "zalo" to "da lô",
        "email" to "i meo", "e-mail" to "i meo", "internet" to "in tơ nét", "online" to "on lai",
        "wifi" to "wai phai", "wi-fi" to "wai phai", "iphone" to "ai phôn", "ok" to "ô kê",
        "okay" to "ô kê", "taxi" to "tắc xi", "video" to "vi đê ô", "laptop" to "láp tốp",
        "website" to "web sai", "covid" to "cô vít", "app" to "áp",
    )

    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"
    // Never touch digits glued to a hyphen or letter: "A-12", "B2", "COVID-19" stay ids.
    private const val NUM_START = "(?<![\\p{L}\\p{N}\\-.,])"

    private val NUMERIC_DATE = Regex("$NUM_START(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?(?![\\d/])")
    private val MONTH_YEAR = Regex(
        "(?:$NUM_START(\\d{1,2})\\s+)?tháng\\s+(\\d{1,2})(?:\\s*,\\s*|\\s+năm\\s+)(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH = Regex("tháng\\s+(1[0-2]|0?[1-9])(?![\\d:h]|[.,]\\d)", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("$NUM_START(\\d{1,2})\\s*(?::\\s*|h)(\\d{2})(?!\\d)")
    private val HOUR_ONLY = Regex("$NUM_START(\\d{1,2})h$NOT_WORD_AFTER")
    private val DOLLAR = Regex("\\$\\s?(\\d[\\d.,]*\\d|\\d)")
    private val PERCENT = Regex("$NUM_START(\\d[\\d.,]*\\d|\\d)\\s?%")
    private val CODE_NUMBER = Regex("$NOT_WORD_BEFORE([A-Z]{2,5})[ -]?(\\d{1,6})$NOT_WORD_AFTER")
    private val ACRONYM = Regex("$NOT_WORD_BEFORE[A-Z]{2,5}$NOT_WORD_AFTER")
    private val VI_SYLLABLE = Regex("(?:ngh|ng|nh|ch|gh|gi|kh|ph|th|tr|qu|[bcdghklmnprstvx])?[aeiouy]{1,3}(?:ng|nh|ch|[cmnpt])?")
    private val NUMBER = Regex("$NUM_START\\d+(?:[.,]\\d+)*(?![\\p{N}\\p{L}])")
    private val GROUPED = Regex("\\d{1,3}(?:([.,])\\d{3})(?:\\1\\d{3})*")
    private val LEXICON_PATTERN = Regex(
        NOT_WORD_BEFORE + "(?:" + LEXICON.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" + NOT_WORD_AFTER,
        RegexOption.IGNORE_CASE,
    )
    private val SYMBOL_WORDS = mapOf("+" to "cộng", "&" to "và", "%" to "phần trăm")
    private val SYMBOLS = Regex("[+&%]")
    private val SPACES = Regex("[ \\t]+")
    private val SPACE_BEFORE_PUNCT = Regex(" ([.,!?;:])")
}
