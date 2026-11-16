package com.k2fsa.sherpa.onnx

/** Native JNI boundary fake only. Production PiperTts lifecycle/normalization executes unchanged. */
data class GenerationConfig(val silenceScale: Float = .2f, val speed: Float = 1f, val sid: Int = 0)
data class GeneratedAudio(val samples: FloatArray, val sampleRate: Int)
data class OfflineTtsVitsModelConfig(
    val model: String, val tokens: String, val dataDir: String,
    val noiseScale: Float, val noiseScaleW: Float, val lengthScale: Float,
)
data class OfflineTtsModelConfig(val vits: OfflineTtsVitsModelConfig, val numThreads: Int, val provider: String)
data class OfflineTtsConfig(val model: OfflineTtsModelConfig)
class OfflineTts(assetManager: Any?, val config: OfflineTtsConfig) {
    val texts = mutableListOf<String>()
    var frees = 0
    init { instances.add(this) }
    fun generate(text: String, sid: Int, speed: Float): GeneratedAudio {
        check(frees == 0)
        if (failGeneration) error("native synthesis failed")
        texts += text
        return GeneratedAudio(floatArrayOf(0.25f, -0.25f), 22050)
    }
    fun generateWithConfig(text: String, config: GenerationConfig): GeneratedAudio = generate(text, config.sid, config.speed)
    fun free() { frees++ }
    companion object {
        val instances = mutableListOf<OfflineTts>()
        var failGeneration = false
    }
}
