package com.voxbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line glyphs drawn in Canvas. Each trust state carries a SHAPE as well as a color (never color
 * alone), and drawing them avoids both emoji (which render differently per OEM) and an icon
 * dependency the APK does not otherwise need.
 */
enum class GlyphKind { MIC, CHECK, REPEAT, SILENT, HAZARD, SPEAKER, SWAP, ALERT }

@Composable
fun Glyph(kind: GlyphKind, color: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            GlyphKind.MIC -> mic(color, stroke)
            GlyphKind.CHECK -> check(color, stroke)
            GlyphKind.REPEAT -> repeat(color, stroke)
            GlyphKind.SILENT -> silent(color, stroke)
            GlyphKind.HAZARD -> hazard(color, stroke)
            GlyphKind.SPEAKER -> speaker(color, stroke)
            GlyphKind.SWAP -> swap(color, stroke)
            GlyphKind.ALERT -> alert(color, stroke)
        }
    }
}

private fun DrawScope.u(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private fun DrawScope.mic(c: Color, s: Stroke) {
    drawRoundRect(c, topLeft = u(.36f, .08f), size = Size(size.width * .28f, size.height * .5f),
        cornerRadius = CornerRadius(size.width * .14f))
    drawArc(c, 0f, 180f, false, topLeft = u(.22f, .3f), size = Size(size.width * .56f, size.height * .46f), style = s)
    drawLine(c, u(.5f, .76f), u(.5f, .9f), s.width, StrokeCap.Round)
    drawLine(c, u(.34f, .92f), u(.66f, .92f), s.width, StrokeCap.Round)
}

private fun DrawScope.check(c: Color, s: Stroke) {
    drawCircle(c, radius = size.minDimension * .44f, style = s)
    val p = Path().apply { moveTo(size.width * .3f, size.height * .52f); lineTo(size.width * .45f, size.height * .66f); lineTo(size.width * .71f, size.height * .38f) }
    drawPath(p, c, style = s)
}

private fun DrawScope.repeat(c: Color, s: Stroke) {
    drawArc(c, -60f, 300f, false, topLeft = u(.14f, .14f), size = Size(size.width * .72f, size.height * .72f), style = s)
    val tip = u(.68f, .2f)
    val p = Path().apply {
        moveTo(tip.x - size.width * .2f, tip.y - size.height * .04f); lineTo(tip.x, tip.y); lineTo(tip.x - size.width * .02f, tip.y + size.height * .2f)
    }
    drawPath(p, c, style = s)
}

private fun DrawScope.silent(c: Color, s: Stroke) {
    mic(c, s)
    drawLine(c, u(.12f, .1f), u(.88f, .9f), s.width, StrokeCap.Round)
}

private fun DrawScope.hazard(c: Color, s: Stroke) {
    val p = Path().apply { moveTo(size.width * .5f, size.height * .08f); lineTo(size.width * .94f, size.height * .88f); lineTo(size.width * .06f, size.height * .88f); close() }
    drawPath(p, c, style = s)
    drawLine(c, u(.5f, .38f), u(.5f, .6f), s.width, StrokeCap.Round)
    drawCircle(c, radius = s.width * .7f, center = u(.5f, .74f))
}

private fun DrawScope.speaker(c: Color, s: Stroke) {
    val p = Path().apply {
        moveTo(size.width * .1f, size.height * .38f); lineTo(size.width * .3f, size.height * .38f); lineTo(size.width * .52f, size.height * .16f)
        lineTo(size.width * .52f, size.height * .84f); lineTo(size.width * .3f, size.height * .62f); lineTo(size.width * .1f, size.height * .62f); close()
    }
    drawPath(p, c, style = s)
    drawArc(c, -45f, 90f, false, topLeft = u(.42f, .3f), size = Size(size.width * .3f, size.height * .4f), style = s)
    drawArc(c, -50f, 100f, false, topLeft = u(.4f, .14f), size = Size(size.width * .5f, size.height * .72f), style = s)
}

private fun DrawScope.swap(c: Color, s: Stroke) {
    drawLine(c, u(.12f, .34f), u(.86f, .34f), s.width, StrokeCap.Round)
    drawPath(Path().apply { moveTo(size.width * .66f, size.height * .16f); lineTo(size.width * .86f, size.height * .34f); lineTo(size.width * .66f, size.height * .52f) }, c, style = s)
    drawLine(c, u(.88f, .68f), u(.14f, .68f), s.width, StrokeCap.Round)
    drawPath(Path().apply { moveTo(size.width * .34f, size.height * .5f); lineTo(size.width * .14f, size.height * .68f); lineTo(size.width * .34f, size.height * .86f) }, c, style = s)
}

private fun DrawScope.alert(c: Color, s: Stroke) {
    drawCircle(c, radius = size.minDimension * .44f, style = s)
    drawLine(c, u(.5f, .26f), u(.5f, .56f), s.width, StrokeCap.Round)
    drawCircle(c, radius = s.width * .7f, center = u(.5f, .72f))
}
