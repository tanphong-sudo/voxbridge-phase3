package com.voxbridge.core.pipeline

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.safety.SafetyFrameComparator
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.translate.Translator
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** All limits apply per rejected utterance, including failed inference calls. */
data class DraftRecoveryConfig(
    val maxTranslationCalls: Int = 6,
    val maxSourceCandidates: Int = 2,
    val maxSegments: Int = 4,
    val textConfig: TentativeOutputConfig = TentativeOutputConfig(),
) {
    init {
        require(maxTranslationCalls in 1..12)
        require(maxSourceCandidates in 1..4)
        require(maxSegments in 1..4)
    }
}

/** Must come from an actual decoder result; this module never manufactures source words. */
data class DraftSourceCandidate(val text: String, val origin: String) {
    init { require(origin.isNotBlank() && origin.length <= 128) }
}

data class DraftRecoveryAttempt(
    val route: String,
    val sourceOrigin: String,
    val usable: Boolean,
    val reused: Boolean = false,
    val modelName: String? = null,
    val failure: String? = null,
)

data class DraftRecoveryResult(val draft: TentativeOutput?, val attempts: List<DraftRecoveryAttempt>,
                               val totalTranslationCalls: Int = 0)

/**
 * Display-only recovery after rejection. Existing model outputs, an optional second translator,
 * actual alternate ASR text and bounded segmentation are the only candidate sources.
 * A successful recovery never changes the parent's rejection, invokes TTS or queries memory.
 */
class BestEffortDraftRecovery(
    private val primary: Translator,
    private val fallback: Translator? = null,
    private val config: DraftRecoveryConfig = DraftRecoveryConfig(),
    private val candidateUsable: (String, String) -> Boolean = DraftTranslationQuality::usable,
) {
    fun recover(
        transcript: TranscriptResult,
        existingPrimaryTranslation: String? = null,
        alternativeSources: List<DraftSourceCandidate> = emptyList(),
        sourceTruncated: Boolean = false,
        priorTranslationCalls: Int = if (existingPrimaryTranslation == null) 0 else 1,
        existingPrimaryHitTokenLimit: Boolean = false,
        checkActive: () -> Unit = {},
    ): DraftRecoveryResult {
        require(priorTranslationCalls in 0..config.maxTranslationCalls)
        require(existingPrimaryTranslation == null || priorTranslationCalls >= 1)
        return RecoveryRun(transcript, sourceTruncated, priorTranslationCalls, checkActive)
            .run(existingPrimaryTranslation, alternativeSources, existingPrimaryHitTokenLimit)
    }

    private inner class RecoveryRun(private val transcript: TranscriptResult, private val truncated: Boolean,
                                    private val priorCalls: Int, private val checkActive: () -> Unit) {
        private val text = TentativeText(config.textConfig)
        private val attempts = mutableListOf<DraftRecoveryAttempt>()
        private val candidates = mutableListOf<Candidate>()
        private var calls = 0
        private var budgetExhausted = false

        fun run(existing: String?, alternatives: List<DraftSourceCandidate>, existingCapped: Boolean): DraftRecoveryResult {
            checkActive()
            val sources = sources(alternatives)
            if (sources.isEmpty()) return DraftRecoveryResult(null, emptyList(), priorCalls)
            val first = sources.first()
            val compareWholeSources = sources.size > 1
            if (existing != null && first.origin == "asr_primary") {
                evaluate(first, existing, "existing_primary", reused = true, forcedPartial = existingCapped)?.let { if (!compareWholeSources && good(it)) return finish(it) }
            } else if (first.origin == "asr_primary" && priorCalls == 0) {
                infer(primary, first, first.source.text, "primary_whole")?.let { if (!compareWholeSources && good(it)) return finish(it) }
            }
            if (fallback != null) {
                infer(fallback, first, first.source.text, "fallback_whole")?.let { if (!compareWholeSources && good(it)) return finish(it) }
            }
            for (source in sources.filter { it.origin != "asr_primary" }) {
                infer(primary, source, source.source.text, "primary_alternative")?.let { if (!compareWholeSources && good(it)) return finish(it) }
                fallback?.let { model ->
                    infer(model, source, source.source.text, "fallback_alternative")?.let { if (!compareWholeSources && good(it)) return finish(it) }
                }
            }
            if (compareWholeSources && candidates.any { good(it) }) return finish(bestCandidate(), first)
            recoverUnknownMarkers(first)?.let {
                if (good(it, allowUnknownSource = true)) return finish(bestCandidate(), first)
            }
            recoverSegments(first)?.let { if (good(it)) return finish(bestCandidate(), first) }
            return finish(bestCandidate(), first)
        }

        private fun sources(alternatives: List<DraftSourceCandidate>): List<Source> =
            (listOf(DraftSourceCandidate(transcript.text, "asr_primary")) + alternatives.take(config.maxSourceCandidates))
                .mapNotNull { candidate -> text.source(candidate.text)?.let { Source(it, candidate.origin) } }
                .distinctBy { it.source.text }.take(config.maxSourceCandidates)

        private fun infer(model: Translator, source: Source, input: String, route: String, retain: Boolean = true): Candidate? {
            checkActive()
            if (priorCalls + calls >= config.maxTranslationCalls) { budgetExhausted = true; return null }
            if (input.isBlank()) return null
            calls++
            val result = try {
                model.translate(input, transcript.sourceLanguage, transcript.targetLanguage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                attempts += DraftRecoveryAttempt(route, source.origin, usable = false, failure = failure.javaClass.simpleName)
                return null
            }
            checkActive()
            return evaluate(source, result.translatedText, route, modelName = result.modelName, retain = retain,
                forcedPartial = result.hitTokenLimit)
        }

        private fun evaluate(source: Source, raw: String, route: String, reused: Boolean = false,
                             modelName: String? = null, retain: Boolean = true, forcedPartial: Boolean = false): Candidate? {
            val normalized = if (raw.length <= config.textConfig.maxTranslatedCharacters) text.normalize(raw) else null
            val bounded = normalized != null && normalized.text.length <= config.textConfig.maxTranslatedCharacters
            val contentUsable = bounded && text.hasWords(normalized!!.text) && candidateUsable(source.source.text, normalized.text)
            val usable = contentUsable && !forcedPartial
            attempts += DraftRecoveryAttempt(route, source.origin, usable, reused, modelName,
                if (usable) null else if (!bounded) "translation_too_long" else if (forcedPartial) "translation_token_limit" else "translation_unusable")
            if (contentUsable) return add(source, normalized!!.text, route, forcedPartial, normalized.unknown, retain)
            // Only actual complete words before a collapse may survive as an explicitly partial draft.
            if (bounded && normalized != null) {
                val prefix = DraftTranslationQuality.nonloopPrefix(normalized.text)
                if (prefix != null && text.hasWords(prefix) && candidateUsable(source.source.text, prefix)) {
                    add(source, prefix, route + "_prefix", true, normalized.unknown, retain)
                }
            }
            return null
        }

        private fun add(source: Source, target: String, route: String, partial: Boolean, unknown: Boolean,
                        retain: Boolean = true): Candidate {
            val extractedSource = SafetyFrameExtractor().extract(source.source.text, transcript.sourceLanguage)
            val extractedTarget = SafetyFrameExtractor().extract(target, transcript.targetLanguage)
            val comparator = SafetyFrameComparator()
            val penalty = comparator.compare(extractedSource, extractedTarget).criticalViolations.size +
                comparator.compare(extractedTarget, extractedSource).criticalViolations.size
            val candidate = Candidate(source, target, route, partial, unknown, penalty,
                DraftTranslationQuality.lengthPenalty(source.source.text, target), candidates.size)
            if (retain) candidates += candidate
            return candidate
        }

        private fun bestCandidate(): Candidate? = candidates.minWithOrNull(compareBy<Candidate>(
            { it.safetyPenalty }, { it.partial }, { it.source.source.unknown }, { it.unknown },
            { it.qualityPenalty }, { it.order }))

        private fun good(candidate: Candidate, allowUnknownSource: Boolean = false): Boolean =
            !candidate.partial && candidate.safetyPenalty == 0 && candidate.qualityPenalty <= 1.1 &&
                (allowUnknownSource || !candidate.source.source.unknown)

        private fun recoverUnknownMarkers(source: Source): Candidate? {
            if (!source.source.unknown) return null
            val input = source.source.text.replace(TentativeOutputConfig.UNCLEAR_MARKER, " ")
                .trim().replace(Regex("\\s+"), " ")
            if (input.isEmpty() || input == source.source.text) return null
            return infer(primary, source, input, "primary_unknown_markers_removed")
                ?: fallback?.let { infer(it, source, input, "fallback_unknown_markers_removed") }
        }

        private fun recoverSegments(source: Source): Candidate? {
            val parts = DraftTranslationQuality.segments(source.source.text, config.maxSegments)
            if (parts.size < 2) return null
            val translated = mutableListOf<String>()
            val model = fallback ?: primary
            val route = if (fallback == null) "primary_segmented" else "fallback_segmented"
            var complete = true
            val modelsUsed = mutableSetOf<String>()
            for (part in parts) {
                if (priorCalls + calls >= config.maxTranslationCalls) { budgetExhausted = true; complete = false; break }
                val partSource = Source(source.source.copy(text = part), source.origin)
                var modelKind = if (fallback == null) "primary" else "fallback"
                var candidate = infer(model, partSource, part, route + "_part", retain = false)
                if (candidate == null && fallback != null) {
                    modelKind = "primary"
                    candidate = infer(primary, partSource, part, "primary_segmented_part", retain = false)
                }
                if (candidate == null) { complete = false; break }
                modelsUsed += modelKind
                translated += candidate.target
                if (candidate.partial) { complete = false; break }
            }
            if (translated.isEmpty()) return null
            val joined = translated.joinToString(" ")
            if (joined.length > config.textConfig.maxTranslatedCharacters || !candidateUsable(source.source.text, joined)) return null
            val actualRoute = if (modelsUsed.size == 1) modelsUsed.first() + "_segmented" else "mixed_segmented"
            return add(source, joined, actualRoute, partial = !complete || translated.size != parts.size,
                unknown = text.normalize(joined).unknown)
        }

        private fun finish(best: Candidate?, fallbackSource: Source? = null): DraftRecoveryResult {
            val source = best?.source ?: fallbackSource
                ?: return DraftRecoveryResult(null, attempts.toList(), priorCalls + calls)
            val warnings = listOfNotNull(
                "alternative_asr_used".takeIf { source.origin != "asr_primary" },
                "translation_input_unknown_markers_removed".takeIf { best?.route?.contains("unknown_markers_removed") == true },
                "partial_model_output".takeIf { best?.partial == true },
                "critical_slots_unverified".takeIf { (best?.safetyPenalty ?: 0) > 0 },
                "recovery_budget_exhausted".takeIf { budgetExhausted || priorCalls + calls >= config.maxTranslationCalls },
            )
            val draft = TentativeOutput(source.source.text, best?.target, source.source.raw,
                source.source.unknown || best?.unknown == true, truncated || source.source.truncated,
                if (best == null) "recovery_exhausted" else null, source.origin, best?.route,
                best?.partial ?: false, calls, warnings)
            return DraftRecoveryResult(draft, attempts.toList(), priorCalls + calls)
        }
    }

    private data class Source(val source: TentativeText.Source, val origin: String)
    private data class Candidate(val source: Source, val target: String, val route: String, val partial: Boolean,
                                 val unknown: Boolean, val safetyPenalty: Int, val qualityPenalty: Double, val order: Int)
}

/** Deterministic diagnostics rank real candidates; these are not calibrated correctness scores. */
internal object DraftTranslationQuality {
    private val words = Regex("[\\p{L}\\p{N}']+")
    fun usable(source: String, target: String): Boolean {
        val tokens = words.findAll(target.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) return false
        return loopStart(target) == null || repetition(source) >= repetition(target)
    }

    fun nonloopPrefix(text: String): String? {
        val start = loopStart(text) ?: return null
        if (start == 0) return null // A loop-only result provides no useful nonloop content.
        return text.take(start).trim().trimEnd(',', ';', ':').takeIf { words.containsMatchIn(it) }
    }

    private fun loopStart(text: String): Int? {
        val tokens = words.findAll(text.lowercase()).toList()
        for (start in tokens.indices) for (size in 1..4) {
            val repeats = if (size == 1) 6 else 3
            if (start + size * repeats > tokens.size) continue
            if ((size until size * repeats).all { tokens[start + it].value == tokens[start + it % size].value }) {
                return tokens[start].range.first
            }
        }
        return null
    }

    private fun repetition(text: String): Double {
        val tokens = words.findAll(text.lowercase()).map { it.value }.toList()
        return if (tokens.isEmpty()) 0.0 else 1.0 - tokens.distinct().size.toDouble() / tokens.size
    }

    fun lengthPenalty(source: String, target: String): Double {
        val input = words.findAll(source.replace(TentativeOutputConfig.UNCLEAR_MARKER, " ")).count().coerceAtLeast(1)
        val output = words.findAll(target.replace(TentativeOutputConfig.UNCLEAR_MARKER, " ")).count()
        return if (output == 0) Double.MAX_VALUE else kotlin.math.abs(kotlin.math.ln(output.toDouble() / input))
    }

    fun segments(text: String, maximum: Int): List<String> {
        val tokens = Regex("\\S+").findAll(text).map { it.value }.toList()
        if (tokens.size < 2 || maximum < 2) return listOf(text)
        val count = minOf(maximum, maxOf(2, ceil(tokens.size / 8.0).toInt()))
        val size = ceil(tokens.size.toDouble() / count).toInt()
        return tokens.chunked(size).map { it.joinToString(" ") }
    }
}
