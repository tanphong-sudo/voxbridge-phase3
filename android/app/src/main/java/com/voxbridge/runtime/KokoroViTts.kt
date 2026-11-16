package com.voxbridge.runtime

import ai.onnxruntime.OnnxTensor
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voxbridge.core.tts.KokoroViPhonemizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro-VI (contextboxai/Kokoro-Vietnamese, voice diem_trinh) split like the Piper QNN path:
 * frontend.onnx on CPU (ALBERT, durations, alignment, F0/N, NSF harmonic source + STFT, all
 * dynamic) and the ISTFTNet decoder as static QNN HTP graphs `decoder<T>/model.onnx` (T frames at
 * 40 fps). Decoder graphs zero every activation past `valid_frames` and take InstanceNorm
 * statistics over valid frames only, so a padded run equals the unpadded decoder
 * (model-tools-python/qaihub/export_kokoro_split.py). The smallest graph that fits is used.
 */
class KokoroViTts(
    private val env: OrtEnvironment,
    private val dir: File,
    private val cpuThreads: Int = 4,
) : ReplacementVoice {
    override val sampleRate = SAMPLE_RATE

    private class Decoder(val frames: Int, val session: OrtSession) {
        val asr = direct(CHANNELS * frames)
        val f0 = direct(2 * frames)
        val n = direct(2 * frames)
        val har = direct(HAR_BINS * (HAR_PER_FRAME * frames + 1))
        val style = direct(STYLE)
        val valid = direct(1)
        val output = session.outputNames.single()
    }

    private var phonemizer: KokoroViPhonemizer? = null
    private var voice: FloatArray? = null
    private var frontend: OrtSession? = null
    private var decoders: List<Decoder> = emptyList()
    private var closed = false

    @Synchronized override fun preload() {
        check(!closed) { "Kokoro TTS is closed" }
        if (frontend != null) return
        try {
            phonemizer = KokoroViPhonemizer(
                KokoroViPhonemizer.parseLexicon(File(dir, "lexicon.tsv").readLines().asSequence()),
                KokoroViPhonemizer.parseVocab(File(dir, "vocab.tsv").readLines().asSequence()),
            )
            voice = readVoice(File(dir, "voice.f32"))
            frontend = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(cpuThreads)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(File(dir, "frontend.onnx").path, options)
            }
            decoders = decoderSizes(dir).map { frames -> Decoder(frames, openQnn(File(dir, "decoder$frames/model.onnx"))) }
            check(decoders.isNotEmpty()) { "No Kokoro decoder graphs in $dir" }
            // First HTP execution pays graph finalization; do it here, not on the first turn.
            for (decoder in decoders) runDecoder(decoder, FrontOutput(decoder.frames / 2), 0, decoder.frames / 2)
        } catch (t: Throwable) { release(); throw t }
    }

    @Synchronized override fun synthesize(spokenText: String): FloatArray {
        preload()
        val g2p = checkNotNull(phonemizer)
        val phonemes = g2p.phonemize(spokenText)
        if (phonemes.isBlank()) return FloatArray(0)
        if (phonemes.codePointCount(0, phonemes.length) > KokoroViPhonemizer.MAX_PHONEMES) {
            val cut = spokenText.lastIndexOf(' ', spokenText.length / 2).takeIf { it > 0 } ?: (spokenText.length / 2)
            return synthesize(spokenText.substring(0, cut)) + synthesize(spokenText.substring(cut))
        }
        val started = System.nanoTime()
        val front = runFrontend(g2p.inputIds(phonemes), phonemes.codePointCount(0, phonemes.length))
        val decodeStart = System.nanoTime()
        val audio = decode(front)
        Log.i(TAG, "frames=${front.frames} frontendMs=${(decodeStart - started) / 1_000_000} " +
            "decoderMs=${(System.nanoTime() - decodeStart) / 1_000_000}")
        return audio
    }

    private class FrontOutput(val frames: Int) {
        var asr = FloatArray(CHANNELS * frames)
        var f0 = FloatArray(2 * frames)
        var n = FloatArray(2 * frames)
        var har = FloatArray(HAR_BINS * (HAR_PER_FRAME * frames + 1))
        var style = FloatArray(STYLE)
    }

    private fun runFrontend(ids: LongArray, phonemeCount: Int): FrontOutput {
        val ref = checkNotNull(voice).copyOfRange((phonemeCount - 1) * REF, phonemeCount * REF)
        val session = checkNotNull(frontend)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(ref), longArrayOf(1, REF.toLong())).use { refS ->
                OnnxTensor.createTensor(env, floatArrayOf(1f)[0]).use { speed ->
                    session.run(mapOf("input_ids" to input, "ref_s" to refS, "speed" to speed)).use { result ->
                        fun floats(name: String): FloatArray {
                            val tensor = result.get(name).get() as OnnxTensor
                            return FloatArray(tensor.floatBuffer.remaining()).also { tensor.floatBuffer.get(it) }
                        }
                        val asr = floats("asr")
                        val frames = asr.size / CHANNELS
                        check(frames > 0 && frames <= MAX_FRAMES) { "Kokoro duration out of range: $frames" }
                        return FrontOutput(frames).also {
                            it.asr = asr; it.f0 = floats("f0"); it.n = floats("n"); it.har = floats("har")
                            it.style = ref.copyOf(STYLE)
                            check(it.f0.size == 2 * frames && it.har.size == HAR_BINS * (HAR_PER_FRAME * frames + 1)) {
                                "Kokoro frontend shape mismatch"
                            }
                        }
                    }
                }
            }
        }
    }

    /** One graph when it fits; otherwise overlapping windows of the largest graph, crossfaded. */
    private fun decode(front: FrontOutput): FloatArray {
        val fit = decoders.firstOrNull { it.frames >= front.frames }
        if (fit != null) return runDecoder(fit, front, 0, front.frames)
        val big = decoders.last()
        val step = big.frames - OVERLAP_FRAMES
        val out = FloatArray(front.frames * HOP)
        var start = 0
        while (true) {
            val end = minOf(front.frames, start + big.frames)
            val wave = runDecoder(big, front, start, end)
            val fade = if (start == 0) 0 else OVERLAP_FRAMES * HOP
            for (i in wave.indices) {
                val at = start * HOP + i
                out[at] = if (i < fade) out[at] * (1f - i.toFloat() / fade) + wave[i] * (i.toFloat() / fade) else wave[i]
            }
            if (end == front.frames) return out
            start += step
        }
    }

    private fun runDecoder(decoder: Decoder, front: FrontOutput, start: Int, end: Int): FloatArray {
        val frames = end - start
        val total = decoder.frames
        fun fill(buffer: ByteBuffer, source: FloatArray, rows: Int, width: Int, from: Int, count: Int, padded: Int) {
            val floats = buffer.asFloatBuffer()
            floats.clear()
            val zeros = FloatArray(padded - count)
            for (row in 0 until rows) {
                floats.put(source, row * width + from, count)
                floats.put(zeros)
            }
        }
        fill(decoder.asr, front.asr, CHANNELS, front.frames, start, frames, total)
        fill(decoder.f0, front.f0, 1, 2 * front.frames, 2 * start, 2 * frames, 2 * total)
        fill(decoder.n, front.n, 1, 2 * front.frames, 2 * start, 2 * frames, 2 * total)
        fill(decoder.har, front.har, HAR_BINS, HAR_PER_FRAME * front.frames + 1, HAR_PER_FRAME * start,
            HAR_PER_FRAME * frames + 1, HAR_PER_FRAME * total + 1)
        decoder.style.asFloatBuffer().put(front.style, 0, STYLE)
        decoder.valid.asFloatBuffer().put(0, frames.toFloat())
        val inputs = linkedMapOf(
            "asr" to tensor(decoder.asr, 1, CHANNELS, total),
            "f0" to tensor(decoder.f0, 1, 2 * total),
            "n" to tensor(decoder.n, 1, 2 * total),
            "har" to tensor(decoder.har, 1, HAR_BINS, HAR_PER_FRAME * total + 1),
            "style" to tensor(decoder.style, 1, STYLE),
            "valid_frames" to tensor(decoder.valid, 1),
        )
        try {
            decoder.session.run(inputs).use { result ->
                val wave = (result.get(decoder.output).get() as OnnxTensor).floatBuffer
                check(wave.remaining() >= total * HOP) { "Kokoro decoder output too short" }
                val samples = FloatArray(frames * HOP).also { wave.get(it) }
                check(samples.all { it.isFinite() }) { "Kokoro decoder produced non-finite audio" }
                return samples
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun tensor(buffer: ByteBuffer, vararg shape: Int): OnnxTensor =
        OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape.map { it.toLong() }.toLongArray())

    private fun openQnn(graph: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
        options.addConfigEntry("ep.context_enable", "1")
        options.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
        env.createSession(graph.path, options)
    }

    private fun release() {
        try { frontend?.close(); decoders.forEach { it.session.close() } } finally {
            frontend = null; decoders = emptyList(); phonemizer = null; voice = null
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; release() } }

    companion object {
        const val SAMPLE_RATE = 24000
        private const val TAG = "KokoroViTts"
        private const val HOP = 600
        private const val CHANNELS = 512
        private const val HAR_BINS = 22
        private const val HAR_PER_FRAME = 120
        private const val STYLE = 128
        private const val REF = 256
        private const val VOICE_ROWS = 510
        private const val OVERLAP_FRAMES = 24
        private const val MAX_FRAMES = 4000

        fun ready(dir: File): Boolean =
            listOf("frontend.onnx", "lexicon.tsv", "vocab.tsv", "voice.f32").all { File(dir, it).let { f -> f.isFile && f.length() > 0 } } &&
                decoderSizes(dir).isNotEmpty()

        /** Sizes of complete `decoder<T>/model.onnx + model.bin` pairs, ascending. */
        fun decoderSizes(dir: File): List<Int> =
            dir.listFiles { f -> f.isDirectory && f.name.matches(Regex("decoder\\d+")) }.orEmpty()
                .filter { File(it, "model.onnx").isFile && File(it, "model.bin").isFile }
                .map { it.name.removePrefix("decoder").toInt() }.filter { it > OVERLAP_FRAMES }.sorted()

        private fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

        private fun readVoice(file: File): FloatArray {
            val bytes = file.readBytes()
            require(bytes.size == VOICE_ROWS * REF * 4) { "Kokoro voice pack has unexpected size ${bytes.size}" }
            return FloatArray(VOICE_ROWS * REF).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
    }
}
