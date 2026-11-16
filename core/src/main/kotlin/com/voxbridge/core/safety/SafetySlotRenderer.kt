package com.voxbridge.core.safety

import com.voxbridge.core.model.Language

/**
 * Renders an individual canonical safety slot into a target-language surface form.
 *
 * This exists ONLY to repair a translation that dropped a slot the source clearly had.
 * It renders single slots (an object name, an id, a negation, an urgency marker), never a
 * whole sentence — repair is constrained insertion, not template translation.
 */
class SafetySlotRenderer {
    /**
     * Renderable target languages. The repairer only inserts surfaces for languages with a
     * validated lexicon (VI, EN); ZH/KO repair is gated to ASK_REPEAT in [SafetyRepairer], so this
     * renderer must never be reached with another language. The guard below turns a future
     * regression (e.g. someone widening the repair gate) into a LOUD failure instead of silently
     * splicing Vietnamese surfaces into a Chinese/Korean sentence — the worst kind of fabrication.
     */
    private fun requireRenderable(language: Language) {
        require(language == Language.VI || language == Language.EN) {
            "SafetySlotRenderer has no validated surfaces for $language; repair must be gated to " +
                "ASK_REPEAT in SafetyRepairer before reaching here. This is a bug, not user input."
        }
    }

    /**
     * Surface form for an object slot. Exhaustive `when` (no `else`) makes this total over the
     * [SafetyObject] enum: adding a new object forces a compile error here rather than a runtime
     * NoSuchElementException on a live safety utterance — the worst possible failure mode.
     */
    fun objectPhrase(obj: SafetyObject, language: Language): String {
        requireRenderable(language)
        return if (language == Language.EN) enObject(obj) else viObject(obj)
    }

    /**
     * Render an object id for insertion. Numeric ids become spelled forms ("number three" /
     * "số ba"); alphanumeric ids ("B", "A-12") are rendered as-is ("number B" / "số B").
     */
    fun objectIdPhrase(id: String, language: Language): String {
        requireRenderable(language)
        val numeric = id.toIntOrNull()
        return if (language == Language.EN) {
            if (numeric != null) "number ${englishNumber(numeric)}" else "number $id"
        } else {
            "số $id"
        }
    }

    fun negationPrefix(language: Language): String {
        requireRenderable(language)
        return if (language == Language.EN) "Do not" else "Không"
    }

    fun urgencySuffix(language: Language): String {
        requireRenderable(language)
        return if (language == Language.EN) "immediately" else "ngay lập tức"
    }

    /** Surface form for an action slot. Exhaustive over [SafetyAction] (no `else`). */
    fun actionVerb(action: SafetyAction, language: Language): String {
        requireRenderable(language)
        return if (language == Language.EN) enAction(action) else viAction(action)
    }

    private fun enObject(obj: SafetyObject): String = when (obj) {
        SafetyObject.MACHINE -> "the machine"
        SafetyObject.CONVEYOR_BELT -> "conveyor belt"
        SafetyObject.FORKLIFT -> "the forklift"
        SafetyObject.PRESSURE_VALVE -> "the pressure valve"
        SafetyObject.POWER -> "the power"
        SafetyObject.DOOR -> "the door"
        SafetyObject.EMERGENCY_STOP -> "the emergency stop"
        SafetyObject.HAZARD_ZONE -> "the hazard zone"
        SafetyObject.PROTECTIVE_EQUIPMENT -> "protective equipment"
        SafetyObject.SUPERVISOR -> "the supervisor"
        SafetyObject.LOCK -> "the lock"
        SafetyObject.TAG -> "the tag"
        SafetyObject.BREAKER -> "the breaker"
        SafetyObject.ISOLATION_POINT -> "the isolation point"
        SafetyObject.ELECTRICAL_PANEL -> "the electrical panel"
    }

    private fun viObject(obj: SafetyObject): String = when (obj) {
        SafetyObject.MACHINE -> "máy"
        SafetyObject.CONVEYOR_BELT -> "băng chuyền"
        SafetyObject.FORKLIFT -> "xe nâng"
        SafetyObject.PRESSURE_VALVE -> "van áp suất"
        SafetyObject.POWER -> "nguồn điện"
        SafetyObject.DOOR -> "cửa"
        SafetyObject.EMERGENCY_STOP -> "nút dừng khẩn cấp"
        SafetyObject.HAZARD_ZONE -> "khu vực nguy hiểm"
        SafetyObject.PROTECTIVE_EQUIPMENT -> "đồ bảo hộ"
        SafetyObject.SUPERVISOR -> "quản lý"
        SafetyObject.LOCK -> "khóa an toàn"
        SafetyObject.TAG -> "thẻ cảnh báo"
        SafetyObject.BREAKER -> "cầu dao"
        SafetyObject.ISOLATION_POINT -> "điểm cô lập"
        SafetyObject.ELECTRICAL_PANEL -> "tủ điện"
    }

    private fun enAction(action: SafetyAction): String = when (action) {
        SafetyAction.STOP -> "Stop"
        SafetyAction.START -> "Start"
        SafetyAction.ENTER -> "Enter"
        SafetyAction.EXIT -> "Exit"
        SafetyAction.OPEN -> "Open"
        SafetyAction.CLOSE -> "Close"
        SafetyAction.TURN_ON -> "Turn on"
        SafetyAction.TURN_OFF -> "Turn off"
        SafetyAction.CALL -> "Call"
        SafetyAction.WAIT -> "Wait"
        SafetyAction.EVACUATE -> "Evacuate"
        SafetyAction.WEAR -> "Wear"
        SafetyAction.MOVE -> "Move"
        SafetyAction.LOCK_OUT -> "Lock out"
        SafetyAction.TAG_OUT -> "Tag out"
        SafetyAction.DE_ENERGIZE -> "De-energize"
        SafetyAction.ENERGIZE -> "Energize"
        SafetyAction.VERIFY_DEAD -> "Verify dead"
        SafetyAction.ISOLATE -> "Isolate"
    }

    private fun viAction(action: SafetyAction): String = when (action) {
        SafetyAction.STOP -> "Dừng"
        SafetyAction.START -> "Khởi động"
        SafetyAction.ENTER -> "Vào"
        SafetyAction.EXIT -> "Ra"
        SafetyAction.OPEN -> "Mở"
        SafetyAction.CLOSE -> "Đóng"
        SafetyAction.TURN_ON -> "Bật"
        SafetyAction.TURN_OFF -> "Tắt"
        SafetyAction.CALL -> "Gọi"
        SafetyAction.WAIT -> "Chờ"
        SafetyAction.EVACUATE -> "Sơ tán"
        SafetyAction.WEAR -> "Mang"
        SafetyAction.MOVE -> "Di chuyển"
        SafetyAction.LOCK_OUT -> "Khóa nguồn"
        SafetyAction.TAG_OUT -> "Gắn thẻ cảnh báo"
        SafetyAction.DE_ENERGIZE -> "Ngắt điện"
        SafetyAction.ENERGIZE -> "Cấp điện"
        SafetyAction.VERIFY_DEAD -> "Kiểm tra hết điện"
        SafetyAction.ISOLATE -> "Cô lập"
    }

    private fun englishNumber(value: Int): String = when (value) {
        1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
        6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"; 10 -> "ten"
        else -> value.toString()
    }
}
