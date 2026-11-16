package com.voxbridge.core.pipeline

import com.voxbridge.core.model.Language

/**
 * Pure output-side policy shared by host tests/harnesses and available to UI adapters.
 *
 * It decides which *kind* of speech side effect is permitted. TTS synthesis/playback remains a
 * platform concern. A per-decision [Session] makes duplicate render/confirm calls idempotent:
 * repaired output cannot speak before confirmation. Confirmation emits once; a later explicit
 * replay may speak that same confirmed decision again.
 */
class SpeechOutputPolicy {
    enum class PromptKind { NO_SPEECH, ASK_REPEAT }

    sealed interface Event {
        data class Prompt(
            val kind: PromptKind,
            val language: Language,
        ) : Event

        data class SpeakTranslation(
            val text: String,
            val language: Language,
            /** Best-effort draft of a rejected turn: spoken, but the UI must flag it as unreliable. */
            val unverified: Boolean = false,
        ) : Event
    }

    fun session(
        decision: TranslationDecision,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): Session = Session(decision, sourceLanguage, targetLanguage)

    class Session internal constructor(
        private val decision: TranslationDecision,
        private val sourceLanguage: Language,
        private val targetLanguage: Language,
    ) {
        private var initialEmitted = false
        private var repairedConfirmed = false

        /** Events permitted immediately after rendering this terminal decision. */
        fun initialEvents(): List<Event> {
            if (initialEmitted) return emptyList()
            initialEmitted = true
            return when (val d = decision) {
                TranslationDecision.NoSpeech ->
                    listOf(Event.Prompt(PromptKind.NO_SPEECH, sourceLanguage))
                is TranslationDecision.AskRepeat -> d.speakableDraft()
                    ?.let { listOf(Event.SpeakTranslation(it, targetLanguage, unverified = true)) }
                    ?: listOf(Event.Prompt(PromptKind.ASK_REPEAT, sourceLanguage))
                is TranslationDecision.MemoryTranslation ->
                    listOf(Event.SpeakTranslation(d.text, targetLanguage))
                is TranslationDecision.ModelTranslation ->
                    listOf(Event.SpeakTranslation(d.text, targetLanguage))
                is TranslationDecision.VerifiedTranslation ->
                    listOf(Event.SpeakTranslation(d.text, targetLanguage))
                is TranslationDecision.RepairedTranslation -> emptyList()
            }
        }

        /** Explicit replay is allowed only for this rendered, accepted decision. */
        fun replayEvents(): List<Event> {
            if (!initialEmitted) return emptyList()
            val text = when (val d = decision) {
                is TranslationDecision.ModelTranslation -> d.text
                is TranslationDecision.MemoryTranslation -> d.text
                is TranslationDecision.VerifiedTranslation -> d.text
                is TranslationDecision.RepairedTranslation -> if (repairedConfirmed) d.text else return emptyList()
                is TranslationDecision.AskRepeat -> return d.speakableDraft()
                    ?.let { listOf(Event.SpeakTranslation(it, targetLanguage, unverified = true)) }.orEmpty()
                else -> return emptyList()
            }
            return listOf(Event.SpeakTranslation(text, targetLanguage))
        }

        /**
         * Simulate the explicit user confirmation required for repaired output. Repeated
         * confirmation attempts are harmless and produce no duplicate speech.
         */
        fun confirmRepaired(): List<Event> {
            val repaired = decision as? TranslationDecision.RepairedTranslation ?: return emptyList()
            if (repairedConfirmed) return emptyList()
            repairedConfirmed = true
            return listOf(Event.SpeakTranslation(repaired.text, targetLanguage))
        }
    }
}

/**
 * A rejected turn speaks its best-effort translation as unverified output, except when the draft is
 * a cut-off partial or its critical slots (numbers, negation, units, IDs) disagree with the source:
 * those stay display-only so a wrong safety-critical value is never voiced.
 */
private fun TranslationDecision.AskRepeat.speakableDraft(): String? {
    val draft = draft ?: return null
    if (draft.partialTranslation || CRITICAL_SLOTS_UNVERIFIED in draft.recoveryWarnings) return null
    return draft.translatedText?.takeIf { it.isNotBlank() }
}

private const val CRITICAL_SLOTS_UNVERIFIED = "critical_slots_unverified"
