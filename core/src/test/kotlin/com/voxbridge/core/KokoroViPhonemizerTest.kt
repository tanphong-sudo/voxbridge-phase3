package com.voxbridge.core

import com.voxbridge.core.tts.KokoroViPhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Expected strings are vig2p 0.1.2 output (VPS, kokoro-vietnamese venv) for the same text. */
class KokoroViPhonemizerTest {
    private val lexicon = mapOf(
        "anh" to "ˈæɲ", "giải" to "ʝˈaː↓j", "thích" to "θˈi↗c", "lại" to "lˈaːʔ↓j", "chậm" to "ʧˈəʔ↓m",
        "hơn" to "hˈəːn", "được" to "dˌyəʔ↓c", "không" to "xˌoŋ",
        "iWatch" to "ˈaɪ wˈɑːʧ", "iwatch" to "ˈaɪwɑːʧ", "f" to "ˈɛ↗p", "i" to "ˈaɪ",
    )
    private val vocab = mapOf('?'.code to 6L, ' '.code to 16L, 'æ'.code to 72L, 'ɲ'.code to 114L, 'ˈ'.code to 156L)
    private val g2p = KokoroViPhonemizer(lexicon, vocab)

    @Test
    fun `matches vig2p on a benchmark sentence regardless of capitalization`() {
        assertEquals(
            "ˈæɲ ʝˈaː↓j θˈi↗c lˈaːʔ↓j ʧˈəʔ↓m hˈəːn dˌyəʔ↓c xˌoŋ?",
            g2p.phonemize("Anh giải thích lại chậm hơn được KHÔNG?"),
        )
    }

    @Test
    fun `camelCase spellings use their exact-case row`() {
        assertEquals("ˈaɪ wˈɑːʧ", g2p.phonemize("iWatch"))
        assertEquals("ˈaɪwɑːʧ", g2p.phonemize("iwatch"))
    }

    @Test
    fun `decomposed Vietnamese input is normalized to NFC before lookup`() {
        val decomposed = java.text.Normalizer.normalize("được", java.text.Normalizer.Form.NFD)
        assertEquals("dˌyəʔ↓c", g2p.phonemize(decomposed))
    }

    @Test
    fun `unknown words are spelled from letter rows and whitespace collapses`() {
        assertEquals("ˈaɪ ˈɛ↗p ˈæɲ", g2p.phonemize("if \n anh"))
    }

    @Test
    fun `non-letter tokens follow vig2p fixups`() {
        assertEquals("anh→↘", KokoroViPhonemizer.fixPunctuation("anh") + KokoroViPhonemizer.fixPunctuation("1") +
            KokoroViPhonemizer.fixPunctuation("2"))
        assertEquals("ˈæɲ   ˈæɲ", g2p.phonemize("anh / anh"))
        assertEquals("ˈæɲ — ˈæɲ", g2p.phonemize("anh – anh"))
    }

    @Test
    fun `input ids are padded and drop unknown phonemes`() {
        assertContentEquals(longArrayOf(0, 156, 72, 114, 16, 6, 0), g2p.inputIds("ˈæɲ ↗?"))
    }

    @Test
    fun `parses lexicon and vocab tables`() {
        val lex = KokoroViPhonemizer.parseLexicon(sequenceOf("anh\tˈæɲ", "", "iWatch\tˈaɪ wˈɑːʧ"))
        assertEquals("ˈaɪ wˈɑːʧ", lex["iWatch"])
        assertEquals(mapOf(63 to 6L), KokoroViPhonemizer.parseVocab(sequenceOf("63\t6")))
    }
}
