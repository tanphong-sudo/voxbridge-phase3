package com.voxbridge.core.safety

/**
 * How the layer may act on a detected violation.
 *  - REPAIRABLE: a lost slot the repairer may try to restore from the source frame.
 *  - DETECT_ONLY: detected and surfaced, but never patched — restoring it would mean guessing
 *    between two contradictory readings (action/object flip) or fabricating a value (quantity).
 *    Always routes to ASK_REPEAT.
 */
enum class RepairPolicy { REPAIRABLE, DETECT_ONLY }

/**
 * The kinds of safety-meaning loss the comparator can detect between a source frame
 * and the frame of a candidate translation. These are the errors that turn a safe
 * instruction into a dangerous one.
 *
 * Criticality and repair policy live ON the enum (single source of truth): a new violation
 * must declare both, so the comparator's critical filter and the repairer's dispatch can never
 * drift out of sync (the LOST_URGENCY regression came from those lists being hand-maintained).
 */
enum class SafetyViolation(val isCritical: Boolean, val repairPolicy: RepairPolicy) {
    LOST_NEGATION(true, RepairPolicy.REPAIRABLE),     // source forbids ("do not"), target permits
    LOST_OBJECT(true, RepairPolicy.REPAIRABLE),       // source names a specific object, target drops it
    CHANGED_OBJECT(true, RepairPolicy.DETECT_ONLY),   // source object replaced by a different in-lexicon object
    LOST_OBJECT_ID(true, RepairPolicy.REPAIRABLE),    // source has machine/line/zone number, target drops it
    CHANGED_ACTION(true, RepairPolicy.DETECT_ONLY),   // stop -> start, enter -> exit, on -> off, etc.
    LOST_ACTION(true, RepairPolicy.DETECT_ONLY),      // source's imperative verb dropped entirely and no other slot bridged it (e.g. "verify dead" -> "already dead")
    LOST_HAZARD(true, RepairPolicy.REPAIRABLE),       // source signals a hazard, target drops it
    CHANGED_HAZARD(true, RepairPolicy.DETECT_ONLY),   // source and target name different hazards
    CHANGED_NEGATION(true, RepairPolicy.DETECT_ONLY), // affirmative/prohibition meaning changed
    LOST_URGENCY(true, RepairPolicy.REPAIRABLE),      // source is urgent, target reads as routine
    LOST_QUANTITY(true, RepairPolicy.DETECT_ONLY)     // source has a measurement (30 bar, 200 C), target drops/changes it
}

data class SafetyComparison(
    val violations: List<SafetyViolation>
) {
    val preserved: Boolean get() = violations.isEmpty()

    /**
     * Violations whose presence must block a plain model translation from being spoken.
     * Driven by [SafetyViolation.isCritical] so the policy is declared once, on the type.
     */
    val criticalViolations: List<SafetyViolation>
        get() = violations.filter { it.isCritical }
}

/**
 * Compares the safety meaning of the source against the safety meaning of the model
 * translation. It detects losses, substitutions and polarity changes. Source-inactive targets
 * also receive a bounded invented-command check through [addsSafetyMeaning].
 *
 * Coverage boundary (stated honestly): this detects DELETION and INVERSION/SUBSTITUTION of
 * lexicon-known guarded slots. It does NOT detect an arbitrary fluent mistranslation outside
 * the lexicon — residual MT quality is owned by model selection and the benchmark, not here.
 */
class SafetyFrameComparator {
    /** Activation flags alone are insufficient: incidental IDs/objects are common in prose.
     * Reject new operative commands or hazards, not mere changes in extraction confidence.
     */
    fun addsSafetyMeaning(source: SafetyFrame, target: SafetyFrame): Boolean {
        if (source.hasSafetySignal || !target.hasSafetySignal) return false
        return (target.hazard != null && target.hazard != source.hazard) ||
            (target.action?.criticalWhenDroppedAlone == true && target.action != source.action) ||
            (target.action != null && target.objectType != null && source.action == null && source.objectType == null)
    }

    fun compare(source: SafetyFrame, target: SafetyFrame): SafetyComparison {
        val violations = mutableListOf<SafetyViolation>()

        if (source.negation == true && target.negation != true) {
            violations += SafetyViolation.LOST_NEGATION
        }
        if (source.negation == false && target.negation == true) {
            violations += SafetyViolation.CHANGED_NEGATION
        }
        if (source.action != null && target.action != null && source.action != target.action) {
            violations += SafetyViolation.CHANGED_ACTION
        }
        // Once a source is a safety instruction, its action is required even if the object
        // survives: "open the door" -> "the door" is ambiguous. Bare critical actions remain
        // guarded; generic verbs in inactive ordinary prose do not become safety commands.
        if (source.action != null && (source.action.criticalWhenDroppedAlone || source.hasSafetySignal) && target.action == null) {
            violations += SafetyViolation.LOST_ACTION
        }
        if (source.objectType != null && target.objectType == null) {
            violations += SafetyViolation.LOST_OBJECT
        }
        // In-lexicon substitution: source said one object, target says a different one.
        if (source.objectType != null && target.objectType != null && source.objectType != target.objectType) {
            violations += SafetyViolation.CHANGED_OBJECT
        }
        if (source.objectId != null && target.objectId != source.objectId) {
            violations += SafetyViolation.LOST_OBJECT_ID
        }
        if (source.hazard != null && target.hazard == null) {
            violations += SafetyViolation.LOST_HAZARD
        }
        if (source.hazard != null && target.hazard != null && source.hazard != target.hazard) {
            violations += SafetyViolation.CHANGED_HAZARD
        }
        if (source.urgency == Urgency.HIGH && target.urgency != Urgency.HIGH) {
            violations += SafetyViolation.LOST_URGENCY
        }
        // A measurement that is dropped or changed (e.g. "30 bar" -> nothing, or "200" -> "20")
        // is dangerous. Quantity is detect-only: the repairer never fabricates a measurement.
        if (source.quantity != null && source.quantity != target.quantity) {
            violations += SafetyViolation.LOST_QUANTITY
        }

        return SafetyComparison(violations)
    }
}
