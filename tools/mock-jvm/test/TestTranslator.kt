import ai.onnxruntime.*
import com.voxbridge.core.model.Language
import com.voxbridge.runtime.*
import java.io.File
import java.nio.FloatBuffer

// Behavioral mock test for OnnxTranslator — the optimum Marian (OPUS-MT) contract.
// Graph family verified from optimum v1.17.1 (OnnxConfigWithPast):
//   encoder: input_ids/attention_mask -> last_hidden_state
//   past-free decoder (bootstrap): input_ids [1,1] (decoder-start) + encoder_hidden_states +
//     encoder_attention_mask -> logits [1,1,V] + present.{l}.decoder.{key,value} + present.{l}.encoder.{key,value}
//   with-past decoder: input_ids [1,1] + optional encoder_attention_mask +
//     past_key_values.{l}.{decoder,encoder}.{key,value} -> logits [1,1,V] + present.{l}.decoder.{key,value} ONLY
// Mock asserts TENSOR IDENTITY for the past chain (encoder part always from the bootstrap,
// decoder part chained step-to-step), single-token feeds, EOS stop, and the translation score.
// A second scenario exercises the degenerate past-free-only export (no with-past graph).

private const val TL = 6       // Marian tiny: 6 layers
private const val TH = 2L      // heads
private const val TD = 8L      // head dim
private const val TV = 12      // vocab size (ids 0..11)

fun runTranslatorTests(dir: File) {
    // vocab.json drives the real SpmTokenizer (stub DJL underneath): pad=0 eos=1, ▁xin=3 ▁chào=4, ▁hello=10 ▁world=11
    val tokDir = File(dir, "mt").apply { mkdirs() }
    File(tokDir, "vocab.json").writeText(
        """{"<pad>":0,"</s>":1,"<unk>":2,"▁xin":3,"▁chào":4,"▁hello":10,"▁world":11}"""
    )
    val tokenizer = SpmTokenizer(File(tokDir, "source.spm"), File(tokDir, "target.spm"), File(tokDir, "vocab.json"))

    var encHiddenRef: OnnxTensor? = null
    val encoder = OrtSession(mutableSetOf("input_ids", "attention_mask"), mutableSetOf("last_hidden_state")).apply {
        handler = { inputs ->
            val ids = inputs["input_ids"]!!.longs
            check(ids.size == 3 && ids.toList() == listOf(3L, 4L, 1L), "[mt] encoder ids = pieces + trailing EOS: $ids")
            check(inputs["attention_mask"]!!.longs.all { it == 1L }, "[mt] encoder mask all ones")
            val hidden = OnnxTensor.createTensor(
                OrtEnvironment(), FloatBuffer.wrap(FloatArray((1 * ids.size * TD).toInt()) { 3.14f }),
                longArrayOf(1, ids.size.toLong(), TD))
            encHiddenRef = hidden
            mapOf("last_hidden_state" to hidden)
        }
    }
    val bootstrapPresent = LinkedHashMap<String, OnnxTensor>()
    var bootstrapCalls = 0
    val bootstrap = OrtSession(
        mutableSetOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"),
        mutableSetOf<String>().apply {
            add("logits")
            for (l in 0 until TL) {
                add("present.$l.decoder.key"); add("present.$l.decoder.value")
                add("present.$l.encoder.key"); add("present.$l.encoder.value")
            }
        }).apply {
        handler = { inputs ->
            bootstrapCalls++
            val ids = inputs["input_ids"]!!.longs
            check(ids.toList() == listOf(0L), "[mt] bootstrap feeds the decoder-start (<pad>) token: $ids")
            val enc = inputs["encoder_hidden_states"]!!
            check(enc === encHiddenRef, "[mt] bootstrap fed the encoder's output tensor (identity)")
            check(!enc.closed, "[mt] encoder result alive during bootstrap")
            val logits = FloatArray(TV); logits[10] = 10.0f   // ▁hello
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, 1, TV.toLong()))
            for (l in 0 until TL) {
                out["present.$l.decoder.key"] = kv(1.0f, 1)
                out["present.$l.decoder.value"] = kv(1.0f, 1)
                out["present.$l.encoder.key"] = kv(2.0f, 3)
                out["present.$l.encoder.value"] = kv(2.0f, 3)
            }
            bootstrapPresent.putAll(out)
            out
        }
    }

    val decScript = intArrayOf(11, 1)   // ▁world, </s> — bootstrap already produced ▁hello
    val prevPresent = LinkedHashMap<String, OnnxTensor>()
    var decCalls = 0
    val decFedIds = mutableListOf<Int>()
    val withPast = OrtSession(
        mutableSetOf<String>().apply {
            add("input_ids"); add("encoder_attention_mask")
            for (l in 0 until TL) {
                add("past_key_values.$l.decoder.key"); add("past_key_values.$l.decoder.value")
                add("past_key_values.$l.encoder.key"); add("past_key_values.$l.encoder.value")
            }
        },
        mutableSetOf<String>().apply {
            add("logits")
            for (l in 0 until TL) { add("present.$l.decoder.key"); add("present.$l.decoder.value") }
        }).apply {
        handler = { inputs ->
            val call = decCalls
            decCalls++
            val ids = inputs["input_ids"]!!.longs
            check(ids.size == 1, "[mt] with-past call $call feeds exactly ONE token")
            decFedIds.add(ids[0].toInt())
            check("encoder_hidden_states" !in inputs, "[mt] with-past call $call does not receive encoder_hidden_states")
            val prev = if (call == 0) bootstrapPresent else prevPresent
            for ((name, tensor) in inputs) {
                if (!name.contains("past_key_values")) continue
                val parts = name.removePrefix("past_key_values").split('.').filter { it.isNotEmpty() }
                check(parts.size == 3, "[mt] past input '$name' parses to 3 parts")
                val source = if (parts[1] == "encoder") bootstrapPresent else prev
                val want = source["present.${parts[0]}.${parts[1]}.${parts[2]}"]
                check(want != null, "[mt] call $call: '$name' has a matching present output in the expected source")
                check(want === tensor, "[mt] call $call: '$name' is the EXACT expected tensor (identity)")
                check(!tensor.closed, "[mt] call $call: fed past-KV tensor alive")
            }
            val logits = FloatArray(TV); logits[decScript[call]] = 10.0f
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, 1, TV.toLong()))
            for (l in 0 until TL) {
                out["present.$l.decoder.key"] = kv(9.0f, 2)
                out["present.$l.decoder.value"] = kv(9.0f, 2)
            }
            prevPresent.putAll(out)
            out
        }
    }

    val translator = newTranslator(encoder, bootstrap, withPast, tokenizer, Language.VI, Language.EN)
    val res = translator.translate("xin chào", Language.VI, Language.EN)
    println("[mt] '$res.sourceText' -> '${res.translatedText}' score=${res.translationScore}")
    check(res.translatedText == "hello world", "[mt] translated text is 'hello world'")
    check(res.sourceText == "xin chào", "[mt] source text echoed")
    check(res.modelName == "opus-mt-VI-EN", "[mt] model name 'opus-mt-VI-EN'")
    check(bootstrapCalls == 1, "[mt] bootstrap ran exactly once")
    check(decCalls == 2, "[mt] with-past ran 2 steps (▁world, EOS)")
    check(decFedIds == listOf(10, 11), "[mt] with-past feeds follow the generated chain: $decFedIds")
    check(close(res.translationScore!!, Math.exp(-Math.log(1 + (TV - 1) * Math.exp(-10.0))), 1e-9),
        "[mt] translationScore = exp(mean log-softmax of 3 scored tokens)")

    // Direction guard: this instance is wired VI->EN; asking for EN->VI must fail fast.
    var guarded = false
    try { translator.translate("hello", Language.EN, Language.VI) } catch (e: IllegalArgumentException) {
        guarded = e.message!!.contains("wired for")
    }
    check(guarded, "[mt] direction mismatch fails fast (IllegalArgumentException)")

    // ---------- scenario 2: degenerate past-free-only export ----------
    println("[mt] degenerate past-free-only export:")
    val seqLogits = mutableListOf<Int>()
    val pastFreeOnly = OrtSession(
        mutableSetOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"),
        mutableSetOf("logits")).apply {
        handler = { inputs ->
            val ids = inputs["input_ids"]!!.longs
            val seq = ids.size
            // script: position 1 -> ▁hello, 2 -> ▁world, 3 -> EOS (position 0 = decoder-start)
            val argmaxAt = when (seq) { 1 -> 0; 2 -> 10; 3 -> 11; else -> 1 }
            seqLogits.add(argmaxAt)
            val logits = FloatArray(seq * TV); logits[(seq - 1) * TV + argmaxAt] = 10.0f
            mapOf("logits" to OnnxTensor.createTensor(
                OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, seq.toLong(), TV.toLong())))
        }
    }
    val degenerate = newTranslator(encoder, null, pastFreeOnly, tokenizer, Language.VI, Language.EN)
    val res2 = degenerate.translate("xin chào", Language.VI, Language.EN)
    println("[mt] degenerate -> '${res2.translatedText}'")
    check(res2.translatedText == "hello world", "[mt] degenerate path decodes 'hello world' (O(n^2) re-run)")
    check(seqLogits.toList() == listOf(0, 10, 11, 1), "[mt] degenerate re-runs growing sequences: $seqLogits")
}

/** Construct OnnxTranslator through its private constructor (env, encoder, bootstrap?, decoder, tokenizer, src, tgt). */
private fun newTranslator(
    encoder: OrtSession,
    bootstrap: OrtSession?,
    decoder: OrtSession,
    tokenizer: SpmTokenizer,
    src: Language,
    tgt: Language,
): OnnxTranslator {
    val ctor = OnnxTranslator::class.java.getDeclaredConstructor(
        OrtEnvironment::class.java, OrtSession::class.java, OrtSession::class.java,
        OrtSession::class.java, SpmTokenizer::class.java, Language::class.java, Language::class.java,
    )
    ctor.isAccessible = true
    return ctor.newInstance(OrtEnvironment(), encoder, bootstrap, decoder, tokenizer, src, tgt) as OnnxTranslator
}

private fun kv(payload: Float, seq: Long): OnnxTensor =
    OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(FloatArray((TH * seq * TD).toInt()) { payload }),
        longArrayOf(1, TH, seq, TD))
// ---------------------------------------------------------------------------
// ChainedTranslator — pivot chain (VI→EN→ZH, KO→EN→VI … MODEL_MANIFEST §1)
// ---------------------------------------------------------------------------

/** A fully scripted single-hop OnnxTranslator: the bootstrap graph always argmaxes
 *  [bootstrapTok]; the with-past graph follows [decScript] (cycled per translate call so
 *  repeated calls behave identically). Fresh sessions per hop — no shared state. */
private fun scriptedTranslator(
    tokenizer: SpmTokenizer, src: Language, tgt: Language,
    bootstrapTok: Int, decScript: IntArray,
): OnnxTranslator {
    val encoder = OrtSession(mutableSetOf("input_ids", "attention_mask"), mutableSetOf("last_hidden_state")).apply {
        handler = { inputs ->
            val ids = inputs["input_ids"]!!.longs
            mapOf("last_hidden_state" to OnnxTensor.createTensor(
                OrtEnvironment(), FloatBuffer.wrap(FloatArray((1 * ids.size * TD).toInt()) { 3.14f }),
                longArrayOf(1, ids.size.toLong(), TD)))
        }
    }
    val bootstrap = OrtSession(
        mutableSetOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"),
        mutableSetOf<String>().apply {
            add("logits")
            for (l in 0 until TL) {
                add("present.$l.decoder.key"); add("present.$l.decoder.value")
                add("present.$l.encoder.key"); add("present.$l.encoder.value")
            }
        }).apply {
        handler = { _ ->
            val logits = FloatArray(TV); logits[bootstrapTok] = 10.0f
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, 1, TV.toLong()))
            for (l in 0 until TL) {
                out["present.$l.decoder.key"] = kv(1.0f, 1); out["present.$l.decoder.value"] = kv(1.0f, 1)
                out["present.$l.encoder.key"] = kv(2.0f, 3); out["present.$l.encoder.value"] = kv(2.0f, 3)
            }
            out
        }
    }
    val withPast = OrtSession(
        mutableSetOf<String>().apply {
            add("input_ids"); add("encoder_attention_mask")
            for (l in 0 until TL) {
                add("past_key_values.$l.decoder.key"); add("past_key_values.$l.decoder.value")
                add("past_key_values.$l.encoder.key"); add("past_key_values.$l.encoder.value")
            }
        },
        mutableSetOf<String>().apply {
            add("logits")
            for (l in 0 until TL) { add("present.$l.decoder.key"); add("present.$l.decoder.value") }
        }).apply {
        var call = 0
        handler = { _ ->
            val idx = call % decScript.size
            call++
            val logits = FloatArray(TV); logits[decScript[idx]] = 10.0f
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, 1, TV.toLong()))
            for (l in 0 until TL) {
                out["present.$l.decoder.key"] = kv(9.0f, 2); out["present.$l.decoder.value"] = kv(9.0f, 2)
            }
            out
        }
    }
    return newTranslator(encoder, bootstrap, withPast, tokenizer, src, tgt)
}

fun runChainTests() {
    println("[chain] ChainedTranslator pivot semantics:")
    val tokDir = File("/tmp/chain-tok").apply { mkdirs() }
    File(tokDir, "vocab.json").writeText(
        """{"<pad>":0,"</s>":1,"<unk>":2,"▁xin":3,"▁chào":4,"▁hello":10,"▁world":11}"""
    )
    val tokenizer = SpmTokenizer(File(tokDir, "source.spm"), File(tokDir, "target.spm"), File(tokDir, "vocab.json"))

    // hop1 VI->EN: "xin chào" -> bootstrap ▁hello + steps [▁world, EOS] = "hello world"
    val hop1 = scriptedTranslator(tokenizer, Language.VI, Language.EN, 10, intArrayOf(11, 1))
    // hop2 EN->ZH: "hello world" -> bootstrap ▁xin + steps [▁chào, EOS] = "xin chào"
    val hop2 = scriptedTranslator(tokenizer, Language.EN, Language.ZH, 3, intArrayOf(4, 1))
    val chain = ChainedTranslator(hop1, hop2)

    val r = chain.translate("xin chào", Language.VI, Language.ZH)
    println("[chain] 'xin chào' -> '${r.translatedText}' via ${r.modelName} score=${r.translationScore}")
    check(r.translatedText == "xin chào", "[chain] VI→EN→ZH decodes through both hops")
    check(r.sourceText == "xin chào", "[chain] sourceText corrected to the ORIGINAL input")
    check(r.sourceLanguage == Language.VI && r.targetLanguage == Language.ZH, "[chain] languages = requested pair")
    check(r.modelName == "opus-mt-VI-EN + opus-mt-EN-ZH", "[chain] modelName joins hop names: ${r.modelName}")

    // min-score semantics: both hops score identically here, so the chain score equals one hop's.
    val s1 = scriptedTranslator(tokenizer, Language.VI, Language.EN, 10, intArrayOf(11, 1))
        .translate("xin chào", Language.VI, Language.EN).translationScore!!
    check(close(r.translationScore!!, s1, 1e-9), "[chain] chain score = min(hop scores) ($s1)")

    // Pair guard: a pair the chain was not built for fails fast.
    var guarded = false
    try { chain.translate("xin chào", Language.VI, Language.KO) } catch (e: IllegalArgumentException) {
        guarded = e.message!!.contains("wired for")
    }
    check(guarded, "[chain] pair mismatch fails fast")

    // Wiring guards fire at CONSTRUCTION (runner-build time, never mid-utterance).
    var badOrder = false
    try { ChainedTranslator(hop2, hop1) } catch (e: IllegalArgumentException) { badOrder = true }
    check(badOrder, "[chain] hop not starting from EN rejected")
    var tooFew = false
    try { ChainedTranslator(hop1) } catch (e: IllegalArgumentException) { tooFew = true }
    check(tooFew, "[chain] single-hop chain rejected")
    var enToEn = false
    try {
        ChainedTranslator(hop1, scriptedTranslator(tokenizer, Language.EN, Language.EN, 10, intArrayOf(11, 1)))
    } catch (e: IllegalArgumentException) { enToEn = true }
    check(enToEn, "[chain] final hop EN→EN rejected")

    // close() is a documented NO-OP (hops are shared and closed exactly once by the owner):
    // after chain.close() the hops must still be fully usable.
    chain.close()
    val afterClose = chain.translate("xin chào", Language.VI, Language.ZH)
    check(afterClose.translatedText == "xin chào", "[chain] close() no-op — hops stay usable")

    // 3-language coverage: KO→EN→VI chain decodes to Vietnamese text.
    val koVi = ChainedTranslator(
        scriptedTranslator(tokenizer, Language.KO, Language.EN, 10, intArrayOf(11, 1)),
        scriptedTranslator(tokenizer, Language.EN, Language.VI, 3, intArrayOf(4, 1)),
    )
    val rKo = koVi.translate("안녕하세요", Language.KO, Language.VI)
    check(rKo.translatedText == "xin chào", "[chain] KO→EN→VI decodes: '${rKo.translatedText}'")

    println("[chain] done.")
}
