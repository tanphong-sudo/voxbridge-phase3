package com.voxbridge.core.pipeline

import com.voxbridge.core.model.Language
import java.util.Locale

/** Whole accepted utterances only. Never fuzzy-match or drop internal punctuation/words. */
object CachedPhraseIndex {
    private val whitespace = Regex("\\s+")
    private val phrases = mapOf(
        Language.VI to mapOf(
            "dừng băng chuyền" to "stop_conveyor_vi",
            "dừng máy" to "stop_machine_vi",
            "không vào khu vực nguy hiểm" to "do_not_enter_vi",
            "sơ tán ngay lập tức" to "evacuate_vi",
        ),
        Language.EN to mapOf(
            "stop the conveyor" to "stop_conveyor_en",
            "stop the machine" to "stop_machine_en",
            "do not enter the hazard zone" to "do_not_enter_en",
            "evacuate immediately" to "evacuate_en",
        ),
    )

    fun lookup(text: String, language: Language): String? {
        // Questions are not commands; only one optional terminal period/exclamation is ignored.
        val normalized = text.trim().lowercase(Locale.ROOT).replace(whitespace, " ")
            .let { if (it.endsWith(".") || it.endsWith("!")) it.dropLast(1) else it }
        return phrases[language]?.get(normalized)
    }
}
