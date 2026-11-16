package com.voxbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voxbridge.ui.theme.Scale
import com.voxbridge.ui.theme.Trust

/**
 * The presented result a worker holds up for the other person to read across a bench (UX-11): the
 * repaired glyph (if any), then the full translated sentence, large and centered. When `faceFlip` is
 * on, the whole block is rotated 180° so it reads right-way-up to someone standing opposite.
 *
 * For a REPAIRED result we also show the raw model text ("model said") in a dim panel beside the
 * safe `output`, so the catch-and-fix is legible — that contrast is the product's core story (the
 * model dropped "máy số 7", the Safety Layer put it back). For non-repaired results only the clean
 * sentence shows.
 *
 * STATUS: Compose UI written against the ViewModel state; not rendered/compiled here.
 */
@Composable
fun PresentedResult(
    sentence: String,
    repairedSlots: String,
    modelTextIfUnsafe: String,
    faceFlip: Boolean,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (repairedSlots.isNotBlank()) RepairedGlyph(repairedSlots)
            if (sentence.isNotBlank()) {
                Text(
                    text = sentence,
                    style = Scale.headline,
                    color = Trust.OnField,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (modelTextIfUnsafe.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Trust.FieldDim)
                        .padding(12.dp),
                ) {
                    Text("model said (unsafe)", style = Scale.label, color = Trust.OnField.copy(alpha = 0.7f))
                    Text(modelTextIfUnsafe, style = Scale.body, color = Trust.OnField, fontWeight = FontWeight.Normal)
                }
            }
        }
    }

    if (faceFlip) {
        Column(modifier.rotate(180f)) { content() }
    } else {
        Column(modifier) { content() }
    }
}
