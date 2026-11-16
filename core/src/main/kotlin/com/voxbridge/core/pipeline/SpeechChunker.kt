package com.voxbridge.core.pipeline

/**
 * Splits a translation into sentence-sized chunks so TTS can start playing the first sentence
 * while later ones are still being synthesized (lower end-of-speech -> first-audio latency).
 *
 * Splits only on `.`, `!`, `?`, `;` FOLLOWED BY WHITESPACE, so decimals ("3.5 bar") and IDs
 * ("A-12") never break. Fragments shorter than [MIN_CHUNK_CHARS] ("OK.") are merged into a
 * neighbour: synthesizing a two-word chunk first saves no time and sounds choppy. The text itself
 * is never rewritten beyond whitespace normalization, so chunks rejoin to the spoken text.
 */
object SpeechChunker {
    const val MIN_CHUNK_CHARS = 12

    private val BOUNDARY = Regex("(?<=[.!?;])\\s+")
    private val WHITESPACE = Regex("\\s+")

    fun split(text: String): List<String> {
        val normalized = text.trim().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return emptyList()
        val pieces = normalized.split(BOUNDARY).filter { it.isNotBlank() }
        val merged = ArrayList<String>(pieces.size)
        var pending = ""
        for (piece in pieces) {
            val candidate = if (pending.isEmpty()) piece else "$pending $piece"
            if (candidate.length < MIN_CHUNK_CHARS) {
                pending = candidate
            } else {
                merged += candidate
                pending = ""
            }
        }
        if (pending.isNotEmpty()) {
            if (merged.isEmpty()) merged += pending
            else merged[merged.lastIndex] = merged.last() + " " + pending
        }
        val first = merged.firstOrNull() ?: return merged
        val clause = firstClauseEnd(first) ?: return merged
        return listOf(first.substring(0, clause).trimEnd(), first.substring(clause).trimStart()) + merged.drop(1)
    }

    /**
     * Piper's CPU frontend time grows with chunk length, and nothing plays until the first chunk
     * is synthesized. A long FIRST sentence is therefore split once at a natural clause boundary
     * (a comma, else just before a subordinating conjunction) so playback starts sooner while
     * the rest is synthesized behind it. Only the first chunk is touched; both halves must stay
     * long enough to sound like phrases. Returns the split index, or null to keep the sentence.
     */
    internal fun firstClauseEnd(sentence: String): Int? {
        if (sentence.length < FIRST_CLAUSE_SPLIT_CHARS) return null
        fun valid(at: Int) = at >= MIN_FIRST_CLAUSE_CHARS && sentence.length - at >= MIN_CHUNK_CHARS
        COMMA.findAll(sentence).map { it.range.first + 1 }.firstOrNull(::valid)?.let { return it }
        return CONJUNCTION.findAll(sentence).map { it.range.first }.firstOrNull(::valid)
    }

    /** First sentences shorter than this synthesize fast enough whole. */
    const val FIRST_CLAUSE_SPLIT_CHARS = 48
    const val MIN_FIRST_CLAUSE_CHARS = 18

    // Comma followed by a space: never "3,5" (Vietnamese decimal) or "1,000".
    private val COMMA = Regex(",(?=\\s)")
    // Clause-opening conjunctions only. "and"/"và" are excluded: they usually join nouns.
    private val CONJUNCTION = Regex(
        "(?<=\\s)(?=(because|but|so that|although|while|when|if|unless|vì|bởi vì|nhưng|nên|mặc dù|khi|nếu|để)\\s)",
        RegexOption.IGNORE_CASE,
    )
}
