package com.voxbridge.core.safety

import com.voxbridge.core.model.Language

/**
 * A single repair attempt with its re-extracted frame and component scores.
 */
data class RepairCandidate(
    val text: String,
    val frame: SafetyFrame,
    val slotPreservationScore: Double,
    val meaningConsistencyScore: Double,
    val fluencyScore: Double,
    val localMemoryScore: Double,
    val riskPolicyScore: Double
) {
    val repairScore: Double =
        0.45 * slotPreservationScore +
            0.20 * meaningConsistencyScore +
            0.15 * fluencyScore +
            0.10 * localMemoryScore +
            0.10 * riskPolicyScore
}

sealed interface RepairOutcome {
    data class Repaired(val text: String, val candidate: RepairCandidate) : RepairOutcome
    data class Rejected(val reason: String) : RepairOutcome
}

/**
 * Constrained semantic repair.
 *
 * When the model translation dropped a safety-critical slot the source clearly had, the
 * repairer generates a small set of correction candidates by inserting ONLY the missing
 * slot(s), re-extracts each candidate's frame to verify the fix, scores them, and emits a
 * repair only when it clears strict gates. It never adds meaning the source lacked: every
 * candidate is checked for new critical slots and rejected if it introduces any.
 */
class SafetyRepairer(
    private val extractor: SafetyFrameExtractor = SafetyFrameExtractor(),
    private val comparator: SafetyFrameComparator = SafetyFrameComparator(),
    private val renderer: SafetySlotRenderer = SafetySlotRenderer()
) {
    fun repair(
        modelTranslation: String,
        sourceFrame: SafetyFrame,
        comparison: SafetyComparison,
        targetLanguage: Language,
        localMemoryScore: Double = 0.0
    ): RepairOutcome {
        // Repair INSERTS surface forms into the target sentence, so it may only run into a target
        // language whose SafetySlotRenderer has been validated (VI, EN). For ZH/KO the detection
        // is universal but no native-reviewed renderer exists yet — rather than splice Vietnamese
        // or English fragments into a Chinese/Korean sentence (a fabrication worse than the loss),
        // we refuse and ask the speaker to repeat. This is a deliberate safe stance, not a gap.
        if (targetLanguage != Language.VI && targetLanguage != Language.EN) {
            return RepairOutcome.Rejected("repair_unavailable_for_target_language")
        }
        if (sourceFrame.confidence < MIN_SOURCE_CONFIDENCE) {
            return RepairOutcome.Rejected("source_frame_confidence_too_low")
        }
        if (comparison.criticalViolations.isEmpty()) {
            return RepairOutcome.Rejected("no_critical_violation_to_repair")
        }
        if (comparison.criticalViolations.any { it.repairPolicy == RepairPolicy.DETECT_ONLY }) {
            return RepairOutcome.Rejected("detect_only_violation_present")
        }

        val candidates = generateCandidates(modelTranslation, sourceFrame, comparison, targetLanguage)
            .map { text -> scoreCandidate(text, sourceFrame, targetLanguage, localMemoryScore) }
            .sortedByDescending { it.repairScore }

        if (candidates.isEmpty()) return RepairOutcome.Rejected("no_candidate_generated")

        val best = candidates.first()
        val runnerUp = candidates.getOrNull(1)

        if (best.slotPreservationScore < 1.0) {
            return RepairOutcome.Rejected("critical_slot_not_fully_preserved")
        }
        if (introducesNewCriticalSlot(sourceFrame, best.frame)) {
            return RepairOutcome.Rejected("candidate_added_new_critical_slot")
        }
        if (best.repairScore < MIN_REPAIR_SCORE) {
            return RepairOutcome.Rejected("repair_score_below_threshold")
        }
        if (runnerUp != null && best.repairScore - runnerUp.repairScore < MIN_MARGIN) {
            return RepairOutcome.Rejected("ambiguous_repair_margin_too_small")
        }

        return RepairOutcome.Repaired(best.text, best)
    }

    /**
     * Generate 2-4 candidates using distinct strategies: slot insertion, negation
     * canonicalization, and urgency canonicalization. Each strategy edits only the missing
     * slot, leaving the rest of the model translation in place.
     *
     * A single helper, [applyRepairFor], maps each REPAIRABLE violation to its insertion strategy
     * via an exhaustive `when` (no silent fall-through), so the per-violation candidates and the
     * combined candidate cannot disagree about which violations are repairable.
     */
    private fun generateCandidates(
        modelTranslation: String,
        sourceFrame: SafetyFrame,
        comparison: SafetyComparison,
        targetLanguage: Language
    ): List<String> {
        val out = LinkedHashSet<String>()
        val base = modelTranslation.trim()

        // One candidate per repairable violation (single-slot fixes).
        comparison.violations.forEach { v ->
            if (v.repairPolicy == RepairPolicy.REPAIRABLE) {
                out += applyRepairFor(v, base, sourceFrame, targetLanguage)
            }
        }

        // A combined candidate that applies every repairable fix at once.
        var working = base
        comparison.violations.forEach { v ->
            if (v.repairPolicy == RepairPolicy.REPAIRABLE) {
                working = applyRepairFor(v, working, sourceFrame, targetLanguage)
            }
        }
        out += working
        return out.filter { it.isNotBlank() }
    }

    /**
     * Apply the insertion strategy for one repairable violation. Exhaustive over [SafetyViolation]
     * so adding a violation forces a decision here. DETECT_ONLY violations never reach this (they
     * route to ASK_REPEAT before repair), so they return the text unchanged.
     */
    private fun applyRepairFor(
        violation: SafetyViolation,
        text: String,
        sourceFrame: SafetyFrame,
        targetLanguage: Language
    ): String = when (violation) {
        SafetyViolation.LOST_NEGATION -> applyNegation(text, targetLanguage)
        SafetyViolation.LOST_OBJECT_ID ->
            if (sourceFrame.objectId != null) insertObjectId(text, sourceFrame, targetLanguage) else text
        SafetyViolation.LOST_OBJECT, SafetyViolation.LOST_HAZARD ->
            if (sourceFrame.objectType != null) insertObject(text, sourceFrame, targetLanguage) else text
        SafetyViolation.LOST_URGENCY -> appendUrgency(text, targetLanguage)
        // DETECT_ONLY — never repaired; routed to ASK_REPEAT upstream.
        SafetyViolation.CHANGED_ACTION, SafetyViolation.CHANGED_OBJECT,
        SafetyViolation.LOST_ACTION, SafetyViolation.LOST_QUANTITY,
        SafetyViolation.CHANGED_HAZARD, SafetyViolation.CHANGED_NEGATION -> text
    }

    private fun applyNegation(text: String, language: Language): String {
        val prefix = renderer.negationPrefix(language)
        if (text.lowercase().startsWith(prefix.lowercase())) return text
        val body = text.replaceFirstChar { it.lowercase() }
        return "$prefix $body".cleanupSpaces()
    }

    private fun insertObjectId(text: String, source: SafetyFrame, language: Language): String {
        val id = source.objectId ?: return text
        val idPhrase = renderer.objectIdPhrase(id, language)
        if (Regex("\\b${Regex.escape(idPhrase)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
        val stripped = text.trimEnd('.', ' ', '!', '?')

        // Anchor the id immediately AFTER the object reference the model actually produced, for
        // grammaticality ("Stop the conveyor" + id -> "Stop the conveyor number three"). The model
        // may use a shorter surface than our canonical phrase, so try the full phrase then its
        // head words. Fall back to appending at the end when no anchor is found.
        val objPhrase = source.objectType?.let { renderer.objectPhrase(it, language).removePrefix("the ") }
        val anchors = buildList {
            if (objPhrase != null) {
                add(objPhrase)
                objPhrase.split(" ").filter { it.length > 2 }.forEach { add(it) }
            }
        }
        for (anchor in anchors) {
            val m = Regex("\\b${Regex.escape(anchor)}\\b", RegexOption.IGNORE_CASE).find(stripped)
            if (m != null) {
                val insertAt = m.range.last + 1
                return (stripped.substring(0, insertAt) + " " + idPhrase + stripped.substring(insertAt) + ".").cleanupSpaces()
            }
        }
        return "$stripped $idPhrase.".cleanupSpaces()
    }

    private fun insertObject(text: String, source: SafetyFrame, language: Language): String {
        val obj = source.objectType ?: return text
        val objPhrase = renderer.objectPhrase(obj, language)
        if (text.lowercase().contains(objPhrase.removePrefix("the ").lowercase())) return text
        val stripped = text.trimEnd('.', ' ')
        return "$stripped $objPhrase.".cleanupSpaces()
    }

    private fun appendUrgency(text: String, language: Language): String {
        val suffix = renderer.urgencySuffix(language)
        if (text.lowercase().contains(suffix.lowercase())) return text
        val stripped = text.trimEnd('.', ' ')
        return "$stripped $suffix.".cleanupSpaces()
    }

    private fun scoreCandidate(
        text: String,
        sourceFrame: SafetyFrame,
        targetLanguage: Language,
        localMemoryScore: Double
    ): RepairCandidate {
        val frame = extractor.extract(text, targetLanguage)
        val comparison = comparator.compare(sourceFrame, frame)
        val slotPreservation = if (comparison.criticalViolations.isEmpty()) 1.0 else 0.0
        val meaningConsistency = if (introducesNewCriticalSlot(sourceFrame, frame)) 0.0 else 1.0
        val fluency = fluencyHeuristic(text)
        val riskPolicy = if (sourceFrame.negation == true || sourceFrame.hazard != null) {
            if (slotPreservation == 1.0) 1.0 else 0.0
        } else 1.0
        return RepairCandidate(
            text = text,
            frame = frame,
            slotPreservationScore = slotPreservation,
            meaningConsistencyScore = meaningConsistency,
            fluencyScore = fluency,
            localMemoryScore = localMemoryScore.coerceIn(0.0, 1.0),
            riskPolicyScore = riskPolicy
        )
    }

    /** Penalize obviously malformed output (double spaces, repeated tokens, runaway length). */
    private fun fluencyHeuristic(text: String): Double {
        val tokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return 0.0
        var score = 1.0
        val repeatedAdjacent = tokens.zipWithNext().count { (a, b) -> a == b }
        score -= 0.2 * repeatedAdjacent
        if (tokens.size > 16) score -= 0.2
        if (text.contains("  ")) score -= 0.1
        return score.coerceIn(0.0, 1.0)
    }

    private fun introducesNewCriticalSlot(source: SafetyFrame, candidate: SafetyFrame): Boolean {
        // A repair must not introduce an action/object/id/hazard the source did not have.
        if (source.action == null && candidate.action != null) return true
        if (source.objectType == null && candidate.objectType != null) return true
        if (source.objectId == null && candidate.objectId != null) return true
        if (source.hazard == null && candidate.hazard != null) return true
        // Flipping negation on (source allowed, candidate forbids) also changes meaning.
        if (source.negation != true && candidate.negation == true) return true
        return false
    }

    private fun String.cleanupSpaces(): String =
        replace(Regex("\\s+"), " ").replace(Regex("\\s+\\."), ".").trim()

    private companion object {
        const val MIN_SOURCE_CONFIDENCE = 0.80
        const val MIN_REPAIR_SCORE = 0.82
        const val MIN_MARGIN = 0.08
    }
}
