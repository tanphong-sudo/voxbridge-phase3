import ai.onnxruntime.*
import com.voxbridge.core.model.Language
import com.voxbridge.runtime.*
import java.io.File
import java.nio.FloatBuffer

// Behavioral mock test for WhisperAsr.decodeOptimum — the optimum v1.17.1 CPU-fallback contract.
// Graph family (verified from optimum exporters source):
//   encoder: input_features -> last_hidden_state [1,1500,384]
//   past-free decoder (bootstrap, one call over the whole forced prompt): input_ids [1,4] int64 +
//     encoder_hidden_states -> logits [1,4,V] + present.{l}.decoder.{key,value} + present.{l}.encoder.{key,value}
//   with-past decoder (one token per call): input_ids [1,1] + optional cache_position [1] +
//     past_key_values.{l}.{decoder|encoder}.{key|value} -> logits [1,1,V] + present.{l}.decoder.{key,value} ONLY
//     (with-past consumes the cross KV but never re-emits it — that asymmetry is what bindPast encodes).
// The mock parses past_key_values.{i}.{part}.{key|value} independently and asserts TENSOR IDENTITY,
// with 13 layers so present.2 vs present.12 confusion is unambiguously caught.

fun runOptimumTests(dir: File, bpe: WhisperBpeDecoder, tokens: WhisperTokens) {
    val V = 51865
    val LAYERS = 13
    val HEADS = 6L
    val DH = 64L
    val HID = 1500L
    val PROMPT = intArrayOf(tokens.sot, tokens.languageId(Language.VI), tokens.transcribe, tokens.notimestamps)
    val decScript = intArrayOf(12035, 50257) // with-past argmax chain: Ġworld, EOT (bootstrap last row already gave 12033)

    fun kv(payload: Float, seq: Long): OnnxTensor =
        OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(FloatArray((HEADS * seq * DH).toInt()) { payload }),
            longArrayOf(1, HEADS, seq, DH))

    // ---------- encoder ----------
    val encoder = OrtSession(mutableSetOf("input_features"), mutableSetOf("last_hidden_state"),
        declaredInfo = mapOf("input_features" to TensorInfo(longArrayOf(1, 80, 3000), OnnxJavaType.FLOAT))).apply {
        handler = { inputs ->
            check(inputs.containsKey("input_features"), "[opt] encoder fed input_features")
            mapOf("last_hidden_state" to OnnxTensor.createTensor(
                OrtEnvironment(), FloatBuffer.wrap(FloatArray((1 * HID * 384).toInt())), longArrayOf(1, HID, 384)))
        }
    }
    // ---------- past-free bootstrap decoder ----------
    val bootstrapEmitted = LinkedHashMap<String, OnnxTensor>()
    var bootstrapCalls = 0
    val first = OrtSession(mutableSetOf("input_ids", "encoder_hidden_states"),
        mutableSetOf<String>().apply {
            add("logits")
            for (l in 0 until LAYERS) {
                add("present.$l.decoder.key"); add("present.$l.decoder.value")
                add("present.$l.encoder.key"); add("present.$l.encoder.value")
            }
        },
        declaredInfo = mapOf(
            "input_ids" to TensorInfo(longArrayOf(1, PROMPT.size.toLong()), OnnxJavaType.INT64),
            "encoder_hidden_states" to TensorInfo(longArrayOf(1, HID, 384), OnnxJavaType.FLOAT),
        )).apply {
        handler = { inputs ->
            bootstrapCalls++
            val ids = inputs["input_ids"]!!.longs
            check(ids.toList() == PROMPT.map { it.toLong() }, "[opt] bootstrap runs the WHOLE forced prompt in one past-free call: $ids")
            val enc = inputs["encoder_hidden_states"]!!
            check(!enc.closed, "[opt] encoder hidden states alive during bootstrap")
            val logits = FloatArray(4 * V)
            logits[50257] = 0.0f        // row 0 (SOT): EOT logit
            // Full-vocab softmax: 13 is deliberately large enough to make no-speech > 0.60
            // even against ~51k competing zero logits. This catches any regression to 2-way softmax.
            logits[50362] = 13.0f       // row 0: <|nocaptions|>
            logits[3 * V + 12033] = 10.0f  // last row argmax = first content token
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(logits), longArrayOf(1, 4, V.toLong()))
            for (l in 0 until LAYERS) {
                out["present.$l.decoder.key"] = kv(9.0f, 4)
                out["present.$l.decoder.value"] = kv(9.0f, 4)
                out["present.$l.encoder.key"] = kv(5.0f, HID)
                out["present.$l.encoder.value"] = kv(5.0f, HID)
            }
            bootstrapEmitted.putAll(out)
            out
        }
    }

    // ---------- with-past decoder ----------
    val prevEmitted = LinkedHashMap<String, OnnxTensor>()
    var decCalls = 0
    val decFedIds = mutableListOf<Int>()
    val dec = OrtSession(mutableSetOf<String>().apply {
        add("input_ids")
        // Newer Transformers/Optimum Whisper exports require this input explicitly. The first
        // generated token follows the four-token forced prompt, so positions are 4, 5, ... .
        add("cache_position")
        for (l in 0 until LAYERS) {
            add("past_key_values.$l.decoder.key"); add("past_key_values.$l.decoder.value")
            add("past_key_values.$l.encoder.key"); add("past_key_values.$l.encoder.value")
        }
    }, mutableSetOf<String>().apply {
        add("logits")
        for (l in 0 until LAYERS) { add("present.$l.decoder.key"); add("present.$l.decoder.value") }
    }, declaredInfo = buildMap {
        put("input_ids", TensorInfo(longArrayOf(1, 1), OnnxJavaType.INT64))
        put("cache_position", TensorInfo(longArrayOf(1), OnnxJavaType.INT64))
        for (l in 0 until LAYERS) {
            put("past_key_values.$l.decoder.key", TensorInfo(longArrayOf(1, HEADS, 4, DH), OnnxJavaType.FLOAT))
            put("past_key_values.$l.decoder.value", TensorInfo(longArrayOf(1, HEADS, 4, DH), OnnxJavaType.FLOAT))
            put("past_key_values.$l.encoder.key", TensorInfo(longArrayOf(1, HEADS, HID, DH), OnnxJavaType.FLOAT))
            put("past_key_values.$l.encoder.value", TensorInfo(longArrayOf(1, HEADS, HID, DH), OnnxJavaType.FLOAT))
        }
    }).apply {
        handler = { inputs ->
            val call = decCalls
            decCalls++
            val ids = inputs["input_ids"]!!.longs
            check(ids.size == 1, "[opt] with-past call $call feeds exactly ONE token")
            decFedIds.add(ids[0].toInt())
            val cachePosition = inputs["cache_position"]!!.longs
            check(
                cachePosition.contentEquals(longArrayOf(PROMPT.size.toLong() + call)),
                "[opt] with-past call $call feeds cache_position=${PROMPT.size + call}",
            )
            check("encoder_hidden_states" !in inputs, "[opt] with-past call $call does not receive encoder_hidden_states")
            // Past KV binding: decoder part from the previous step's present.* (bootstrap itself on
            // call 0, since prevRes === bootstrapRes there), encoder part ALWAYS from the bootstrap
            // (with-past never re-emits cross KV). Identity equality catches any layer mix-up (2 vs 12).
            val prev = if (call == 0) bootstrapEmitted else prevEmitted
            for ((name, tensor) in inputs) {
                if (!name.contains("past_key_values")) continue
                val parts = name.removePrefix("past_key_values").split('.').filter { it.isNotEmpty() }
                check(parts.size == 3, "[opt] past input '$name' parses to exactly 3 parts")
                val source = if (parts[1] == "encoder") bootstrapEmitted else prev
                val want = source["present.${parts[0]}.${parts[1]}.${parts[2]}"]
                check(want != null, "[opt] call $call: '$name' maps to ${"present.${parts[0]}.${parts[1]}.${parts[2]}"} in the expected source result")
                check(want === tensor, "[opt] call $call: '$name' is the EXACT expected tensor (identity)")
                check(!tensor.closed, "[opt] call $call: fed past-KV tensor alive at run time")
            }
            val row = FloatArray(V); row[decScript[call]] = 10.0f
            val out = LinkedHashMap<String, OnnxTensor>()
            out["logits"] = OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(row), longArrayOf(1, 1, V.toLong()))
            for (l in 0 until LAYERS) {
                out["present.$l.decoder.key"] = kv(9.0f, 5)
                out["present.$l.decoder.value"] = kv(9.0f, 5)
            }
            prevEmitted.putAll(out)
            out
        }
    }

    // ---------- build WhisperAsr (reflection, private ctor) ----------
    val mel = MelSpectrogram(dir.resolve("mel.bin"))
    val ctor = WhisperAsr::class.java.getDeclaredConstructor(
        OrtEnvironment::class.java, OrtSession::class.java, OrtSession::class.java,
        MelSpectrogram::class.java, WhisperAsr.Backend::class.java, WhisperAsr.Contract::class.java,
        OrtSession::class.java, WhisperTokens::class.java, WhisperBpeDecoder::class.java,
    )
    ctor.isAccessible = true
    val asr = ctor.newInstance(OrtEnvironment(), encoder, dec, mel,
        WhisperAsr.Backend.CPU_FALLBACK, WhisperAsr.Contract.HF_OPTIMUM, first, tokens, bpe) as WhisperAsr

    // ---------- run ----------
    val res = asr.transcribe(FloatArray(16000), Language.VI, Language.EN)
    println("[opt] text='${res.text}' score=${res.asrScore} noSpeech=${res.noSpeechProbability}")
    check(res.text.isBlank(), "[opt] high no-speech probability suppresses a hallucinated transcript")
    check(bootstrapCalls == 1, "[opt] past-free bootstrap ran exactly once")
    check(decCalls == 2, "[opt] with-past decoder ran 2 steps (Ġworld, EOT)")
    check(decFedIds == listOf(12033, 12035), "[opt] with-past feeds follow the generated chain: $decFedIds")
    check(res.asrScore == 0.0, "[opt] suppressed no-speech transcript has zero ASR score")
    val expectedNoSpeech = Math.exp(13.0) / (Math.exp(13.0) + (V - 1).toDouble())
    check(close(res.noSpeechProbability!!, expectedNoSpeech, 1e-9),
        "[opt] noSpeechProbability from bootstrap row 0 uses full-vocab softmax")
}
