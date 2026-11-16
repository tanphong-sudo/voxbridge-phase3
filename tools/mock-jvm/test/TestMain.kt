import ai.onnxruntime.*
import com.voxbridge.core.model.Language
import com.voxbridge.runtime.*
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

var failures = 0
var checks = 0
fun check(cond: Boolean, msg: String) {
    checks++
    if (cond) println("PASS  $msg") else { println("FAIL  $msg"); failures++ }
}
fun close(a: Double, b: Double, eps: Double): Boolean = Math.abs(a - b) < eps
fun identityOf(t: OnnxTensor): String = Integer.toHexString(System.identityHashCode(t))

fun f(arr: FloatArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(OrtEnvironment(), FloatBuffer.wrap(arr), shape)
fun i(arr: IntArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(OrtEnvironment(), IntBuffer.wrap(arr), shape)

fun main() {
    // ---------- vocab + tokens ----------
    val dir = File("/tmp/wasr_stub/assets").apply { mkdirs() }
    File(dir, "vocab.json").writeText("""{"Hello": 12033, "Ġworld": 12035}""")
    val tokensJson = File(dir, "whisper_tokens.json")
    tokensJson.writeText("""{"sot":50258,"eot":50257,"transcribe":50359,"notimestamps":50363,"nospeech":50362,"languages":{"vi":50278,"en":50259}}""")
    val bpe = WhisperBpeDecoder.load(dir)
    val tokens = WhisperTokens.resolve(bpe, tokensJson)

    val MASK_LEN = 200
    val CACHE_LEN = 199
    val HID = 1500
    val LAYERS = 4
    val HEADS = 6
    val DH = 64
    val V = 51865

    // ---------- mock decoder (AI Hub contract) ----------
    val scripted = intArrayOf(50278, 50359, 50363, 12033, 12035, 50257) // per-step argmax
    val decoderFeeds = mutableListOf<Map<String, OnnxTensor>>()
    val selfK0Identity = mutableListOf<String>()
    val decIn = mutableSetOf<String>()
    decIn.add("input_ids"); decIn.add("attention_mask"); decIn.add("position_ids")
    for (l in 0 until LAYERS) { decIn.add("k_cache_self_${l}_in"); decIn.add("v_cache_self_${l}_in")
        decIn.add("k_cache_cross_${l}"); decIn.add("v_cache_cross_${l}") }
    val decOut = mutableSetOf<String>("logits")
    for (l in 0 until LAYERS) { decOut.add("k_cache_self_${l}_out"); decOut.add("v_cache_self_${l}_out") }

    // Real metadata the ONNX Runtime reports per input — decodeAiHub reads declared shapes
    // for mask length (200) and cache length (199), falling back to constants only if unknown.
    val decInfo = buildMap<String, TensorInfo> {
        put("input_ids", TensorInfo(longArrayOf(1, 1), OnnxJavaType.INT32))
        put("attention_mask", TensorInfo(longArrayOf(1, 1, 1, MASK_LEN.toLong()), OnnxJavaType.FLOAT))
        put("position_ids", TensorInfo(longArrayOf(1), OnnxJavaType.INT32))
        for (l in 0 until LAYERS) {
            put("k_cache_self_${l}_in", TensorInfo(longArrayOf(HEADS.toLong(), 1, DH.toLong(), CACHE_LEN.toLong()), OnnxJavaType.FLOAT))
            put("v_cache_self_${l}_in", TensorInfo(longArrayOf(HEADS.toLong(), 1, CACHE_LEN.toLong(), DH.toLong()), OnnxJavaType.FLOAT))
            put("k_cache_cross_${l}", TensorInfo(longArrayOf(HEADS.toLong(), 1, DH.toLong(), HID.toLong()), OnnxJavaType.FLOAT))
            put("v_cache_cross_${l}", TensorInfo(longArrayOf(HEADS.toLong(), 1, HID.toLong(), DH.toLong()), OnnxJavaType.FLOAT))
        }
    }

    fun cachePayload(v: Float) = FloatArray(HEADS * DH * CACHE_LEN) { v }

    val decoder = OrtSession(decIn, decOut, declaredInfo = decInfo).apply {
        var step = -1
        handler = { inputs ->
            step += 1
            decoderFeeds.add(inputs.toMap())
            val ids = inputs["input_ids"]!!.ints
            val mask = inputs["attention_mask"]!!.floats
            val pos = inputs["position_ids"]!!.ints
            check(ids.size == 1, "decoder ids tensor is [1]")
            check(mask.size == MASK_LEN, "mask length is 200")
            check(pos[0] == step, "position_ids[$step] == $step")
            // mask: cumulative — last (step+1) entries are 0, the rest -100
            var maskOk = true
            for (k in 0 until MASK_LEN) {
                val expect = if (k >= MASK_LEN - 1 - step) 0.0f else -100.0f
                if (mask[k] != expect) { maskOk = false; break }
            }
            check(maskOk, "step $step: attention_mask last ${step + 1} entries zeroed, rest -100")
            // self-cache: zeros at step 0, previous outputs (7.0) afterwards
            val k0 = inputs["k_cache_self_0_in"]!!.floats
            check(!(inputs["k_cache_self_0_in"] as OnnxTensor).closed, "step $step: fed self-cache tensor still alive at run time")
            if (step == 0) check(k0.all { it == 0.0f }, "step 0 self-cache is zeros")
            else check(k0.all { it == 7.0f }, "step $step self-cache echoes previous _out (7.0)")
            selfK0Identity.add(identityOf(inputs["k_cache_self_0_in"] as OnnxTensor))
            // cross KV must be present as inputs
            check(inputs.containsKey("k_cache_cross_0") && inputs.containsKey("v_cache_cross_3"),
                "step $step: cross KV bound from encoder")
            // logits: scripted argmax. no-speech must be computed from the FULL vocabulary softmax.
            val logits = FloatArray(V)
            logits[scripted[step]] = 10.0f
            if (step == 0) { logits[50257] = 0.0f; logits[50362] = 0.0f }
            val out = HashMap<String, OnnxTensor>()
            out["logits"] = f(logits, longArrayOf(1, V.toLong(), 1, 1))
            for (l in 0 until LAYERS) {
                out["k_cache_self_${l}_out"] = f(cachePayload(7.0f), longArrayOf(HEADS.toLong(), 1, DH.toLong(), CACHE_LEN.toLong()))
                out["v_cache_self_${l}_out"] = f(cachePayload(7.0f), longArrayOf(HEADS.toLong(), 1, CACHE_LEN.toLong(), DH.toLong()))
            }
            out
        }
    }

    // ---------- mock encoder ----------
    val encoder = OrtSession(mutableSetOf("input_features"), mutableSetOf(),
        declaredInfo = mapOf("input_features" to TensorInfo(longArrayOf(1, 80, 3000), OnnxJavaType.FLOAT))).apply {
        outputNames.clear()
        for (l in 0 until LAYERS) { outputNames.add("k_cache_cross_${l}"); outputNames.add("v_cache_cross_${l}") }
        handler = { inputs ->
            check(inputs.containsKey("input_features"), "encoder fed input_features")
            val out = HashMap<String, OnnxTensor>()
            for (l in 0 until LAYERS) {
                out["k_cache_cross_${l}"] = f(FloatArray(HEADS * DH * HID), longArrayOf(HEADS.toLong(), 1, DH.toLong(), HID.toLong()))
                out["v_cache_cross_${l}"] = f(FloatArray(HEADS * HID * DH), longArrayOf(HEADS.toLong(), 1, HID.toLong(), DH.toLong()))
            }
            out
        }
    }

    // ---------- build WhisperAsr via reflection (private ctor) ----------
    // Real MelSpectrogram requires an 80x201 little-endian float32 filterbank file; the decode
    // path under test never depends on filterbank values, so a zeroed one suffices.
    val melBin = File(dir, "mel.bin")
    val bb = java.nio.ByteBuffer.allocate(80 * 201 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    repeat(80 * 201) { bb.putFloat(0f) }
    melBin.writeBytes(bb.array())
    val mel = MelSpectrogram(melBin)
    val ctor = WhisperAsr::class.java.getDeclaredConstructor(
        OrtEnvironment::class.java, OrtSession::class.java, OrtSession::class.java,
        MelSpectrogram::class.java, WhisperAsr.Backend::class.java, WhisperAsr.Contract::class.java,
        OrtSession::class.java, WhisperTokens::class.java, WhisperBpeDecoder::class.java,
    )
    ctor.isAccessible = true
    val asr = ctor.newInstance(OrtEnvironment(), encoder, decoder, mel,
        WhisperAsr.Backend.QNN_NPU, WhisperAsr.Contract.AI_HUB_QNN, null, tokens, bpe) as WhisperAsr

    // ---------- run ----------
    val res = asr.transcribe(FloatArray(16000), Language.VI, Language.EN)
    println("text='${res.text}' score=${res.asrScore} noSpeech=${res.noSpeechProbability}")
    check(res.text == "Hello world", "decoded text is 'Hello world' (specials skipped)")
    check(close(res.asrScore, Math.exp(-Math.log(1 + 51864 * Math.exp(-10.0))), 1e-9),
        "asrScore = exp(mean log-softmax of 3 scored tokens at logit 10)")
    val expectedNoSpeech = 1.0 / (Math.exp(10.0) + (V - 1).toDouble())
    check(close(res.noSpeechProbability!!, expectedNoSpeech, 1e-9),
        "noSpeechProbability = full-vocab softmax at SOT")
    check(decoderFeeds.size == 6, "decoder ran 6 steps (SOT + 3 forced + 2 content incl. EOT)")
    check(selfK0Identity.toSet().size == 6, "self-cache input is a distinct tensor object per step (reference, not copy)")

    // feed id script: SOT, vi, transcribe, notimestamps, then generated chain
    val feeds = decoderFeeds.map { it["input_ids"]!!.ints[0] }
    check(feeds == listOf(50258, 50278, 50359, 50363, 12033, 12035),
        "feeds follow forced prompt then generated tokens: $feeds")

    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    check(failures == 0, "no assertion failures overall")

    // ---------- optimum contract (CPU fallback): bootstrap + with-past chain ----------
    println("--- decodeOptimum (HF optimum contract) ---")
    runOptimumTests(dir, bpe, tokens)
    println(if (failures == 0) "ALL OK" else "$failures FAILURES (cumulative)")

    // ---------- OPUS-MT translator: Marian past-KV chain + degenerate path ----------
    println("--- OnnxTranslator (Marian optimum contract) ---")
    runTranslatorTests(dir)
    runChainTests()
    println(if (failures == 0) "ALL OK (Whisper contracts + optimum + translator)" else "$failures FAILURES (cumulative)")
    // Machine-readable count — setup.sh's "707 + 22 expected" echo is checked against this line
    // (Round-4 review LOW: only the installer's 22 was machine-printed before).
    println("main: $checks checks, failed=$failures")
}
