package com.voxbridge.core.pipeline

import com.voxbridge.core.model.Language
import java.util.Locale

internal data class ImportanceToken(
    val text: String, val normalized: String, val start: Int, val endExclusive: Int, val category: WordImportance,
) {
    fun span(side: WordEvidenceSide, uncertain: Boolean, confidence: Double? = null) = WordReliabilitySpan(
        text,start,endExclusive,category,side,uncertain,confidence,
        category.weight * (confidence?.let { 1.0-it } ?: if(uncertain) 1.0 else 0.0),
    )
}

internal fun importanceTokens(text: String, language: Language): List<ImportanceToken> {
    val matches = WORD.findAll(text).toList()
    val normalized = matches.map { it.value.lowercase(Locale.ROOT).replace('’','\'') }
    val lowPhrases = if (language == Language.VI) setOf("làm ơn", "vui lòng") else emptySet()
    return matches.mapIndexed { i, match ->
        val word = normalized[i]
        val inLowPhrase = (i > 0 && "${normalized[i-1]} $word" in lowPhrases) ||
            (i+1 < normalized.size && "$word ${normalized[i+1]}" in lowPhrases)
        val category = when {
            word in NEGATION -> WordImportance.CRITICAL_NEGATION
            word.any(Char::isDigit) && word.any(Char::isLetter) -> WordImportance.CRITICAL_ID
            word.any(Char::isDigit) || word in NUMBER_WORDS -> WordImportance.CRITICAL_NUMBER
            word in UNITS && i>0 && (normalized[i-1].any(Char::isDigit) || normalized[i-1] in NUMBER_WORDS) -> WordImportance.CRITICAL_QUANTITY
            word in ACTIONS -> WordImportance.HIGH_ACTION
            word in EQUIPMENT -> WordImportance.HIGH_EQUIPMENT
            language == Language.EN && word in LOW_EN || language == Language.VI && (word in LOW_VI || inLowPhrase) -> WordImportance.LOW
            else -> WordImportance.NORMAL
        }
        ImportanceToken(match.value,word,match.range.first,match.range.last+1,category)
    }
}

/**
 * Comparison-only canonical token: one or more original tokens rewritten to a spelling-neutral form.
 * It never changes the transcript that is shown, translated or spoken; it only prevents two decoder
 * outputs that say the same thing ("32" / "thirty-two", "can't" / "can not") from being counted as a
 * disagreement. [sources] are indices into the original token list.
 */
internal data class CanonicalToken(val token: ImportanceToken, val sources: List<Int>)

internal fun canonicalComparisonTokens(tokens: List<ImportanceToken>, language: Language): List<CanonicalToken> {
    val pieces = mutableListOf<Pair<String, Int>>()
    tokens.forEachIndexed { index, token ->
        for (piece in splitForComparison(token.normalized, language)) pieces += piece to index
    }
    val result = mutableListOf<CanonicalToken>()
    var i = 0
    while (i < pieces.size) {
        if (language == Language.EN && pieces[i].first in EN_NUMBER_VALUES) {
            var j = i
            val values = mutableListOf<Long>()
            while (j < pieces.size && pieces[j].first in EN_NUMBER_VALUES) { values += EN_NUMBER_VALUES.getValue(pieces[j].first); j++ }
            for ((value, range) in englishNumbers(values)) {
                val sources = (i + range.first..i + range.last).map { pieces[it].second }.distinct()
                result += canonical(value.toString(), WordImportance.CRITICAL_NUMBER, sources, tokens)
            }
            i = j
            continue
        }
        val (piece, source) = pieces[i]
        val category = when {
            piece in NEGATION -> WordImportance.CRITICAL_NEGATION
            piece.all(Char::isDigit) -> WordImportance.CRITICAL_NUMBER
            else -> tokens[source].category
        }
        result += canonical(piece, category, listOf(source), tokens)
        i++
    }
    return result
}

private fun canonical(value: String, category: WordImportance, sources: List<Int>, tokens: List<ImportanceToken>): CanonicalToken {
    val first = tokens[sources.first()]
    val last = tokens[sources.last()]
    return CanonicalToken(ImportanceToken(value, value, first.start, last.endExclusive, category), sources)
}

/** Splits only spelling variants; ordinary words pass through unchanged. */
private fun splitForComparison(word: String, language: Language): List<String> {
    // Decimal/time separators: "8.30" and "8:30" are both spoken "eight thirty".
    if (word.any(Char::isDigit) && word.all { it.isDigit() || it == '.' || it == ':' || it == ',' }) {
        return word.split('.', ':', ',').filter { it.isNotEmpty() }.map { it.trimStart('0').ifEmpty { "0" } }
    }
    if (language != Language.EN) return listOf(word)
    if (word.contains('-') && word.split('-').all { it in EN_NUMBER_VALUES }) return word.split('-')
    EN_CONTRACTIONS[word]?.let { return it }
    val apostrophe = word.indexOf('\'')
    if (apostrophe > 0) {
        val stem = word.substring(0, apostrophe)
        val suffix = word.substring(apostrophe + 1)
        when (suffix) {
            "t" -> if (stem.endsWith("n") && stem.length > 1) return listOf(stem.dropLast(1), "not")
            "re" -> return listOf(stem, "are")
            "ve" -> return listOf(stem, "have")
            "ll" -> return listOf(stem, "will")
            "m" -> return listOf(stem, "am")
            "s", "d" -> return listOf(word)
            // A missing space before a clipped word ("small'cause") is tokenization, not speech.
            else -> if (suffix.length > 2 && suffix.all(Char::isLetter)) return listOf(stem, suffix)
        }
    }
    return listOf(word)
}

/** Groups a run of English number words into values; a new number starts when the grammar restarts. */
private fun englishNumbers(values: List<Long>): List<Pair<Long, IntRange>> {
    val out = mutableListOf<Pair<Long, IntRange>>()
    var start = 0
    var total = 0L
    var current = -1L
    var lastWasTen = false
    fun isTen(value: Long) = value in 20..90 && value % 10 == 0L
    values.forEachIndexed { index, value ->
        when {
            value >= 1000 -> { total += current.coerceAtLeast(1) * value; current = -1; lastWasTen = false }
            value == 100L -> { current = current.coerceAtLeast(1) * 100; lastWasTen = false }
            current < 0 -> { current = value; lastWasTen = isTen(value) }
            lastWasTen && value in 1..9 -> { current += value; lastWasTen = false }
            current >= 100 && current % 100 == 0L && value < 100 -> { current += value; lastWasTen = isTen(value) }
            else -> {
                out += (total + current) to (start until index)
                start = index; total = 0; current = value; lastWasTen = isTen(value)
            }
        }
    }
    if (values.isNotEmpty()) out += (total + current.coerceAtLeast(0)) to (start until values.size)
    return out
}

/** Weighted LCS protects repeated content/critical tokens before spending matches on fillers. */
internal fun importanceMatches(first: List<ImportanceToken>, second: List<ImportanceToken>): List<Pair<Int,Int>> {
    val width=second.size+1
    val scores=LongArray((first.size+1)*width)
    for(i in first.indices) for(j in second.indices) {
        val diagonal = if(first[i].normalized == second[j].normalized) scores[i*width+j]+reward(first[i].category) else -1L
        scores[(i+1)*width+j+1]=maxOf(scores[i*width+j+1],scores[(i+1)*width+j],diagonal)
    }
    val result=mutableListOf<Pair<Int,Int>>()
    var i=first.size;var j=second.size
    while(i>0 && j>0) {
        if(first[i-1].normalized == second[j-1].normalized &&
            scores[i*width+j] == scores[(i-1)*width+j-1]+reward(first[i-1].category)) {
            result += (i-1) to (j-1);i--;j--
        } else if(scores[(i-1)*width+j] >= scores[i*width+j-1]) i-- else j--
    }
    return result.reversed()
}

private fun reward(category: WordImportance): Long = when {
    // Each tier exceeds the sum of every lower-tier match at the 1024-token cap.
    category.name.startsWith("CRITICAL_") -> TIER_BASE * TIER_BASE * TIER_BASE
    category.weight >= .8 -> TIER_BASE * TIER_BASE
    category == WordImportance.NORMAL -> TIER_BASE
    else -> 1L
}

private const val TIER_BASE=2_048L
private val WORD=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*(?:[._/-][\\p{L}\\p{N}]+)*")
private val LOW_EN=setOf("a","an","the","please","uh","um","erm")
private val LOW_VI=setOf("ạ","nhé","nha","ừm","ờ")
private val NEGATION=setOf("no","not","never","without","don't","dont","doesn't","doesnt","didn't","didnt",
    "can't","cant","cannot","won't","wont","isn't","isnt","aren't","arent","không","đừng","chưa","chẳng","chớ")
private val NUMBER_WORDS=setOf("zero","one","two","three","four","five","six","seven","eight","nine","ten",
    "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty",
    "thirty","forty","fifty","sixty","seventy","eighty","ninety","hundred","thousand","million","billion",
    "first","second","third","một","hai","ba","bốn","tư","năm","sáu","bảy","tám","chín","mười","trăm","nghìn","ngàn","triệu","tỷ")
private val UNITS=setOf("kg","g","mg","l","ml","mm","cm","m","bar","psi","v","volt","volts","a","amp","amps",
    "liters","litres","kilograms","grams","minutes","seconds","degrees","phút","giây","giờ","lít","gam","ký","độ")
private val ACTIONS=setOf("stop","start","open","close","shutdown","shut","disconnect","connect","remove","enter","touch",
    "dừng","mở","đóng","chạy","ngắt","tắt","bật","vặn","đặt","rút","cắm","khởi")
private val EQUIPMENT=setOf("valve","conveyor","machine","pump","motor","switch","pressure","equipment","guard",
    "van","máy","bơm","động","công","tắc","thiết","bị","áp","suất")
private val EN_CONTRACTIONS=mapOf("cannot" to listOf("can","not"),"can't" to listOf("can","not"),
    "cant" to listOf("can","not"),"won't" to listOf("will","not"),"wont" to listOf("will","not"),
    "shan't" to listOf("shall","not"),"dont" to listOf("do","not"),"doesnt" to listOf("does","not"),
    "didnt" to listOf("did","not"),"isnt" to listOf("is","not"),"arent" to listOf("are","not"),
    "ok" to listOf("okay"))
private val EN_NUMBER_VALUES=mapOf("zero" to 0L,"one" to 1L,"two" to 2L,"three" to 3L,"four" to 4L,"five" to 5L,
    "six" to 6L,"seven" to 7L,"eight" to 8L,"nine" to 9L,"ten" to 10L,"eleven" to 11L,"twelve" to 12L,"thirteen" to 13L,
    "fourteen" to 14L,"fifteen" to 15L,"sixteen" to 16L,"seventeen" to 17L,"eighteen" to 18L,"nineteen" to 19L,
    "twenty" to 20L,"thirty" to 30L,"forty" to 40L,"fifty" to 50L,"sixty" to 60L,"seventy" to 70L,"eighty" to 80L,
    "ninety" to 90L,"hundred" to 100L,"thousand" to 1_000L,"million" to 1_000_000L,"billion" to 1_000_000_000L)
