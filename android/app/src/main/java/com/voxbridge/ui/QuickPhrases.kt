package com.voxbridge.ui

import com.voxbridge.core.model.Language

/**
 * Fixed site-safety phrases a worker can play with one tap. They skip listening, recognition and
 * translation entirely, so they work in any noise and start as fast as playback can: the audio for
 * the current target language is synthesized once after the direction loads and kept in memory.
 * The wording is written by hand in both languages, not machine-translated.
 */
data class QuickPhrase(val id: String, val vi: String, val en: String, val urgent: Boolean) {
    fun text(language: Language): String = if (language == Language.VI) vi else en
}

/** Decision tag for a turn that played a quick phrase; shown like an accepted translation. */
const val QUICK_PHRASE_DECISION = "QUICK_PHRASE"

val QUICK_PHRASES: List<QuickPhrase> = listOf(
    QuickPhrase("stop", "Dừng lại!", "Stop!", urgent = true),
    QuickPhrase("danger", "Nguy hiểm, tránh xa ra!", "Danger, stay back!", urgent = true),
    QuickPhrase("hard_hat", "Đội mũ bảo hộ vào.", "Put your hard hat on.", urgent = false),
    QuickPhrase("ambulance", "Gọi cấp cứu ngay!", "Call an ambulance!", urgent = true),
)

fun quickPhrase(id: String): QuickPhrase? = QUICK_PHRASES.firstOrNull { it.id == id }

fun quickPhraseAudioKey(phrase: QuickPhrase, target: Language): String = "${phrase.id}_${target.name}"
