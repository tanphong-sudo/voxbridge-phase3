package com.voxbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxbridge.core.model.Language
import com.voxbridge.ui.QUICK_PHRASES
import com.voxbridge.ui.QuickPhrase
import com.voxbridge.ui.theme.Trust

/**
 * One-tap safety phrases above the talk slab, two by two. Each button shows the phrase in the
 * speaker's language and plays it in the listener's; urgent ones carry the hazard mark and the
 * hazard tint so they can be found without reading.
 */
@Composable
fun QuickPhraseBar(
    source: Language,
    target: Language,
    enabled: Boolean,
    playsInLabel: String,
    onPlay: (QuickPhrase) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QUICK_PHRASES.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { phrase ->
                    QuickPhraseButton(
                        phrase.text(source), "$playsInLabel: ${phrase.text(target)}",
                        phrase.urgent, enabled, Modifier.weight(1f),
                    ) { onPlay(phrase) }
                }
            }
        }
    }
}

@Composable
private fun QuickPhraseButton(
    label: String,
    spokenDescription: String,
    urgent: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = when {
        !enabled -> Trust.SurfaceDisabled
        urgent -> Trust.HazardField
        else -> Trust.Surface
    }
    val fg = when {
        !enabled -> Trust.DisabledText
        urgent -> Trust.Hazard
        else -> Trust.Ink
    }
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (enabled) Modifier.border(1.5.dp, if (urgent) Trust.Hazard.copy(alpha = 0.35f) else Trust.SurfaceDisabled, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "$label. $spokenDescription" }
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (urgent) GlyphKind.HAZARD else GlyphKind.SPEAKER, fg, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 2)
    }
}
