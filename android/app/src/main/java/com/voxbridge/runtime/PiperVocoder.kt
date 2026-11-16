package com.voxbridge.runtime

import com.voxbridge.core.model.Language

/** Optional accelerated waveform stage. Frontend values are latent features, never PCM. */
interface PiperVocoder : AutoCloseable {
    fun synthesize(latent: FloatArray, language: Language): FloatArray

    /** A complete neural voice that replaces Piper for [language], or null to keep Piper. */
    fun replacementVoice(language: Language): ReplacementVoice? = null
}

/** Whole-voice TTS on spoken-form text (already through SpokenTextNormalizer). Mono float PCM. */
interface ReplacementVoice : AutoCloseable {
    val sampleRate: Int
    fun preload()
    fun synthesize(spokenText: String): FloatArray
}
