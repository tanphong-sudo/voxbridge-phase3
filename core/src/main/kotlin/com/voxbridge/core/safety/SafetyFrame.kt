package com.voxbridge.core.safety

import com.voxbridge.core.model.Language

/**
 * Canonical safety-critical action. Language-independent so a VI source frame and an
 * EN target frame can be compared directly. Includes Lockout-Tagout (LOTO) verbs, the most-cited
 * industrial safety procedure, which the verifier treats as critical.
 *
 * [criticalWhenDroppedAlone] marks the verbs whose bare presence IS the safety content — so a
 * model that drops the verb entirely (leaving no recognizable action) is dangerous even when no
 * object accompanied it. LOTO/energy-control verbs plus STOP and EVACUATE qualify: "verify dead",
 * "de-energize", "stop", "evacuate" carry their danger in the verb itself. Generic verbs (MOVE,
 * WAIT, CALL, WEAR, OPEN/CLOSE, ENTER/EXIT, START, TURN_ON/OFF) are only safety-relevant when a
 * guarded object accompanies them. Once a sentence is safety-active the operative verb must
 * survive; generic verbs in ordinary inactive prose still do not force a refusal. This single source of truth drives the comparator's LOST_ACTION rule.
 */
enum class SafetyAction(val criticalWhenDroppedAlone: Boolean = false) {
    STOP(criticalWhenDroppedAlone = true), START, ENTER, EXIT, OPEN, CLOSE, TURN_ON, TURN_OFF,
    CALL, WAIT, EVACUATE(criticalWhenDroppedAlone = true), WEAR, MOVE,
    // Lockout-Tagout / energy isolation — bare verb is safety-critical
    LOCK_OUT(criticalWhenDroppedAlone = true), TAG_OUT(criticalWhenDroppedAlone = true),
    DE_ENERGIZE(criticalWhenDroppedAlone = true), ENERGIZE(criticalWhenDroppedAlone = true),
    VERIFY_DEAD(criticalWhenDroppedAlone = true), ISOLATE(criticalWhenDroppedAlone = true)
}

/** Canonical equipment / entity the action applies to, incl. LOTO isolation hardware. */
enum class SafetyObject {
    MACHINE, CONVEYOR_BELT, FORKLIFT, PRESSURE_VALVE, POWER,
    DOOR, EMERGENCY_STOP, HAZARD_ZONE, PROTECTIVE_EQUIPMENT, SUPERVISOR,
    // Lockout-Tagout hardware
    LOCK, TAG, BREAKER, ISOLATION_POINT,
    // Electrical panel / control cabinet — a "do not touch" target is a live-electrical hazard
    ELECTRICAL_PANEL
}

/**
 * Canonical hazard type when the utterance signals danger. Anchored on the OSHA "Fatal Four"
 * plus the common industrial hazards an EHS officer screens for.
 */
enum class HazardType {
    FIRE, GAS_LEAK, ELECTRIC_SHOCK, SPILL, GENERIC_DANGER,
    // Fatal Four + common floor hazards
    CRUSH, STRUCK_BY, FALL, CONFINED_SPACE, HOT_SURFACE, MOVING_PARTS, HIGH_VOLTAGE
}

/** How urgent the instruction is. */
enum class Urgency { NORMAL, HIGH }

/**
 * A lightweight, verifiable summary of the safety-critical meaning in one sentence.
 *
 * The [SafetyFrame] is NOT a translation mechanism. It only captures the slots whose loss
 * would make an instruction dangerous, so the same slots can be compared between the source
 * transcript and the model translation. A sentence with no real industrial slot yields a
 * low-confidence / empty frame, and the pipeline simply keeps the neural translation.
 */
data class SafetyFrame(
    val language: Language,
    val action: SafetyAction? = null,
    val objectType: SafetyObject? = null,
    val objectId: String? = null,
    val location: String? = null,
    val negation: Boolean? = null,
    val urgency: Urgency? = null,
    val hazard: HazardType? = null,
    /**
     * Safety measurements: `<value><unit>` (e.g. `30bar`), optional `lt:/le:/gt:/ge:` limit,
     * and `|` between multiple measurements in source order. Equality retains the legacy format.
     * Losing or changing it ("30 bar" -> "bar", "200" -> "20") is dangerous, so it is guarded.
     */
    val quantity: String? = null,
    val confidence: Double = 0.0,
    /**
     * Source-side activation context. `false` means the lexical slots were found inside ordinary
     * narrative text and must not route the utterance through the safety gate. `null` preserves
     * conservative legacy behavior for persisted frames created before this field existed.
     */
    val activationEligible: Boolean? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
    }

    /**
     * True when the frame carries enough evidence to enter the safety-verification path.
     *
     * Modifier-only frames are deliberately inactive: ordinary prose frequently contains generic
     * negation ("no damages", "không thể") or urgency nouns ("emergency management") without
     * being an industrial instruction. Likewise a generic object mention alone is not enough.
     * Generic actions become safety-relevant when attached to a guarded object. Critical actions,
     * explicit hazards, and safety quantities remain independently sufficient because dropping
     * those can change a dangerous instruction even without another recognized slot.
     */
    val hasSafetySignal: Boolean
        get() {
            if (activationEligible == false) return false
            return action?.criticalWhenDroppedAlone == true ||
                hazard != null || quantity != null || objectId != null ||
                (action != null && objectType != null) ||
                (objectType != null && (negation == true || urgency == Urgency.HIGH))
        }
}
