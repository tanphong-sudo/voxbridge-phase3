package com.voxbridge.runtime

import com.sentencepiece.Model
import com.sentencepiece.Scoring
import com.sentencepiece.SentencePieceAlgorithm
import com.sentencepiece.ResultBuilderImpl
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * SentencePiece tokenizer for OPUS-MT (source.spm / target.spm), backed by SentencePiece4J.
 * It is pure Java so the same tokenizer works on Android arm64 and the desktop JVM harness
 * without loading a platform-specific SentencePiece JNI library.
 *
 * Flow matches HF MarianTokenizer: text -> SP pieces -> ids via the model's vocab.json (the ONNX
 * graph indexes by vocab.json, which may reindex the raw SP ids, so we map through it rather than
 * trusting SP-internal ids). Decode reverses it: ids -> pieces -> detokenized string.
 *
 * Special-token ids (</s> EOS, <pad>) are READ from vocab.json — standard for OPUS-MT exports.
 * Marian uses the pad token as the decoder-start token. These are read, not guessed; if a given
 * export uses different special tokens the values still come from that model's own vocab.json.
 */
class SpmTokenizer(
    sourceSpm: File,
    targetSpm: File,
    vocabJson: File,
) : AutoCloseable {

    private val srcSp = Model.parseFrom(sourceSpm.toPath())
    private val tgtSp = Model.parseFrom(targetSpm.toPath())
    private val algorithm = SentencePieceAlgorithm(true, Scoring.HIGHEST_SCORE)

    private val pieceToId: Map<String, Int>
    private val idToPiece: Map<Int, String>
    private val unkId: Int
    val eosId: Long
    val decoderStartId: Long   // Marian convention: the pad token

    init {
        val vocab = JSONObject(vocabJson.readText())
        val p2i = HashMap<String, Int>(vocab.length())
        val i2p = HashMap<Int, String>(vocab.length())
        for (key in vocab.keys()) {
            val id = vocab.getInt(key)
            p2i[key] = id; i2p[id] = key
        }
        pieceToId = p2i; idToPiece = i2p
        unkId = p2i["<unk>"] ?: 1
        eosId = (p2i["</s>"] ?: 0).toLong()
        decoderStartId = (p2i["<pad>"] ?: 0).toLong()   // Marian decoder-start = pad
    }

    /** text -> token ids the encoder graph expects (with trailing EOS, as Marian does). */
    fun encode(text: String): LongArray {
        // SentencePiece's own ids are not assumed to equal Marian vocab.json ids. Resolve the
        // segmented pieces first and then map those strings through the exported Marian vocab.
        // SentencePiece4J's encodeNormalized lowercases unconditionally. Marian's nmt_nfkc
        // tokenizer is case-sensitive: changing "Không" to "không" changes the input IDs.
        // Prepare the word markers ourselves and use the library's raw unigram segmenter.
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        val prepared = if (normalized.isEmpty()) "" else "▁" + normalized.replace(" ", "▁")
        val builder = ResultBuilderImpl()
        algorithm.segment(prepared, builder, srcSp)
        val pieces = builder.tokenIds.map(srcSp::tokenById)
        val ids = ArrayList<Long>(pieces.size + 1)
        for (p in pieces) ids.add((pieceToId[p] ?: unkId).toLong())
        ids.add(eosId)
        return ids.toLongArray()
    }

    /** decoder output ids -> detokenized target string (stops at EOS, strips specials). */
    fun decode(ids: List<Long>): String {
        val pieces = ids.asSequence()
            .takeWhile { it != eosId }
            .map { idToPiece[it.toInt()] ?: "" }
            .filter { it.isNotEmpty() && !(it.startsWith("<") && it.endsWith(">")) }
            .toList()
        if (pieces.isEmpty()) return ""
        // Convert exported Marian pieces back to the target SPM's internal ids before asking the
        // model to detokenize. Unknown pieces deliberately become the model's <unk> id.
        val targetIds = pieces.map(tgtSp::getIdForToken)
        return tgtSp.decodeSmart(targetIds)
    }

    override fun close() = Unit
}
