package com.voxbridge.core.pipeline

import com.voxbridge.core.asr.AsrEvidenceKind
import com.voxbridge.core.asr.AsrReliabilityEvidence
import com.voxbridge.core.asr.TranscriptResult

enum class WordImportance(val weight: Double) {
    LOW(.1), NORMAL(.35), HIGH_ACTION(.8), HIGH_EQUIPMENT(.8),
    CRITICAL_NEGATION(1.0), CRITICAL_NUMBER(1.0), CRITICAL_QUANTITY(1.0), CRITICAL_ID(1.0),
}

enum class WordEvidenceSide { PRIMARY, ALTERNATIVE }
enum class WordReliabilityOutcome {
    LOW_ONLY_UNCERTAINTY, PROTECTED_OR_CONTENT_UNCERTAINTY, STABLE, INSUFFICIENT_EVIDENCE,
}

data class WordImportanceConfig(
    val maxCharacters: Int = 4_000,
    val maxTokens: Int = 512,
    val maxAlignmentCells: Int = 263_169,
    val reliableWordConfidence: Double = .8,
) {
    init {
        require(maxCharacters in 1..50_000 && maxTokens in 1..1_024 && maxAlignmentCells in 1..1_050_625)
        require(reliableWordConfidence.isFinite() && reliableWordConfidence in 0.0..1.0)
    }
}

data class WordReliabilitySpan(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val category: WordImportance,
    val side: WordEvidenceSide,
    val uncertain: Boolean?,
    val confidence: Double? = null,
    val risk: Double? = null,
) {
    val importanceWeight: Double get() = category.weight
}

data class WordReliabilityAssessment(
    val outcome: WordReliabilityOutcome,
    val spans: List<WordReliabilitySpan> = emptyList(),
    val signal: String? = null,
    val reason: String,
    val weightedRisk: Double? = null,
    val alternativeText: String? = null,
) {
    /** Diagnostic only: no gate consumes it, so a baseline rejection is never lifted by word evidence. */
    val canRelaxAsrRejection: Boolean get() = outcome == WordReliabilityOutcome.LOW_ONLY_UNCERTAINTY
}

/** Evidence-based importance check; it never guesses an omitted word from a sentence score. */
class WordImportancePolicy(private val config: WordImportanceConfig = WordImportanceConfig()) {
    /** Actual bounded observation for display recovery, even when primary words cannot be assessed. */
    fun validatedAlternativeText(transcript: TranscriptResult): String? {
        val evidence=transcript.reliability ?: return null
        if(evidence.primaryText != transcript.text || transcript.text.length > config.maxCharacters ||
            !validComparison(evidence)) return null
        return evidence.alternativeText?.takeIf { it.length <= config.maxCharacters }
    }

    fun assess(transcript: TranscriptResult): WordReliabilityAssessment {
        val evidence = transcript.reliability ?: return insufficient("missing_word_evidence")
        if (evidence.primaryText != transcript.text) return insufficient("primary_text_mismatch")
        val primary = tokens(transcript.text, transcript.sourceLanguage) ?: return insufficient("primary_outside_bounds")
        if (primary.isEmpty() || hasUnknown(transcript.text)) return insufficient("empty_or_unknown_primary")
        return if (evidence.kind == AsrEvidenceKind.NATIVE_WORD_CONFIDENCE) native(primary, evidence)
        else comparison(primary, transcript, evidence)
    }

    private fun comparison(
        primary: List<ImportanceToken>, transcript: TranscriptResult, evidence: AsrReliabilityEvidence,
    ): WordReliabilityAssessment {
        if (!validComparison(evidence)) return insufficient("invalid_comparison_provenance")
        val alternativeText = evidence.alternativeText ?: return insufficient("missing_alternative")
        val alternative = tokens(alternativeText, transcript.sourceLanguage) ?: return insufficient("alternative_outside_bounds")
        if (alternative.isEmpty() || hasUnknown(alternativeText)) return insufficient("empty_or_unknown_alternative")
        val primaryCanonical = canonicalComparisonTokens(primary, transcript.sourceLanguage)
        val alternativeCanonical = canonicalComparisonTokens(alternative, transcript.sourceLanguage)
        val cells = (primaryCanonical.size + 1).toLong() * (alternativeCanonical.size + 1)
        if (cells > config.maxAlignmentCells) return insufficient("alignment_budget_exceeded")
        val matches = importanceMatches(primaryCanonical.map { it.token }, alternativeCanonical.map { it.token })
        val spans = originalSpans(primary, primaryCanonical, matches.map { it.first }.toSet(), WordEvidenceSide.PRIMARY) +
            originalSpans(alternative, alternativeCanonical, matches.map { it.second }.toSet(), WordEvidenceSide.ALTERNATIVE)
        val signal = if (evidence.kind == AsrEvidenceKind.DECODE_COMPARISON) "decode_agreement_heuristic"
            else "signal_variant_agreement_heuristic"
        return assessment(spans, signal, alternativeText)
    }

    /** Spans stay on the exact displayed words; an original word is uncertain if any canonical piece is. */
    private fun originalSpans(
        original: List<ImportanceToken>, canonical: List<CanonicalToken>, matched: Set<Int>, side: WordEvidenceSide,
    ): List<WordReliabilitySpan> = original.mapIndexed { index, token ->
        val pieces = canonical.withIndex().filter { index in it.value.sources }
        val category = (pieces.map { it.value.token.category } + token.category).maxBy { it.weight }
        token.copy(category = category).span(side, pieces.any { it.index !in matched })
    }

    private fun validComparison(e: AsrReliabilityEvidence): Boolean {
        if (e.alternativeConfigurationId == null || e.alternativeConfigurationId == e.primaryConfigurationId ||
            e.alternativeOriginalAudioId != e.originalAudioId || e.alternativeInputId == null || e.words.isNotEmpty()) return false
        return when (e.kind) {
            AsrEvidenceKind.DECODE_COMPARISON -> e.alternativeInputId == e.primaryInputId
            AsrEvidenceKind.SIGNAL_VARIANT_COMPARISON -> e.alternativeInputId != e.primaryInputId
            else -> false
        }
    }

    private fun native(primary: List<ImportanceToken>, evidence: AsrReliabilityEvidence): WordReliabilityAssessment {
        if (!evidence.wordConfidencesCalibrated || evidence.provider == null || evidence.words.isEmpty() ||
            evidence.alternativeText != null) return insufficient("uncalibrated_or_missing_native_words")
        val words = evidence.words.sortedBy { it.start }
        if (words.zipWithNext().any { (a,b) -> a.endExclusive > b.start } ||
            words.any { it.endExclusive > evidence.primaryText.length }) return insufficient("invalid_word_ranges")
        val confidences = primary.map { token ->
            words.singleOrNull { it.start <= token.start && it.endExclusive >= token.endExclusive }?.confidence
        }
        if (confidences.any { it == null }) return insufficient("incomplete_native_word_coverage")
        val spans = primary.mapIndexed { i, token ->
            val confidence = requireNotNull(confidences[i])
            token.span(WordEvidenceSide.PRIMARY, confidence < config.reliableWordConfidence, confidence)
        }
        return assessment(spans, "calibrated_native_word_confidence", null)
    }

    private fun assessment(
        spans: List<WordReliabilitySpan>, signal: String, alternative: String?,
    ): WordReliabilityAssessment {
        val uncertain = spans.filter { it.uncertain == true }
        val outcome = when {
            uncertain.isEmpty() -> WordReliabilityOutcome.STABLE
            uncertain.any { it.category != WordImportance.LOW } -> WordReliabilityOutcome.PROTECTED_OR_CONTENT_UNCERTAINTY
            else -> WordReliabilityOutcome.LOW_ONLY_UNCERTAINTY
        }
        val reason = when (outcome) {
            WordReliabilityOutcome.STABLE -> "observed_words_stable"
            WordReliabilityOutcome.LOW_ONLY_UNCERTAINTY -> "only_low_importance_words_uncertain"
            else -> "content_or_protected_words_uncertain"
        }
        return WordReliabilityAssessment(outcome, spans, signal, reason,
            spans.mapNotNull { it.risk }.maxOrNull() ?: 0.0, alternative)
    }

    private fun tokens(text: String, language: com.voxbridge.core.model.Language): List<ImportanceToken>? {
        if (text.length > config.maxCharacters) return null
        val result = importanceTokens(text, language)
        return result.takeIf { it.size <= config.maxTokens }
    }

    private fun insufficient(reason: String) = WordReliabilityAssessment(WordReliabilityOutcome.INSUFFICIENT_EVIDENCE, reason = reason)
    private fun hasUnknown(text: String) = UNKNOWN.containsMatchIn(text)

    companion object {
        private val UNKNOWN = Regex("(?:\\[(?:mask|unk|unclear|inaudible)]|<(?:mask|unk|unclear|inaudible)>)", RegexOption.IGNORE_CASE)
    }
}
