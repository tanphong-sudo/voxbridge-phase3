package com.voxbridge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Traffic-light field palette for the worker-facing safety HMI (doc 14 §3, §5).
 *
 * These are FULL-BLEED field colors, not accents: a worker reads the screen at arm's length, in
 * glare, possibly color-vision-deficient, so trust is carried by the whole field + a shape glyph
 * (never color alone — UX-5). Each field color is chosen dark enough that WHITE text/glyphs stay
 * legible on it under direct sun. The hazard red is a SEPARATE accent stripe, never a trust state,
 * so "danger inside a safe translation" (e.g. "do NOT enter") still reads GREEN-with-hazard rather
 * than being miscolored as a failure.
 *
 * STATUS: Compose color tokens; values are design constants, not rendered/measured here.
 */
object Trust {
    // Light canvas for the primary translation screen; physical glare testing remains required.
    val Background = Color(0xFFF7F9FA)
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFEEF2F4)
    val SurfaceAction = Color(0xFFE5EBEE)
    val SurfaceDisabled = Color(0xFFD8E0E4)
    val SecondaryText = Color(0xFF52606D)
    val DisabledText = Color(0xFF7B8790)
    val GreenBright = Color(0xFF087F5B)
    val Result = Color(0xFFE5F5EC)
    val Green = Color(0xFF0B6E4F)   // safe / act on it — VERIFIED, REPAIRED-confirmed, TRANSLATED, MEMORY
    val Amber = Color(0xFFB5651D)   // say it again — ASK_REPEAT (device's fault, not the worker's)
    val Grey = Color(0xFF5B6166)    // nothing heard — NO_SPEECH (neutral, not an error)
    val Hazard = Color(0xFF8A1C1C)  // hazard accent stripe over any field when the message itself warns
    val Ink = Color(0xFF17212B)
    val WarningInk = Color(0xFF814509)
    val OnField = Color.White // legacy saturated safety fields retain white text
    val Offline = Color(0xFF087F5B)
    val FieldDim = Color(0x14000000)

    // Turn-panel fields: pale tints of the trust colors so 30sp text stays at AAA contrast in glare.
    val GreenField = Result
    val AmberField = Color(0xFFFBEEDB)
    val HazardField = Color(0xFFF8E3E1)
    val NeutralField = SurfaceSubtle
    // Talk slab fills: each keeps white bold text at or above 4.5:1.
    val SlabRest = Ink
    val SlabGood = Green
    val SlabWarning = Color(0xFF9A5414)
    val SlabDanger = Hazard
    val SlabNeutral = Color(0xFF2F4A5C)
    val OnSlabMuted = Color(0xCCFFFFFF)
}
