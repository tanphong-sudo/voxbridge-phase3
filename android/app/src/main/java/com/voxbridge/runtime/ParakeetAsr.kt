package com.voxbridge.runtime

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.model.Language
import org.json.JSONArray
import java.io.File

/** Frozen Parakeet v2 weights and TDT greedy decoding; no word boosting or audio gain. */
class ParakeetAsr private constructor(
    private val mel: ParakeetMel,
    private val engine: ParakeetEngine,
    private val pieces: List<String>,
) : AutoCloseable {
    @Synchronized fun transcribeDetailed(pcm: FloatArray, src: Language, tgt: Language): DetailedTranscript {
        require(src == Language.EN && tgt == Language.VI)
        val start = System.nanoTime()
        val features = mel.compute(pcm)
        val melMs = (System.nanoTime() - start) / 1_000_000
        val decoded = engine.recognize(features)
        check(!decoded.tokenLimit) { "Parakeet decoding reached its token budget" }
        val text = decoded.tokens.joinToString("") { pieces[it] }.replace('▁', ' ').trim()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        // Greedy TDT argmax does not provide a calibrated utterance confidence.
        return DetailedTranscript(
            TranscriptResult(src, tgt, text, 0.0, asrScoreCalibrated = false),
            AsrDiagnostics("parakeet-v2", "QNN_NPU", "PARAKEET_TDT_GREEDY", -1, -1,
                pcm.size, pcm.size.toLong() * 1000 / 16000, text, null, null,
                0.0, decoded.tokens.size, "encoded_frames_exhausted", elapsed, melLatencyMs = melMs),
        )
    }
    override fun close() = engine.close()

    companion object {
        fun load(env: OrtEnvironment, paths: ModelPaths): ParakeetAsr {
            val dir = paths.whisperOnnxDir
            val vocab = JSONArray(File(dir, "tokens.json").readText())
            check(vocab.length() == 1024) { "Parakeet vocabulary mismatch" }
            val mel = ParakeetMel(File(dir, "mel_128_257.f32"), File(dir, "hann_400.f32"))
            val pair = checkNotNull(paths.qnnContextPair()) { "Parakeet QNN context pair missing" }
            fun session(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(3)
                options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
                // AI Hub precompiled_qnn_onnx graphs contain an external EPContext/model.bin.
                // Tell ORT to load that context instead of treating EPContext as a normal op.
                options.addConfigEntry("ep.context_enable", "1")
                options.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
                env.createSession(file.path, options)
            }
            val encoder = session(pair.first)
            val decoder = try { session(pair.second) } catch (t: Throwable) { encoder.close(); throw t }
            val engine = ParakeetEngine(env, encoder, decoder)
            return try {
                // Warm recurrent states and encoder outside the user's release timer.
                engine.recognize(mel.compute(FloatArray(16000)))
                ParakeetAsr(mel, engine, List(vocab.length()) { vocab.getString(it) })
            } catch (t: Throwable) { engine.close(); throw t }
        }
    }
}
