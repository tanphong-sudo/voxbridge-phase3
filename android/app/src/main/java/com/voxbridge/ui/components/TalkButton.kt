package com.voxbridge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxbridge.ui.GuidanceTone
import com.voxbridge.ui.runPushToTalkHold
import com.voxbridge.ui.theme.Trust

/** What the slab shows; built by the screen from ViewModel state and [com.voxbridge.ui.TurnPresentation]. */
data class TalkSlabState(
    val enabled: Boolean,
    val listening: Boolean,
    /** Large label: "Giữ để nói" at rest, "Thả để dịch" while held, or why it is unavailable. */
    val label: String,
    /** Second line: the language to speak, or the step in progress. */
    val detail: String,
    val tone: GuidanceTone = GuidanceTone.NEUTRAL,
    val levelDbfs: Double = -120.0,
    val timerText: String = "",
    val nearLimit: Boolean = false,
)

private const val METER_BARS = 36
private const val COMPACT_SCREEN_HEIGHT_DP = 700
private const val METER_FLOOR_DBFS = -60.0
private const val METER_RANGE_DB = 48.0

/**
 * The one control a worker needs: a full-width slab in the thumb zone, sized for a gloved hand.
 *
 * Press-and-hold records; release translates. While held, the slab itself becomes the microphone
 * meter: a rolling history of the real input level runs across its bottom edge and the fill color
 * follows the live quality state, so the speaker sees "am I recording, and is it coming through"
 * without looking anywhere else. Disabled until the mic permission is granted and the pipeline is
 * idle, because a new capture cannot start while a turn is still being processed.
 */
@Composable
fun TalkButton(
    state: TalkSlabState,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = when {
        !state.enabled -> Trust.SurfaceDisabled
        !state.listening -> Trust.SlabRest
        state.tone == GuidanceTone.GOOD -> Trust.SlabGood
        state.tone == GuidanceTone.WARNING -> Trust.SlabWarning
        state.tone == GuidanceTone.DANGER -> Trust.SlabDanger
        else -> Trust.SlabNeutral
    }
    val fill by animateColorAsState(target, animationSpec = tween(180), label = "slab")
    // A busy slab is a status line, not a dead control: keep its text readable (>= 4.5:1).
    val ink = if (state.enabled) Color.White else Trust.SecondaryText
    val muted = if (state.enabled) Trust.OnSlabMuted else Trust.SecondaryText
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP

    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.listening) { if (state.listening) history.clear() }
    LaunchedEffect(state.levelDbfs, state.listening) {
        if (!state.listening) return@LaunchedEffect
        history += ((state.levelDbfs - METER_FLOOR_DBFS) / METER_RANGE_DB).coerceIn(0.0, 1.0).toFloat()
        while (history.size > METER_BARS) history.removeAt(0)
    }

    val gesture = if (!state.enabled) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = { runPushToTalkHold(onPressStart, onPressEnd) { tryAwaitRelease() } })
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) 136.dp else 168.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(fill)
            .then(gesture)
            .semantics {
                role = Role.Button
                contentDescription = listOf(state.label, state.detail, state.timerText).filter { it.isNotBlank() }.joinToString(". ")
                if (!state.enabled) disabled()
                else onClick(label = state.label) {
                    if (state.listening) onPressEnd() else onPressStart()
                    true
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 24.dp, top = if (compact) 20.dp else 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(GlyphKind.MIC, ink, size = 44.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.label, fontSize = if (compact) 24.sp else 28.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = if (state.listening) 1 else 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.detail, fontSize = 17.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1)
                    if (state.listening && state.timerText.isNotBlank()) {
                        Text(
                            state.timerText,
                            fontSize = if (state.nearLimit) 20.sp else 17.sp,
                            fontWeight = if (state.nearLimit) FontWeight.Bold else FontWeight.Medium,
                            color = ink,
                        )
                    }
                }
            }
        }
        if (state.listening) LevelTrace(history, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LevelTrace(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(40.dp).padding(start = 28.dp, end = 28.dp, bottom = 18.dp)) {
        val slot = size.width / METER_BARS
        val barWidth = slot * 0.55f
        // Newest sample on the right; empty slots on the left draw as a flat baseline.
        val offset = METER_BARS - levels.size
        for (i in 0 until METER_BARS) {
            val level = levels.getOrNull(i - offset) ?: 0f
            val h = (size.height * (0.12f + 0.88f * level)).coerceAtLeast(barWidth)
            drawRoundRect(
                color = Color.White.copy(alpha = if (level > 0f) 0.9f else 0.35f),
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
