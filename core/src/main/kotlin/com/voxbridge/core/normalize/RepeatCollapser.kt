package com.voxbridge.core.normalize

import com.voxbridge.core.model.Language

/**
 * Removes speaker stutter ("it's it's it's", "quality time quality time") from an ASR transcript
 * before it reaches the translator, which otherwise either copies the stutter or drops nearby
 * words to "fix" it.
 *
 * Conservative on purpose:
 * - Vietnamese reduplication is grammatical ("từ từ", "đi đi"), so a single word must repeat
 *   three or more times before it is collapsed there; English collapses a doubled word except
 *   for the few that legitimately double ("that that", "had had").
 * - A phrase of two to four words repeated back-to-back is collapsed in both languages.
 * - Anything containing a digit is never touched, so numbers the safety check compares stay intact.
 * Matching ignores case and trailing punctuation; the first occurrence is kept as spoken.
 */
object RepeatCollapser {
    private const val MAX_PHRASE_WORDS = 4
    private val EN_LEGIT_DOUBLES = setOf("that", "had", "is", "do", "very", "so", "no", "bye", "now")

    fun collapse(text: String, language: Language): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return text
        val keys = words.map { it.lowercase().trimEnd(',', '.', '!', '?', ';', ':', '…') }
        val out = ArrayList<Int>(words.size)
        var i = 0
        while (i < words.size) {
            val skip = repeatedSpan(keys, i, language)
            if (skip > 0) {
                i += skip
                continue
            }
            out += i
            i++
        }
        if (out.size == words.size) return text
        return out.joinToString(" ") { words[it] }
    }

    /**
     * Number of words starting at [start] that duplicate the phrase right before them and should be
     * dropped, or 0. Longer phrases are tried first so "quality time quality time" collapses as a pair.
     */
    private fun repeatedSpan(keys: List<String>, start: Int, language: Language): Int {
        for (n in MAX_PHRASE_WORDS downTo 1) {
            if (start - n < 0 || start + n > keys.size) continue
            val previous = keys.subList(start - n, start)
            if (previous != keys.subList(start, start + n)) continue
            if (previous.any { k -> k.isEmpty() || k.any(Char::isDigit) }) continue
            if (n == 1 && !singleWordRepeatAllowed(keys, start, language)) continue
            return n
        }
        return 0
    }

    private fun singleWordRepeatAllowed(keys: List<String>, start: Int, language: Language): Boolean {
        val word = keys[start]
        if (language == Language.EN) return word !in EN_LEGIT_DOUBLES
        // Elsewhere only collapse a run of three or more: "từ từ" stays, "từ từ từ" becomes "từ".
        val runStart = generateSequence(start) { it - 1 }.takeWhile { it >= 0 && keys[it] == word }.last()
        var runEnd = start
        while (runEnd + 1 < keys.size && keys[runEnd + 1] == word) runEnd++
        return runEnd - runStart + 1 >= 3
    }
}
