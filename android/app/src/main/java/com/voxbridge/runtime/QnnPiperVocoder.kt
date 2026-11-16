package com.voxbridge.runtime

import ai.onnxruntime.*
import com.voxbridge.core.model.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** QNN HTP waveform stage. Dynamic phonemization/duration/flow explicitly remains CPU. */
class QnnPiperVocoder(private val env: OrtEnvironment, private val paths: ModelPaths) : PiperVocoder {
    private var language: Language? = null
    private var session: OrtSession? = null
    private var input: OnnxTensor? = null
    private var valid: OnnxTensor? = null
    private var output: OnnxTensor? = null
    private var inputName: String? = null
    private val inputBytes = ByteBuffer.allocateDirect(192 * 64 * 4).order(ByteOrder.nativeOrder())
    private val validBytes = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    private val outputBytes = ByteBuffer.allocateDirect(64 * 256 * 4).order(ByteOrder.nativeOrder())
    private var closed = false
    private var kokoro: KokoroViTts? = null

    /** Kokoro-VI replaces the Piper VI voice whenever its bundle is installed (ModelPaths.kokoroViDir). */
    @Synchronized override fun replacementVoice(language: Language): ReplacementVoice? {
        if (language != Language.VI || closed || !KokoroViTts.ready(paths.kokoroViDir)) return null
        return kokoro ?: KokoroViTts(env, paths.kokoroViDir).also { kokoro = it }
    }

    private fun prepare(lang: Language) {
        check(!closed)
        if (language == lang && session != null) return
        release()
        val tag = when(lang) { Language.VI -> "vi"; Language.EN -> "en"; else -> error("Unsupported QNN voice") }
        val graph = File(paths.root, "text_to_speech/piper-qnn/$tag/model.onnx")
        val loaded = OrtSession.SessionOptions().use { options ->
            options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
                // AI Hub precompiled_qnn_onnx graphs contain an external EPContext/model.bin.
                // Tell ORT to load that context instead of treating EPContext as a normal op.
                options.addConfigEntry("ep.context_enable", "1")
            options.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
            env.createSession(graph.path, options)
        }
        session = loaded
        try {
            check(loaded.inputNames.size == 2 && loaded.inputNames.contains("valid_frames"))
            inputName = loaded.inputNames.single { it != "valid_frames" }
            check(loaded.outputNames.size == 1)
            input = OnnxTensor.createTensor(env, inputBytes, longArrayOf(1,192,64), OnnxJavaType.FLOAT)
            valid = OnnxTensor.createTensor(env, validBytes, longArrayOf(1), OnnxJavaType.INT32)
            output = OnnxTensor.createTensor(env, outputBytes, longArrayOf(1,1,1,16384), OnnxJavaType.FLOAT)
            language = lang
        } catch (t: Throwable) { release(); throw t }
    }
    @Synchronized override fun synthesize(latent: FloatArray, language: Language): FloatArray {
        prepare(language)
        val waveform = PiperTiles.render(latent) { tile, frames ->
            inputBytes.asFloatBuffer().put(tile)
            validBytes.asIntBuffer().put(0, frames)
            checkNotNull(session).run(mapOf(checkNotNull(inputName) to checkNotNull(input), "valid_frames" to checkNotNull(valid)),
                mapOf(checkNotNull(session).outputNames.single() to checkNotNull(output))).use {
                FloatArray(16384).also { values -> checkNotNull(output).floatBuffer.get(values) }
            }
        }
        return PiperTiles.scaleSilence(waveform, 22050)
    }
    private fun release() {
        try { input?.close(); valid?.close(); output?.close() } finally {
            input=null;valid=null;output=null;language=null;inputName=null;session?.close();session=null
        }
    }
    @Synchronized override fun close() { if (!closed) { closed=true; try { release() } finally { kokoro?.close(); kokoro=null } } }
}
