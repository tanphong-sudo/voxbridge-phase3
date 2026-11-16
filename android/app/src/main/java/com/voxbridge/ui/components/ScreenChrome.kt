package com.voxbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxbridge.core.model.Language
import com.voxbridge.ui.ownLanguageName
import com.voxbridge.ui.theme.Trust

/** App name and the language-direction switch at the top of the main screen. */
@Composable
fun Header() {
    Text(
        "VoxBridge", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Trust.Ink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
fun DirectionControl(source: Language, target: Language, enabled: Boolean, swapLabel: String, onSwap: () -> Unit) {
    val description = "$swapLabel: ${ownLanguageName(source)} → ${ownLanguageName(target)}"
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(20.dp))
            .background(Trust.Surface)
            .border(1.5.dp, Trust.SurfaceDisabled, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onSwap)
            .semantics { role = Role.Button; contentDescription = description }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageTag(ownLanguageName(source), speaking = true, modifier = Modifier.weight(1f))
        Glyph(GlyphKind.SWAP, Trust.Ink, Modifier.padding(horizontal = 10.dp), size = 28.dp)
        LanguageTag(ownLanguageName(target), speaking = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LanguageTag(name: String, speaking: Boolean, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (speaking) Trust.Ink else Color.Transparent)
            .defaultMinSize(minHeight = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (speaking) Color.White else Trust.Ink)
    }
}

