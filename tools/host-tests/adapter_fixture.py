"""Extract production callbacks, rejection publication and render; not the complete ViewModel.

Android playback and BuildConfig remain explicit host boundaries. Cached prompts are held pending
in SPEAKING until the test completes them; the existing AudioOut tests cover real drain handling.
"""
from pathlib import Path
import subprocess

PREFIX = '''package com.voxbridge.ui
import android.util.Log
import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.audio.SpeechLoudness
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.pipeline.TurnTiming
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors

// Compile-only Android build boundary: these tests exercise the debug draft-enabled behavior.
private object BuildConfig { const val RETRY_DRAFTS=true }

internal class SpeechAdapterHarness : AutoCloseable {
 data class State(val pipelineStage:String="READY",val listening:Boolean=false,val awaitingConfirm:Boolean=false,
  val output:String="",val direction:Pair<Language,Language> = Language.VI to Language.EN,val debugTtsStatus:String="",val timing:String="",
  val transcript:String="",val debugReport:String="",val debugPipeline:String="",val latencyMs:Long=0,
  val modelText:String="",val repairedSlots:String="",val canApprove:Boolean=false,val feedbackNote:String="",
  val tentativeOutput:TentativeOutput?=null,val micLevelDbfs:Double=-120.0,val decision:String="")
 private val _state=MutableStateFlow(State())
 val state get()=_state.value
 private val viewModelScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
 private val runnerDispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
 private var speechOutputSession:SpeechOutputPolicy.Session?=null
 private val speechOutputPolicy=SpeechOutputPolicy()
 private var speechJob:Job?=null
 @Volatile private var speechGeneration=0L
 private var timingForInitialSpeech:TurnTiming?=null
 @Volatile private var captureGen=0
 private var pendingPrompt=false
 val promptPending get()=pendingPrompt
 val events=mutableListOf<SpeechOutputPolicy.Event>()
 fun render(d:TranslationDecision) {
  speechOutputSession=SpeechOutputPolicy().session(d,Language.VI,Language.EN).also { it.initialEvents() }
  _state.value=State(output="old visible text",awaitingConfirm=d is TranslationDecision.RepairedTranslation)
 }
 fun beginCapture(generation:Int,transcript:String="",decision:String="",
  direction:Pair<Language,Language> = Language.VI to Language.EN) {
  captureGen=generation
  pendingPrompt=false
  _state.value=State(pipelineStage="LISTENING",listening=true,transcript=transcript,decision=decision,direction=direction)
 }
 fun finishRejected(generation:Int,transcript:String,decision:TranslationDecision,
  direction:Pair<Language,Language> = _state.value.direction) {
  publishRejectedCapture(generation,transcript,"completed diagnostics","ASR draft completed",decision,30L,
   direction,TurnTiming(nowMs(),10L,20L))
 }
 fun renderDecision(d:TranslationDecision) { render(d,30L,_state.value.direction,TurnTiming(nowMs(),10L,20L)) }
 fun completePrompt() {
  check(pendingPrompt) { "No host prompt is pending" }
  pendingPrompt=false
  _state.update { it.copy(pipelineStage="READY") }
 }
 fun stage(value:String,listening:Boolean=false) { _state.value=_state.value.copy(pipelineStage=value,listening=listening) }
 fun launch(block:()->PcmAudio) { launchSpeech("host-test",listOf(block)) }
 private fun emitSpeechEvents(value:List<SpeechOutputPolicy.Event>) {
  events.addAll(value)
  // Cached prompt synthesis/playback is a controlled host boundary, never an immediate finish.
  if (value.any { it is SpeechOutputPolicy.Event.Prompt || it is SpeechOutputPolicy.Event.SpeakTranslation }) {
   pendingPrompt=true
   _state.update { it.copy(pipelineStage="SPEAKING") }
  }
 }
 // This boundary also lets the pre-fix callback run and demonstrate its policy bypass.
 private fun speak(text:String,language:Language,cachedId:String?) { events.add(SpeechOutputPolicy.Event.SpeakTranslation(text,language)) }
 private fun nowMs()=System.nanoTime()/1_000_000
 private val fakePipeline=false
 class FakeAsr {
  var selected:Language?=null
  fun preload(lang:Language):Long { selected=lang;return 10 }
 }
 class FakeTts {
  var selected:Language?=null
  var prepare:(()->Unit)?=null
  fun preload(lang:Language):Long { prepare?.invoke();selected=lang;return 20 }
 }
 class FakeTranslator { fun preload(source:Language,target:Language):Long=0L }
 data class FakeRunners(val asr:FakeAsr=FakeAsr(),val tts:FakeTts=FakeTts(),val translator:FakeTranslator=FakeTranslator())
 val runners:FakeRunners?=FakeRunners()
 fun prepareDirection()=prepareSelectedAsr()
 // Quick phrases are exercised by QuickPhrasesTest; the harness only needs the call sites to compile.
 private var lastQuickPhraseId:String?=null
 var quickPhrasesPreparedFor:Language?=null
 private fun prepareQuickPhrases(target:Language) { quickPhrasesPreparedFor=target }
 fun playQuickPhrase(id:String) {}
 private val ASR_TAG="host-test"
 private val TAG="host-test";private val TTS_TAG="host-test";private val TIMING_TAG="host-test"
 override fun close() { stopSpeech();viewModelScope.cancel();runnerDispatcher.close() }
'''

def build(root, output, callback_ref=None):
    path='android/app/src/main/java/com/voxbridge/ui/PushToTalkViewModel.kt'
    current=(root/path).read_text()
    callback_source=subprocess.check_output(['git','show',callback_ref+':'+path],cwd=root,text=True) if callback_ref else current
    callback_start=callback_source.index('    fun confirmRepairedAndSpeak()')
    callback_ends=[callback_source.find(marker,callback_start) for marker in (
        '    private fun publishRejectedCapture(', '    private fun render(')]
    callbacks=callback_source[callback_start:min(index for index in callback_ends if index >= 0)]
    publication=current[current.index('    private fun publishRejectedCapture('):current.index('    private fun render(')]
    render=current[current.index('    private fun render('):current.index('    private fun emitSpeechEvents(')]
    stop=current[current.index('    private fun stopSpeech()'):current.index('    private fun speak(text:')]
    play=current[current.index('    private fun launchSpeech('):current.index('    private fun langTag')]
    prepare=current[current.index('    private fun prepareSelectedAsr()'):current.index('    /** A result belongs')]
    output.write_text(PREFIX+callbacks+publication+render+stop+play+prepare+'\n}\n')
    return output
