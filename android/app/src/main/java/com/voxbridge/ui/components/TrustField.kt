package com.voxbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.voxbridge.ui.TrustLevel
import com.voxbridge.ui.theme.Trust

/**
 * Edge-to-edge trust field: the whole background is the traffic-light color, and a bold
 * language-independent SHAPE is stroked over it so trust never depends on color alone (UX-5,
 * accessibility for color-vision deficiency + glare):
 *
 *   GREEN -> check mark            (safe, act on it)
 *   AMBER -> circular retry arrow  (say it again)
 *   GREY  -> muted mic-off slash   (nothing heard)
 *
 * The glyphs are drawn with Canvas, not an icon font, so there is no material-icons-extended
 * dependency (keeps the APK on the on-device size budget). When `hazard` is set, a red stripe is
 * laid across the top edge as a separate danger accent — independent of the trust color, so a safe
 * translation that *contains* a warning ("do NOT enter") still reads GREEN + hazard stripe.
 *
 * STATUS: Compose UI written against the trust model; not rendered/compiled here.
 */
@Composable
fun TrustField(level: TrustLevel, hazard: Boolean, modifier: Modifier = Modifier) {
    val field = when (level) {
        TrustLevel.GREEN -> Trust.Green
        TrustLevel.AMBER -> Trust.Amber
        TrustLevel.GREY -> Trust.Grey
    }
    Box(modifier.background(field), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.42f               // bias the glyph above center; text sits below it
            val r = minOf(w, h) * 0.14f
            val stroke = Stroke(width = r * 0.34f, cap = StrokeCap.Round)

            when (level) {
                TrustLevel.GREEN -> {
                    // Check mark: short down-stroke into a long up-stroke.
                    val p0 = Offset(cx - r, cy)
                    val p1 = Offset(cx - r * 0.25f, cy + r * 0.8f)
                    val p2 = Offset(cx + r * 1.1f, cy - r * 0.9f)
                    drawLine(Trust.OnField, p0, p1, stroke.width, stroke.cap)
                    drawLine(Trust.OnField, p1, p2, stroke.width, stroke.cap)
                }
                TrustLevel.AMBER -> {
                    // Circular retry arrow: a near-full arc with a small arrowhead.
                    drawArc(
                        color = Trust.OnField,
                        startAngle = 40f, sweepAngle = 280f, useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = stroke,
                    )
                    // Arrowhead at the arc's open end (~40°, lower-right).
                    val tip = Offset(cx + r * 0.77f, cy + r * 0.64f)
                    drawLine(Trust.OnField, tip, Offset(tip.x - r * 0.5f, tip.y - r * 0.15f), stroke.width, stroke.cap)
                    drawLine(Trust.OnField, tip, Offset(tip.x + r * 0.1f, tip.y - r * 0.55f), stroke.width, stroke.cap)
                }
                TrustLevel.GREY -> {
                    // Muted mic-off: a small mic capsule with a diagonal slash through it.
                    val capW = r * 0.9f
                    drawRoundRect(
                        color = Trust.OnField,
                        topLeft = Offset(cx - capW / 2, cy - r),
                        size = Size(capW, r * 1.6f),
                        cornerRadius = CornerRadius(capW / 2, capW / 2),
                        style = stroke,
                    )
                    drawLine(Trust.OnField, Offset(cx - r, cy - r), Offset(cx + r, cy + r), stroke.width, stroke.cap)
                }
            }
        }
        if (hazard) {
            // Top-edge danger stripe — a separate accent, NOT the trust color.
            Box(Modifier.fillMaxWidth().height(12.dp).background(Trust.Hazard))
        }
    }
}
