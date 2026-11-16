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
    val translationScore: Double? = null,
    /** True when the decoder stopped at its hard output limit; clipped text must never be spoken. */
    val hitTokenLimit: Boolean = false,
) {
    init {
        translationScore?.let {
            require(it in 0.0..1.0) { "translationScore must be between 0 and 1" }
        }
    }
}

/**
 * Abstraction over an on-device neural translator. Android uses static QNN EnViT5/VinAI; historical
 * laptop benchmarks can still use OPUS-MT. The core has no dependency on either SDK.
 */
fun interface Translator {
    fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): TranslationResult
}
