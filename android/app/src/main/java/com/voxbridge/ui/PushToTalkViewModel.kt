package com.voxbridge.ui

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxbridge.BuildConfig
import com.voxbridge.audio.AudioRecordSource
import com.voxbridge.audio.AudioDebugStore
import com.voxbridge.audio.AudioQualityTelemetry
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.AudioQualitySnapshot
import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.audio.SpeechLoudness
import com.voxbridge.core.learn.FeedbackPromoter
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.learn.UserFeedback
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.UtteranceKey
import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.RejectedCaptureDraft
import com.voxbridge.core.pipeline.TentativeOutput
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.SpeechAudioPlan
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.pipeline.TurnTiming
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.runtime.AndroidModelPaths
import com.voxbridge.runtime.AsrDiagnostics
import com.voxbridge.runtime.ModelProfile
import com.voxbridge.runtime.ModelRepository
import com.voxbridge.runtime.ModelSource
import com.voxbridge.runtime.PreparedTranslator
import com.voxbridge.runtime.StaticMtTranslator
import java.io.File
import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.QnnPiperVocoder
import com.voxbridge.runtime.PiperTts
import com.voxbridge.runtime.SileroVad
import com.voxbridge.runtime.LanguageRoutedAsr
import com.voxbridge.store.RoomLearningStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Model-set startup phase surfaced through [PushToTalkViewModel.UiState.setup]. */
sealed interface Setup {
    /** Installing (first launch) or building the native runners. */
    data object Preparing : Setup

    /** Required files/models and selected ASR are ready; Piper voices remain lazy-loaded. */
    data object Ready : Setup

    /** Install or runner-build failure — the setup screen names the problem instead of crashing. */
    data class Problem(val message: String) : Setup
}

/**
 * Drives one push-to-talk utterance end to end and owns the WHOLE runtime lifecycle:
 * install (unpack) the bundled model set → build the native runners → drive PTT → close in
 * [onCleared]. Owning the build (not receiving pre-built runners) is what makes the
 * ViewModelStore contract work end to end.
 *
 * WHY THE VIEWMODELSTORE MATTERS (Round-3 review HIGH): this instance must live behind
 * [factory] inside `ViewModelProvider`, never constructed directly. A directly constructed
 * ViewModel is in no ViewModelStore, so the framework never calls [onCleared] — the ~GB of
 * native sessions (ORT Whisper, translation, sherpa TTS, SQLCipher store) leaked on
 * every Activity recreation. Store ownership also RETAINS the runners across rotation, so
 * the multi-second build runs once per task, not once per orientation.
 *
 * Startup sequence (in [init], all heavy work off-main): install on IO → readiness gate →
 * runner build on Default → Ready. Failure contract: ANY startup exception renders
 * Setup.Problem naming the problem/missing files instead of crashing (a launch-time crash
 * would look like "the app is broken" when it is only the model set); ANY pipeline/TTS error
 * degrades to an ASK_REPEAT-style prompt instead of killing the process.
 *
 * Phase-2 scope: VI/EN with language-routed ASR and one resident MT direction.
 */
class PushToTalkViewModel private constructor(
    private val appContext: Context,
    private val modelRepository: ModelRepository,
    private val fakePipeline: Boolean,
    private val extractor: SafetyFrameExtractor = SafetyFrameExtractor(),
    private val promoter: FeedbackPromoter = FeedbackPromoter(),
) : ViewModel() {

    private val paths: ModelPaths = modelRepository.paths

    data class UiState(
        val setup: Setup = Setup.Preparing,
        val direction: Pair<Language, Language> = Language.VI to Language.EN,
        val listening: Boolean = false,
        val transcript: String = "",
        val modelText: String = "",          // raw neural MT, shown before the safety layer (demo trap defusal)
        val output: String = "",
        val tentativeOutput: TentativeOutput? = null,
        val decision: String = "",           // one of the six states, for the badge
        val repairedSlots: String = "",
        val latencyMs: Long = 0,
        val timing: String = "",              // per-stage timing up to first audio, shown on screen
        val awaitingConfirm: Boolean = false, // REPAIRED must be confirm-tapped before audio (FR-17)
        val canApprove: Boolean = false,      // a spoken translation is on screen, awaiting approve/edit
        val feedbackNote: String = "",        // transient confirmation of a stored correction
        val offline: Boolean = true,
        val translationProvider: String = "envit5",
        val debugReport: String = "No debug capture yet.",
        val debugPipeline: String = "IDLE",
        val debugBypassResult: String = "",
        val debugTtsStatus: String = "TTS: idle",
        val modelStatus: String = "Model status: checking",
        val setupMessage: String = "Đang kiểm tra các mô hình trên máy…",
        val pipelineStage: String = "READY",
        val micLevelDbfs: Double = -120.0,
        val liveAudioQuality: AudioQualitySnapshot? = null,
        val liveAudioHapticsEnabled: Boolean = false,
        val audioQualityReport: String = "No audio quality metrics yet.",
    )

    private val liveAudioPreferences by lazy { appContext.getSharedPreferences("live-audio-status", Context.MODE_PRIVATE) }
    private val liveAudioHapticPolicy = LiveAudioHapticPolicy()
    private val _state = MutableStateFlow(UiState(
        translationProvider = paths.profile.mtBackend,
        liveAudioHapticsEnabled = BuildConfig.LIVE_AUDIO_STATUS && liveAudioPreferences.getBoolean("haptics", false),
    ))
    val state = _state.asStateFlow()

    fun setLiveAudioHapticsEnabled(enabled: Boolean) {
        if (!BuildConfig.LIVE_AUDIO_STATUS) return
        _state.update { it.copy(liveAudioHapticsEnabled = enabled) }
        liveAudioPreferences.edit().putBoolean("haptics", enabled).apply()
    }

    fun shouldFireLiveAudioHaptic(): Boolean {
        val current = _state.value
        return BuildConfig.LIVE_AUDIO_STATUS && liveAudioHapticPolicy.onState(
            current.listening, current.liveAudioQuality?.state, current.liveAudioHapticsEnabled,
        )
    }

    // Single-threaded lane for every blocking native call (transcribe, synthesize, Room I/O):
    // overlapping utterances can't double-enter ORT/sherpa, and onCleared can queue the runner
    // teardown behind any in-flight call instead of freeing sessions under a live native call.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val runnerDispatcher = Dispatchers.Default.limitedParallelism(1)
    // Outlives viewModelScope (cancelled by the time onCleared runs) so the close job survives.
    private val closeScope = CoroutineScope(SupervisorJob() + runnerDispatcher)
    private var startupJob: Job? = null

    init {
        startSetup()
    }

    fun retrySetup() {
        if (_state.value.setup is Setup.Problem) startSetup()
    }

    private fun startSetup() {
        if (startupJob?.isActive == true) return
        _state.update { it.copy(setup = Setup.Preparing,
            setupMessage = "Đang kiểm tra các mô hình trên máy…") }
        startupJob = viewModelScope.launch {
            try {
                if (fakePipeline) {
                    Log.i(TAG, "debugUi FakePipeline ready; no model files loaded")
                    _state.update {
                        it.copy(
                            setup = Setup.Ready,
                            modelStatus = "Model status: debugUi FakePipeline (no models required)",
                            debugPipeline = "FakePipeline: ready",
                            pipelineStage = "READY",
                        )
                    }
                    return@launch
                }

                // Only BUNDLED_RELEASE extracts APK assets. EXTERNAL_DEV validates local files
                // (and caches an integrity fingerprint) without downloading or replacing models.
                // Discovery must not wait for the first multi-GB integrity scan.
                Log.i(MODEL_TAG, "MODEL_ROOT=${paths.root.absolutePath}")
                val modelStatus = withContext(Dispatchers.IO) { modelRepository.prepare() }
                Log.i(MODEL_TAG, modelStatus.humanReadable())
                if (!modelStatus.ready) {
                    Log.e(TAG, "startup failed: ${modelStatus.humanReadable()}")
                    _state.update {
                        it.copy(
                            setup = Setup.Problem(modelStatus.humanReadable()),
                            modelStatus = modelStatus.humanReadable(),
                        )
                    }
                    return@launch
                }
                // Build the native runners off-main. The assignment inside the block lands even
                // if the scope is cancelled while the (non-interruptible) build finishes; the
                // cancellation path closes the orphans instead of leaking a second ~GB set.
                var built: Runners? = null
                try {
                    withContext(runnerDispatcher) { built = buildRunners(paths) }
                } catch (e: CancellationException) {
                    runCatching { built?.close() }
                    throw e
                }
                val r = checkNotNull(built) { "runner build returned without a runner set" }
                runners = r
                capture = AudioRecordSource(r.vad)
                Log.i(TAG, "startup ready: native runners loaded")
                prepareQuickPhrases(_state.value.direction.second)
                _state.update {
                    it.copy(
                        setup = Setup.Ready,
                        modelStatus = modelStatus.humanReadable() + "\n✓ ${paths.profile.mtBackend} VI↔EN (on-device)",
                        pipelineStage = "READY",
                    )
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "startup failed", t)
                _state.update { it.copy(setup = Setup.Problem(t.message ?: t.toString())) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Native runner ownership
    // ------------------------------------------------------------------

    private var runners: Runners? = null
    private var capture: AudioRecordSource? = null
    private var lastCapture: AudioRecordSource.Capture? = null
    private var lastArtifacts: AudioDebugStore.Artifacts? = null
    private var lastLiveAsr: AsrDiagnostics? = null
    private val speechOutputPolicy = SpeechOutputPolicy()
    private var speechOutputSession: SpeechOutputPolicy.Session? = null
    private var speechJob: Job? = null
    /** Quick-phrase PCM per phrase and target language, synthesized once in the background. */
    private val quickPhraseAudio = java.util.concurrent.ConcurrentHashMap<String, PcmAudio>()
    private var quickPhraseJob: Job? = null
    private var lastQuickPhraseId: String? = null
    @Volatile private var speechGeneration = 0L
    // Set only while render() emits a turn's INITIAL speech events, so the first audio of that
    // turn (not a later replay/confirm tap) closes the end-of-speech -> first-audio measurement.
    private var timingForInitialSpeech: TurnTiming? = null

    /** Owns and closes the selected native sessions. */
    private class Runners(
        val vad: SileroVad,
        val asr: LanguageRoutedAsr,
        val translator: PreparedTranslator,
        val tts: PiperTts,
        val store: RoomLearningStore,
        val engine: DecisionEngine,
    ) : AutoCloseable {
        override fun close() {
            listOf<AutoCloseable>(vad, asr, translator, tts, store).forEach { runCatching { it.close() } }
        }
    }

    /** Prepare the on-device MT, then build the native runners. If any step throws, built ones close before
     *  the exception propagates (a half-loaded ORT set would otherwise leak). Runs on the
     *  worker dispatcher — model downloads and native session creation never touch main. */
    private fun buildRunners(paths: ModelPaths): Runners {
        val keep = ArrayList<AutoCloseable>(11)
        try {
            _state.update { it.copy(setupMessage = "Đang nạp mô hình dịch…") }
            val env = OrtEnvironment.getEnvironment()
            android.system.Os.setenv("ADSP_LIBRARY_PATH",appContext.applicationInfo.nativeLibraryDir+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/dsp",true)
            val translator: PreparedTranslator =
                StaticMtTranslator(env,File(paths.root,"translation/qnn"),appContext.applicationInfo.nativeLibraryDir,paths.profile.mtBackend)
            keep.add(translator)
            translator.prepare()
            Log.i(TAG, "Translation backend ready: ${paths.profile.mtBackend}")
            _state.update { it.copy(setupMessage = "Đang mở nhận giọng nói và giọng đọc…") }
            val vad = SileroVad.load(env, paths.vad); keep.add(vad)
            val asr = LanguageRoutedAsr(env, paths, preferCpu = !paths.profile.qnnAsr, requireQnn = paths.profile.qnnAsr); keep.add(asr)
            _state.update { it.copy(setupMessage = "Đang nạp phần nhận giọng nói…") }
            val preloadMs = asr.preload(_state.value.direction.first)
            Log.i(ASR_TAG, "startup preloadMs=$preloadMs source=${_state.value.direction.first}")
            val qnnVocoder = if (paths.profile.qnnAsr)
                QnnPiperVocoder(env, paths) else null
            val tts = PiperTts(paths, vocoder = qnnVocoder); keep.add(tts)
            _state.update { it.copy(setupMessage = "Đang nạp giọng đọc…") }
            val ttsPreloadMs = tts.preload(_state.value.direction.second)
            translator.preload(_state.value.direction.first,_state.value.direction.second)
            Log.i(TTS_TAG, "startup preloadMs=$ttsPreloadMs target=${_state.value.direction.second}")
            val store = RoomLearningStore(appContext); keep.add(store)
            val engine = DecisionEngine(translator = translator, memory = LocalMemoryRetriever(store),
                config = DecisionConfig(
                    tentativeOutputEnabled = BuildConfig.RETRY_DRAFTS,
                    wordImportanceEnabled = BuildConfig.RETRY_DRAFTS,
                    bestEffortRecoveryEnabled = BuildConfig.RETRY_DRAFTS,
                ))
            return Runners(vad, asr, translator, tts, store, engine)
        } catch (t: Throwable) {
            // Throwable, not Exception: an OOM while loading the second graph must still close
            // the first one before propagating (Round-3 review LOW).
            keep.forEach { runCatching { it.close() } }
            throw t
        }
    }

    // ------------------------------------------------------------------
    // Direction
    // ------------------------------------------------------------------

    /** Phase-2 pair; the swap button covers the reverse direction. */
    val targetPairs: List<Pair<Language, Language>> = listOf(
        Language.VI to Language.EN,
    )

    /** Select a contest pair from the UI chips. If the current direction already belongs to the
     *  pair (either orientation) it is KEPT — picking a chip must never silently flip direction
     *  under the speaker; a brand-new pair starts in its canonical first→second orientation. */
    fun selectPair(pair: Pair<Language, Language>) {
        require(pair in targetPairs) { "Unsupported Phase-2 pair" }
        val before = _state.value.direction
        _state.update {
            if (it.listening || it.pipelineStage != "READY") return@update it
            val d = it.direction
            val belongs = d == pair || (d.first == pair.second && d.second == pair.first)
            val next = if (belongs) d else pair
            if (next == d) it else it.forDirectionChange(next)
        }
        if (_state.value.direction != before) {
            speechOutputSession = null
            stopSpeech()
            prepareSelectedAsr()
        }
    }

    fun swapDirection() {
        val before = _state.value.direction
        _state.update {
            if (it.listening || it.pipelineStage != "READY") return@update it
            it.forDirectionChange(it.direction.second to it.direction.first)
        }
        if (_state.value.direction != before) {
            speechOutputSession = null
            stopSpeech()
            prepareSelectedAsr()
        }
    }

    private fun prepareSelectedAsr() {
        if (fakePipeline) return
        val current = runners ?: return
        val (source, target) = _state.value.direction
        _state.update { it.copy(pipelineStage = "PREPARING_ASR", debugPipeline = "ASR/TTS: preparing $source → $target") }
        viewModelScope.launch {
            try {
                val (asrMs, ttsMs) = withContext(runnerDispatcher) {
                    current.translator.preload(source,target)
                    current.asr.preload(source) to current.tts.preload(target)
                }
                Log.i(ASR_TAG, "direction preloadMs=$asrMs source=$source")
                Log.i(TTS_TAG, "direction preloadMs=$ttsMs target=$target")
                _state.update { it.copy(pipelineStage = "READY", debugPipeline = "ASR/TTS ready (ASR $asrMs ms, TTS $ttsMs ms)") }
                prepareQuickPhrases(target)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "ASR/TTS preload failed", t)
                _state.update { it.copy(pipelineStage = "PREPARATION_FAILED", debugPipeline = "Direction preload failed: ${t.message}") }
            }
        }
    }

    fun retryDirectionPreparation() {
        if (_state.value.pipelineStage == "PREPARATION_FAILED") prepareSelectedAsr()
    }

    /** A result belongs to the direction that produced it; never carry it across a direction flip. */
    private fun UiState.forDirectionChange(next: Pair<Language, Language>): UiState = copy(
        direction = next,
        tentativeOutput = null,
        transcript = "",
        modelText = "",
        output = "",
        decision = "",
        repairedSlots = "",
        latencyMs = 0,
        awaitingConfirm = false,
        canApprove = false,
        feedbackNote = "",
    )

    // ------------------------------------------------------------------
    // Push-to-talk pipeline
    // ------------------------------------------------------------------

    @Volatile private var held = false
    // Generation token for capture loops: a rapid release-and-re-press must retire the previous
    // loop, not join it. `held` alone is racy — if the new press flips it back to true before the
    // old loop's next (never-suspending) iteration, the old loop would keep running and feed the
    // same unsynchronized SileroVad state. `captureGen` monotonically increments per press, so an
    // old loop's generation can never match again.
    @Volatile private var captureGen = 0
    private var captureJob: Deferred<AudioRecordSource.Capture>? = null
    // Kept through onPressEnd: teardown must also wait for a capture being finalized.
    private var activeCaptureJob: Job? = null

    /**
     * Start capturing the moment the button is pressed — the mic must run DURING the hold. The
     * earlier version set held=false before record() ever ran, so the read loop never executed and
     * every utterance captured zero audio (=> NoSpeech for everything). Capture now runs in a
     * Deferred started here and awaited in onPressEnd.
     */
    fun onPressStart() {
        val state = _state.value
        if (!canStartCapture(state.pipelineStage, state.listening)) return
        liveAudioHapticPolicy.reset()

        // A new speaker turn owns the microphone. Stop any previous TTS playback first so the
        // device cannot feed VoxBridge's own translated speech straight back into AudioRecord.
        stopSpeech()
        speechOutputSession = null
        lastCapture = null
        lastArtifacts = null

        if (fakePipeline) {
            held = true
            _state.update {
                it.copy(
                    listening = true,
                    tentativeOutput = null,
                    canApprove = false,
                    awaitingConfirm = false,
                    debugPipeline = "FakePipeline: LISTENING",
                    pipelineStage = "LISTENING",
                    micLevelDbfs = -18.0,
                    liveAudioQuality = null,
                    transcript = "",
                    output = "",
                    decision = "",
                )
            }
            return
        }
        val src = capture ?: return   // runners not Ready — the UI only shows once Ready, belt-and-braces
        val myGen = ++captureGen
        held = true
        _state.update {
            it.copy(
                listening = true,
                tentativeOutput = null,
                transcript = "", output = "", decision = "", canApprove = false,
                awaitingConfirm = false,
                debugPipeline = "MIC: recording",
                pipelineStage = "LISTENING",
                micLevelDbfs = -120.0,
                liveAudioQuality = null,
                audioQualityReport = if (BuildConfig.LIVE_AUDIO_STATUS) "Collecting live audio metrics..." else it.audioQualityReport,
                debugBypassResult = "",
            )
        }
        // The loop predicate `held && captureGen == myGen` retires any previous loop exactly once
        // (its generation is now stale, even if held was flipped back to true). AudioRecordSource
        // re-checks the predicate per 512-sample VAD chunk and SileroVad synchronizes
        // chunkProbability/reset, so a retired loop can no longer even touch the shared recurrent
        // state after the new press (Round-3 review MED).
        captureJob = viewModelScope.async(Dispatchers.Default) {
            src.record(
                isHeld = { held && captureGen == myGen },
                onLevelDbfs = { dbfs -> _state.update {
                    if (held && captureGen == myGen && it.listening) it.copy(micLevelDbfs = dbfs) else it
                } },
                retainRaw = BuildConfig.CAPTURE_ARTIFACTS,
                onAudioQuality = if (BuildConfig.LIVE_AUDIO_STATUS) { quality ->
                    _state.update {
                        if (held && captureGen == myGen && it.listening) it.copy(liveAudioQuality = quality) else it
                    }
                } else null,
            )
        }
        activeCaptureJob = captureJob
    }

    fun onPressEnd() {
        // Start at PTT release, before waiting for capture drain/resampling.
        val releasedAt = nowMs()
        held = false  // signals the record() loop (running in captureJob) to stop
        liveAudioHapticPolicy.reset()
        val endedCaptureGen = captureGen
        if (fakePipeline) {
            val (src, tgt) = _state.value.direction
            val heard = if (src == Language.VI) "Xin chào, tôi cần hỗ trợ." else "Hello, I need help."
            val translated = if (tgt == Language.EN) "Hello, I need help." else "Xin chào, tôi cần hỗ trợ."
            _state.update {
                it.copy(
                    listening = false,
                    transcript = heard,
                    output = translated,
                    decision = "TRANSLATED",
                    latencyMs = 90,
                    canApprove = false,
                    debugPipeline = "FakePipeline: ASR → MT → Safety → TTS complete",
                    debugReport = "debugUi FakePipeline\nsource=$src\ntarget=$tgt\nheard=$heard\ntranslation=$translated",
                    pipelineStage = "READY",
                    micLevelDbfs = -120.0,
                    liveAudioQuality = null,
                )
            }
            return
        }
        val job = captureJob ?: return
        captureJob = null
        _state.update {
            it.copy(
                listening = false,
                pipelineStage = "UNDERSTANDING",
                debugPipeline = "MIC: finalizing capture",
                micLevelDbfs = -120.0,
                liveAudioQuality = null,
            )
        }
        // Capture the runner set ONCE into a local: `asr`/`engine` are members of the nested
        // Runners class — NOT in scope of outer methods — so the previous bare `asr`/`engine`
        // references never compiled (Round-4 review BLOCK). Reading `runners` once here also
        // removes the read-race with onCleared's `runners = null`.
        val r = runners ?: return
        viewModelScope.launch {
            // Capture the direction NOW — selectPair()/swapDirection() during the (multi-second)
            // inference window must not flip the language the ASR prompt and TTS voice use.
            val direction = _state.value.direction
            val (src, tgt) = direction
            var turnTranscript: TranscriptResult? = null
            try {
                val cap = job.await()                 // the captured audio (loop ran during the hold)
                lastCapture = if (BuildConfig.CAPTURE_ARTIFACTS) cap else null
                if (BuildConfig.LIVE_AUDIO_STATUS) {
                    val qualityReport = AudioQualityTelemetry.encode(
                        cap.liveAudioQuality, cap.debug.capturedDurationMs, endedCaptureGen,
                        cap.debug.actualSampleRate, cap.liveAudioQualityError,
                    )
                    Log.i("AudioQuality", qualityReport)
                    _state.update {
                        if (captureGen == endedCaptureGen) it.copy(audioQualityReport = qualityReport) else it
                    }
                }
                if (BuildConfig.DEBUG) {
                    Log.i(
                        AUDIO_TAG,
                        "requested=${cap.debug.requestedSampleRate} actual=${cap.debug.actualSampleRate} " +
                            "durationMs=${cap.debug.capturedDurationMs} rmsDbfs=${fmt(cap.debug.rmsDbfs)} " +
                            "peakDbfs=${fmt(cap.debug.peakDbfs)} clips=${cap.debug.clippingCount}",
                    )
                    Log.i(
                        VAD_TAG,
                        "speech=${cap.speechPresent} voicedMs=${cap.validSpeechMs} " +
                            "startMs=${cap.debug.speechStartMs} endMs=${cap.debug.speechEndMs} " +
                            "maxProb=${fmt(cap.debug.vadFrames.maxOfOrNull { it.probability.toDouble() })}",
                    )
                }
                _state.update {
                    it.copy(
                        debugPipeline = "MIC -> resample -> VAD: captured",
                        pipelineStage = "UNDERSTANDING",
                        micLevelDbfs = -120.0,
                    )
                }
                // Opt-in diagnostics only: normal debug/benchmark runs do not write audio.
                val artifactJob = async(Dispatchers.IO) {
                    if (!BuildConfig.CAPTURE_ARTIFACTS) return@async null
                    try {
                        AudioDebugStore.saveCapture(appContext, cap)
                    } catch (t: Throwable) {
                        Log.e(TAG, "debug artifact save failed", t)
                        null
                    }
                }
                val started = releasedAt              // release -> first audio, including capture finalization
                val captureFinalizeMs = nowMs() - started
                val clippingDetected = isMeaningfullyClipped(
                    clippingCount = cap.debug.clippingCount,
                    sampleCount = cap.debug.asrSampleCount,
                )
                val audio = AudioEvidence(
                    speechPresent = cap.speechPresent,
                    audioScore = cap.audioScore,
                    durationMs = cap.pcm16k.size.toLong() * 1000 / 16000,
                    validSpeechDurationMs = cap.validSpeechMs,
                    clippingDetected = clippingDetected,
                    silenceRatio = cap.debug.silenceRatio,
                )
                Log.i(TIMING_TAG, "phase=capture_finalized turnId=$releasedAt captureFinalizeMs=$captureFinalizeMs")
                // One shared, configured capture gate decides whether ASR may run. This must stay
                // before transcribeDetailed: clipping/noise/low-quality/short/too-long captures
                // are terminal ASK_REPEAT outcomes just like no-speech is terminal NO_SPEECH.
                // Debug salvage can only attach a red draft.
                val preAsr = preAsrDecision(r.engine, audio, cap.debug.captureLimitReached)
                if (preAsr != null) {
                    var diagnostics: AsrDiagnostics? = null
                    val draftStarted = nowMs()
                    val salvage = withContext(runnerDispatcher) {
                        RejectedCaptureDraft.attempt(preAsr, audio, cap.pcm16k.size, BuildConfig.RETRY_DRAFTS,
                            decode = {
                                val detailed = r.asr.transcribeDetailed(cap.pcm16k, src, tgt)
                                ensureActive() // Cancellation during native ASR must stop before MT.
                                diagnostics = detailed.diagnostics
                                detailed.result.also { turnTranscript = it }
                            },
                            attachDraft = { rejection, transcript ->
                                r.engine.withTentativeOutput(rejection, transcript,
                                    sourceTruncated = cap.debug.captureLimitReached)
                            })
                    }
                    val draftMs = nowMs() - draftStarted
                    val decision = salvage.decision
                    val totalLatency = nowMs() - started
                    lastLiveAsr = diagnostics
                    val gateReason = when (preAsr) {
                        TranslationDecision.NoSpeech -> "capture gate: no speech"
                        is TranslationDecision.AskRepeat -> "capture gate: ${preAsr.reason}"
                        else -> error("capture gate returned non-terminal decision")
                    }
                    val status = if (salvage.attempted) "$gateReason; unreliable draft attempted" else "$gateReason; ASR skipped"
                    val artifacts = artifactJob.await()
                    lastArtifacts = artifacts
                    val report = buildDebugReport(cap, diagnostics, decision, totalLatency, artifacts,
                        asrSkipReason = if (salvage.attempted) null else gateReason)
                    artifacts?.let {
                        try {
                            withContext(Dispatchers.IO) { AudioDebugStore.writeReport(it.report, report) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.e(TAG, "debug report save failed", failure)
                        }
                    }
                    salvage.failure?.let { Log.w(TAG, "Rejected capture draft unavailable: $it") }
                    if (BuildConfig.DEBUG) Log.i(TAG, report)
                    ensureActive()
                    publishRejectedCapture(endedCaptureGen, salvage.transcript?.text.orEmpty(), report,
                        status, decision, totalLatency, direction,
                        TurnTiming(speechEndMs = started,
                            asrMs = diagnostics?.latencyMs,
                            decideMs = if (salvage.attempted) (draftMs - (diagnostics?.latencyMs ?: 0)).coerceAtLeast(0) else null,
                            asrBackend = diagnostics?.backend))
                    return@launch
                }
                _state.update { it.copy(debugPipeline = "ASR: running", pipelineStage = "UNDERSTANDING") }
                val asrStarted = nowMs()
                val detailed = withContext(runnerDispatcher) {
                    r.asr.transcribeDetailed(cap.pcm16k, src, tgt)
                }
                turnTranscript = detailed.result
                val asrMs = nowMs() - asrStarted
                _state.update { it.copy(pipelineStage = "TRANSLATING", debugPipeline = "MT + Safety: running") }
                val decideStarted = nowMs()
                val decision = withContext(runnerDispatcher) {
                    r.engine.decide(audio, detailed.result)
                }
                val turnTiming = TurnTiming(
                    speechEndMs = started,
                    asrMs = asrMs,
                    decideMs = nowMs() - decideStarted,
                    asrBackend = detailed.diagnostics.backend,
                )
                val transcript = detailed.result
                lastLiveAsr = detailed.diagnostics
                if (BuildConfig.DEBUG) {
                    Log.i(
                        ASR_TAG,
                        "model=${detailed.diagnostics.modelName} raw=${detailed.diagnostics.rawTranscript} " +
                            "noSpeech=${fmt(detailed.diagnostics.noSpeechProbability)} latencyMs=${detailed.diagnostics.latencyMs} " +
                            "coldStart=${detailed.diagnostics.coldStart} coldLoadMs=${detailed.diagnostics.coldLoadMs} " +
                            "melLatencyMs=${detailed.diagnostics.melLatencyMs}",
                    )
                    Log.i(TRANSLATOR_TAG, "result=$decision")
                    Log.i(SAFETY_TAG, "decision=$decision")
                }
                val totalLatency = nowMs() - started
                val artifacts = artifactJob.await()
                lastArtifacts = artifacts
                val report = buildDebugReport(cap, detailed.diagnostics, decision, totalLatency, artifacts)
                artifacts?.let {
                    try {
                        withContext(Dispatchers.IO) { AudioDebugStore.writeReport(it.report, report) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "debug report save failed", t)
                    }
                }
                if (BuildConfig.DEBUG) Log.i(TAG, report)
                // Surface what ASR heard so the UI "Heard" field is populated (was dropped before).
                _state.update {
                    it.copy(
                        transcript = transcript.text,
                        debugReport = report,
                        debugPipeline = "ASR -> MT -> Safety: ${decision.javaClass.simpleName}",
                    )
                }
                render(decision, latency = totalLatency, direction, turnTiming)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Degrade, never crash: a failed ORT run / engine error is a runtime failure, not a
                // reason to kill the process. The safe outcome is the repeat prompt (we never
                // speak a translation we could not produce).
                Log.e(TAG, "pipeline failure ($src->$tgt)", t)
                _state.update {
                    it.copy(
                        listening = false, decision = "ASK_REPEAT (runtime error)", output = "",
                        transcript = turnTranscript?.text.orEmpty(),
                        tentativeOutput = if (BuildConfig.RETRY_DRAFTS)
                            RejectedCaptureDraft.sourceOnly(turnTranscript, "runtime_error") else null,
                        modelText = "", repairedSlots = "", awaitingConfirm = false, canApprove = false,
                        debugPipeline = "FAILED: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                        pipelineStage = "READY",
                        micLevelDbfs = -120.0,
                    )
                }
                speakCache("ask_repeat_${langTag(src)}")
            }
        }
    }

    /** Replays the exact post-resample PCM that was saved for Whisper. */
    fun playLastDebugCapture() {
        val state = _state.value
        if (!canStartCapture(state.pipelineStage, state.listening)) return
        val cap = lastCapture ?: return
        AudioOut.playFloatMono(cap.pcm16k, AudioRecordSource.TARGET_SAMPLE_RATE)
    }

    /** Bypass microphone + VAD by READING the saved WAV back and feeding it to the same ASR. */
    fun runAsrOnSavedDebugAudio() {
        val r = runners ?: return
        val file = lastArtifacts?.asrWav ?: return
        val direction = _state.value.direction
        viewModelScope.launch {
            _state.update { it.copy(debugPipeline = "BYPASS WAV -> ASR: running") }
            try {
                val wav = withContext(Dispatchers.IO) { AudioDebugStore.readMono16Wav(file) }
                require(wav.sampleRate == AudioRecordSource.TARGET_SAMPLE_RATE) {
                    "saved ASR WAV is ${wav.sampleRate} Hz, expected ${AudioRecordSource.TARGET_SAMPLE_RATE}"
                }
                val detailed = withContext(runnerDispatcher) {
                    r.asr.transcribeDetailed(wav.pcm, direction.first, direction.second)
                }
                val live = lastLiveAsr
                val sameRaw: Boolean? = live?.let { it.rawTranscript == detailed.diagnostics.rawTranscript }
                val bypass = buildString {
                    appendLine("BYPASS TEST")
                    appendLine("saved WAV: ${file.name}")
                    appendLine("samples: ${wav.pcm.size} @ ${wav.sampleRate} Hz")
                    appendLine("raw transcript: ${detailed.diagnostics.rawTranscript}")
                    appendLine("no_speech_prob: ${fmt(detailed.diagnostics.noSpeechProbability)}")
                    appendLine("avg_logprob: ${fmt(detailed.diagnostics.averageLogProbability)}")
                    appendLine("latency: ${detailed.diagnostics.latencyMs} ms")
                    appendLine("matches live raw transcript: ${sameRaw ?: "n/a"}")
                }
                val combined = _state.value.debugReport.trimEnd() + "\n\n" + bypass
                lastArtifacts?.let { withContext(Dispatchers.IO) { AudioDebugStore.writeReport(it.report, combined) } }
                _state.update {
                    it.copy(
                        debugBypassResult = bypass,
                        debugReport = combined,
                        debugPipeline = "BYPASS WAV -> ASR: complete",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "debug bypass ASR failed", t)
                _state.update {
                    it.copy(debugPipeline = "BYPASS FAILED: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
                }
            }
        }
    }

    /** Shares raw WAV + exact ASR WAV + VAD CSV + report through Android's chooser. */
    fun exportDebugArtifacts() {
        val a = lastArtifacts ?: return
        val files = listOf(a.rawWav, a.asrWav, a.vadCsv, a.report).filter { it.isFile }
        if (files.isEmpty()) return
        val uris = ArrayList(files.map {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", it)
        })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export VoxBridge audio debug").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(chooser)
    }

    private fun buildDebugReport(
        cap: AudioRecordSource.Capture,
        asr: AsrDiagnostics?,
        decision: TranslationDecision,
        endToResultMs: Long,
        artifacts: AudioDebugStore.Artifacts?,
        asrSkipReason: String? = null,
    ): String = buildString {
        val d = cap.debug
        appendLine("VOXBRIDGE AUDIO/ASR DEBUG")
        appendLine()
        appendLine("MIC")
        appendLine("requested sample rate: ${d.requestedSampleRate} Hz")
        appendLine("actual sample rate: ${d.actualSampleRate} Hz")
        appendLine("audio source: VOICE_RECOGNITION (${d.audioSource})")
        appendLine("channels: ${d.channelCount}")
        appendLine("PCM encoding: PCM_16BIT (${d.encoding})")
        appendLine("buffer: ${d.bufferSizeBytes} bytes")
        appendLine("raw samples: ${d.rawSampleCount}")
        appendLine("ASR samples: ${d.asrSampleCount} @ ${AudioRecordSource.TARGET_SAMPLE_RATE} Hz")
        appendLine("captured: ${d.capturedDurationMs} ms")
        appendLine("RMS: ${fmt(d.rmsDbfs)} dBFS")
        appendLine("peak: ${fmt(d.peakDbfs)} dBFS")
        appendLine("clipping samples: ${d.clippingCount}")
        appendLine("meaningful clipping: ${isMeaningfullyClipped(d.clippingCount, d.asrSampleCount)}")
        appendLine("AudioRecord read error: ${d.readError ?: "none"}")
        if (BuildConfig.LIVE_AUDIO_STATUS) {
            appendLine("Live quality (guidance only, uncalibrated): ${cap.liveAudioQuality}")
            appendLine("Live monitoring error: ${cap.liveAudioQualityError ?: "none"}")
        }
        appendLine()
        appendLine("VAD")
        appendLine("threshold: ${AudioRecordSource.SPEECH_THRESHOLD}")
        appendLine("frames: ${d.vadFrames.size}")
        appendLine("max speech probability: ${fmt(d.vadFrames.maxOfOrNull { it.probability.toDouble() })}")
        appendLine("speech start: ${d.speechStartMs?.let { "$it ms" } ?: "none"}")
        appendLine("speech end: ${d.speechEndMs?.let { "$it ms" } ?: "none"}")
        appendLine("voiced duration: ${cap.validSpeechMs} ms")
        appendLine("retained pre-roll: ${d.preRollAvailableMs} ms")
        appendLine("retained post-roll/hangover: ${d.postRollAvailableMs} ms")
        appendLine("silence ratio: ${fmt(d.silenceRatio)}")
        appendLine("capture limit reached: ${d.captureLimitReached}")
        appendLine("segmentation: VAD trim with 300 ms pre-roll / 400 ms post-roll; ASR receives the retained segment")
        appendLine()
        appendLine("ASR")
        if (asr == null) {
            appendLine("skipped: ${asrSkipReason ?: "not run"}")
        } else {
            appendLine("model: ${asr.modelName}")
            appendLine("backend: ${asr.backend}")
            appendLine("contract: ${asr.contract}")
            appendLine("language token: ${asr.languageToken}")
            appendLine("task token: ${asr.taskToken} (transcribe)")
            appendLine("input: ${asr.inputSampleCount} samples / ${asr.inputDurationMs} ms")
            appendLine("raw transcript: ${asr.rawTranscript}")
            appendLine("no_speech_prob: ${fmt(asr.noSpeechProbability)}")
            appendLine("avg_logprob: ${fmt(asr.averageLogProbability)}")
            appendLine("raw sequence probability: ${fmt(asr.rawSequenceProbability)} (UNCALIBRATED)")
            appendLine("generated token count: ${asr.tokenCount}")
            appendLine("stop reason: ${asr.stopReason}")
            appendLine("ASR latency: ${asr.latencyMs} ms")
        }
        appendLine()
        appendLine("PIPELINE")
        appendLine("decision: $decision")
        appendLine("end-of-speech -> result: $endToResultMs ms")
        artifacts?.let {
            appendLine("raw WAV: ${it.rawWav.absolutePath}")
            appendLine("ASR WAV: ${it.asrWav.absolutePath}")
            appendLine("VAD CSV: ${it.vadCsv.absolutePath}")
        }
    }

    private fun fmt(value: Double?): String = value?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a"

    /** For REPAIRED output the listener taps confirm before the rewritten instruction is spoken. */
    fun confirmRepairedAndSpeak() {
        val s = _state.value
        if (!canStartCapture(s.pipelineStage, s.listening) || !s.awaitingConfirm) return
        val events = speechOutputSession?.confirmRepaired().orEmpty()
        if (events.isNotEmpty()) {
            _state.update { it.copy(awaitingConfirm = false) }
            emitSpeechEvents(events)
        }
    }

    fun replayOutput() {
        val s = _state.value
        if (!canStartCapture(s.pipelineStage, s.listening) || s.awaitingConfirm) return
        if (s.decision == QUICK_PHRASE_DECISION) { lastQuickPhraseId?.let(::playQuickPhrase); return }
        emitSpeechEvents(speechOutputSession?.replayEvents().orEmpty())
    }

    /** Complete delayed diagnostics before this call; the render owns prompt playback state. */
    private fun publishRejectedCapture(
        generation: Int,
        transcript: String,
        report: String,
        status: String,
        decision: TranslationDecision,
        latency: Long,
        direction: Pair<Language, Language>,
        timing: TurnTiming,
    ) {
        if (captureGen != generation) return
        _state.update { it.copy(transcript = transcript, debugReport = report, debugPipeline = status) }
        render(decision, latency, direction, timing)
    }

    private fun render(
        d: TranslationDecision,
        latency: Long,
        direction: Pair<Language, Language>,
        timing: TurnTiming,
    ) {
        val (src, tgt) = direction
        val policySession = speechOutputPolicy.session(d, src, tgt)
        Log.i(TIMING_TAG, "phase=decided turnId=${timing.speechEndMs} source=${src.name} target=${tgt.name} decision=${d.javaClass.simpleName} ${timing.logLine()}")
        // Clear per-utterance fields (modelText + repairedSlots) so a prior REPAIRED can't leak
        // its raw model text / slots into the next decision's state.
        val next = _state.value.copy(
            listening = false, latencyMs = latency, timing = timing.displaySummary(),
            modelText = "", repairedSlots = "", awaitingConfirm = false,
            canApprove = false, feedbackNote = "",
            tentativeOutput = tentativeForDecision(d, BuildConfig.RETRY_DRAFTS),
            micLevelDbfs = -120.0,
        ).let { base ->
            when (d) {
                is TranslationDecision.NoSpeech ->
                    // The no-speech prompt addresses the SPEAKER (they must try again) — source language.
                    base.copy(decision = "NO_SPEECH", output = "")
                is TranslationDecision.AskRepeat ->
                    // ASK_REPEAT asks the speaker to repeat (doc 14 §6 — "failure without blame"):
                    // the prompt plays in the SOURCE language, not the target's.
                    base.copy(decision = "ASK_REPEAT (${d.reason})", output = "")
                is TranslationDecision.MemoryTranslation ->
                    base.copy(decision = "MEMORY", output = d.text, canApprove = true)
                is TranslationDecision.ModelTranslation ->
                    base.copy(decision = "TRANSLATED", output = d.text, canApprove = true)
                is TranslationDecision.VerifiedTranslation ->
                    base.copy(decision = "VERIFIED", output = d.text, canApprove = true)
                is TranslationDecision.RepairedTranslation ->
                    // Do NOT auto-speak: requires a confirm-tap (FR-17). Show model text + restored slots.
                    base.copy(
                        decision = "REPAIRED", output = d.text, modelText = d.modelText,
                        repairedSlots = d.repaired.joinToString(", "), awaitingConfirm = true, canApprove = true,
                    )
            }
        }
        // Side effects (TTS synthesis/playback) live OUTSIDE the state write (Round-4 review
        // MED): MutableStateFlow.update's transform can re-execute under CAS contention, so a
        // speak() inside it could synthesize and play the same phrase twice, and a concurrent
        // feedbackNote landing mid-transform would be silently clobbered by the captured `base`.
        // Land the state first, then play — exactly once per render.
        _state.value = next
        speechOutputSession = policySession
        timingForInitialSpeech = timing
        try {
            emitSpeechEvents(policySession.initialEvents())
        } finally {
            timingForInitialSpeech = null
        }
    }

    private fun emitSpeechEvents(events: List<SpeechOutputPolicy.Event>) {
        for (event in events) {
            when (event) {
                is SpeechOutputPolicy.Event.Prompt -> {
                    val prefix = when (event.kind) {
                        SpeechOutputPolicy.PromptKind.NO_SPEECH -> "no_speech"
                        SpeechOutputPolicy.PromptKind.ASK_REPEAT -> "ask_repeat"
                    }
                    speakCache("${prefix}_${langTag(event.language)}")
                }
                is SpeechOutputPolicy.Event.SpeakTranslation -> speak(event.text, event.language, cachedId = null)
            }
        }
    }

    // ------------------------------------------------------------------
    // Feedback (Local Learning Loop)
    // ------------------------------------------------------------------

    /**
     * Approve the on-screen translation (optionally with an edited target). Runs the verified
     * FeedbackPromoter (write-path: slot-conflict block + promotion ladder, both core-tested) and
     * persists a Promote/HintOnly/Demoted outcome to the encrypted store. A Blocked outcome (the
     * correction would drop/flip a safety slot) is surfaced and NOT stored — the loop can never
     * learn an unsafe correction.
     */
    fun approve(correctedTarget: String? = null) = submitFeedback(approved = true, correctedTarget = correctedTarget)
    fun reject() = submitFeedback(approved = false, correctedTarget = null)

    private fun submitFeedback(approved: Boolean, correctedTarget: String?) {
        val s = _state.value
        if (!s.canApprove || s.output.isBlank()) return
        val (src, tgt) = s.direction
        // `store` is a member of the nested Runners class — capture the set once (same reason as
        // onPressEnd: unresolved bare identifier + read-race with onCleared, Round-4 BLOCK).
        val r = runners ?: return
        viewModelScope.launch(runnerDispatcher) {
            try {
                val feedback = UserFeedback(
                    sourceText = s.transcript,
                    targetText = s.output,
                    approved = approved,
                    correctedTargetText = correctedTarget,
                    sourceLanguage = src,
                    targetLanguage = tgt,
                    decisionBeforeFeedback = s.decision,
                    createdAtMs = System.currentTimeMillis(),
                )
                val sourceFrame = extractor.extract(s.transcript, src)
                val correctedFrame = extractor.extract(correctedTarget ?: s.output, tgt)
                val key = normalizeForKey(s.transcript, src)
                val existing = r.store.findExact(key, src, tgt)

                // A failed upsert must not masquerade as success ("Saved") — the learning loop would
                // silently not persist while the user believes it did.
                val note = when (val outcome = promoter.evaluate(feedback, sourceFrame, correctedFrame, existing)) {
                    is FeedbackPromoter.PromotionDecision.Promote ->
                        if (r.store.upsert(outcome.example)) "Saved (promoted)" else "Save FAILED (promoted)"
                    is FeedbackPromoter.PromotionDecision.HintOnly ->
                        if (r.store.upsert(outcome.example)) "Saved (hint)" else "Save FAILED (hint)"
                    is FeedbackPromoter.PromotionDecision.Demoted ->
                        if (r.store.upsert(outcome.example)) "Correction noted" else "Save FAILED (correction)"
                    is FeedbackPromoter.PromotionDecision.Blocked -> "Not learned: ${outcome.reason}"
                }
                _state.update { it.copy(feedbackNote = note, canApprove = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Store failure must surface, not crash (and never masquerade as saved).
                Log.e(TAG, "feedback persistence failed", t)
                _state.update {
                    it.copy(feedbackNote = "Save FAILED (${t.message ?: t.javaClass.simpleName})", canApprove = false)
                }
            }
        }
    }

    /** Lookup key for the existing-example search. MUST be the same normalization the write
     *  side (FeedbackPromoter) used for the stored `normalizedSource` — both delegate to
     *  [UtteranceKey], the one shared normalizer (Round-4 review HIGH). */
    private fun normalizeForKey(text: String, language: Language): String =
        UtteranceKey.normalize(text, language)

    // ------------------------------------------------------------------
    // Audio out
    // ------------------------------------------------------------------

    /**
     * Synthesize every quick phrase for [target] one at a time on the runner thread. Each phrase is
     * its own dispatch, so a capture started meanwhile waits for at most one short phrase.
     */
    private fun prepareQuickPhrases(target: Language) {
        val r = runners ?: return
        quickPhraseJob?.cancel()
        quickPhraseJob = viewModelScope.launch {
            for (phrase in QUICK_PHRASES) {
                val key = quickPhraseAudioKey(phrase, target)
                if (quickPhraseAudio.containsKey(key)) continue
                try {
                    quickPhraseAudio[key] = withContext(runnerDispatcher) { r.tts.synthesize(phrase.text(target), target) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TTS_TAG, "quick phrase ${phrase.id} $target synthesis failed; will synthesize on tap", t)
                }
            }
        }
    }

    /** Play a fixed safety phrase in the listener's language, skipping ASR and MT. */
    fun playQuickPhrase(id: String) {
        val phrase = quickPhrase(id) ?: return
        val s = _state.value
        if (!canStartCapture(s.pipelineStage, s.listening)) return
        val (source, target) = s.direction
        lastQuickPhraseId = id
        speechOutputSession = null
        _state.update {
            it.copy(
                transcript = phrase.text(source), modelText = "", output = phrase.text(target),
                tentativeOutput = null, decision = QUICK_PHRASE_DECISION, repairedSlots = "",
                awaitingConfirm = false, canApprove = false, feedbackNote = "", timing = "",
            )
        }
        val r = runners ?: return
        val key = quickPhraseAudioKey(phrase, target)
        launchSpeech("TTS quick ${phrase.id}", listOf {
            quickPhraseAudio[key] ?: r.tts.synthesize(phrase.text(target), target).also { quickPhraseAudio[key] = it }
        })
    }

    private fun stopSpeech() {
        speechGeneration++
        speechJob?.cancel()
        speechJob = null
        AudioOut.stop()
    }

    private fun speak(text: String, lang: Language, cachedId: String?) {
        val r = runners ?: return
        val cached = cachedId?.let { id -> { checkNotNull(r.tts.cachedPhrase(id)) { "Missing phrase: $id" } } }
        // Sentence chunks let the first sentence play while the next one is synthesized.
        val producers: List<() -> PcmAudio> = if (cached != null) listOf(cached)
            else SpeechAudioPlan.producers(text, lang, r.tts::cachedPhrase, r.tts::synthesize)
        launchSpeech("TTS $lang", producers)
    }

    private fun speakCache(id: String) {
        val r = runners ?: return
        launchSpeech("TTS cache $id", listOf {
            checkNotNull(r.tts.cachedPhrase(id)) { "Missing safety phrase: $id" }
        })
    }

    /** Keep the UI busy through actual drain/release, not just synthesis/queue submission. */
    private fun launchSpeech(label: String, producers: List<() -> PcmAudio>) {
        stopSpeech()
        val generation = speechGeneration
        val turnTiming = timingForInitialSpeech
        timingForInitialSpeech = null
        _state.update { it.copy(debugTtsStatus = "$label: synthesizing", pipelineStage = "SPEAKING") }
        speechJob = viewModelScope.launch {
            try {
                val started = nowMs()
                playChunked(
                    producers = producers.map { produce ->
                        suspend { withContext(runnerDispatcher) { SpeechLoudness.apply(produce()).also { ensureActive() } } }
                    },
                    startPlayback = { pcm, onStarted -> AudioOut.play(pcm, onStarted) },
                    onFirstAudio = { onFirstAudio(label, generation, turnTiming) },
                )
                if (generation == speechGeneration) {
                    val elapsed = nowMs() - started
                    Log.i(TTS_TAG, "$label synthesis+playback=${elapsed}ms")
                    _state.update { it.copy(debugTtsStatus = "$label: playback complete (${elapsed} ms)", pipelineStage = "READY") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "$label failed", t)
                if (generation == speechGeneration) {
                    _state.update { it.copy(debugTtsStatus = "$label FAILED: ${t.message ?: t.javaClass.simpleName}", pipelineStage = "READY") }
                }
            }
        }
    }

    /** Runs on the playback thread when the first chunk's bytes reach the AudioTrack. */
    private fun onFirstAudio(label: String, generation: Long, turnTiming: TurnTiming?) {
        if (generation != speechGeneration) return
        val measured = turnTiming?.withFirstAudioAt(nowMs())
        measured?.let { Log.i(TIMING_TAG, "phase=first_audio turnId=${it.speechEndMs} audioKind=${if(label.startsWith("TTS cache")) "prompt" else "translation"} ${it.logLine()}") }
        _state.update {
            it.copy(
                debugTtsStatus = "$label: playing",
                timing = measured?.displaySummary() ?: it.timing,
            )
        }
    }

    private fun langTag(l: Language) = l.name.lowercase()
    private fun nowMs() = android.os.SystemClock.elapsedRealtime()

    // Haptic dedup: LaunchedEffect re-runs on every composition recreation (rotation), and the
    // retained VM still carries the last decision — without this check the same decision would
    // re-buzz on every configuration change. The UI asks; the VM remembers what already fired.
    private var hapticFiredFor: String? = null
    fun shouldFireHaptic(decision: String): Boolean {
        if (decision.isBlank() || decision == hapticFiredFor) return false
        hapticFiredFor = decision
        return true
    }

    /**
     * Release native resources when the framework clears the ViewModelStore. Because this VM is
     * store-owned (constructed via [factory] through ViewModelProvider), this runs exactly when
     * the task truly ends — and, just as important, the retained runners mean it does NOT run on
     * mere rotation (the build ran once in [init], not once per Activity).
     *
     * Each close is guarded so one failure can't block the rest.
     *
     * Ordering: transcribe/synthesize are BLOCKING native calls on [runnerDispatcher] — cancelling
     * viewModelScope does not interrupt a worker already inside session.run(). The close therefore
     * runs on that same single-threaded lane (via [closeScope], which outlives the cancelled
     * viewModelScope), queueing behind any in-flight call instead of freeing sessions under it.
     */
    override fun onCleared() {
        held = false
        liveAudioHapticPolicy.reset()
        captureGen++
        speechOutputSession = null
        stopSpeech()
        val finishingCapture = activeCaptureJob
        activeCaptureJob = null
        captureJob = null
        lastCapture = null
        lastArtifacts = null
        val current = runners
        runners = null
        capture = null
        closeScope.launch {
            try {
                closeAfterCapture(finishingCapture) {
                    // A blocking synthesis may have completed after the first stop above.
                    AudioOut.stop()
                    current?.close()
                }
            } finally {
                closeScope.cancel()
            }
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "VoxBridge"
        private const val MODEL_TAG = "ModelRepository"
        private const val AUDIO_TAG = "AudioCapture"
        private const val VAD_TAG = "SileroVad"
        private const val ASR_TAG = "WhisperAsr"
        private const val TRANSLATOR_TAG = "Translator"
        private const val SAFETY_TAG = "SafetyLayer"
        private const val TTS_TAG = "PiperTts"
        private const val TIMING_TAG = "VoxTiming"

        /**
         * The VM MUST live in the ViewModelStore: build the factory through `viewModels(factory)`
         * / ViewModelProvider — never `PushToTalkViewModel(...)` directly. Store ownership is what
         * makes onCleared() run (runner teardown) and what retains the built runners across
         * rotation (the multi-second build happens once per task). Holds only applicationContext —
         * never an Activity.
         */
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(PushToTalkViewModel::class.java)) {
                    "factory only creates PushToTalkViewModel (requested $modelClass)"
                }
                val app = context.applicationContext
                val profile = ModelProfile.fromId(BuildConfig.MODEL_PROFILE)
                val source = ModelSource.valueOf(BuildConfig.MODEL_SOURCE)
                val repository = AndroidModelPaths.repository(app, source, profile)
                return PushToTalkViewModel(
                    appContext = app,
                    modelRepository = repository,
                    fakePipeline = BuildConfig.USE_FAKE_PIPELINE,
                ) as T
            }
        }
    }
}
