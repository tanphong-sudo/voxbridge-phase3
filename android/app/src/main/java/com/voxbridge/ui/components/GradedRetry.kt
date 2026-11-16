package com.voxbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.voxbridge.ui.RetryHint
import com.voxbridge.ui.theme.Scale
import com.voxbridge.ui.theme.Trust

/**
 * The AMBER "say it again" panel (UX-20..UX-23). Two parts:
 *   - a language-independent hint glyph + bilingual caption telling the worker WHAT to change
 *     (closer / softer / slower) — framed as the device's limitation, never "you spoke wrong";
 *   - the partial transcript ASR did manage to catch, greyed, so the worker can see how far it got.
 *
 * The hint glyph is Canvas-drawn (no icon dependency), matching TrustField.
 *
 * STATUS: Compose UI written against the trust model; not rendered/compiled here.
 */
@Composable
fun GradedRetry(
    hint: RetryHint,
    captionVi: String,
    captionEn: String,
    partialTranscript: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp)) {
                Canvas(Modifier.fillMaxWidth().size(72.dp)) {
                    val w = size.width; val h = size.height
                    val cx = w / 2f; val cy = h / 2f
                    val r = minOf(w, h) * 0.34f
                    val stroke = Stroke(width = r * 0.32f, cap = StrokeCap.Round)
                    when (hint) {
                        // MIC_CLOSER: a down-arrow into a bar (bring it toward you).
                        RetryHint.MIC_CLOSER -> {
                            drawLine(Trust.OnField, Offset(cx, cy - r), Offset(cx, cy + r * 0.4f), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx - r * 0.6f, cy - r * 0.2f), Offset(cx, cy + r * 0.4f), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx + r * 0.6f, cy - r * 0.2f), Offset(cx, cy + r * 0.4f), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx - r, cy + r), Offset(cx + r, cy + r), stroke.width, stroke.cap)
                        }
                        // SOFTER: descending bars (turn it down).
                        RetryHint.SOFTER -> {
                            drawLine(Trust.OnField, Offset(cx - r, cy - r * 0.8f), Offset(cx - r, cy + r), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx, cy - r * 0.1f), Offset(cx, cy + r), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx + r, cy + r * 0.5f), Offset(cx + r, cy + r), stroke.width, stroke.cap)
                        }
                        // SLOWER: a circle with a clock hand (take your time).
                        RetryHint.SLOWER -> {
                            drawArc(Trust.OnField, 0f, 360f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = stroke)
                            drawLine(Trust.OnField, Offset(cx, cy), Offset(cx, cy - r * 0.7f), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx, cy), Offset(cx + r * 0.5f, cy), stroke.width, stroke.cap)
                        }
                        // GENERIC: an ear / question — a simple "?" stroke.
                        RetryHint.GENERIC -> {
                            drawArc(Trust.OnField, 200f, 220f, false, Offset(cx - r, cy - r), Size(r * 2, r * 1.4f), style = stroke)
                            drawLine(Trust.OnField, Offset(cx + r * 0.2f, cy + r * 0.1f), Offset(cx, cy + r * 0.5f), stroke.width, stroke.cap)
                            drawLine(Trust.OnField, Offset(cx, cy + r * 0.9f), Offset(cx, cy + r), stroke.width, stroke.cap)
                        }
                    }
                }
            }
            Column {
                Text(captionVi, style = Scale.headline, color = Trust.OnField)
                Text(captionEn, style = Scale.body, color = Trust.OnField)
            }
        }
        if (partialTranscript.isNotBlank()) {
            Text(
                text = "…$partialTranscript…",
                style = Scale.body,
                color = Trust.OnField.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
