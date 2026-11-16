package com.voxbridge.core.pipeline

import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer

/** Call only for speech authorized by SpeechOutputPolicy. Cache IO stays on the synthesis worker. */
object SpeechAudioPlan {
    fun producers(
        text: String,
        language: Language,
        cache: (String) -> PcmAudio?,
        synthesize: (String, Language) -> PcmAudio,
    ): List<() -> PcmAudio> {
        val id = CachedPhraseIndex.lookup(text, language)
        if (id != null) return listOf({ cache(id) ?: synthesize(text, language) })
        // Spoken forms before chunking: "20 tháng 4, 1980" must not be split at its comma.
        // The normalizer is idempotent, so the synthesizer normalizing each chunk again is harmless.
        return SpeechChunker.split(SpokenTextNormalizer.normalize(text, language)).map { chunk -> { synthesize(chunk, language) } }
    }
}
