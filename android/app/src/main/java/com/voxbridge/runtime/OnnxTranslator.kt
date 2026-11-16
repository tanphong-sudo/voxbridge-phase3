package com.voxbridge.runtime

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * On-device OPUS-MT translator (Marian, optimum export): implements the core [Translator] port
 * with ONNX Runtime, greedy host-side decode with a full past-key-values chain.
 *
 * CONTRACT (optimum v1.17.1 seq2seq exports — same OnnxConfigWithPast machinery as Whisper,
 * verified against exporters/onnx sources):
 *  - `encoder_model.onnx`: input_ids int64 [1,n] + attention_mask [1,n] -> last_hidden_state [1,n,h].
 *  - `decoder_model.onnx` (past-free, the BOOTSTRAP graph): input_ids [1,1] int64 (the
 *    decoder-start token) + encoder_hidden_states -> logits [1,1,V] + present.{l}.decoder.{key,value}
 *    + present.{l}.encoder.{key,value}. The past-free graph emits BOTH cache families ("both the
 *    decoder without past and with past have the KV cache as an output").
 *  - `decoder_with_past_model.onnx`: input_ids [1,1] + optional encoder_attention_mask +
 *    past_key_values.{l}.{decoder,encoder}.{key,value} -> logits [1,1,V] + present.{l}.decoder.*
 *    ONLY (it consumes the cross KV as constants and never re-emits them) — so the encoder part of
 *    the cache is bound from the bootstrap result on EVERY step, the decoder part from the previous
 *    step's present.*.
 *  - Degenerate export (no decoder_with_past produced): re-run the past-free graph over the whole
 *    generated sequence each step (O(n^2), correct; bring-up-only path).
 *
 * KEY DESIGN: every tensor name is resolved by INTROSPECTION of the live sessions (inputNames /
 * outputNames / declared types and shapes) — nothing hard-coded, no io.json needed. Past-cache
 * names are matched by their `.{layer}.{part}.{key|value}` structure (exactly 3 parts after the
 * prefix), never by raw substring, so `present.2.…` can never be confused with `present.12.…`.
 *
 * One model per direction is kept resident; the caller swaps direction by constructing the matching
 * instance (MODEL_MANIFEST footprint rule: active direction only). The direction is fixed at
 * [load] time and [translate] fails fast on a mismatched pair rather than silently using the
 * wrong model.
 */
class OnnxTranslator private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val bootstrap: OrtSession?,   // past-free decoder graph (null iff no with-past export exists)
    private val decoder: OrtSession,      // with-past decoder graph, or the past-free graph in the degenerate case
    private val tokenizer: SpmTokenizer,
    private val src: Language,
    private val tgt: Language,
) : Translator, AutoCloseable {

    private val usePast = bootstrap != null
    private val maxNewTokens = 64   // latency budget for one PTT utterance

    /** Fixed direction this instance is wired for (ChainedTranslator / routing introspect it). */
    val sourceLanguage: Language get() = src
    val targetLanguage: Language get() = tgt

    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        require(sourceLanguage == src && targetLanguage == tgt) {
            "OnnxTranslator instance is wired for $src->$tgt but was asked for $sourceLanguage->$targetLanguage"
        }
        require(text.isNotBlank()) { "cannot translate blank text" }

        val ids = tokenizer.encode(text)   // Marian: pieces + trailing EOS (>=1 element)
        val attn = LongArray(ids.size) { 1L }

        // Caller-owned input tensors must be closed once their run() returns (ORT contract —
        // native-heap leak otherwise; see WhisperAsr.decodeAiHub). Borrowed tensors (encHidden,
        // past-KV outputs) are owned by the Result that produced them and are NOT tracked here.
        val stepInputs = ArrayList<OnnxTensor>(4)
        fun track(t: OnnxTensor): OnnxTensor { stepInputs.add(t); return t }

        // Names resolved BEFORE any tensor exists, and the try encloses tensor CREATION too, so
        // a failure while building the second input closes the first (Round-4 review LOW — the
        // old code left the tracked ids tensor open if typedIds threw for the mask).
        val encIdsName = inputName(encoder, "input_ids")
        val encMaskName = inputName(encoder, "attention_mask")
        val encRes: OrtSession.Result
        try {
            val encIds = track(typedIds(encoder, encIdsName, ids))
            val encMask = track(typedIds(encoder, encMaskName, attn))
            encRes = encoder.run(mapOf(encIdsName to encIds, encMaskName to encMask))
        } finally {
            stepInputs.forEach { it.close() }
            stepInputs.clear()
        }
        try {
            val encHiddenName = encoder.outputNames.firstOrNull { it.contains("hidden") }
                ?: encoder.outputNames.first()
            val encHidden = encRes[encHiddenName]
                .orElseThrow { IllegalStateException("Missing encoder output: $encHiddenName") } as OnnxTensor

            // Greedy decode: token 0 from the bootstrap graph, then one token per with-past call.
            val out = ArrayList<Long>(maxNewTokens)
            var sumLogProb = 0.0
            var scored = 0
            var prevRes: OrtSession.Result? = null
            var bootstrapRes: OrtSession.Result? = null
            try {
                if (usePast) {
                    // CALL A (bootstrap): decoder-start token only; logits row 0 = first token.
                    // (usePast == bootstrap != null, but that is a separate property — bind a
                    // local so the compiler sees the invariant instead of a `!!`.)
                    val boot = requireNotNull(bootstrap) { "past-KV path requires the bootstrap (past-free) decoder graph" }
                    val start = longArrayOf(tokenizer.decoderStartId)
                    val inpA = HashMap<String, OnnxTensor>()
                    stepInputs.clear()
                    // Creation → run() inside one try: a failing hidden-states lookup closes the
                    // already-tracked ids tensor (Round-4 review LOW). The bootstrap call binds NO
                    // past KV — it IS the past-free graph; only the step loop below uses bindPast.
                    val resA: OrtSession.Result
                    try {
                        val idsA = inputName(boot, "input_ids")
                        inpA[idsA] = track(typedIds(boot, idsA, start))
                        val encHsA = boot.inputNames.firstOrNull {
                            it.contains("encoder") && (it.contains("hidden") || it.contains("encoder_outputs"))
                        } ?: error("bootstrap decoder has no encoder hidden-states input (inputs: ${boot.inputNames})")
                        inpA[encHsA] = encHidden
                        bootstrapMaskName(boot)?.let { inpA[it] = track(onesTensor(boot, it, longArrayOf(1, ids.size.toLong()))) }
                        resA = boot.run(inpA)
                    } finally {
                        stepInputs.forEach { it.close() }
                        stepInputs.clear()
                    }
                    bootstrapRes = resA
                    prevRes = resA
                    val logitsOutput = outputName(boot, "logits")
                    val logitsA = resA[logitsOutput]
                        .orElseThrow { IllegalStateException("Missing decoder output: $logitsOutput") } as OnnxTensor
                    var row = lastVocabRow(logitsA)
                    var next = argmax(row)
                    sumLogProb += logProbOf(row, next)
                    scored++
                    if (next.toLong() != tokenizer.eosId) {
                        out.add(next.toLong())
                        var lastToken = next
                        while (out.size < maxNewTokens) {
                            val inp = HashMap<String, OnnxTensor>()
                            stepInputs.clear()
                            // Creation → bindPast → run() inside ONE try (Round-4 review LOW):
                            // bindPast only reads from Results, and ANY throw before run() (name
                            // lookup, hidden-states check, tensor alloc) still closes the tensors
                            // already tracked into stepInputs.
                            val r: OrtSession.Result
                            try {
                                val idsN = inputName(decoder, "input_ids")
                                inp[idsN] = track(typedIds(decoder, idsN, longArrayOf(lastToken.toLong())))
                                // Cross attention comes from the bootstrap's encoder K/V. The
                                // with-past graph intentionally does not accept hidden states.
                                bootstrapMaskName(decoder)?.let { inp[it] = track(onesTensor(decoder, it, longArrayOf(1, ids.size.toLong()))) }
                                bindPast(bootstrapRes, requireNotNull(prevRes) { "previous-step Result missing for past binding" }, inp)
                                r = decoder.run(inp)
                            } finally {
                                stepInputs.forEach { it.close() }
                                stepInputs.clear()
                            }
                            if (prevRes !== bootstrapRes) prevRes?.close()
                            prevRes = r
                            val stepLogitsOutput = outputName(decoder, "logits")
                            row = lastVocabRow(
                                r[stepLogitsOutput]
                                    .orElseThrow { IllegalStateException("Missing decoder output: $stepLogitsOutput") } as OnnxTensor
                            )
                            next = argmax(row)
                            sumLogProb += logProbOf(row, next)
                            scored++
                            if (next.toLong() == tokenizer.eosId) break
                            out.add(next.toLong())
                            lastToken = next
                        }
                    }
                } else {
                    // Degenerate export: past-free graph only — re-run the whole sequence per step.
                    val seq = ArrayList<Long>(maxNewTokens + 1).apply { add(tokenizer.decoderStartId) }
                    while (seq.size <= maxNewTokens) {
                        val inp = HashMap<String, OnnxTensor>()
                        stepInputs.clear()
                        // Creation → run() inside one try (Round-4 review LOW): a failing
                        // encoderHsName() lookup no longer leaks the tracked ids tensor.
                        val r: OrtSession.Result
                        try {
                            val decIds = inputName(decoder, "input_ids")
                            inp[decIds] = track(typedIds(decoder, decIds, seq.toLongArray()))
                            inp[encoderHsName(decoder)] = encHidden
                            bootstrapMaskName(decoder)?.let { inp[it] = track(onesTensor(decoder, it, longArrayOf(1, ids.size.toLong()))) }
                            r = decoder.run(inp)
                        } finally {
                            stepInputs.forEach { it.close() }
                            stepInputs.clear()
                        }
                        prevRes?.close()
                        prevRes = r
                        val logitsOutput = outputName(decoder, "logits")
                        val row = lastVocabRow(
                            r[logitsOutput]
                                .orElseThrow { IllegalStateException("Missing decoder output: $logitsOutput") } as OnnxTensor
                        )
                        val next = argmax(row)
                        sumLogProb += logProbOf(row, next)
                        scored++
                        if (next.toLong() == tokenizer.eosId) break
                        seq.add(next.toLong()); out.add(next.toLong())
                    }
                }
            } finally {
                if (prevRes !== bootstrapRes) prevRes?.close()
                bootstrapRes?.close()
            }

            val translated = tokenizer.decode(out)
            val score = if (scored == 0) 0.0
            else exp(sumLogProb / scored).coerceIn(0.0, 1.0)
            return TranslationResult(text, translated, src, tgt, "opus-mt-$src-$tgt", score)
        } finally {
            encRes.close()
        }
    }

    override fun close() {
        encoder.close()
        bootstrap?.close()
        decoder.close()
        tokenizer.close()
    }

    // -----------------------------------------------------------------
    // Past-key-values chaining (exact structure parse — see class KDoc)
    // -----------------------------------------------------------------

    /**
     * Feed `past_key_values.{l}.{decoder|encoder}.{key|value}` inputs from the previous step's
     * `present.{l}.decoder.*` (chained decoder cache) and the bootstrap's `present.{l}.encoder.*`
     * (constant cross cache). The bootstrap Result must stay open for the whole decode — the
     * translate() finally block guarantees it.
     */
    private fun bindPast(
        bootstrapRes: OrtSession.Result,
        prevRes: OrtSession.Result,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        for (name in decoder.inputNames) {
            if (!name.contains("past_key_values")) continue
            // `past_key_values.2.decoder.key` -> [2, decoder, key]
            val parts = name.removePrefix("past_key_values").split('.').filter { it.isNotEmpty() }
            require(parts.size == 3) {
                "unexpected past-KV input name '$name' (expected past_key_values.<l>.<part>.<key|value>)"
            }
            val source = if (parts[1] == "encoder") bootstrapRes else prevRes
            val expected = "present.${parts[0]}.${parts[1]}.${parts[2]}"
            val outName = source.asIterable().map { it.key }.firstOrNull { it == expected }
                // tolerant fallback: same layer/part/key structure, different output prefix
                ?: source.asIterable().map { it.key }.firstOrNull {
                    it.endsWith(".${parts[0]}.${parts[1]}.${parts[2]}")
                }
                ?: error(
                    "no present output matching past input '$name' (bootstrap outputs: " +
                        "${bootstrap?.outputNames ?: decoder.outputNames}; with-past outputs: ${decoder.outputNames})"
                )
            inputs[name] = source[outName]
                .orElseThrow { IllegalStateException("Missing cached decoder output: $outName") } as OnnxTensor
        }
    }

    private fun encoderHsName(session: OrtSession): String =
        session.inputNames.firstOrNull {
            it.contains("encoder") && (it.contains("hidden") || it.contains("encoder_outputs"))
        } ?: error("decoder has no encoder hidden-states input (inputs: ${session.inputNames})")

    /** Marian decoders take an encoder_attention_mask (unlike Whisper); present only when declared. */
    private fun bootstrapMaskName(session: OrtSession): String? =
        session.inputNames.firstOrNull { it.contains("attention_mask") && it.contains("encoder") }

    // -----------------------------------------------------------------
    // Tensor + name helpers (introspection only — no hard-coded tensor names)
    // -----------------------------------------------------------------

    private fun inputName(session: OrtSession, needle: String): String =
        session.inputNames.firstOrNull { it.contains(needle) }
            ?: error("no '$needle' input (inputs: ${session.inputNames})")

    private fun outputName(session: OrtSession, needle: String): String =
        session.outputNames.firstOrNull { it.contains(needle) }
            ?: error("no '$needle' output (outputs: ${session.outputNames})")

    private fun inputType(session: OrtSession, name: String): OnnxJavaType =
        (session.getInputInfo()[name]?.info as? TensorInfo)?.type ?: OnnxJavaType.INT64

    /** int64 ids by default; honors an int32-declaring graph. */
    private fun typedIds(session: OrtSession, name: String, ids: LongArray): OnnxTensor {
        val shape = longArrayOf(1, ids.size.toLong())
        return when (inputType(session, name)) {
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(
                env, IntBuffer.wrap(ids.map { it.toInt() }.toIntArray()), shape)
            else -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        }
    }

    private fun onesTensor(session: OrtSession, name: String, shape: LongArray): OnnxTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        return when (inputType(session, name)) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(n) { 1 }), shape)
            else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { 1f }), shape)
        }
    }

    /** Last position's vocab row of a [b, seq, V] logits tensor (optimum Marian contract). */
    private fun lastVocabRow(logits: OnnxTensor): FloatArray {
        val shape = (logits.info as TensorInfo).shape
        require(shape.size >= 2) { "unexpected logits shape ${shape.toList()}" }
        val v = shape.max().toInt()
        val seqLen = if (shape.size == 3) shape[1].toInt() else 1
        val fb = logits.floatBuffer
        return FloatArray(v).also {
            fb.position(((seqLen - 1) * v).toInt()); fb.get(it, 0, v); fb.position(0)
        }
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        return best
    }

    /** log-softmax of the chosen token (logSumExp form — numerically stable). */
    private fun logProbOf(row: FloatArray, token: Int): Double {
        var m = Float.NEGATIVE_INFINITY
        for (x in row) if (x > m) m = x
        var sum = 0.0
        for (x in row) sum += exp((x - m).toDouble())
        return (row[token] - m - Math.log(sum))
    }

    // -----------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------

    companion object {
        private fun variants(stem: String): List<String> =
            listOf("${stem}_quantized.onnx", "$stem.onnx")

        private fun pick(dir: File, stem: String): File? =
            variants(stem).map { File(dir, it) }.firstOrNull { it.exists() }

        /** Load the direction's graphs + tokenizer from the model dir (io.json not required). */
        fun load(env: OrtEnvironment, dir: File, src: Language, tgt: Language): OnnxTranslator {
            val encFile = pick(dir, "encoder_model")
                ?: error("no encoder model in ${dir.path} (looked for ${variants("encoder_model")}) — run export_opus_mt.py")
            val withPast = pick(dir, "decoder_with_past_model")
            val pastFree = pick(dir, "decoder_model")
            val decFile = withPast ?: pastFree
                ?: error("no decoder model in ${dir.path} — run export_opus_mt.py")
            return ResourceScope().use { scope ->
            OrtSession.SessionOptions().use { opts ->
            val enc = scope.own(env.createSession(encFile.path, opts))
            val dec = scope.own(env.createSession(decFile.path, opts))
            // With-past graph present: the past-free graph becomes the one-call bootstrap (it alone
            // emits the cross KV). Past-free-only export: no bootstrap, degenerate decode path.
            val bootstrap = if (withPast != null && pastFree != null) scope.own(env.createSession(pastFree.path, opts)) else null
            val tok = scope.own(SpmTokenizer(File(dir, "source.spm"), File(dir, "target.spm"), File(dir, "vocab.json")))
            OnnxTranslator(env, enc, bootstrap, dec, tok, src, tgt).also { scope.commit() }
            }
            }
        }
    }
}
