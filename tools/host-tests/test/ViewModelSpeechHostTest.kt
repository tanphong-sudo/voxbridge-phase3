package com.voxbridge.ui

import android.media.AudioTrack
import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyViolation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executes the verbatim production callback/method bodies with Android boundaries replaced. */
class ViewModelSpeechHostTest {
    @Test fun repairedOutputCannotBeReplayedBeforeConfirmation() {
        SpeechAdapterHarness().use { vm ->
            vm.render(TranslationDecision.RepairedTranslation("Stop three.","Start.",listOf(SafetyViolation.LOST_NEGATION)))
            vm.replayOutput()
            assertTrue(vm.events.isEmpty())
            vm.confirmRepairedAndSpeak()
            assertEquals(1,vm.events.size)
            vm.confirmRepairedAndSpeak()
            assertEquals(1,vm.events.size)
        }
    }

    @Test fun busyOrListeningCallbacksCannotEmitOldSpeech() {
        SpeechAdapterHarness().use { vm ->
            vm.render(TranslationDecision.ModelTranslation("old output"))
            for (stage in listOf("LISTENING","UNDERSTANDING","SPEAKING","PREPARING_ASR")) {
                vm.stage(stage)
                vm.replayOutput()
            }
            vm.stage("READY",listening=true)
            vm.replayOutput()
            assertTrue(vm.events.isEmpty())
        }
    }

    @Test fun readyOnlyAfterActualPlaybackCompletion() {
        SpeechAdapterHarness().use { vm ->
            val before=AudioTrack.tracks.size
            vm.launch { PcmAudio(ByteArray(640),16000) }
            await { AudioTrack.tracks.size==before+1 && AudioTrack.tracks.last().started.count==0L }
            assertEquals("SPEAKING",vm.state.pipelineStage)
            val track=AudioTrack.tracks.last()
            track.playbackHeadPosition=320
            await { vm.state.pipelineStage=="READY" }
            assertEquals(1,track.releaseCount)
            assertTrue(vm.state.debugTtsStatus.contains("playback complete"))
        }
    }

    @Test fun cancelledSynthesisCannotPlayOrFinishTheReplacementTurn() {
        SpeechAdapterHarness().use { vm ->
            val started=CountDownLatch(1);val unblock=CountDownLatch(1)
            val before=AudioTrack.tracks.size
            vm.launch { started.countDown();check(unblock.await(2,TimeUnit.SECONDS));PcmAudio(ByteArray(640),16000) }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            vm.launch { PcmAudio(ByteArray(640),16000) }
            unblock.countDown()
            await { AudioTrack.tracks.size==before+1 && AudioTrack.tracks.last().started.count==0L }
            assertEquals("SPEAKING",vm.state.pipelineStage)
            AudioTrack.tracks.last().playbackHeadPosition=320
            await { vm.state.pipelineStage=="READY" }
            assertEquals(before+1,AudioTrack.tracks.size)
        }
    }

    @Test fun emptyOrFailedSynthesisReturnsReadyWithFailureAndNoPlayback() {
        SpeechAdapterHarness().use { vm ->
            val before=AudioTrack.tracks.size
            vm.launch { PcmAudio(ByteArray(0),16000) }
            await { vm.state.pipelineStage=="READY" }
            assertTrue(vm.state.debugTtsStatus.contains("FAILED"))
            vm.launch { error("missing safety phrase") }
            await { vm.state.pipelineStage=="READY" }
            assertTrue(vm.state.debugTtsStatus.contains("missing safety phrase"))
            assertEquals(before,AudioTrack.tracks.size)
        }
    }

    @Test fun captureStaysBlockedUntilTargetVoiceWarmupFinishes() {
        SpeechAdapterHarness().use { vm ->
            val started=CountDownLatch(1);val finish=CountDownLatch(1)
            vm.runners!!.tts.prepare={ started.countDown();check(finish.await(2,TimeUnit.SECONDS)) }
            try {
                vm.prepareDirection()
                assertTrue(started.await(2,TimeUnit.SECONDS))
                assertFalse(canStartCapture(vm.state.pipelineStage,vm.state.listening))
                assertEquals("PREPARING_ASR",vm.state.pipelineStage)
            } finally { finish.countDown() }
            await { vm.state.pipelineStage=="READY" }
            assertEquals(com.voxbridge.core.model.Language.VI,vm.runners!!.asr.selected)
            assertEquals(com.voxbridge.core.model.Language.EN,vm.runners!!.tts.selected)
            assertTrue(vm.state.debugPipeline.contains("TTS 20 ms"))
        }
    }

    @Test fun currentRejectedCaptureKeepsDraftSeparateAndStaysBusyUntilPromptCompletes() {
        SpeechAdapterHarness().use { vm ->
            val direction = Language.EN to Language.VI
            val draft = TentativeOutput("Possibly open valve", "Có thể mở van")
            vm.beginCapture(1, direction = direction)
            vm.stage("UNDERSTANDING")
            vm.finishRejected(1, draft.sourceText, TranslationDecision.AskRepeat("audio_low_quality", draft), direction)

            assertEquals("ASK_REPEAT (audio_low_quality)", vm.state.decision)
            assertEquals(draft, vm.state.tentativeOutput)
            assertEquals(draft.sourceText, vm.state.transcript)
            assertEquals("", vm.state.output)
            assertFalse(vm.state.canApprove)
            assertFalse(vm.state.awaitingConfirm)
            assertFalse(vm.state.listening)
            assertEquals("SPEAKING", vm.state.pipelineStage)
            assertTrue(vm.promptPending)
            assertEquals(
                listOf(SpeechOutputPolicy.Event.SpeakTranslation("Có thể mở van", Language.VI, unverified = true)),
                vm.events,
            )

            vm.completePrompt()
            assertEquals("READY", vm.state.pipelineStage)
            vm.replayOutput()
            vm.confirmRepairedAndSpeak()
            assertEquals(2, vm.events.size)
        }
    }

    @Test fun delayedRejectedCaptureCannotReplaceNewTurnOrStartAnOldPrompt() {
        SpeechAdapterHarness().use { vm ->
            vm.beginCapture(1, transcript = "old capture")
            vm.stage("UNDERSTANDING")
            vm.beginCapture(2, transcript = "new capture", decision = "NEW_TURN", direction = Language.VI to Language.EN)
            val newTurn = vm.state
            vm.finishRejected(
                generation = 1,
                transcript = "stale transcript",
                decision = TranslationDecision.AskRepeat("audio_clipped", TentativeOutput("stale draft", "stale translation")),
                direction = Language.EN to Language.VI,
            )

            assertEquals(newTurn, vm.state)
            assertEquals("LISTENING", vm.state.pipelineStage)
            assertTrue(vm.state.listening)
            assertEquals("new capture", vm.state.transcript)
            assertEquals("NEW_TURN", vm.state.decision)
            assertFalse(vm.promptPending)
            assertTrue(vm.events.isEmpty())
        }
    }

    @Test fun productionRenderClearsAnOldDraftOnAcceptedAndNoSpeechDecisions() {
        for (next in listOf(TranslationDecision.ModelTranslation("accepted"), TranslationDecision.NoSpeech)) {
            SpeechAdapterHarness().use { vm ->
                vm.beginCapture(1)
                vm.stage("UNDERSTANDING")
                vm.finishRejected(1, "uncertain source", TranslationDecision.AskRepeat("uncertain", TentativeOutput("uncertain source")))
                assertNotNull(vm.state.tentativeOutput)
                vm.completePrompt()

                vm.renderDecision(next)

                assertNull(vm.state.tentativeOutput)
                assertEquals(if (next is TranslationDecision.ModelTranslation) "accepted" else "", vm.state.output)
                assertEquals(next is TranslationDecision.ModelTranslation, vm.state.canApprove)
            }
        }
    }

    private fun await(predicate:()->Boolean) {
        val deadline=System.nanoTime()+2_000_000_000L
        while (!predicate()) { check(System.nanoTime()<deadline) { "Timed out waiting for host boundary" }; Thread.sleep(2) }
    }
}
