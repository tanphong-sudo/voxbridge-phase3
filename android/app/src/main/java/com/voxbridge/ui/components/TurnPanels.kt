package com.voxbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxbridge.ui.GuidanceTone
import com.voxbridge.ui.IdleCopy
import com.voxbridge.ui.LiveGuidance
import com.voxbridge.ui.RepeatGuidance
import com.voxbridge.ui.TentativeOutputPresentation
import com.voxbridge.ui.theme.Trust

private val PanelShape = RoundedCornerShape(24.dp)

/** One field per turn: the whole panel takes the state's tint, and a glyph repeats it in shape. */
@Composable
private fun Field(color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(color)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun StatusLine(glyph: GlyphKind, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyph, color, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.semantics { heading() })
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: GlyphKind? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled -> Trust.SurfaceDisabled
        primary -> Trust.Ink
        else -> Trust.Surface
    }
    val fg = when {
        !enabled -> Trust.DisabledText
        primary -> Color.White
        else -> Trust.Ink
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (!primary && enabled) Modifier.border(1.5.dp, Trust.SurfaceDisabled, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label }
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Glyph(glyph, fg, size = 24.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
fun IdlePanel(copy: IdleCopy) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(copy.title, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Trust.Ink)
        Text(copy.detail, fontSize = 19.sp, lineHeight = 27.sp, color = Trust.SecondaryText)
    }
}

@Composable
fun ListeningPanel(guidance: LiveGuidance) {
    val (field, ink, glyph) = when (guidance.tone) {
        GuidanceTone.GOOD -> Triple(Trust.GreenField, Trust.Green, GlyphKind.CHECK)
        GuidanceTone.WARNING -> Triple(Trust.AmberField, Trust.WarningInk, GlyphKind.ALERT)
        GuidanceTone.DANGER -> Triple(Trust.HazardField, Trust.Hazard, GlyphKind.ALERT)
        GuidanceTone.NEUTRAL -> Triple(Trust.NeutralField, Trust.Ink, GlyphKind.MIC)
    }
    Field(field) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, ink, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Text(guidance.title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Text(guidance.action, fontSize = 26.sp, lineHeight = 34.sp, color = Trust.Ink)
    }
}

@Composable
fun WorkingPanel(title: String, steps: List<String>, current: Int) {
    Field(Trust.NeutralField) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Trust.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, step ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (i <= current) Trust.Green else Trust.SurfaceDisabled),
                    )
                    Text(
                        step,
                        fontSize = 16.sp,
                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (i <= current) Trust.Ink else Trust.SecondaryText,
                    )
                }
            }
        }
    }
}

data class ResultPanelState(
    val targetLabel: String,
    val output: String,
    val transcriptLabel: String,
    val transcript: String,
    val speakingLabel: String?,
    val hazardLabel: String?,
    val confirmTitle: String?,
    val confirmDetail: String?,
    val playbackProblem: String?,
    val volumeHint: String?,
    val timing: String,
)

@Composable
fun ResultPanel(
    state: ResultPanelState,
    replayLabel: String,
    confirmLabel: String,
    raiseVolumeLabel: String,
    actionsEnabled: Boolean,
    onReplay: () -> Unit,
    onConfirm: () -> Unit,
    onRaiseVolume: () -> Unit,
) {
    val confirming = state.confirmTitle != null
    val field = when {
        confirming -> Trust.AmberField
        state.hazardLabel != null -> Trust.HazardField
        else -> Trust.GreenField
    }
    Field(field) {
        when {
            confirming -> StatusLine(GlyphKind.ALERT, state.confirmTitle.orEmpty(), Trust.WarningInk)
            state.hazardLabel != null -> StatusLine(GlyphKind.HAZARD, state.hazardLabel, Trust.Hazard)
            else -> StatusLine(GlyphKind.CHECK, state.targetLabel, Trust.Green)
        }
        Text(state.output, fontSize = 32.sp, lineHeight = 41.sp, fontWeight = FontWeight.Medium, color = Trust.Ink)
        if (state.speakingLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(GlyphKind.SPEAKER, Trust.SecondaryText, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.speakingLabel, fontSize = 16.sp, color = Trust.SecondaryText)
            }
        }
        if (confirming && state.confirmDetail != null) {
            Text(state.confirmDetail, fontSize = 18.sp, lineHeight = 25.sp, color = Trust.WarningInk)
        }
        if (state.playbackProblem != null) {
            Text(state.playbackProblem, fontSize = 18.sp, lineHeight = 25.sp, color = Trust.Hazard, fontWeight = FontWeight.SemiBold)
        }
        if (state.volumeHint != null) {
            Text(state.volumeHint, fontSize = 17.sp, lineHeight = 24.sp, color = Trust.WarningInk)
            ActionButton(raiseVolumeLabel, onRaiseVolume, glyph = GlyphKind.SPEAKER)
        }
        if (confirming) {
            ActionButton(confirmLabel, onConfirm, glyph = GlyphKind.SPEAKER, primary = true, enabled = actionsEnabled)
        } else {
            ActionButton(replayLabel, onReplay, glyph = GlyphKind.SPEAKER, enabled = actionsEnabled)
        }
        if (state.transcript.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.transcriptLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Trust.SecondaryText)
                Text(state.transcript, fontSize = 18.sp, lineHeight = 25.sp, color = Trust.SecondaryText)
            }
        }
        if (state.timing.isNotBlank()) Text(state.timing, fontSize = 13.sp, color = Trust.SecondaryText)
    }
}

/** Ask-repeat and no-speech share one shape: what happened, then the one thing to change. */
@Composable
fun RetryPanel(guidance: RepeatGuidance, noSpeech: Boolean, heardLabel: String, heard: String) {
    val field = if (noSpeech) Trust.NeutralField else Trust.AmberField
    val ink = if (noSpeech) Trust.Ink else Trust.WarningInk
    Field(field) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (noSpeech) GlyphKind.SILENT else GlyphKind.REPEAT, ink, size = 34.dp)
            Spacer(Modifier.width(14.dp))
            Text(guidance.title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Text(guidance.action, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = Trust.Ink)
        if (heard.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heardLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Trust.SecondaryText)
                Text(heard, fontSize = 18.sp, lineHeight = 25.sp, color = Trust.SecondaryText)
            }
        }
    }
}

/** Debug-build-only draft of a rejected turn; never playable, never presented as a translation. */
@Composable
fun DraftPanel(draft: TentativeOutputPresentation) {
    Column(
        Modifier.fillMaxWidth().clip(PanelShape).border(2.dp, Trust.Hazard, PanelShape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusLine(GlyphKind.ALERT, draft.warningLabel, Trust.Hazard)
        Text(draft.sourceLabel, fontSize = 15.sp, color = Trust.SecondaryText)
        Text(draft.sourceText, fontSize = 18.sp, color = Trust.Ink)
        if (draft.translatedText != null) {
            Text(draft.translationLabel.orEmpty(), fontSize = 15.sp, color = Trust.SecondaryText)
            Text(draft.translatedText, fontSize = 18.sp, color = Trust.Ink)
        }
        for (hint in draft.hints) Text(hint, fontSize = 15.sp, color = Trust.Hazard)
    }
}

@Composable
fun ProgressPanel(title: String, detail: String) {
    Field(Trust.NeutralField) {
        Text(title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Trust.Ink)
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Trust.Green, trackColor = Trust.SurfaceDisabled)
        if (detail.isNotBlank()) Text(detail, fontSize = 17.sp, lineHeight = 24.sp, color = Trust.SecondaryText)
    }
}

@Composable
fun ProblemPanel(title: String, detail: String, actionLabel: String?, onAction: () -> Unit) {
    Field(Trust.HazardField) {
        StatusLine(GlyphKind.ALERT, title, Trust.Hazard)
        Text(detail, fontSize = 20.sp, lineHeight = 28.sp, color = Trust.Ink)
        if (actionLabel != null) ActionButton(actionLabel, onAction, primary = true)
    }
}
