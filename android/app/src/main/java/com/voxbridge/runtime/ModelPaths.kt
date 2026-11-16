package com.voxbridge.runtime

import java.io.File
import org.json.JSONObject

data class ModelComponentStatus(
    val label: String,
    val ready: Boolean,
    val missing: List<String> = emptyList(),
)

/** Phase-2 VI/EN artifact contract resolved under one external/bundled model root. */
class ModelPaths(
    val root: File,
    val profile: ModelProfile = ModelProfile.VI_EN_PHASE2,
    val asrLanguage: String = "vi",
) {
    init { require(asrLanguage in profile.asrModels) { "Unsupported ASR language: $asrLanguage" } }
    fun forLanguage(language: String) = ModelPaths(root, profile, language)
    fun asrModelName(language: String = asrLanguage): String = profile.asrModels.getValue(language)

    val vad = File(root, "vad/silero_vad.onnx")
    fun translationDir(src: String, tgt: String) = File(root, "translation/opus-mt-$src-$tgt")
    val whisperQnnDir = File(root, "speech_to_text/${asrModelName()}-qnn")
    val whisperOnnxDir = File(root, "speech_to_text/${asrModelName()}-onnx")
    val melFilterFile = File(whisperOnnxDir, "mel_80_201.f32.bin")

    /** A sherpa-onnx Piper voice bundle dir: <voice>.onnx (or model.onnx) + tokens.txt + espeak-ng-data/. */
    fun piperVoiceDir(voice: String) = File(root, "text_to_speech/piper/$voice")
    fun qnnPiperReady(language: String): Boolean {
        val model = File(root, "text_to_speech/piper-qnn/$language/model.onnx")
        return model.isFile && File(model.parentFile, "model.bin").isFile
    }

    /** Optional Kokoro-VI bundle (KokoroViTts); when complete it replaces the Piper VI voice on QNN profiles. */
    val kokoroViDir = File(root, "text_to_speech/kokoro-vi")

    fun cachedPhrase(id: String) = File(root, "text_to_speech/cache/$id.pcm")

    /** Sample rate sidecar written next to a cached phrase (Piper voice-native, e.g. 22050). */
    fun cachedPhraseRate(id: String): Int? =
        File(root, "text_to_speech/cache/$id.sr").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    /** True when the QNN context-binary pair (NPU path) is present — optional for bring-up. */
    fun qnnPairReady(): Boolean = qnnContextPair() != null

    /** The QNN ctx wrappers (encoder, decoder), matched by filename, or null when absent. */
    fun qnnContextPair(): Pair<File, File>? {
        val files = whisperQnnDir.listFiles { f -> f.name.endsWith("_ctx.onnx") }?.toList().orEmpty()
        val enc = files.firstOrNull { it.name.contains("enc", ignoreCase = true) }
        val dec = files.firstOrNull { it.name.contains("dec", ignoreCase = true) }
        if (enc != null && dec != null) return enc to dec
        // AI Hub precompiled_qnn_onnx artifacts are shipped as one model.onnx plus model.bin
        // per graph. Keep the directory contract explicit so decoder_joint is never mistaken for
        // the encoder graph.
        val packagedEnc = whisperQnnDir.resolve("encoder/model.onnx")
        val packagedDec = whisperQnnDir.resolve("decoder_joint/model.onnx")
        return if (packagedEnc.isFile && packagedEnc.resolveSibling("model.bin").isFile &&
            packagedDec.isFile && packagedDec.resolveSibling("model.bin").isFile) packagedEnc to packagedDec else null
    }

    /**
     * Graph file present under either variant the loader accepts. OnnxTranslator prefers
     * `<stem>_quantized.onnx` (INT8) and falls back to the fp32 `<stem>.onnx`, so an INT8-only
     * bundle is complete — the Colab build drops the fp32 twins to keep the APK lean.
     */
    private fun hasGraph(dir: File, stem: String): Boolean =
        dir.resolve("${stem}_quantized.onnx").exists() || dir.resolve("$stem.onnx").exists()

    private fun translationReady(dir: File): Boolean =
        hasGraph(dir, "encoder_model") &&
            (hasGraph(dir, "decoder_model") || hasGraph(dir, "decoder_with_past_model")) &&
            dir.resolve("source.spm").exists() &&
            dir.resolve("target.spm").exists() &&
            dir.resolve("vocab.json").exists()

    private fun translationMissing(dir: File, tag: String): List<String> = buildList {
        if (!hasGraph(dir, "encoder_model")) add("$tag/encoder_model[_quantized].onnx")
        for (stem in listOf("decoder_model", "decoder_with_past_model"))
            if (!hasGraph(dir, stem)) add("$tag/${stem}[_quantized].onnx")
        if (!dir.resolve("source.spm").exists()) add("$tag/source.spm")
        if (!dir.resolve("target.spm").exists()) add("$tag/target.spm")
        if (!dir.resolve("vocab.json").exists()) add("$tag/vocab.json")
    }

    /** True when the auto-download/export CPU set is present (NPU binary is separate, optional). */
    fun cpuSetReady(): Boolean = missing().isEmpty()

    /** Every file the CPU pipeline needs that is absent — shown on the setup screen. */
    fun missing(): List<String> = buildList {
        if (!vad.isFile || vad.length() == 0L) add("vad/silero_vad.onnx")
        addAll(staticTranslationMissing())
        for (model in profile.asrModels.values.distinct()) {
            val dir = File(root, "speech_to_text/$model-onnx")
            val required = if (model == "parakeet-v2") listOf("tokens.json", "mel_128_257.f32", "hann_400.f32", "frontend.json") else if (profile.qnnAsr) listOf("whisper_tokens.json", "mel_80_201.f32.bin") else
                listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx", "whisper_tokens.json", "mel_80_201.f32.bin", "shared_weights.bin")
            for (file in required) {
                if (!dir.resolve(file).isFile || dir.resolve(file).length() == 0L) add("$model-onnx/$file")
            }
            if (model != "parakeet-v2" && !dir.resolve("vocab.json").isFile && !dir.resolve("tokenizer.json").isFile)
                add("$model-onnx/vocab.json or tokenizer.json")
        }
        if (profile.qnnAsr) for (lang in profile.asrModels.keys) {
            if (!forLanguage(lang).qnnPairReady()) add("speech_to_text/${asrModelName(lang)}-qnn/<encoder|decoder>_ctx.onnx")
        }
        addAll(piperMissing())
        if(profile.qnnAsr) addAll(qnnPiperMissing())
        addAll(safetyCacheMissing())
        addAll(integrityErrors())
    }

    /** Piper voice bundles the TTS engine opens lazily but the pipeline needs to actually speak. */
    fun piperMissing(): List<String> = buildList {
        for (voice in PIPER_VOICES) {
            val dir = piperVoiceDir(voice)
            if (!dir.isDirectory) { add("text_to_speech/piper/$voice/"); continue }
            if (dir.listFiles { f -> f.extension == "onnx" && nonempty(f) }.isNullOrEmpty()) add("piper/$voice/<voice>.onnx")
            if (!nonempty(dir.resolve("tokens.txt"))) add("piper/$voice/tokens.txt")
            if (!dir.resolve("espeak-ng-data").isDirectory) add("piper/$voice/espeak-ng-data/")
        }
    }

    private fun qnnPiperMissing(): List<String> = buildList {
        for ((lang,voice) in listOf("vi","en").zip(PIPER_VOICES)) {
            if(!nonempty(File(piperVoiceDir(voice),"frontend.onnx"))) add("piper/$voice/frontend.onnx")
            if(!qnnPiperReady(lang)) add("piper-qnn/$lang/model.onnx + model.bin")
        }
    }

    private fun nonempty(file: File) = file.isFile && file.length() > 0

    private fun staticTranslationMissing(): List<String> = buildList {
        val base = File(root,"translation/qnn")
        val file = File(base,"models/models.json")
        if (!nonempty(file)) { add("translation/qnn/models/models.json"); return@buildList }
        val models = runCatching { JSONObject(file.readText()) }.getOrElse { add("Invalid QNN MT manifest"); return@buildList }
        for (name in listOf(profile.mtBackend)) {
            for (part in listOf("encoder","decoder")) {
                val entry=models.optJSONObject("$name/$part")
                if(entry==null){add("Missing $name/$part QNN manifest entry");continue}
                val files=entry.optJSONArray("files")
                if(files==null){add("Missing $name/$part file checksums");continue}
                for(i in 0 until files.length()) {
                    val relative="models/"+files.getJSONObject(i).getString("path")
                    val actual=File(base,relative).canonicalFile
                    if(!actual.toPath().startsWith(base.canonicalFile.toPath()) || !nonempty(actual)) add("translation/qnn/$relative")
                }
            }
            for (sidecar in listOf("tokenizer.json","generation_config.json"))
                if(!nonempty(File(base,"tokenizers/$name/$sidecar")))add("translation/qnn/tokenizers/$name/$sidecar")
        }
    }

    private fun safetyCacheMissing(): List<String> = buildList {
        for (id in SAFETY_PHRASES) {
            val pcm = cachedPhrase(id)
            if (!nonempty(pcm) || pcm.length() % 2 != 0L) add("text_to_speech/cache/$id.pcm")
            val rate = runCatching { cachedPhraseRate(id) }.getOrNull()
            if (rate == null || rate <= 0) add("text_to_speech/cache/$id.sr")
        }
    }

    fun componentStatus(): List<ModelComponentStatus> = buildList {
        fun component(label: String, missing: List<String>) =
            ModelComponentStatus(label, missing.isEmpty(), missing)

        add(component("Silero", if (nonempty(vad)) emptyList() else listOf("vad/silero_vad.onnx")))
        for ((lang, model) in profile.asrModels) {
            val dir = File(root, "speech_to_text/$model-onnx")
            val missing = buildList {
                val required = if (model == "parakeet-v2") listOf("tokens.json", "mel_128_257.f32", "hann_400.f32", "frontend.json") else if (profile.qnnAsr) listOf("whisper_tokens.json", "mel_80_201.f32.bin") else listOf(
                    "encoder_model.onnx",
                    "decoder_model.onnx",
                    "decoder_with_past_model.onnx",
                    "whisper_tokens.json",
                    "mel_80_201.f32.bin",
                    "shared_weights.bin",
                )
                for(file in required) if (!nonempty(dir.resolve(file))) add("speech_to_text/$model-onnx/$file")
                if (model != "parakeet-v2" && !nonempty(dir.resolve("vocab.json")) && !nonempty(dir.resolve("tokenizer.json"))) {
                    add("speech_to_text/$model-onnx/vocab.json or tokenizer.json")
                }
                if(profile.qnnAsr && !forLanguage(lang).qnnPairReady()) add("speech_to_text/$model-qnn/<encoder|decoder>_ctx.onnx")
            }
            add(component("${lang.uppercase()} ASR ($model)", missing))
        }
        add(component("${profile.mtBackend} QNN MT",staticTranslationMissing()))
        for ((index, voice) in PIPER_VOICES.withIndex()) {
            val lang = if (index == 0) "Vietnamese TTS" else "English TTS"
            val dir = piperVoiceDir(voice)
            val missing = buildList {
                if (!dir.isDirectory) add("text_to_speech/piper/$voice/")
                else {
                    if (dir.listFiles { f -> f.extension == "onnx" && nonempty(f) }.isNullOrEmpty()) add("text_to_speech/piper/$voice/<voice>.onnx")
                    if (!nonempty(dir.resolve("tokens.txt"))) add("text_to_speech/piper/$voice/tokens.txt")
                    if (!dir.resolve("espeak-ng-data").isDirectory) add("text_to_speech/piper/$voice/espeak-ng-data/")
                }
            }
            add(component(lang, missing))
        }
        if(profile.qnnAsr) add(component("QNN Piper waveform",qnnPiperMissing()))
        add(component("Safety phrase cache", safetyCacheMissing()))
        add(component("Model integrity", integrityErrors()))
    }

    private fun integrityErrors(): List<String> {
        val trees = profile.asrModels.values.distinct().map { File(root, "speech_to_text/$it-onnx") } +
            PIPER_VOICES.map { piperVoiceDir(it) } +
            (if(profile.qnnAsr) profile.asrModels.values.distinct().map { File(root,"speech_to_text/$it-qnn") } else emptyList()) +
            listOf(File(root,"translation/qnn")) +
            (if(profile.qnnAsr) listOf(File(root,"text_to_speech/piper-qnn")) else emptyList()) +
            (if(profile.qnnAsr && kokoroViDir.isDirectory) listOf(kokoroViDir) else emptyList())
        val required = listOf("vad/silero_vad.onnx") + SAFETY_PHRASES.flatMap {
            listOf("text_to_speech/cache/$it.pcm", "text_to_speech/cache/$it.sr")
        } + trees.flatMap { tree ->
            tree.walkTopDown().filter { it.isFile && !it.name.startsWith('.') }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        return ModelIntegrity.validate(root, required)
    }

    companion object {
        private val SAFETY_PHRASES = listOf("ask_repeat_vi", "ask_repeat_en", "no_speech_vi", "no_speech_en")
        const val PROFILE = "SMOKE_VI_EN"
        val PIPER_VOICES = listOf(
            "vits-piper-vi_VN-vais1000-medium",
            "vits-piper-en_US-lessac-medium",
        )
        val MT_DIRECTIONS = listOf("vi" to "en", "en" to "vi")
    }
}
