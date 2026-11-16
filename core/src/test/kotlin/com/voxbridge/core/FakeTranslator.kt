package com.voxbridge.core

import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator

/**
 * A deterministic fake neural translator for tests. It returns whatever translation is
 * registered for a source string, defaulting to an echo. This lets tests drive the
 * DecisionEngine through verified/repaired/ask-repeat branches by simulating a model that
 * sometimes drops safety slots — exactly the failure the safety layer exists to catch.
 */
class FakeTranslator(
    private val table: Map<String, String> = emptyMap(),
    private val modelName: String = "fake-mt"
) : Translator {
    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        val translated = table[text.trim()] ?: text
        return TranslationResult(
            sourceText = text,
            translatedText = translated,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            modelName = modelName
        )
    }
}
