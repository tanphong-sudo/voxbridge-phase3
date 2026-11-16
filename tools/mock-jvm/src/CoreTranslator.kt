package com.voxbridge.core.translate

import com.voxbridge.core.model.Language

/**
 * Result of a single neural machine-translation pass.
 *
 * The neural model owns translation. [translationScore] is used when the runtime exposes one;
 * otherwise it stays null and the pipeline relies on the safety verifier plus the benchmark
 * calibration instead of fabricating a score.
 */
data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val modelName: String,
    val translationScore: Double? = null
) {
    init {
        translationScore?.let {
            require(it in 0.0..1.0) { "translationScore must be between 0 and 1" }
        }
    }
}

/**
 * Abstraction over the on-device neural translator (OPUS-MT exported to ONNX, run through
 * ONNX Runtime Android). The core logic depends only on this interface so laptop tests can
 * supply a fake while Android supplies the real ONNX-backed implementation.
 */
fun interface Translator {
    fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): TranslationResult
}
