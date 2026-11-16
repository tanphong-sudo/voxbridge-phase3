package com.voxbridge.runtime

import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator

/**
 * Composite [Translator] that dispatches to the per-direction entry: a single-hop
 * [OnnxTranslator] where a direct OPUS-MT graph exists, or a [ChainedTranslator] pivot through
 * English where it does not (MODEL_MANIFEST §1). The core port already passes
 * (sourceLanguage, targetLanguage) on every call, so this picks the matching entry.
 *
 * Contest direction set (OneVoice AI Challenge: VI↔EN, VI↔Mandarin, VI↔Korean):
 *   VI→EN, EN→VI, VI→ZH (vi-en+en-zh), ZH→VI (direct opus-mt-zh-vi),
 *   VI→KO (vi-en+en-ko), KO→VI (ko-en+en-vi).
 *
 * Footprint rule (MODEL_MANIFEST §6): only the directions actually used are loaded — all six
 * hops stay resident for the contest set (the three pairs share four of them), ~150–250 MB
 * INT8 total. An unregistered pair fails fast rather than silently using the wrong model.
 */
class DirectionalTranslator(
    private val byDirection: Map<Pair<Language, Language>, Translator>,
) : Translator, AutoCloseable {

    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        val t = byDirection[sourceLanguage to targetLanguage]
            ?: throw IllegalStateException("No translator loaded for $sourceLanguage->$targetLanguage")
        return t.translate(text, sourceLanguage, targetLanguage)
    }

    override fun close() {
        // No-op BY CONTRACT: this class is a ROUTER, not an owner. Its map values share
        // OnnxTranslator hops (each ChainedTranslator reuses the vi-en / en-vi / en-… hops also
        // registered as direct entries), so closing them here would double-close shared
        // sessions. Ownership lives with PushToTalkViewModel.Runners, which closes every hop
        // exactly once.
    }
}

/** Owns one direction at a time, including when the user swaps languages. */
class SelectedDirectionTranslator(
    private val load: (Language, Language) -> OnnxTranslator,
) : Translator, AutoCloseable {
    private var direction: Pair<Language, Language>? = null
    private var active: OnnxTranslator? = null

    @Synchronized
    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        require((sourceLanguage == Language.VI && targetLanguage == Language.EN) ||
            (sourceLanguage == Language.EN && targetLanguage == Language.VI)) { "Unsupported Phase-2 direction" }
        val requested = sourceLanguage to targetLanguage
        if (direction != requested || active == null) {
            close()
            active = load(sourceLanguage, targetLanguage)
            direction = requested
        }
        return checkNotNull(active).translate(text, sourceLanguage, targetLanguage)
    }

    @Synchronized
    override fun close() {
        val previous = active
        active = null
        direction = null
        previous?.close()
    }
}
