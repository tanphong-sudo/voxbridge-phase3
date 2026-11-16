package com.voxbridge.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD — streaming speech detection during capture (MODEL_MANIFEST §3).
 *
 * Runs per 512-sample (32 ms @ 16 kHz) chunk, carrying the recurrent state across chunks. Emits a
 * speech probability the capture layer thresholds to mark valid-speech boundaries, so the audio
 * gate's validSpeechDurationMs is ready the instant push-to-talk releases.
 *
 * GRAPH VARIANTS (both handled — detected by introspecting the live session, not guessed):
 *  - Silero v5 (`silero_vad.onnx` from snakers4 ≥ v5): inputs `input` [1,N], `state` [2,1,128],
 *    `sr` [1]; outputs `output` (probability), `stateN` (next recurrent state).
 *  - Silero v4 (older fetches): inputs `input`, `h`/`c` [2,1,128] pair, `sr`; outputs `output`,
 *    `hn`/`cn`. The v4 h/c pair is fed as two tensors exactly as the graph declares.
 * The hidden size and state shape come from the graph's own declared TensorInfo (fallback 128
 * only if the export omitted metadata); the probability is read from the non-state output.
 *
 * State tensors returned by a run belong to that Result — closing the Result frees them, so the
 * next state is COPIED out (floats + shape) and a fresh tensor is fed on the following chunk.
 */
class SileroVad(private val env: OrtEnvironment, private val session: OrtSession) : AutoCloseable {

    /** One recurrent state: copied floats + the shape to re-feed them with. */
    private data class StateData(val floats: FloatArray, val shape: LongArray)

    private val isV5 = session.inputNames.any { it == "state" }
    private val hIn = session.inputNames.firstOrNull { it == "h" || it.endsWith("_h") }
    private val cIn = session.inputNames.firstOrNull { it == "c" || it.endsWith("_c") }
    private val stateIn = session.inputNames.firstOrNull { it == "state" }
    private val srIn = session.inputNames.firstOrNull { it.contains("sr") }

    private val stateOuts = session.outputNames.filter {
        it == "stateN" || it == "hn" || it == "cn" || it.endsWith("_state")
    }.toSet()
    private val probOut = session.outputNames.firstOrNull { it !in stateOuts }
        ?: error("Silero VAD graph has no probability output (outputs: ${session.outputNames})")

    /** State tensor shape from the graph's declaration (e.g. [2,1,128]); 128-hidden fallback. */
    private val declaredStateShape: LongArray = run {
        val probe = stateIn ?: hIn
        (session.getInputInfo()[probe]?.info as? TensorInfo)?.shape
            ?.takeIf { it.isNotEmpty() && it.all { d -> d > 0 } }
            ?: longArrayOf(2, 1, 128)
    }

    private val state: MutableMap<String, StateData> = mutableMapOf()
    /**
     * Silero's official ONNX wrapper prepends 64 samples of context at 16 kHz to every
     * 512-sample frame. The first frame receives zeros; subsequent frames receive the tail of the
     * previous frame. Feeding bare 512-sample frames materially suppresses speech probabilities
     * for quiet but intelligible real recordings.
     */
    private var context = FloatArray(CONTEXT_SAMPLES_16K)
    private var framedBuffer = FloatArray(CONTEXT_SAMPLES_16K + 512)
    private var closed = false

    /** One recurrent slot: fed under the INPUT name, refreshed from the OUTPUT name. */
    private data class StateSlot(val inName: String, val outName: String)

    /**
     * v4: inputs h/c -> outputs hn/cn ; v5: input `state` -> output `stateN`.
     * Input and output names are NOT identical (that mismatch was a real crash caught by
     * tools/e2e-jvm on the real v5 graph), so every slot resolves its output counterpart.
     */
    private val stateSlots: List<StateSlot> = run {
        val ins = if (isV5) listOfNotNull(stateIn) else listOfNotNull(hIn, cIn)
        ins.map { input ->
            val out = stateOuts.firstOrNull { it.equals(input + "n", ignoreCase = true) }
                ?: stateOuts.firstOrNull { it.startsWith(input) }
                ?: error("Silero VAD: no state output matches input '$input' (outputs: ${session.outputNames})")
            StateSlot(input, out)
        }
    }

    /** @return speech probability [0,1] for this chunk (512 samples = 32 ms @ 16 kHz).
     *
     * Threading: @Synchronized on [chunkProbability] and [reset] — the recurrent state slots are
     * read-modify-write inside run(), and a rapid release-and-re-press can legitimately overlap
     * the retiring capture loop's last chunkProbability with the new press's reset (both now run
     * through one monitor; AudioRecordSource additionally re-checks its predicate per chunk, so
     * the stale loop stops FEEDING frames — this guard makes even a racing call STATE-safe).
     * Input framing follows the official Silero ONNX wrapper: 64 samples of carried context are
     * prepended to each 512-sample frame at 16 kHz.
     */
    @Synchronized
    fun chunkProbability(chunk: FloatArray): Float {
        return chunkProbability(chunk, 0, chunk.size)
    }

    @Synchronized
    fun chunkProbability(chunk: FloatArray, offset: Int, length: Int): Float {
        check(!closed) { "Silero VAD is closed" }
        require(length > 0 && offset >= 0 && offset <= chunk.size - length) { "invalid VAD slice" }
        val feed = HashMap<String, OnnxTensor>()
        try {
            if (framedBuffer.size != context.size + length) framedBuffer = FloatArray(context.size + length)
            val framed = framedBuffer
            context.copyInto(framed, destinationOffset = 0)
            chunk.copyInto(framed, destinationOffset = context.size, startIndex = offset, endIndex = offset + length)
            feed[session.inputNames.first { it.contains("input") }] =
                OnnxTensor.createTensor(env, FloatBuffer.wrap(framed), longArrayOf(1, framed.size.toLong()))
            for (slot in stateSlots) {
                val sd = state[slot.inName]
                    ?: StateData(FloatArray(declaredStateShape.fold(1L) { a, b -> a * b }.toInt()), declaredStateShape)
                feed[slot.inName] = OnnxTensor.createTensor(env, FloatBuffer.wrap(sd.floats), sd.shape)
            }
            srIn?.let {
                feed[it] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000L)), longArrayOf(1))
            }

            val out = session.run(feed)
            val prob: Float
            try {
                val probT = out[probOut]
                    .orElseThrow { IllegalStateException("Missing Silero probability output: $probOut") } as OnnxTensor
                prob = lastFloatOf(probT)
                // Copy the next state out before the Result's tensors are released.
                for (slot in stateSlots) {
                    val t = out[slot.outName].orElseThrow {
                        IllegalStateException("Silero VAD produced no state output '${slot.outName}' (outputs: ${session.outputNames})")
                    } as OnnxTensor
                    val info = t.info as TensorInfo
                    // Sanitize dynamic dims (0/-1) against the declared input shape — a copied
                    // dynamic dim would blow up the SECOND feed (negative/zero dim tensor).
                    val shape = LongArray(info.shape.size) { i ->
                        val d = info.shape[i]
                        if (d > 0) d else declaredStateShape.getOrElse(i) { 1L }
                    }
                    state[slot.inName] = StateData(copyFloats(t), shape)
                }
                framed.copyInto(context, startIndex = framed.size - CONTEXT_SAMPLES_16K)
            } finally {
                out.close()
            }
            return prob
        } finally {
            // ORT contract: the CALLER owns input tensors. These are recreated ~30×/s during
            // PTT — never closing them churns the native heap (the classic ORT pitfall).
            feed.values.forEach { it.close() }
        }
    }

    /** Clears the recurrent state so the next capture starts from silence (same monitor as
     *  [chunkProbability] — reset can never interleave with an in-flight chunk update). */
    @Synchronized
    fun reset() {
        check(!closed) { "Silero VAD is closed" }
        state.clear()
        context.fill(0f)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        state.clear()
        session.close()
    }

    private fun copyFloats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr
    }

    private fun lastFloatOf(t: OnnxTensor): Float {
        val fb = t.floatBuffer
        return fb.get(fb.remaining() - 1)
    }

    companion object {
        private const val CONTEXT_SAMPLES_16K = 64

        fun load(env: OrtEnvironment, file: File): SileroVad {
            require(file.exists()) { "Silero VAD missing at $file — run fetch_models.py --vad" }
            return ResourceScope().use { scope ->
                val session = OrtSession.SessionOptions().use { options ->
                    scope.own(env.createSession(file.path, options))
                }
                SileroVad(env, session).also { scope.commit() }
            }
        }
    }
}
