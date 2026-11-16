package com.voxbridge.runtime

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.model.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.exp

data class AsrDiagnostics(
    val modelName: String,
    val backend: String,
    val contract: String,
    val languageToken: Int,
    val taskToken: Int,
    val inputSampleCount: Int,
    val inputDurationMs: Long,
    val rawTranscript: String,
    val noSpeechProbability: Double?,
    val averageLogProbability: Double?,
    val rawSequenceProbability: Double,
    val tokenCount: Int,
    val stopReason: String,
    val latencyMs: Long,
    val coldLoadMs: Long = 0,
    val melLatencyMs: Long = 0,
    val coldStart: Boolean = false,
    /** QNN encoder run (AI Hub contract); 0 on the CPU contract. */
    val encoderMs: Double = 0.0,
    /** Sum of decoder run() calls only (device + ORT IO copy). */
    val decoderRunMs: Double = 0.0,
    /** Decoder steps executed, including the forced language/task prompt. */
    val decodeSteps: Int = 0,
)

data class DetailedTranscript(
    val result: TranscriptResult,
    val diagnostics: AsrDiagnostics,
)

/**
 * On-device Whisper-family ASR used by the selected PhoWhisper Base / Whisper Small exports.
 *
 * AI Hub exports Whisper as TWO graphs (verified against qualcomm/ai-hub-models v0.61.0,
 * `models/_shared/hf_whisper/{model,app}.py`):
 *
 *  - `HfWhisperEncoder`: `input_features` [1,80,3000] f32 -> per-layer cross-attention KV,
 *    `k_cache_cross_{i}` [H,1,Dh,1500] and `v_cache_cross_{i}` [H,1,1500,Dh]  (shape/layer counts
 *    H=6 heads, Dh=64).
 *  - `HfWhisperDecoder` (fixed-shape, ONE token per call): `input_ids` [1,1] int32,
 *    `attention_mask` [1,1,1,200] f32 (init ALL -100.0 = MASK_NEG; at step n set
 *    `mask[199-n]=0.0`), `k/v_cache_self_{i}_in` [H,1,64,199]/[H,1,199,64] (zeros first step),
 *    the cross KV, `position_ids` [1] int32 (starts 0, +1 per step). Outputs: `logits`
 *    [1,51865,1,1] — VOCAB IS AXIS 1 — and `k/v_cache_self_{i}_out` fed back next step.
 *    Greedy loop: max 199 steps (MEAN_DECODE_LEN-1), stop at EOT; argmax over axis 1.
 *
 * This class implements that loop EXACTLY (QNN/NPU path — the Qualcomm-critical path, loading
 * the precompiled `*_ctx.onnx` wrappers via ORT's QNN Execution Provider), plus an equivalent
 * decode for the optimum ONNX CPU fallback (`encoder_model/decoder_model/
 * decoder_with_past_model.onnx`, past-key-values bound by name introspection).
 *
 * Additions beyond app.py (both documented, both required by the product):
 *  - LANGUAGE FORCING: app.py v0.61.0 relies on auto language detection; this app must be
 *    deterministic (VI->EN or EN->VI), so right after SOT we force `<|lang|>`, `<|transcribe|>`,
 *    `<|notimestamps|>` step-by-step (HF forced-decoder-prompt semantics): their argmax outputs
 *    are discarded, they are not scored, positions/masks advance exactly like generated tokens.
 *  - `noSpeechProbability`: OpenAI Whisper's reference calculation — softmax the COMPLETE vocab
 *    row at the SOT position and read the `<|nospeech|>` token probability. A previous 2-way
 *    EOT-vs-no-speech approximation inflated this signal and could suppress real speech.
 *  - `asrScore` currently carries exp(mean log-softmax of GENERATED non-forced tokens) as a raw
 *    decoder diagnostic. It is explicitly marked uncalibrated in TranscriptResult, so the core
 *    cannot compare it against a threshold learned in a calibrated reliability-score space.
 *
 * Tensor names are NEVER hard-coded: everything binds via session input/output-name
 * introspection (+ shapes from TensorInfo), so a re-export that renames tensors still binds.
 */
class WhisperAsr private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val mel: MelSpectrogram,
    private val backend: Backend,
    private val contract: Contract,
    private val decoderFirstStep: OrtSession?,
    private val tokens: WhisperTokens,
    private val bpe: WhisperBpeDecoder,
) : AutoCloseable {

    enum class Backend { QNN_NPU, CPU, CPU_FALLBACK }

    /** Which exported graph family `decoder` speaks (detected from input names at load). */
    enum class Contract { AI_HUB_QNN, HF_OPTIMUM }

    /** Result of the graph-selection step in [load] (Quad via data class — Triple holds 3). */
    private data class DecoderChoice(
        val session: OrtSession,
        val firstStep: OrtSession?,
        val contract: Contract,
        val backend: Backend,
    )

    companion object {
        private const val TAG = "WhisperAsr"
        /** Matches the core DecisionEngine no-speech reliability gate. */
        private const val NO_SPEECH_PROBABILITY_MAX = 0.60
        /** MEAN_DECODE_LEN from AI Hub (model.py v0.61.0). Overridden by graph shapes if present. */
        private const val MEAN_DECODE_LEN = 200
        private const val MASK_NEG = -100.0f

        /** Prefer the QNN context-binary pair if present, else the CPU ONNX encoder+decoder.
         *  [preferCpu] skips the QNN branch even when the ctx pair exists — the desktop e2e
         *  harness (tools/e2e-jvm) passes it: its ORT build has no QNN EP and no Hexagon
         *  backend, so loading `*_ctx.onnx` there can only fail. On-device it stays false. */
        fun load(env: OrtEnvironment, paths: ModelPaths, preferCpu: Boolean = false): WhisperAsr = ResourceScope().use { scope ->
            fun session(file: File, options: OrtSession.SessionOptions = OrtSession.SessionOptions()): OrtSession =
                options.use { scope.own(env.createSession(file.path, it)) }
            val mel = MelSpectrogram(paths.melFilterFile)
            // QNN loads the *_ctx.onnx WRAPPER (ORT cannot load a raw .bin directly — the wrapper
            // points at the embedded context binary). Match encoder vs decoder by filename.
            val contextPair = paths.qnnContextPair()
            val encCtx = contextPair?.first
            val decCtx = contextPair?.second

            // ORT's QNN Execution Provider on the Hexagon HTP (onnxruntime.ai QNN-ExecutionProvider docs).
            fun qnnSessionOptions(): OrtSession.SessionOptions {
                val options = OrtSession.SessionOptions()
                try {
                options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
                // AI Hub precompiled_qnn_onnx graphs contain an external EPContext/model.bin.
                // Tell ORT to load that context instead of treating EPContext as a normal op.
                options.addConfigEntry("ep.context_enable", "1")
                options.addQnn(mapOf(
                    "backend_path" to "libQnnHtp.so",          // Hexagon Tensor Processor backend
                    "htp_performance_mode" to "burst",         // lowest-latency mode for a short PTT utterance
                    "htp_graph_finalization_optimization_mode" to "3",
                ))
                return options
                } catch (failure: Throwable) {
                    try { options.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }

            // Encoder session: QNN ctx wrapper when the pair is present, else the optimum CPU ONNX
            // (required either way — the host-side decode loop always starts from an encoder run).
            val encoder: OrtSession = if (!preferCpu && encCtx != null && decCtx != null) {
                session(encCtx, qnnSessionOptions())
            } else {
                val encFile = File(paths.whisperOnnxDir, "encoder_model.onnx")
                require(encFile.exists()) {
                    "No selected Whisper model found; run export_selected_asr.py"
                }
                session(encFile)
            }

            val choice: DecoderChoice = if (!preferCpu && encCtx != null && decCtx != null) {
                val dec = session(decCtx, qnnSessionOptions())
                val c = if (dec.inputNames.any { it.contains("k_cache_self") }) Contract.AI_HUB_QNN
                        else Contract.HF_OPTIMUM
                DecoderChoice(dec, null, c, Backend.QNN_NPU)
            } else {
                val dir = paths.whisperOnnxDir
                // decoder_model.onnx is the (past-free) first-step graph. A lean bundle may ship
                // ONLY the with-past graph (same hasGraph contract as OPUS-MT) — then decode
                // re-runs the bootstrap graph every step (first == dec, degenerate path).
                val withPast = File(dir, "decoder_with_past_model.onnx").takeIf { it.exists() }
                val mainFile = withPast ?: File(dir, "decoder_model.onnx").takeIf { it.exists() }
                    ?: error("decoder_model.onnx missing in $dir — re-run export_selected_asr.py")
                val dec = session(mainFile)
                val first = if (withPast != null && File(dir, "decoder_model.onnx").exists()) {
                    session(File(dir, "decoder_model.onnx"))
                } else null
                val c = if (dec.inputNames.any { it.contains("k_cache_self") }) Contract.AI_HUB_QNN
                        else Contract.HF_OPTIMUM
                DecoderChoice(dec, first, c, if (preferCpu) Backend.CPU else Backend.CPU_FALLBACK)
            }

            val bpe = WhisperBpeDecoder.load(paths.whisperOnnxDir)
            val tokens = WhisperTokens.resolve(
                bpe,
                File(paths.whisperOnnxDir, "whisper_tokens.json"),
            )
            val asr = WhisperAsr(
                env, encoder, choice.session, mel, choice.backend, choice.contract,
                choice.firstStep, tokens, bpe,
            )
            // Warm-up: one bounded dummy decode finalizes the HTP graphs so the user's FIRST real
            // push-to-talk does not pay graph-finalization latency (roadmap M3.3).
            asr.warmUp()
            scope.commit()
            asr
        }
    }

    /**
     * @param pcm16k mono 16 kHz PCM (the VAD-trimmed valid-speech segment)
     */
    fun transcribe(pcm16k: FloatArray, src: Language, tgt: Language): TranscriptResult =
        transcribeDetailed(pcm16k, src, tgt).result

    fun transcribeDetailed(
        pcm16k: FloatArray,
        src: Language,
        tgt: Language,
        modelName: String = "whisper",
    ): DetailedTranscript {
        val startedNs = System.nanoTime()
        // STEP 1: host-side log-mel, 80x3000, exactly Whisper's preprocessing (pure DSP).
        val logMel: FloatArray = mel.logMel(pcm16k)
        val melLatencyMs = (System.nanoTime() - startedNs) / 1_000_000
        // STEP 2: encoder run + host-driven greedy decode with the forced-language prompt.
        val outcome = when (contract) {
            Contract.AI_HUB_QNN -> synchronized(this) { decodeAiHub(logMel, src) }
            Contract.HF_OPTIMUM -> decodeOptimum(logMel, src)
        }
        // Whisper can greedily emit plausible text for digital silence. Its no-speech head is
        // evaluated from the SOT logits, before that hallucinated continuation is exposed.
        val isNoSpeech = (outcome.noSpeechProb ?: 0.0) > NO_SPEECH_PROBABILITY_MAX
        val rawText = bpe.decode(outcome.ids)
        val text = if (isNoSpeech) "" else rawText
        val score = if (isNoSpeech || outcome.scoredTokens == 0) 0.0
        else exp(outcome.sumLogProb / outcome.scoredTokens).coerceIn(0.0, 1.0)
        val avgLogProb = outcome.sumLogProb.takeIf { outcome.scoredTokens > 0 }
            ?.div(outcome.scoredTokens)
        val result = TranscriptResult(
            sourceLanguage = src,
            targetLanguage = tgt,
            text = text,
            asrScore = score,
            noSpeechProbability = outcome.noSpeechProb,
            // This is raw exp(mean token log-probability), NOT the benchmark-calibrated score
            // that DecisionConfig.minAsrScore is defined against.
            asrScoreCalibrated = false,
        )
        return DetailedTranscript(
            result = result,
            diagnostics = AsrDiagnostics(
                modelName = modelName,
                backend = backend.name,
                melLatencyMs = melLatencyMs,
                contract = contract.name,
                languageToken = tokens.languageId(src),
                taskToken = tokens.transcribe,
                inputSampleCount = pcm16k.size,
                inputDurationMs = pcm16k.size.toLong() * 1000 / 16000,
                rawTranscript = rawText,
                noSpeechProbability = outcome.noSpeechProb,
                averageLogProbability = avgLogProb,
                rawSequenceProbability = score,
                tokenCount = outcome.scoredTokens,
                stopReason = outcome.stopReason,
                latencyMs = (System.nanoTime() - startedNs) / 1_000_000,
                encoderMs = outcome.encoderMs,
                decoderRunMs = outcome.decoderRunMs,
                decodeSteps = outcome.steps,
            ),
        )
    }

    /** One bounded zero-feature decode: finalizes the HTP graphs at app open (cheap, ~a few steps). */
    fun warmUp() {
        val t0 = System.nanoTime()
        // Decoder entry points consume log-mel [1, 80, 3000], not PCM. This exercises the
        // sessions without computing an unnecessary 30-second DFT during app startup.
        val features = FloatArray(MelSpectrogram.N_MELS * MelSpectrogram.N_FRAMES)
        when (contract) {
            Contract.AI_HUB_QNN -> synchronized(this) { decodeAiHub(features, Language.EN, maxStepsCap = 4) }
            Contract.HF_OPTIMUM -> decodeOptimum(features, Language.EN, maxStepsCap = 4)
        }
        Log.i(TAG, "warm-up done in ${(System.nanoTime() - t0) / 1_000_000} ms ($backend/$contract)")
    }

    // ---------------------------------------------------------------------
    // Shared per-decode state
    // ---------------------------------------------------------------------

    private class Outcome(
        val ids: IntArray,
        val sumLogProb: Double,
        val scoredTokens: Int,
        val noSpeechProb: Double?,
        val stopReason: String,
        val encoderMs: Double = 0.0,
        val decoderRunMs: Double = 0.0,
        val steps: Int = 0,
    )

    /** Forced prompt fed right after SOT (HF forced-decoder-prompt semantics). */
    private fun forcedPrompt(src: Language): IntArray =
        intArrayOf(tokens.languageId(src), tokens.transcribe, tokens.notimestamps)

    // ---------------------------------------------------------------------
    // Contract 1: AI Hub QNN graphs — exact port of app.py _transcribe_single_chunk
    // ---------------------------------------------------------------------

    private fun decodeAiHub(logMel: FloatArray, src: Language, maxStepsCap: Int = Int.MAX_VALUE): Outcome {
        // Resolve the encoder input name BEFORE creating the tensor: if pickEncoderInput() were
        // to throw after createTensor (malformed export), nothing guarded melTensor yet — one
        // leaked native tensor (Round-4 review LOW). Name first, tensor second.
        val encInName = pickEncoderInput()
        val melTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(logMel),
            longArrayOf(1, MelSpectrogram.N_MELS.toLong(), MelSpectrogram.N_FRAMES.toLong()),
        )
        // melTensor is caller-owned and closed below; if encoder.run itself throws, close it
        // before propagating (the finally at the bottom only covers the happy-path binding).
        val encBegin = System.nanoTime()
        val encRes = try {
            encoder.run(mapOf(encInName to melTensor))
        } catch (t: Throwable) {
            melTensor.close()
            throw t
        }
        try {
            // Cross KV: encoder outputs, referenced (not copied) as decoder inputs. The encoder
            // Result stays open for the whole decode; tensors die with it at the end.
            val crossK = HashMap<String, OnnxTensor>()
            val crossV = HashMap<String, OnnxTensor>()
            for ((name, value) in encRes) {
                when {
                    name.contains("k_cache_cross") -> crossK[name] = value as OnnxTensor
                    name.contains("v_cache_cross") -> crossV[name] = value as OnnxTensor
                }
            }
            require(crossK.isNotEmpty() && crossV.size == crossK.size) {
                "AI Hub encoder produced ${crossK.size} k / ${crossV.size} v cross-cache tensors " +
                    "(outputs: ${encoder.outputNames}) — unexpected QNN export"
            }

            // Graph-derived dimensions — all read from the selected export, none hard-coded.
            // A dynamic dim (-1) in the export falls back to the AI Hub constants.
            val maskLen: Int = inputShape(maskName(decoder))?.last()?.toInt()?.takeIf { it > 0 }
                ?: MEAN_DECODE_LEN
            val selfKIn = decoder.inputNames.filter { it.contains("k_cache_self") }
            val selfVIn = decoder.inputNames.filter { it.contains("v_cache_self") }
            require(selfKIn.size == selfVIn.size && selfKIn.isNotEmpty()) {
                "decoder self-cache inputs k=${selfKIn.size} v=${selfVIn.size} — unexpected export"
            }
            val layers = selfKIn.size
            val cacheLen = inputShape(selfKIn[0])?.last()?.toInt()?.takeIf { it > 0 }
                ?: (MEAN_DECODE_LEN - 1)
            // The encoder must emit exactly one cross-KV pair per decoder layer (both == n_layers).
            val crossKCount = encoder.outputNames.count { it.contains("k_cache_cross") }
            require(crossKCount == layers) {
                "decoder expects $layers self-cache layers but encoder emits $crossKCount k_cache_cross " +
                    "tensors — mismatched QNN export pair"
            }
            val maxSteps = minOf(cacheLen, maxStepsCap)  // app.py: range(mean_decode_len - 1)

            val encoderMs = (System.nanoTime() - encBegin) / 1e6
            val io = pinnedIo(maskLen)
            val forced = forcedPrompt(src)
            val mask = FloatArray(maskLen) { MASK_NEG }
            val idsOut = ArrayList<Int>(cacheLen + 1)
            idsOut.add(tokens.sot)
            var sumLogProb = 0.0
            var scored = 0
            var noSpeechProb: Double? = null
            var lastGen = tokens.sot
            var stopReason = "max_steps"
            var decoderRunNs = 0L
            var steps = 0
            val row = FloatArray(io.vocab)
            val inputs = HashMap<String, OnnxTensor>(decoder.inputNames.size * 2)
            for ((name, t) in crossK) inputs[name] = t   // owned by encRes
            for ((name, t) in crossV) inputs[name] = t   // owned by encRes
            inputs[io.idsName] = io.ids
            inputs[io.maskName] = io.mask
            inputs[io.posName] = io.position
            // Fixed-shape decoder, ONE token per call. All step IO lives in tensors allocated once
            // per session (same scheme as StaticMtEngine): two pinned output sets alternate and the
            // previous set's *_out self-cache tensors are fed straight back as *_in. No per-token
            // tensor creation, no ORT output allocation, no self-cache copy on the Java side.
            for (n in 0 until maxSteps) {
                val feed = when {
                    n == 0 -> tokens.sot
                    n <= forced.size -> forced[n - 1]
                    else -> lastGen
                }
                mask[maskLen - 1 - n] = 0.0f   // app.py: attention_mask[..., 199 - n] = 0.0
                io.writeIds(feed)
                io.writePosition(n)
                io.maskBuf.clear(); io.maskBuf.asFloatBuffer().put(mask)
                val previous = if (n == 0) null else io.outputs[(n - 1) % 2]
                for ((input, output) in io.selfPairs) {
                    inputs[input] = previous?.getValue(output) ?: io.zeros.getValue(input)
                }
                val runBegin = System.nanoTime()
                decoder.run(inputs, io.outputs[n % 2]).use { }
                decoderRunNs += System.nanoTime() - runBegin
                steps++

                // logits [1, V, 1, 1]: vocab on axis 1 -> flat index == vocab index.
                val logitsBuf = io.outputBuffers[n % 2].getValue(io.logitsName)
                logitsBuf.clear(); logitsBuf.asFloatBuffer().get(row, 0, io.vocab)
                if (n == 0) noSpeechProb = noSpeechFrom(row, io.vocab)

                val next = argmax(row)
                if (n < forced.size) {
                    idsOut.add(forced[n])      // forced slot: discard the argmax (see class KDoc)
                } else {
                    lastGen = next
                    idsOut.add(next)
                    sumLogProb += logProbOf(row, next)
                    scored++
                    if (next == tokens.eot) {
                        stopReason = "eot"
                        break
                    }
                }
            }
            return Outcome(idsOut.toIntArray(), sumLogProb, scored, noSpeechProb, stopReason,
                encoderMs = encoderMs, decoderRunMs = decoderRunNs / 1e6, steps = steps)
        } finally {
            encRes.close()
            melTensor.close()
        }
    }

    /** Session-lifetime decoder IO for the AI Hub contract (see [decodeAiHub]). */
    private inner class PinnedIo(maskLen: Int) : AutoCloseable {
        private val owned = ArrayList<OnnxTensor>()
        val idsName = inputName(decoder, "input_ids")
        val posName = inputName(decoder, "position_ids")
        val maskName = maskName(decoder)
        val logitsName = outputName(decoder, "logits")
        private val idsType = inputType(decoder, idsName)
        private val posType = inputType(decoder, posName)
        val ids: OnnxTensor
        val position: OnnxTensor
        val mask: OnnxTensor
        private val idsBuf: ByteBuffer
        private val posBuf: ByteBuffer
        val maskBuf: ByteBuffer
        val zeros = HashMap<String, OnnxTensor>()
        /** self-cache input name -> matching output name. */
        val selfPairs: List<Pair<String, String>>
        val outputs = ArrayList<Map<String, OnnxTensor>>(2)
        val outputBuffers = ArrayList<Map<String, ByteBuffer>>(2)
        val vocab: Int

        init {
            try {
                allocate(longArrayOf(1, 1), idsType).let { ids = it.first; idsBuf = it.second }
                allocate(longArrayOf(1), posType).let { position = it.first; posBuf = it.second }
                allocate(longArrayOf(1, 1, 1, maskLen.toLong()), OnnxJavaType.FLOAT).let { mask = it.first; maskBuf = it.second }
                selfPairs = decoder.inputNames.mapNotNull { name -> selfInToOut(name)?.let { name to it } }
                require(selfPairs.isNotEmpty() && selfPairs.all { it.second in decoder.outputNames }) {
                    "decoder self-cache inputs/outputs do not pair up — unexpected export"
                }
                val shared = HashMap<String, OnnxTensor>()
                for ((input, _) in selfPairs) {
                    val info = decoder.getInputInfo().getValue(input).info as TensorInfo
                    zeros[input] = shared.getOrPut(info.shape.contentToString() + info.type) {
                        allocate(info.shape, info.type).first
                    }
                }
                repeat(2) {
                    val tensors = LinkedHashMap<String, OnnxTensor>()
                    val buffers = HashMap<String, ByteBuffer>()
                    for ((name, node) in decoder.getOutputInfo()) {
                        val info = node.info as TensorInfo
                        val (tensor, buffer) = allocate(info.shape, info.type)
                        tensors[name] = tensor; buffers[name] = buffer
                    }
                    outputs.add(tensors); outputBuffers.add(buffers)
                }
                val logitsInfo = decoder.getOutputInfo().getValue(logitsName).info as TensorInfo
                require(logitsInfo.type == OnnxJavaType.FLOAT) { "AI Hub decoder logits must be float32" }
                vocab = logitsInfo.shape.max().toInt()
            } catch (t: Throwable) {
                close(); throw t
            }
        }

        private fun allocate(shape: LongArray, type: OnnxJavaType): Pair<OnnxTensor, ByteBuffer> {
            require(shape.isNotEmpty() && shape.all { it > 0 }) { "dynamic decoder shape ${shape.toList()} unsupported" }
            val size = when (type) {
                OnnxJavaType.FLOAT, OnnxJavaType.INT32 -> 4
                OnnxJavaType.INT64 -> 8
                OnnxJavaType.FLOAT16 -> 2
                else -> error("unsupported decoder dtype $type")
            }
            val count = shape.fold(1L) { a, b -> Math.multiplyExact(a, b) }
            val buffer = ByteBuffer.allocateDirect(Math.toIntExact(count * size)).order(ByteOrder.nativeOrder())
            val tensor = OnnxTensor.createTensor(env, buffer, shape, type)
            owned.add(tensor)
            return tensor to buffer
        }

        private fun write(buffer: ByteBuffer, type: OnnxJavaType, value: Int) {
            if (type == OnnxJavaType.INT64) buffer.putLong(0, value.toLong()) else buffer.putInt(0, value)
        }
        fun writeIds(value: Int) = write(idsBuf, idsType, value)
        fun writePosition(value: Int) = write(posBuf, posType, value)

        override fun close() {
            owned.forEach { runCatching { it.close() } }
            owned.clear()
        }
    }

    private var pinned: PinnedIo? = null

    private fun pinnedIo(maskLen: Int): PinnedIo =
        pinned ?: PinnedIo(maskLen).also { pinned = it }

    /** `k_cache_self_3_in` -> `k_cache_self_3_out` (AI Hub output naming). */
    private fun selfInToOut(inputName: String): String? =
        if (inputName.contains("cache_self") && inputName.endsWith("_in")) {
            inputName.removeSuffix("_in") + "_out"
        } else null

    /**
     * OpenAI Whisper reference semantics (`decoding.py`): softmax the entire vocabulary at the
     * SOT step, then read the `<|nospeech|>` entry. This MUST NOT be reduced to EOT vs no-speech:
     * doing so discards ~51k competing logits and can turn a tiny full-vocab probability into a
     * value near 0.5/1.0, falsely triggering the no-speech gate.
     */
    private fun noSpeechFrom(row: FloatArray, vocab: Int): Double {
        val nospeech = tokens.nospeech
        require(nospeech < vocab) { "nospeech id $nospeech outside vocab $vocab" }
        require(row.size >= vocab) { "logit row has ${row.size} entries, expected at least $vocab" }
        var max = Double.NEGATIVE_INFINITY
        for (i in 0 until vocab) max = maxOf(max, row[i].toDouble())
        var denominator = 0.0
        for (i in 0 until vocab) denominator += exp(row[i].toDouble() - max)
        val numerator = exp(row[nospeech].toDouble() - max)
        return numerator / denominator
    }

    // ---------------------------------------------------------------------
    // Contract 2: optimum ONNX CPU fallback (encoder hidden states + past_key_values)
    // ---------------------------------------------------------------------

    private fun decodeOptimum(logMel: FloatArray, src: Language, maxStepsCap: Int = Int.MAX_VALUE): Outcome {
        // Name resolution BEFORE tensor creation (same leak-proofing as decodeAiHub, Round-4 LOW).
        val encInName = pickEncoderInput()
        val melTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(logMel),
            longArrayOf(1, MelSpectrogram.N_MELS.toLong(), MelSpectrogram.N_FRAMES.toLong()),
        )
        // Same leak-proof binding as decodeAiHub: encoder.run throwing must not leak melTensor.
        val encRes = try {
            encoder.run(mapOf(encInName to melTensor))
        } catch (t: Throwable) {
            melTensor.close()
            throw t
        }
        // optimum semantics (v1.17.1 exporters/onnx/base.py, OnnxConfigWithPast.outputs): BOTH
        // decoder graphs output the KV cache, and the past-free graph additionally emits the
        // cross (encoder) KV which the with-past graph consumes as `past_key_values.*.encoder.*`
        // inputs (constant across steps — the with-past graph does NOT re-emit them). So the
        // bootstrap Result stays open for the whole decode, exactly like encRes.
        var bootstrapRes: OrtSession.Result? = null
        var prevRes: OrtSession.Result? = null
        // Caller-owned input tensors (see decodeAiHub): every tracked tensor is a fresh nio
        // buffer created below; borrowed tensors (encHidden, past-KV outputs) are NOT tracked —
        // they die with the Result that owns them.
        val stepInputs = ArrayList<OnnxTensor>(8)
        fun track(t: OnnxTensor): OnnxTensor { stepInputs.add(t); return t }
        try {
            val encHiddenName = encoder.outputNames.firstOrNull { it.contains("hidden") }
                ?: encoder.outputNames.first()
            val encHidden = encRes[encHiddenName]
                .orElseThrow { IllegalStateException("Missing encoder output: $encHiddenName") } as OnnxTensor
            val hiddenLen = (encHidden.info as TensorInfo).shape[1].toInt()  // 1500

            val dec = decoder
            val first = decoderFirstStep ?: dec
            // Names resolved PER SESSION: the two optimum decoder graphs may name inputs/outputs
            // independently ("input_ids" vs "decoder_input_ids", etc.); both variants contain the
            // needles below, and each name is only ever fed to the session it came from.
            val idsNameFirst = inputName(first, "input_ids")
            val idsName = inputName(dec, "input_ids")
            val logitsNameFirst = outputName(first, "logits")
            val logitsName = outputName(dec, "logits")
            val encHsNameFirst = first.inputNames.firstOrNull {
                it.contains("encoder") && (it.contains("hidden") || it.contains("encoder_outputs"))
            } ?: error("optimum first-step decoder has no encoder hidden-states input (inputs: ${first.inputNames})")
            // Whisper's optimum decoder takes NO attention-mask inputs (AudioToTextOnnxConfig pops
            // them for DECODER behavior); the lookups stay defensive in case an export adds them.
            val encMaskName = dec.inputNames.firstOrNull {
                it.contains("attention_mask") && it.contains("encoder")
            }
            val selfMaskName = dec.inputNames.firstOrNull {
                it.contains("attention_mask") && !it.contains("encoder")
            }
            val encMaskNameFirst = first.inputNames.firstOrNull {
                it.contains("attention_mask") && it.contains("encoder")
            }
            val selfMaskNameFirst = first.inputNames.firstOrNull {
                it.contains("attention_mask") && !it.contains("encoder")
            }
            // Newer Transformers/Optimum Whisper exports declare an explicit cache_position;
            // older PhoWhisper exports do not. Bind it only when the live graph asks for it.
            val cachePositionName = dec.inputNames.firstOrNull { it.endsWith("cache_position") }
            val usePast = dec.inputNames.any { it.contains("past_key_values") }
            val prompt = intArrayOf(tokens.sot) + forcedPrompt(src)
            val maxGenerated = minOf(MEAN_DECODE_LEN - 1 - prompt.size, maxStepsCap)

            val ids = ArrayList<Int>(MEAN_DECODE_LEN).apply { addAll(prompt.toList()) }
            var sumLogProb = 0.0
            var scored = 0

            // CALL A: past-free graph over the whole forced prompt ([SOT, lang, transcribe,
            // notimestamps] in one call — exactly HF semantics, positions 0..3 internal).
            // Creation → run() inside one try: a failing onesTensor/typedIds closes what was
            // already tracked (Round-4 review LOW).
            val inpA = HashMap<String, OnnxTensor>()
            stepInputs.clear()
            val resA: OrtSession.Result
            try {
                inpA[idsNameFirst] = track(typedIds(first, idsNameFirst, prompt, longArrayOf(1, prompt.size.toLong())))
                inpA[encHsNameFirst] = encHidden
                encMaskNameFirst?.let { inpA[it] = track(onesTensor(first, it, longArrayOf(1, hiddenLen.toLong()))) }
                selfMaskNameFirst?.let { inpA[it] = track(onesTensor(first, it, longArrayOf(1, prompt.size.toLong()))) }
                resA = first.run(inpA)
            } finally {
                stepInputs.forEach { it.close() }; stepInputs.clear()
            }
            bootstrapRes = resA
            prevRes = resA
            val logitsA = resA[logitsNameFirst]
                .orElseThrow { IllegalStateException("Missing decoder output: $logitsNameFirst") } as OnnxTensor
            val vocab = vocabSizeOf(logitsA)
            val noSpeechProb = noSpeechFrom(firstVocabRow(logitsA, vocab), vocab)
            val lastRowA = lastVocabRow(logitsA)
            var lastToken = argmax(lastRowA)
            sumLogProb += logProbOf(lastRowA, lastToken)
            scored++
            ids.add(lastToken)
            if (lastToken == tokens.eot) {
                return Outcome(ids.toIntArray(), sumLogProb, scored, noSpeechProb, "eot")
            }

            // STEPS 1..: one token per call. With past: feed the with-past graph the last token +
            // decoder KV from the previous step's present.* and the (constant) encoder KV from the
            // bootstrap. Without (degenerate export without decoder_with_past_model.onnx): re-run
            // the full sequence each step and take the last row (correct, O(n^2), bring-up only).
            var generated = 1
            var stopReason = "max_steps"
            while (generated < maxGenerated) {
                val inp = HashMap<String, OnnxTensor>()
                stepInputs.clear()
                // Creation → bindPast → run() inside ONE try (Round-4 review LOW): bindPast only
                // reads from Results, and any throw before run() still closes the tracked tensors.
                val r: OrtSession.Result
                try {
                    if (usePast) {
                        inp[idsName] = track(typedIds(dec, idsName, intArrayOf(lastToken), longArrayOf(1, 1)))
                        cachePositionName?.let {
                            // ids already contains the token being fed, so its zero-based absolute
                            // decoder position is size - 1 (4 on the first cached step).
                            inp[it] = track(typedIds(dec, it, intArrayOf(ids.size - 1), longArrayOf(1)))
                        }
                        // The with-past graph consumes cross attention only through
                        // past_key_values.*.encoder.* bound from the bootstrap result.
                        encMaskName?.let { inp[it] = track(onesTensor(dec, it, longArrayOf(1, hiddenLen.toLong()))) }
                        selfMaskName?.let { inp[it] = track(onesTensor(dec, it, longArrayOf(1, ids.size.toLong()))) }
                        bindPast(
                            requireNotNull(bootstrapRes) { "bootstrap Result missing for past binding" },
                            requireNotNull(prevRes) { "previous-step Result missing for past binding" },
                            inp,
                        )
                    } else {
                        inp[idsName] = track(typedIds(dec, idsName, ids.toIntArray(), longArrayOf(1, ids.size.toLong())))
                        val encHsName = dec.inputNames.firstOrNull {
                            it.contains("encoder") && (it.contains("hidden") || it.contains("encoder_outputs"))
                        } ?: error("past-free decoder has no encoder hidden-states input (inputs: ${dec.inputNames})")
                        inp[encHsName] = encHidden
                        encMaskName?.let { inp[it] = track(onesTensor(dec, it, longArrayOf(1, hiddenLen.toLong()))) }
                        selfMaskName?.let { inp[it] = track(onesTensor(dec, it, longArrayOf(1, ids.size.toLong()))) }
                    }
                    r = dec.run(inp)
                } finally {
                    stepInputs.forEach { it.close() }
                    stepInputs.clear()
                }
                if (prevRes !== bootstrapRes) prevRes?.close()
                prevRes = r
                val logits = r[logitsName]
                    .orElseThrow { IllegalStateException("Missing decoder output: $logitsName") } as OnnxTensor
                val row = lastVocabRow(logits)
                val next = argmax(row)
                sumLogProb += logProbOf(row, next)
                scored++
                ids.add(next)
                lastToken = next
                generated++
                if (next == tokens.eot) {
                    stopReason = "eot"
                    break
                }
            }
            return Outcome(ids.toIntArray(), sumLogProb, scored, noSpeechProb, stopReason)
        } finally {
            if (prevRes !== bootstrapRes) prevRes?.close()
            bootstrapRes?.close()
            encRes.close()
            melTensor.close()
        }
    }

    /**
     * Feed `past_key_values.{i}.{decoder|encoder}.{key|value}` inputs from the previous step's
     * `present.*` outputs (decoder KV) and the bootstrap run's `present.{i}.encoder.*` (cross KV).
     *
     * optimum v1.17.1 naming, verified from exporters source:
     *  - decoder self-KV:  output `present.{i}.decoder.{key,value}` (past-free bootstrap) ->
     *    with-past input `past_key_values.{i}.decoder.{key,value}`, `{2: past_sequence_length}`;
     *  - cross (encoder) KV: output `present.{i}.encoder.{key,value}` exists ONLY on the past-free
     *    decoder graph (with-past consumes it but does not re-emit), so it is bound from
     *    `bootstrapRes` and is constant for all steps.
     * Key matching uses the `.{layer}.{part}.{key|value}` structure itself, never raw substring,
     * so `present.2.decoder.key` can never be confused with `present.12.decoder.key`.
     */
    private fun bindPast(
        bootstrapRes: OrtSession.Result,
        prevRes: OrtSession.Result,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        for (name in decoder.inputNames) {
            if (!name.contains("past_key_values")) continue
            // `past_key_values.2.decoder.key` -> parts after the prefix: [2, decoder, key]
            val parts = name.removePrefix("past_key_values").split('.').filter { it.isNotEmpty() }
            if (parts.size != 3) {
                error("unexpected past-KV input name '$name' (expected past_key_values.<i>.<part>.<key|value>)")
            }
            val source = if (parts[1] == "encoder") bootstrapRes else prevRes
            val outName = presentOutputFor(source, parts) ?: error(
                "no present output matching past input '$name' " +
                    "(bootstrap outputs: ${decoderFirstStep?.outputNames ?: decoder.outputNames}; " +
                    "with-past outputs: ${decoder.outputNames})"
            )
            inputs[name] = source[outName]
                .orElseThrow { IllegalStateException("Missing cached decoder output: $outName") } as OnnxTensor
        }
    }

    /** `["3", "decoder", "key"]` -> `present.3.decoder.key` on the given result (if present). */
    private fun presentOutputFor(res: OrtSession.Result, parts: List<String>): String? {
        val expected = "present.${parts[0]}.${parts[1]}.${parts[2]}"
        return res.asIterable().map { it.key }.firstOrNull { it == expected }
            // tolerant fallback: same layer/part/key structure with a different output prefix
            ?: res.asIterable().map { it.key }.firstOrNull {
                it.endsWith(".${parts[0]}.${parts[1]}.${parts[2]}")
            }
    }

    // ---------------------------------------------------------------------
    // Tensor + name helpers (introspection only — no hard-coded tensor names)
    // ---------------------------------------------------------------------

    private fun maskName(session: OrtSession): String =
        session.inputNames.firstOrNull { it.contains("attention_mask") }
            ?: error("no attention_mask input (inputs: ${session.inputNames})")

    private fun inputName(session: OrtSession, needle: String): String =
        session.inputNames.firstOrNull { it.contains(needle) }
            ?: error("no '$needle' input (inputs: ${session.inputNames})")

    private fun outputName(session: OrtSession, needle: String): String =
        session.outputNames.firstOrNull { it.contains(needle) }
            ?: error("no '$needle' output (outputs: ${session.outputNames})")

    private fun pickEncoderInput(): String {
        val names = encoder.inputNames.toList()
        return names.firstOrNull { it.contains("input_features") } ?: names.singleOrNull()
        ?: error("cannot pick encoder input among $names")
    }

    private fun inputShape(name: String): LongArray? =
        (decoder.getInputInfo()[name]?.info as? TensorInfo)?.shape?.takeIf { it.isNotEmpty() }

    private fun inputType(session: OrtSession, name: String): OnnxJavaType =
        ((session.getInputInfo()[name]?.info) as? TensorInfo)?.type ?: OnnxJavaType.INT64

    private fun intTensor(data: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, IntBuffer.wrap(data), shape)

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    /** input_ids tensor with the element type the graph declares (optimum: int64, AI Hub: int32). */
    private fun typedIds(session: OrtSession, name: String, ids: IntArray, shape: LongArray): OnnxTensor =
        when (inputType(session, name)) {
            OnnxJavaType.INT32 -> intTensor(ids, shape)
            else -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), shape)
        }

    /** attention/encoder mask with the element type the graph declares. */
    private fun onesTensor(session: OrtSession, name: String, shape: LongArray): OnnxTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        return when (inputType(session, name)) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape)
            OnnxJavaType.INT32 -> intTensor(IntArray(n) { 1 }, shape)
            else -> floatTensor(FloatArray(n) { 1f }, shape)
        }
    }

    /** Vocab size from the logits tensor shape (AI Hub [1,V,1,1]; optimum [1,seq,V]). */
    private fun vocabSizeOf(logits: OnnxTensor): Int {
        val shape = (logits.info as TensorInfo).shape
        return shape.max().toInt()
    }

    /** The full vocab row of a [1,V,...] logits tensor (AI Hub contract: vocab on axis 1). */
    private fun vocabRow(logits: OnnxTensor, vocab: Int): FloatArray {
        val fb = logits.floatBuffer
        return FloatArray(vocab).also { fb.get(it, 0, vocab) }
    }

    /** First position's vocab row of a [1,seq,V] logits tensor (SOT step — no-speech head location). */
    private fun firstVocabRow(logits: OnnxTensor, vocab: Int): FloatArray {
        val fb = logits.floatBuffer
        return FloatArray(vocab).also { fb.get(it, 0, vocab) }
    }

    /** Last position's vocab row of a [1,seq,V] logits tensor (optimum contract). */
    private fun lastVocabRow(logits: OnnxTensor): FloatArray {
        val shape = (logits.info as TensorInfo).shape
        return if (shape.size == 3 && shape[1] > 1) {
            val v = shape[2].toInt()
            val fb = logits.floatBuffer
            FloatArray(v).also { fb.position(((shape[1] - 1) * v).toInt()); fb.get(it, 0, v); fb.position(0) }
        } else {
            vocabRow(logits, vocabSizeOf(logits))
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
        for (v in row) if (v > m) m = v
        var sum = 0.0
        for (v in row) sum += exp((v - m).toDouble())
        return (row[token] - m - Math.log(sum))
    }

    override fun close() {
        synchronized(this) { pinned?.close(); pinned = null }
        decoderFirstStep?.close()
        decoder.close()
        encoder.close()
    }
}

/**
 * The special-token ids the decode loop needs (SOT/EOT/task/no-speech/language).
 *
 * Primary source: `whisper_tokens.json` written by export_whisper_onnx.py from the REAL
 * WhisperTokenizer (special ids + `languages` map). Fallback (file absent or a key missing):
 * canonical `<|...|>` names resolved against the exported vocab — never hard-coded numeric ids.
 */
class WhisperTokens private constructor(
    val sot: Int,
    val eot: Int,
    val transcribe: Int,
    val notimestamps: Int,
    val nospeech: Int,
    private val jsonLanguages: Map<String, Int>?,
    private val bpe: WhisperBpeDecoder,
) {
    private val languageIds = java.util.concurrent.ConcurrentHashMap<Language, Int>()

    /** Language prompt id (`<|vi|>`/`<|en|>`/`<|zh|>`/`<|ko|>`): json value when present, else
     *  resolved against the vocab once and cached (single decode thread; concurrent-safe anyway). */
    fun languageId(language: Language): Int = languageIds.getOrPut(language) {
        val name = "<|${language.name.lowercase()}|>"
        jsonLanguages?.get(language.name.lowercase())
            ?: Companion.scanVocab(bpe, name)
            ?: error("language token $name not in Whisper vocab — re-run export_whisper_onnx.py")
    }

    companion object {
        private const val TEXT_VOCAB_END = 50257
        private const val VOCAB_END = 51865

        private val CANONICAL = mapOf(
            "sot" to "<|startoftranscript|>",
            "eot" to "<|endoftext|>",
            "transcribe" to "<|transcribe|>",
            "notimestamps" to "<|notimestamps|>",
            "nospeech" to "<|nocaptions|>",   // HF ships OpenAI's <|nospeech|> under this name (id 50362)
        )

        /** Resolve `<|name|>` against the special-token window (50257..51864) of the real vocab. */
        private fun scanVocab(bpe: WhisperBpeDecoder, token: String): Int? {
            for (id in TEXT_VOCAB_END until VOCAB_END) if (bpe.tokenAt(id) == token) return id
            return null
        }

        fun resolve(bpe: WhisperBpeDecoder, tokensJson: File): WhisperTokens {
            val json: org.json.JSONObject? = if (tokensJson.exists()) {
                try {
                    org.json.JSONObject(tokensJson.readText())
                } catch (e: org.json.JSONException) {
                    null   // malformed file -> fall through to vocab resolution rather than crash
                }
            } else null
            fun id(role: String): Int {
                val fromJson = json?.optInt(role, -1)
                if (fromJson != null && fromJson >= 0) return fromJson
                return scanVocab(bpe, CANONICAL.getValue(role))
                    ?: error("Whisper token for '$role' (${CANONICAL.getValue(role)}) not found — " +
                        "re-run export_whisper_onnx.py")
            }
            val langs = json?.optJSONObject("languages")?.let { jo ->
                buildMap {
                    for (k in jo.keys()) put(k, jo.getInt(k))
                }
            }
            return WhisperTokens(
                id("sot"), id("eot"), id("transcribe"), id("notimestamps"), id("nospeech"), langs, bpe,
            )
        }
    }
}

/** Keeps only the selected Phase-2 ASR resident; switching closes the old model first. */
class LanguageRoutedAsr(
    private val env: OrtEnvironment,
    private val paths: ModelPaths,
    private val preferCpu: Boolean = true,
    private val requireQnn: Boolean = false,
) : AutoCloseable {
    private var language: Language? = null
    private var active: WhisperAsr? = null
    private var parakeet: ParakeetAsr? = null

    /** Load and warm only one selected language. Returns load+warm-up milliseconds (zero if cached). */
    @Synchronized
    fun preload(src: Language): Long {
        if (language == src && (active != null || parakeet != null)) return 0
        val tag = when (src) { Language.VI -> "vi"; Language.EN -> "en"; else -> error("Unsupported ASR language: $src") }
        close()
        val start = System.nanoTime()
        if (requireQnn) check(!preferCpu && paths.forLanguage(tag).qnnPairReady()) { "Selected QNN ASR pair missing: $tag" }
        if (paths.asrModelName(tag) == "parakeet-v2") {
            check(requireQnn && !preferCpu) { "Parakeet profile requires QNN" }
            parakeet = ParakeetAsr.load(env, paths.forLanguage(tag))
        } else active = WhisperAsr.load(env, paths.forLanguage(tag), preferCpu = preferCpu)
        language = src
        return (System.nanoTime() - start) / 1_000_000
    }

    @Synchronized
    fun transcribe(pcm16k: FloatArray, src: Language, tgt: Language): TranscriptResult =
        transcribeDetailed(pcm16k, src, tgt).result

    @Synchronized
    fun transcribeDetailed(pcm16k: FloatArray, src: Language, tgt: Language): DetailedTranscript {
        val tag = when (src) {
            Language.VI -> "vi"
            Language.EN -> "en"
            else -> error("Unsupported Phase-2 ASR language: $src")
        }
        val cold = language != src || (active == null && parakeet == null)
        val loadMs = preload(src)
        val detailed = parakeet?.transcribeDetailed(pcm16k, src, tgt) ?: checkNotNull(active).transcribeDetailed(
            pcm16k,
            src,
            tgt,
            modelName = paths.asrModelName(tag),
        )
        return detailed.copy(diagnostics = detailed.diagnostics.copy(coldLoadMs = loadMs, coldStart = cold))
    }

    @Synchronized
    override fun close() {
        val previous = active
        active = null
        language = null
        try { previous?.close() } finally { parakeet?.close(); parakeet = null }
    }
}
