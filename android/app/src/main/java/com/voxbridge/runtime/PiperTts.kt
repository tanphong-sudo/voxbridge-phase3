package com.voxbridge.runtime

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.voxbridge.core.model.Language
import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.audio.TtsEngine
import com.voxbridge.core.normalize.SpokenTextNormalizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline speech output (MODEL_MANIFEST §4): cached PCM for the high-frequency safety-phrase bank
 * (avoids live synthesis), Piper (VITS) via sherpa-onnx for dynamic sentences.
 * Actual end-of-speech to first-audio latency must be measured on the target device.
 *
 * Implemented against the REAL sherpa-onnx Kotlin API (verified from source):
 *   OfflineTts(assetManager, OfflineTtsConfig(model = OfflineTtsModelConfig(vits = ...)))
 *   generate(text, sid, speed) : GeneratedAudio(samples: FloatArray, sampleRate: Int)
 *
 * IMPORTANT: Piper "medium" voices synthesize at the voice's native rate (typically 22050 Hz), NOT
 * 16 kHz. The returned [PcmAudio] carries its own sampleRate so playback never assumes 16 kHz.
 *
 * Data dependency (documented, not guessed): sherpa-onnx Piper needs the sherpa-packaged voice
 * bundle (`<voice>.onnx` + `tokens.txt` + `espeak-ng-data/`). MODEL_MANIFEST §4 records the layout;
 * fetch_models.py places it under text_to_speech/piper/<voice>/.
 */
class PiperTts(
    private val paths: ModelPaths,
    // Three threads improved paired VPS first-chunk p50/p95 on all four BTC200 groups.
    // Keep configurable: Snapdragon must compare 2 vs 3 under the complete app workload.
    private val numThreads: Int = 3,
    private val vocoder: PiperVocoder? = null,
) : TtsEngine {
    init { require(numThreads > 0) { "TTS thread count must be positive" } }
    private var closed = false
    private var warmedLanguage: Language? = null

    // One resident voice bounds native weights. Swapping languages frees the previous engine.
    // The same lock covers generate and close, not merely map lookup.
    private val engines = HashMap<Language, OfflineTts>()

    private fun engineFor(language: Language): OfflineTts = synchronized(engines) {
        check(!closed) { "TTS is closed" }
        if (!engines.containsKey(language)) {
            engines.values.forEach { it.free() }
            engines.clear()
            warmedLanguage = null
        }
        engines.getOrPut(language) {
            // Bundle dir names == sherpa-onnx release bundle names (what fetch_models.py extracts), so
            // the Python write side and this load side share one string and cannot drift. All four
            // voices are plain Piper bundles (vits + tokens.txt + espeak-ng-data/): ZH = piper
            // zh_CN-huayan-medium, KO = mimic3 ko_KO-kss_low — same contract, same engine path.
            val dir = when (language) {
                Language.VI -> paths.piperVoiceDir("vits-piper-vi_VN-vais1000-medium")
                Language.EN -> paths.piperVoiceDir("vits-piper-en_US-lessac-medium")
                Language.ZH -> paths.piperVoiceDir("vits-piper-zh_CN-huayan-medium")
                Language.KO -> paths.piperVoiceDir("vits-mimic3-ko_KO-kss_low")
            }
            // The bundle's model file is the single *.onnx inside it (name varies: <voice>.onnx) — glob
            // rather than assume, so we never depend on a renamed file.
            val model = (if (vocoder != null) File(dir, "frontend.onnx").takeIf { it.isFile } else
                dir.listFiles { f -> f.extension == "onnx" && f.name != "frontend.onnx" }?.firstOrNull())
                ?: error("Piper voice missing at $dir — run fetch_models.py --piper")
            val vits = OfflineTtsVitsModelConfig(
                model = model.path,
                tokens = File(dir, "tokens.txt").path,
                dataDir = File(dir, "espeak-ng-data").path,
                // VI uses the voice card's VITS noise (0.667 / 0.8) and 10% slower speech: zero noise
                // made vais1000 sound flat and hard to follow, and listening A/B on real EnViT5
                // output (output/tts-ab-20261116, config D) picked this. Safety prompts are cached
                // PCM, so only live translations vary run to run. EN keeps the deterministic
                // zero-noise rendering that passed the Android runtime regression.
                noiseScale = if (language == Language.VI) VI_NOISE_SCALE else 0f,
                noiseScaleW = if (language == Language.VI) VI_NOISE_SCALE_W else 0f,
                lengthScale = if (language == Language.VI) VI_LENGTH_SCALE else 1f,
            )
            OfflineTts(
                assetManager = null,  // models live in filesDir, loaded from path (newFromFile)
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = numThreads, provider = "cpu"),
                ),
            )
        }
    }

    /**
     * Load and exercise the target voice before the UI accepts a new recording. The warm-up
     * waveform is discarded: only SpeechOutputPolicy may authorize actual speech output.
     * One resident voice is retained; a failed warm-up is never marked as completed.
     */
    fun preload(language: Language): Long = synchronized(engines) {
        val started = System.nanoTime()
        vocoder?.replacementVoice(language)?.let { voice ->
            voice.preload()
            if (warmedLanguage != language) {
                val audio = voice.synthesize(if (language == Language.VI) "Xin chào." else "Hello.")
                check(audio.isNotEmpty() && audio.all { it.isFinite() }) { "TTS warm-up produced invalid audio" }
                warmedLanguage = language
            }
            return@synchronized (System.nanoTime() - started) / 1_000_000
        }
        val engine = engineFor(language)
        if (warmedLanguage != language) {
            val text = when (language) {
                Language.VI -> "Xin chào."
                Language.EN -> "Hello."
                Language.ZH -> "你好。"
                Language.KO -> "안녕하세요."
            }
            val audio = generate(engine, text, language)
            check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() } && audio.sampleRate > 0) {
                "TTS warm-up produced invalid audio"
            }
            warmedLanguage = language
        }
        (System.nanoTime() - started) / 1_000_000
    }

    /** Returns the pre-rendered cached phrase + its rate, or null if not cached. */
    fun cachedPhrase(id: String): PcmAudio? {
        val f = paths.cachedPhrase(id)
        if (!f.exists()) return null
        val rate = paths.cachedPhraseRate(id) ?: return null
        return PcmAudio(f.readBytes(), rate)
    }

    /** Synthesize an arbitrary sentence live. */
    override fun synthesize(text: String, language: Language): PcmAudio = synchronized(engines) {
        require(text.length <= 2000) { "TTS text exceeds safe 2000-character limit" }
        // Proposal §4.2/§4.5: verbalize measurement values/units immediately before TTS. The
        // verifier sees the original machine-readable translation; only the spoken surface changes.
        val spokenText = SpokenTextNormalizer.normalize(text, language)
        vocoder?.replacementVoice(language)?.let { voice ->
            return PcmAudio(floatsToPcm16(voice.synthesize(spokenText)), voice.sampleRate)
        }
        val audio = generate(engineFor(language), spokenText, language)
        return PcmAudio(floatsToPcm16(audio.samples), audio.sampleRate)
    }

    override fun close() = synchronized(engines) {
        if (!closed) { closed = true; warmedLanguage = null; try { engines.values.forEach { it.free() } } finally { engines.clear(); vocoder?.close() } }
    }

    private fun generate(engine: OfflineTts, text: String, language: Language): GeneratedAudio {
        val stage = vocoder ?: return engine.generate(text = text, sid = 0, speed = 1f)
        val features = engine.generateWithConfig(text, GenerationConfig(silenceScale = 1f, speed = 1f, sid = 0))
        val samples = stage.synthesize(features.samples, language)
        return GeneratedAudio(samples, features.sampleRate)
    }

    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }

    private companion object {
        // vi_VN-vais1000-medium.onnx.json inference defaults; length 1.1 = 10% slower speech.
        const val VI_NOISE_SCALE = 0.667f
        const val VI_NOISE_SCALE_W = 0.8f
        const val VI_LENGTH_SCALE = 1.1f
    }
}
