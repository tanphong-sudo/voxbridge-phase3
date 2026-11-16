package com.voxbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxbridge.BuildConfig
import com.voxbridge.ui.theme.Scale
import com.voxbridge.ui.theme.Trust

/** Developer evidence for debug builds; never part of the worker-facing flow. */
@Composable
internal fun DebugPanel(s: PushToTalkViewModel.UiState, vm: PushToTalkViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Trust.SurfaceSubtle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Audio / ASR diagnostics", style = Scale.body, color = Trust.Ink, fontWeight = FontWeight.Bold)
        Text(s.modelStatus, style = Scale.label, color = Trust.SecondaryText)
        Text(s.debugPipeline, style = Scale.label, color = Trust.SecondaryText)
        Text(s.debugTtsStatus, style = Scale.label, color = Trust.SecondaryText)
        if (BuildConfig.LIVE_AUDIO_STATUS && s.listening) {
            val quality = s.liveAudioQuality
            Text(
                "Live quality: ${quality?.state ?: "NO_SPEECH"}\n" +
                    "RMS ${formatAudioQualityValue(quality?.rmsDbfs)} dBFS · " +
                    "Noise ${formatAudioQualityValue(quality?.noiseFloorDbfs)} dBFS\n" +
                    "Contrast ${formatAudioQualityValue(quality?.snrProxyDb)} dB proxy · " +
                    "VAD ${formatAudioQualityValue(quality?.vadProbability?.toDouble())}\n" +
                    "Noise estimate: ${if (quality?.noiseReady == true) "ready" else "learning / unavailable"}",
                style = Scale.label,
                color = Trust.SecondaryText,
            )
        }
        if (BuildConfig.LIVE_AUDIO_STATUS) {
            DebugAction("Live audio vibration: ${if (s.liveAudioHapticsEnabled) "On" else "Off"}") {
                vm.setLiveAudioHapticsEnabled(!s.liveAudioHapticsEnabled)
            }
        }

        DebugAction(if (s.listening) "Stop test" else "Record test") {
            if (s.listening) vm.onPressEnd() else vm.onPressStart()
        }
        DebugAction("Play capture", vm::playLastDebugCapture)
        DebugAction("Run ASR again", vm::runAsrOnSavedDebugAudio)
        DebugAction("Export WAV + trace", vm::exportDebugArtifacts)
        DebugAction("Copy debug report") { clipboard.setText(AnnotatedString(s.debugReport)) }

        Spacer(Modifier.height(2.dp))
        Text(s.debugReport, style = Scale.label, color = Trust.Ink, modifier = Modifier.fillMaxWidth())
        if (BuildConfig.LIVE_AUDIO_STATUS) {
            Text(s.audioQualityReport, style = Scale.label, color = Trust.Ink, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DebugAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Trust.SurfaceAction)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Scale.label, color = Trust.Ink, fontWeight = FontWeight.Bold)
    }
}
