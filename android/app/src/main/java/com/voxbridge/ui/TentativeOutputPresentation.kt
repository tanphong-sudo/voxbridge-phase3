package com.voxbridge.ui

import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TranslationDecision

/** Display-only candidate: no approval, playback, or accepted-translation action belongs here. */
data class TentativeOutputPresentation(
    val warningLabel: String,
    val sourceLabel: String,
    val sourceText: String,
    val translationLabel: String?,
    val translatedText: String?,
    val hints: List<String>,
) {
    val warningSymbol: String get() = "!"
    val wholeUtteranceUncertain: Boolean get() = true
}

/** Every new decision derives its own candidate; successful/no-speech results cannot retain one. */
fun tentativeForDecision(decision: TranslationDecision, enabled: Boolean = true): TentativeOutput? =
    if (enabled && decision is TranslationDecision.AskRepeat) decision.draft else null

fun shouldShowAcceptedOutput(decision: String, output: String): Boolean =
    output.isNotBlank() && !decision.startsWith("ASK_REPEAT") && !decision.startsWith("NO_SPEECH")

fun tentativeOutputPresentation(
    decision: String,
    sourceLanguage: Language,
    draft: TentativeOutput?,
    enabled: Boolean,
): TentativeOutputPresentation? {
    if (!enabled || !decision.startsWith("ASK_REPEAT") || draft == null || draft.sourceText.isBlank()) return null
    val vi = sourceLanguage == Language.VI
    val translation = draft.translatedText?.takeIf { it.isNotBlank() }
    return TentativeOutputPresentation(
        warningLabel = if (vi) "Bản nháp chưa tin cậy · Xin nói lại" else "Unreliable draft · Please repeat",
        sourceLabel = if (vi) "Có thể đã nghe" else "Possibly heard",
        sourceText = draft.sourceText,
        translationLabel = if (translation == null) null else if (vi) "Bản dịch có thể" else "Possible translation",
        translatedText = translation,
        hints = listOfNotNull(
            if (draft.containsUnknownSpans) {
                if (vi) "Một số từ chưa nghe rõ." else "Some words could not be heard."
            } else null,
            if (draft.sourceTruncated) {
                if (vi) "Bản nháp đã được rút gọn." else "Draft shortened."
            } else null,
            if (translation == null) {
                if (vi) "Chưa có bản dịch. Xin nói lại." else "Translation unavailable. Please repeat."
            } else null,
        ),
    )
}
