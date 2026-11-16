package com.voxbridge.runtime

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Whisper BPE byte-level detokenizer: token id -> text, WITHOUT any ML dependency.
 *
 * Why this exists: the Android runtime has no HuggingFace tokenizers library, but the decode loop
 * needs to turn the model's token ids into text. Whisper's vocabulary is a GPT-2-style byte-level
 * BPE: each vocab entry is a STRING OF UNICODE CHARS that encodes raw UTF-8 bytes through the
 * reversible `bytes_to_unicode` map (GPT-2/Whisper `encoder.py`), with "Ġ" standing in for a
 * space. Decoding = ids -> vocab strings -> per-char inverse byte map -> UTF-8 text.
 *
 * Vocab source (whichever exists in the exported dir, as written by export_whisper_onnx.py's
 * `save_pretrained`):
 *   - vocab.json       { token: id }
 *   - tokenizer.json   { model: { vocab: { token: id } } }
 *
 * Text-vs-special rule for multilingual Whisper (verified against the real vocab layout):
 * ids 0..50256 are text tokens; 50257 is <|endoftext|> (EOT); EVERYTHING >= 50258 is
 * non-text (language codes, task tokens, control tokens, timestamp tokens up to the vocab end).
 * So text decode = "keep ids < 50257, skip the rest" — no per-name special-list needed.
 */
class WhisperBpeDecoder internal constructor(
    private val idToToken: Array<String?>,
) {
    /** Inverse of GPT-2 bytes_to_unicode: unicode codepoint in a token string -> original byte. */
    private val codepointToByte: IntArray = buildCodepointToByte()

    /** Number of real text tokens (ids >= this are EOT/specials/timestamps — never decoded). */
    private val textVocabEnd: Int = 50257

    /** @param ids model token ids (any mix of text/special). @return decoded text. */
    fun decode(ids: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id < 0 || id >= textVocabEnd) continue        // specials / languages / timestamps
            val tok = idToToken.getOrNull(id) ?: continue
            var i = 0
            while (i < tok.length) {
                val cp = tok.codePointAt(i)
                i += Character.charCount(cp)
                val b = byteFor(cp)
                if (b >= 0) bytes.write(b)
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    /** Vocab token string at [id], or null (used by WhisperTokens' fallback resolution). */
    fun tokenAt(id: Int): String? = idToToken.getOrNull(id)

    private fun byteFor(cp: Int): Int {
        if (cp < codepointToByte.size) return codepointToByte[cp]
        return -1
    }

    /**
     * GPT-2 bytes_to_unicode, inverted. Forward map: printable bytes 33..126, 161..172, 174..255
     * map to the char with the same codepoint; every other byte b is handed out, in ascending
     * order (0..32, 127..160, 173), the chars 256+n. We build the inverse table directly.
     */
    private fun buildCodepointToByte(): IntArray {
        val printable = HashSet<Int>().apply {
            for (b in 33..126) add(b)
            for (b in 161..172) add(b)
            for (b in 174..255) add(b)
        }
        // 188 printable + 68 others = 256 mapped bytes; max codepoint used = 256 + 67 = 323.
        val table = IntArray(256 + (256 - printable.size)) { -1 }
        for (b in 33..126) table[b] = b
        for (b in 161..172) table[b] = b
        for (b in 174..255) table[b] = b
        var n = 0
        for (b in 0..255) {
            if (b in printable) continue
            table[256 + n] = b
            n++
        }
        return table
    }

    companion object {
        /** Max token id seen in vocab (vocab.json/tokenizer.json) — bounds the reverse array. */
        fun load(dir: File): WhisperBpeDecoder {
            val vocabJson = File(dir, "vocab.json")
            val tokenizerJson = File(dir, "tokenizer.json")
            val pairs: MutableList<Pair<String, Int>> = ArrayList()
            when {
                vocabJson.exists() -> {
                    val j = JSONObject(vocabJson.readText())
                    for (key in j.keys()) pairs.add(key to j.getInt(key))
                }
                tokenizerJson.exists() -> {
                    val model = JSONObject(tokenizerJson.readText()).getJSONObject("model")
                    val vocab = model.getJSONObject("vocab")
                    for (key in vocab.keys()) pairs.add(key to vocab.getInt(key))
                }
                else -> error(
                    "No Whisper vocab found in ${dir.path} (expected vocab.json or tokenizer.json " +
                        "from export_whisper_onnx.py save_pretrained). The BPE detokenizer cannot " +
                        "be built without it — re-run the export."
                )
            }
            require(pairs.isNotEmpty()) { "Whisper vocab in ${dir.path} is empty" }
            val maxId = pairs.maxOf { it.second }
            require(maxId < 1_000_000) { "implausible Whisper vocab size $maxId" }
            val idToToken = arrayOfNulls<String>(maxId + 1)
            for ((tok, id) in pairs) idToToken[id] = tok
            return WhisperBpeDecoder(idToToken)
        }

        /** True if the given model dir carries a usable vocab (for ModelPaths readiness checks). */
        fun availableIn(dir: File): Boolean =
            File(dir, "vocab.json").exists() || File(dir, "tokenizer.json").exists()
    }
}
