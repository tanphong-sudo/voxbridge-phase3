package com.voxbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voxbridge.ui.theme.Scale
import com.voxbridge.ui.theme.Trust

/**
 * Large, glove-spaced action buttons (UX-16, UX-30). Which actions are shown depends on the state,
 * driven by the locked ViewModel flags:
 *
 *   - `awaitingConfirm` (REPAIRED): a single wide CONFIRM & PLAY button — the rewritten instruction
 *     is only spoken after this explicit tap (FR-17).
 *   - `canApprove` (a spoken translation is on screen): Approve ✓ / Wrong ✗ feed the Local Learning
 *     Loop via the verified FeedbackPromoter.
 *   - Always: an "Understood / Đã hiểu" receipt — a UI-LOCAL acknowledgement distinct from Approve
 *     (UX-30: the listener confirming they got it is not the same as the speaker rating the
 *     translation), plus a reply-swap that flips the direction for the other person to answer.
 *
 * Understood fires a SAFE haptic and a local confirmation only; it never calls the ViewModel, so it
 * can't be confused with feedback.
 *
 * STATUS: Compose UI written against the ViewModel state; not rendered/compiled here.
 */
@Composable
fun BigActionRow(
    awaitingConfirm: Boolean,
    canApprove: Boolean,
    onConfirmRepaired: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onUnderstood: () -> Unit,
    onSwapReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (awaitingConfirm) {
            ActionTile("⚠ Confirm & play · Xác nhận & phát", Trust.Amber, Modifier.fillMaxWidth(), onConfirmRepaired)
        }
        if (canApprove) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile("✓ Correct · Đúng", Trust.Green, Modifier.weight(1f), onApprove)
                ActionTile("✗ Wrong · Sai", Trust.Hazard, Modifier.weight(1f), onReject)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Understood · Đã hiểu", Trust.Grey, Modifier.weight(1f), onUnderstood)
            ActionTile("Reply · Trả lời ⇄", Trust.Grey, Modifier.weight(1f), onSwapReply)
        }
    }
}

@Composable
private fun ActionTile(label: String, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Scale.body, color = Trust.OnField, textAlign = TextAlign.Center)
    }
}
