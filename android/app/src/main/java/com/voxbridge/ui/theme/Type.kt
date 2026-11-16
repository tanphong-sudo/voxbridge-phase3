package com.voxbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Oversized type scale for arm's-length, glove-and-glare reading (doc 14 §5, UX-4/UX-6).
 *
 * A factory worker is not holding the phone 30 cm from their face in good light — they glance at it
 * across a bench, in noise, in sun. So the scale is deliberately huge: the single repaired slot
 * ("7", "B-12", "KHÔNG") is the largest thing on screen, the trust word/sentence is next, and raw
 * engineering text is demoted. We do NOT depend on a bundled font (no res/ dir, no font assets that
 * bloat the APK off the on-device budget) — the system font at these weights/sizes is legible.
 *
 * STATUS: Compose typography tokens; not rendered/measured here.
 */
object Scale {
    /** The restored safety slot — the single most important glyph on the screen. */
    val glyph = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 0.sp)
    /** The trust word or the translated sentence presented across the bench. */
    val headline = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)
    /** Compact screen title; preserve headline size for the translated sentence. */
    val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    /** Body: transcript, model-said text, captions. */
    val body = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal)
    /** Small labels: latency, offline badge, field labels. */
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

/**
 * Material3 typography mapped onto the oversized scale, so any stray Material component that reads
 * the theme typography still renders at field-legible sizes rather than the 14sp Material default.
 */
val OneVoiceTypography = Typography(
    displayLarge = Scale.glyph,
    headlineLarge = Scale.headline,
    headlineMedium = Scale.headline,
    titleLarge = Scale.title,
    bodyLarge = Scale.body,
    bodyMedium = Scale.body,
    labelLarge = Scale.label,
    labelMedium = Scale.label,
    labelSmall = Scale.label,
)
