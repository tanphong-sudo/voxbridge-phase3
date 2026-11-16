package com.voxbridge.core.pipeline

/** Display-only best effort. It is never an accepted decision or permission for TTS/reuse. */
data class TentativeOutput(
    val sourceText: String,
    val translatedText: String? = null,
    val rawSourceText: String = sourceText,
    val containsUnknownSpans: Boolean = false,
    val sourceTruncated: Boolean = false,
    val unavailableReason: String? = null,
    val sourceOrigin: String = "asr_primary",
    val translationOrigin: String? = null,
    val partialTranslation: Boolean = false,
    val recoveryAttemptCount: Int = 0,
    val recoveryWarnings: List<String> = emptyList(),
    /**
     * Exact words of [rawSourceText] that two actual ASR observations disagreed on, excluding
     * low-importance fillers. Display hint only: absence never means the other words are verified.
     */
    val uncertainWords: List<String> = emptyList(),
) {
    /** Native word confidence is unavailable: every word stays explicitly unverified. */
    val wholeUtteranceUncertain: Boolean get() = true
}

/** Literal decoder markers only; this config must never turn domain words into guessed gaps. */
data class TentativeOutputConfig(
    val unknownMarkers: Set<String> = setOf("[mask]", "<mask>", "[unk]", "<unk>", "[inaudible]", "[unclear]"),
    val maxSourceCharacters: Int = 2000,
    val maxTranslatedCharacters: Int = 4000,
) {
    init {
        require(maxSourceCharacters in 1..MAX_CHARACTERS && maxTranslatedCharacters in 1..MAX_CHARACTERS)
        require(unknownMarkers.size <= MAX_MARKERS)
        require(unknownMarkers.all { it.length in 3..MAX_MARKER_CHARACTERS && MARKER_SHAPE.matches(it) }) {
            "Unknown markers must be literal bracketed tokens, never natural-language vocabulary"
        }
    }

    companion object {
        const val UNCLEAR_MARKER = "[unclear]"
        private const val MAX_CHARACTERS = 16_384
        private const val MAX_MARKERS = 64
        internal const val MAX_MARKER_CHARACTERS = 64
        private val MARKER_SHAPE = Regex("(?:\\[[^\\[\\]\\r\\n]+]|<[^<>\\r\\n]+>)")
    }
}

/** Bounded display normalization; original TranscriptResult is never modified. */
internal class TentativeText(private val config: TentativeOutputConfig) {
    private val markers = Regex(
        (config.unknownMarkers + TentativeOutputConfig.UNCLEAR_MARKER)
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    data class Source(val text: String, val raw: String, val unknown: Boolean, val truncated: Boolean)
    data class Normalized(val text: String, val unknown: Boolean)

    fun source(raw: String): Source? {
        val excerpt = bounded(raw, config.maxSourceCharacters)
        val normalized = normalize(excerpt)
        val display = bounded(normalized.text, config.maxSourceCharacters)
        if (!hasWords(display)) return null
        return Source(display, excerpt, normalized.unknown,
            raw.length > excerpt.length || normalized.text.length > display.length)
    }

    fun normalize(text: String): Normalized = Normalized(
        markers.replace(text, TentativeOutputConfig.UNCLEAR_MARKER), markers.containsMatchIn(text),
    )

    fun hasWords(text: String): Boolean = markers.replace(text, "").any { it.isLetterOrDigit() }

    private fun bounded(text: String, maximum: Int): String {
        var end = minOf(maximum, text.length)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        if (end < text.length) {
            // Scan only bounded lookahead, preventing a truncated technical marker becoming a word.
            val window = text.take(minOf(text.length, end + TentativeOutputConfig.MAX_MARKER_CHARACTERS))
            val crossing = markers.findAll(window).firstOrNull { it.range.first < end && it.range.last >= end }
            if (crossing != null) end = crossing.range.first
        }
        return text.take(end)
    }
}
