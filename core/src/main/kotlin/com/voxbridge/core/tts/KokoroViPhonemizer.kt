package com.voxbridge.core.tts

import java.text.Normalizer

/**
 * Kokoro-VI text -> phonemes -> input ids, matching vig2p 0.1.2 (`phonemize_text`).
 *
 * vig2p phonemizes every letter token on its own with sea_g2p, so the bundled lexicon
 * (model-tools-python/qaihub/gen_kokoro_lexicon.py: every Vietnamese syllable spelling plus the
 * benchmark vocabulary, already passed through vig2p's fixups) reproduces it exactly for the
 * tokens it covers. Unknown words fall back to their hyphen parts, then to spelled letters.
 * Text must already be in spoken form (SpokenTextNormalizer): digits are not verbalized here.
 */
class KokoroViPhonemizer(
    private val lexicon: Map<String, String>,
    private val vocab: Map<Int, Long>,
) {
    init { require(lexicon.isNotEmpty() && vocab.isNotEmpty()) { "Kokoro lexicon/vocab is empty" } }

    fun phonemize(text: String): String {
        val source = Normalizer.normalize(text, Normalizer.Form.NFC).replace('’', '\'').replace('‘', '\'')
        val out = StringBuilder()
        for (match in TOKEN.findAll(source)) {
            val token = match.value
            when {
                token.isBlank() -> out.append(' ')
                WORD.matches(token) -> out.append(lexicon[token] ?: word(token.lowercase()))
                else -> out.append(fixPunctuation(token))
            }
        }
        return out.toString().trim()
    }

    /** Kokoro ids `[0, ids..., 0]`; phonemes missing from the vocab are dropped, as KModel does. */
    fun inputIds(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids += 0L
        phonemes.codePoints().forEach { cp -> vocab[cp]?.let { ids += it } }
        ids += 0L
        return ids.toLongArray()
    }

    private fun word(lower: String): String {
        lexicon[lower]?.let { return it }
        if ('-' in lower || '\'' in lower) {
            val parts = lower.split('-', '\'').filter { it.isNotEmpty() }
            if (parts.all { it in lexicon }) return parts.joinToString("") { lexicon.getValue(it) }
        }
        return lower.mapNotNull { lexicon[it.toString()] }.joinToString(" ")
    }

    companion object {
        /** Kokoro context is 512 ids including the two pads; KModel asserts on longer input. */
        const val MAX_PHONEMES = 510

        private const val LETTER = "A-Za-zÀ-ỹĐđ"
        private val TOKEN = Regex("[$LETTER]+(?:[-'][$LETTER]+)*|\\s+|.")
        private val WORD = Regex("[$LETTER]+(?:[-'][$LETTER]+)*")

        /** vig2p VI_FIXUPS as they apply to a single non-letter token. */
        internal fun fixPunctuation(token: String): String = when (token) {
            "1", "7" -> "→"
            "2" -> "↘"
            "3" -> "↗"
            "4" -> "↓"
            "5" -> "ʔ↗"
            "6" -> "ʔ↓"
            "–" -> "—"
            "-", "*", "'", "’", "‘" -> ""
            "/", "&" -> " "
            else -> token
        }

        /** Lexicon TSV: `word<TAB>phonemes`, one per line. */
        fun parseLexicon(lines: Sequence<String>): Map<String, String> = HashMap<String, String>(65536).also { map ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                require(tab > 0 && tab < line.length - 1) { "Invalid Kokoro lexicon row" }
                map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }

        /** Vocab TSV: `codepoint<TAB>id` (decimal), one per line. */
        fun parseVocab(lines: Sequence<String>): Map<Int, Long> = HashMap<Int, Long>().also { map ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val (cp, id) = line.split('\t').also { require(it.size == 2) { "Invalid Kokoro vocab row" } }
                map[cp.toInt()] = id.toLong()
            }
        }
    }
}
