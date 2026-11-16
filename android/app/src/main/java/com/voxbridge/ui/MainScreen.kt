package com.voxbridge.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxbridge.BuildConfig
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.ui.components.ActionButton
import com.voxbridge.ui.components.DirectionControl
import com.voxbridge.ui.components.Header
import com.voxbridge.ui.components.DraftPanel
import com.voxbridge.ui.components.Glyph
import com.voxbridge.ui.components.GlyphKind
import com.voxbridge.ui.components.IdlePanel
import com.voxbridge.ui.components.ListeningPanel
import com.voxbridge.ui.components.ProblemPanel
import com.voxbridge.ui.components.ProgressPanel
import com.voxbridge.ui.components.QuickPhraseBar
import com.voxbridge.ui.components.ResultPanel
import com.voxbridge.ui.components.ResultPanelState
import com.voxbridge.ui.components.RetryPanel
import com.voxbridge.ui.components.TalkButton
import com.voxbridge.ui.components.TalkSlabState
import com.voxbridge.ui.components.WorkingPanel
import com.voxbridge.ui.theme.Trust
import kotlinx.coroutines.delay

private const val HOLD_TICK_MS = 250L
private const val AUTO_RETRY_DELAY_MS = 1_500L
private const val RAISED_VOLUME_FRACTION = 0.8f

/**
 * The worker's whole app on one screen: language direction on top, one panel describing the
 * current turn in the middle, and the hold-to-talk slab in the thumb zone. Every turn state has
 * its own panel; every failure says what happened and the one thing to do next. Developer
 * evidence stays behind Diagnostics in debug builds.
 */
@Composable
fun MainScreen(
    vm: PushToTalkViewModel,
    haptics: Haptics,
    micGranted: Boolean = true,
    onRequestMic: () -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val source = s.direction.first
    val target = s.direction.second
    val vi = source == Language.VI
    fun t(viText: String, enText: String) = if (vi) viText else enText

    val mode = turnModeOf(s.pipelineStage, s.listening, s.decision, s.output)
    val level = trustOf(s.decision)
    val hazard = level == TrustLevel.GREEN && containsHazardCue(s.output + " " + s.repairedSlots)
    val tentative = tentativeOutputPresentation(s.decision, source, s.tentativeOutput, BuildConfig.RETRY_DRAFTS)
    val guidance = liveGuidance(s.liveAudioQuality?.state, source)
    var debugOpen by remember { mutableStateOf(false) }

    KeepScreenOn()
    TurnHaptics(vm, haptics, s.decision, level, hazard, s.listening, s.liveAudioQuality?.state)

    // Hold timer: the capture is capped, so show elapsed time and count down near the cap.
    var holdElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s.listening) {
        holdElapsedMs = 0L
        if (!s.listening) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            holdElapsedMs = System.currentTimeMillis() - startedAt
            delay(HOLD_TICK_MS)
        }
    }
    val holdLimit = CaptureAnalysis.MAX_CAPTURE_MS

    // Self-recovery: a failed direction load is retried once on its own before asking the worker.
    var autoRetries by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode) {
        if (mode == TurnMode.IDLE || mode == TurnMode.RESULT) autoRetries = 0
        if (mode == TurnMode.PREPARATION_FAILED && autoRetries == 0) {
            delay(AUTO_RETRY_DELAY_MS)
            autoRetries = 1
            vm.retryDirectionPreparation()
        }
    }

    val volume = rememberMediaVolume(mode == TurnMode.RESULT, s.output)
    val idleReady = s.pipelineStage == "READY" && !s.listening

    Box(
        Modifier
            .fillMaxSize()
            .background(Trust.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            DirectionControl(source, target, enabled = idleReady, swapLabel = t("Đổi chiều dịch", "Swap languages"), onSwap = vm::swapDirection)

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!micGranted) {
                    ProblemPanel(
                        t("Chưa có quyền micro", "Microphone access is off"),
                        t("Máy cần micro để nghe bạn nói. Âm thanh chỉ xử lý trên máy, không gửi đi đâu.",
                            "The app needs the microphone to hear you. Audio is processed on this phone and never sent anywhere."),
                        t("Cho phép dùng micro", "Allow microphone"),
                        onRequestMic,
                    )
                }
                when (mode) {
                    TurnMode.IDLE -> if (micGranted) IdlePanel(idleCopy(source, target))
                    TurnMode.LISTENING -> ListeningPanel(guidance)
                    TurnMode.WORKING -> WorkingPanel(
                        t("Đang dịch…", "Translating…"), workingSteps(source), workingStepOf(s.pipelineStage),
                    )
                    TurnMode.RESULT -> ResultPanel(
                        ResultPanelState(
                            targetLabel = languageName(target, source),
                            output = s.output,
                            transcriptLabel = t("Bạn nói", "You said"),
                            transcript = s.transcript,
                            speakingLabel = if (s.pipelineStage == "SPEAKING") t("Đang đọc to", "Reading aloud") else null,
                            hazardLabel = if (hazard) t("Câu cảnh báo an toàn", "Safety warning") else null,
                            confirmTitle = if (s.awaitingConfirm) t("Kiểm tra trước khi phát", "Check before playing") else null,
                            confirmDetail = if (s.awaitingConfirm) t(
                                "Máy đã sửa lại số hoặc tên trong câu này. Đọc lại, đúng thì bấm phát.",
                                "A number or name in this sentence was corrected. Read it, then play it if it is right.",
                            ) else null,
                            playbackProblem = if (isPlaybackFailure(s.debugTtsStatus)) t(
                                "Không phát được tiếng. Bấm Phát lại, hoặc đưa màn hình cho người nghe đọc.",
                                "Could not play the audio. Tap Play again, or show the screen to the listener.",
                            ) else null,
                            volumeHint = if (volume.isLow) t(
                                "Âm lượng đang nhỏ, chỗ ồn sẽ khó nghe.", "Media volume is low and may be hard to hear here.",
                            ) else null,
                            timing = if (BuildConfig.DEBUG) s.timing else "",
                        ),
                        replayLabel = t("Phát lại", "Play again"),
                        confirmLabel = t("Đúng, phát câu này", "Correct, play it"),
                        raiseVolumeLabel = t("Tăng âm lượng", "Turn volume up"),
                        actionsEnabled = idleReady,
                        onReplay = vm::replayOutput,
                        onConfirm = vm::confirmRepairedAndSpeak,
                        onRaiseVolume = volume.raise,
                    )
                    TurnMode.REPEAT -> {
                        RetryPanel(
                            repeatGuidance(s.decision, source), noSpeech = false,
                            heardLabel = t("Máy nghe được", "What it heard"),
                            heard = if (tentative == null) s.transcript else "",
                        )
                        if (tentative != null) DraftPanel(tentative)
                    }
                    TurnMode.NO_SPEECH -> RetryPanel(noSpeechGuidance(source), noSpeech = true, heardLabel = "", heard = "")
                    TurnMode.PREPARING_DIRECTION -> ProgressPanel(
                        t("Đang chuẩn bị ${languageName(source, source).replaceFirstChar { it.lowercase() }} → ${languageName(target, source).replaceFirstChar { it.lowercase() }}",
                            "Getting ${languageName(source, source)} → ${languageName(target, source)} ready"),
                        t("Mất vài giây khi đổi chiều dịch.", "This takes a few seconds after switching."),
                    )
                    TurnMode.PREPARATION_FAILED -> if (autoRetries == 0) {
                        ProgressPanel(t("Đang thử lại…", "Trying again…"), t("Chưa nạp được chiều dịch này.", "This direction did not load."))
                    } else {
                        ProblemPanel(
                            t("Chưa nạp được chiều dịch", "This direction did not load"),
                            t("Bấm thử lại. Nếu vẫn lỗi, đổi chiều dịch rồi đổi lại.",
                                "Tap Try again. If it still fails, swap languages and swap back."),
                            t("Thử lại", "Try again"),
                            vm::retryDirectionPreparation,
                        )
                    }
                }
            }

            if (BuildConfig.DEBUG) DiagnosticsToggle(debugOpen) { debugOpen = !debugOpen }

            if (!s.listening) QuickPhraseBar(
                source, target, enabled = idleReady,
                playsInLabel = t("Phát bằng ${languageName(target, source).replaceFirstChar { it.lowercase() }}", "Plays in ${languageName(target, source)}"),
                onPlay = { vm.playQuickPhrase(it.id) },
            )

            TalkButton(
                state = talkSlabState(mode, s, micGranted, guidance.tone, holdElapsedMs, holdLimit, source) { a, b -> t(a, b) },
                onPressStart = vm::onPressStart,
                onPressEnd = vm::onPressEnd,
            )
        }
    }
    if (debugOpen) AlertDialog(
        onDismissRequest = { debugOpen = false },
        title = { Text("Chẩn đoán") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { DebugPanel(s, vm) } },
        confirmButton = { TextButton(onClick = { debugOpen = false }) { Text("Đóng") } },
    )
}

private fun talkSlabState(
    mode: TurnMode,
    s: PushToTalkViewModel.UiState,
    micGranted: Boolean,
    tone: GuidanceTone,
    holdElapsedMs: Long,
    holdLimit: Long,
    source: Language,
    t: (String, String) -> String,
): TalkSlabState {
    val ready = micGranted && s.pipelineStage == "READY"
    return when {
        s.listening -> TalkSlabState(
            enabled = true, listening = true,
            label = t("Thả để dịch", "Let go to translate"),
            detail = languageName(source, source),
            tone = tone, levelDbfs = s.micLevelDbfs,
            timerText = holdTimerText(holdElapsedMs, holdLimit, source),
            nearLimit = isHoldNearLimit(holdElapsedMs, holdLimit),
        )
        !micGranted -> TalkSlabState(false, false, t("Cần quyền micro", "Microphone needed"), "")
        ready -> TalkSlabState(true, false, t("Giữ để nói", "Hold to talk"), t("Nói tiếng Việt", "Speak English"))
        mode == TurnMode.PREPARING_DIRECTION || mode == TurnMode.PREPARATION_FAILED ->
            TalkSlabState(false, false, t("Đang chuẩn bị…", "Getting ready…"), "")
        s.pipelineStage == "SPEAKING" -> TalkSlabState(false, false, t("Đang đọc…", "Speaking…"), t("Đọc xong là nói tiếp được", "You can talk again when it finishes"))
        else -> TalkSlabState(false, false, t("Đang dịch…", "Translating…"), "")
    }
}

@Composable
private fun TurnHaptics(vm: PushToTalkViewModel, haptics: Haptics, decision: String, level: TrustLevel, hazard: Boolean, listening: Boolean, quality: com.voxbridge.core.audio.AudioQualityState?) {
    LaunchedEffect(decision) {
        if (!vm.shouldFireHaptic(decision)) return@LaunchedEffect
        when (level) {
            TrustLevel.GREEN -> if (hazard) haptics.hazard() else haptics.safe()
            TrustLevel.AMBER -> haptics.sayAgain()
            TrustLevel.GREY -> Unit
        }
    }
    LaunchedEffect(listening) { if (listening) haptics.captureStarted() }
    LaunchedEffect(listening, quality) {
        if (BuildConfig.LIVE_AUDIO_STATUS && vm.shouldFireLiveAudioHaptic()) haptics.audioQualityWarning()
    }
}

/** A conversation pauses between turns; the screen must not sleep in the middle of one. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private class MediaVolume(val isLow: Boolean, val raise: () -> Unit)

/** Reads media volume when a translation appears; the raise action is only ever user-triggered. */
@Composable
private fun rememberMediaVolume(active: Boolean, output: String): MediaVolume {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var low by remember { mutableStateOf(false) }
    LaunchedEffect(active, output) {
        low = active && audio != null &&
            isMediaVolumeLow(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }
    return MediaVolume(low) {
        if (audio != null) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val wanted = (max * RAISED_VOLUME_FRACTION).toInt().coerceAtLeast(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, AudioManager.FLAG_SHOW_UI)
            low = isMediaVolumeLow(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max)
        }
    }
}

@Composable
private fun DiagnosticsToggle(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (open) "Ẩn chẩn đoán" else "Chẩn đoán", fontSize = 14.sp, color = Trust.SecondaryText)
    }
}
