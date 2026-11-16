package com.voxbridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voxbridge.ui.theme.Scale
import com.voxbridge.ui.theme.Trust

/**
 * The single most important thing on the screen: the restored safety slot, rendered enormous.
 *
 * When the Safety Preservation Layer repairs a dropped/flipped slot, THAT slot — the machine number
 * "7", the bay "B-12", the prohibition "KHÔNG / DO NOT" — is what the worker must not miss. We show
 * it at `Scale.glyph` (96sp black), centered, above the full sentence. Slot ids are rendered as the
 * raw string the core produced (`repairedSlots`), never re-translated or interpreted here. A
 * negation slot is shown as the bilingual prohibition so it reads in either language at a glance.
 *
 * STATUS: Compose UI written against the ViewModel state; not rendered/compiled here.
 */
@Composable
fun RepairedGlyph(repairedSlots: String, modifier: Modifier = Modifier) {
    if (repairedSlots.isBlank()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = repairedSlots,
            style = Scale.glyph,
            color = Trust.OnField,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "restored · đã khôi phục",
            style = Scale.label,
            color = Trust.OnField,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
