package voxbridge.e2e

import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.learn.FeedbackPromoter
import com.voxbridge.core.learn.FeedbackType
import com.voxbridge.core.learn.LearningExample
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.learn.UserFeedback
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import com.voxbridge.runtime.LanguageRoutedAsr
import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.OnnxTranslator
import com.voxbridge.runtime.SelectedDirectionTranslator
import com.voxbridge.runtime.SileroVad
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class UnifiedRunSummary(
    val gateFailures: Int,
    val logFile: File,
)

/**
 * Real Mac/JVM sample runner for the product state machine.
 *
 * Neural outputs are never injected. Every speech sample runs the shipping Silero VAD,
 * language-routed Whisper and OPUS-MT implementations. The only deterministic scenario injection
 * is the contents/failure mode of the Local Learning store, which is the exact seam Task 3 needs to
 * exercise memory miss/reuse/failure behavior without mocking ASR or MT.
 */
class UnifiedRealRunner(
    private val env: OrtEnvironment,
    private val paths: ModelPaths,
) {
    private val extractor = SafetyFrameExtractor()
    private val outputPolicy = SpeechOutputPolicy()

    private data class ManifestRow(
        val id: String,
        val wav: File,
        val source: Language,
        val target: Language,
        val sourceKind: String,
        val safetyBearing: Boolean,
        val sha256: String?,
        val provenance: Any?,
        val referenceSource: String?,
        val referenceSlots: Any?,
    )

    private enum class Scenario {
        MISS,
        SILENCE,
        STORE_FAILURE,
        UNTRUSTED_HIT,
        TRUSTED_HIT,
        CORRECTION_REUSE,
        SAFETY_CONFLICT,
    }

    private data class Seed(
        val transcript: String,
        val source: Language,
        val target: Language,
        val targetText: String,
    )

    private data class SampleOutcome(
        val id: String,
        val scenario: Scenario,
        val transcript: String?,
        val mtText: String?,
        val finalText: String?,
        val decision: TranslationDecision?,
        val storeCalls: Int,
        val translatorCalls: Int,
        val gateFailures: List<String>,
    ) {
        fun seedOrNull(): Seed? {
            val text = transcript?.takeIf(String::isNotBlank) ?: return null
            val targetText = finalText?.takeIf(String::isNotBlank) ?: mtText?.takeIf(String::isNotBlank) ?: return null
            val decisionValue = decision ?: return null
            if (decisionValue is TranslationDecision.NoSpeech || decisionValue is TranslationDecision.AskRepeat) return null
            val parts = id.substringAfterLast("|").split("->")
            if (parts.size != 2) return null
            return Seed(text, language(parts[0]), language(parts[1]), targetText)
        }
    }

    private class ScenarioStore(
        private val examples: List<LearningExample>,
        private val fail: Boolean = false,
    ) : LearningStore {
        var calls: Int = 0
            private set

        override fun all(): List<LearningExample> {
            calls++
            if (fail) error("simulated local-memory store failure")
            return examples
        }
    }

    private class RecordingTranslator(
        private val delegate: Translator,
    ) : Translator {
        var calls: Int = 0
            private set
        var lastText: String? = null
            private set
        var lastMs: Long = 0
            private set

        fun reset() {
            calls = 0
            lastText = null
            lastMs = 0
        }

        override fun translate(
            text: String,
            sourceLanguage: Language,
            targetLanguage: Language,
        ): TranslationResult {
            calls++
            val t0 = System.nanoTime()
            val result = delegate.translate(text, sourceLanguage, targetLanguage)
            lastMs += elapsedMs(t0)
            lastText = result.translatedText
            return result
        }
    }

    fun run(manifest: File): UnifiedRunSummary {
        require(manifest.isFile) { "unified manifest not found: ${manifest.absolutePath}" }
        val rows = parseManifest(manifest)
        val vi = rows.firstOrNull { it.source == Language.VI && it.sourceKind == "fleurs-human-clean" }
            ?: error("manifest has no VI human-clean sample")
        val en = rows.firstOrNull { it.source == Language.EN && it.sourceKind == "fleurs-human-clean" }
            ?: error("manifest has no EN human-clean sample")
        val silence = rows.firstOrNull { it.sourceKind == "synthetic-silence" }
            ?: error("manifest has no synthetic-silence edge sample")

        val logFile = System.getenv("VOXBRIDGE_UNIFIED_LOG")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File("reports/mac-cpu/unified-e2e-results.jsonl")
        logFile.parentFile?.mkdirs()
        logFile.writeText("")

        var gateFailures = 0
        var memoryEligible = 0
        var memoryAccepted = 0

        SileroVad.load(env, paths.vad).use { vad ->
            LanguageRoutedAsr(env, paths).use { asr ->
                SelectedDirectionTranslator { src, tgt ->
                    val s = tag(src)
                    val t = tag(tgt)
                    OnnxTranslator.load(env, paths.translationDir(s, t), src, tgt)
                }.use { realMt ->
                    val recorder = RecordingTranslator(realMt)

                    fun execute(
                        label: String,
                        row: ManifestRow,
                        scenario: Scenario,
                        seed: Seed? = null,
                        compareWith: SampleOutcome? = null,
                    ): SampleOutcome {
                        val outcome = runSample(label, row, scenario, seed, compareWith, vad, asr, recorder, logFile)
                        gateFailures += outcome.gateFailures.size
                        if (scenario in setOf(Scenario.UNTRUSTED_HIT, Scenario.TRUSTED_HIT, Scenario.CORRECTION_REUSE, Scenario.SAFETY_CONFLICT)) {
                            memoryEligible++
                            if (outcome.decision is TranslationDecision.MemoryTranslation) memoryAccepted++
                        }
                        return outcome
                    }

                    // Shared sessions deliberately alternate VI -> silence -> EN -> VI to expose
                    // recurrent/KV/session leakage. The second VI run is forced through real MT via
                    // a failing memory store and must reproduce the first VI neural outputs.
                    val viMiss = execute("vi-miss", vi, Scenario.MISS)
                    execute("silence", silence, Scenario.SILENCE)
                    execute("en-miss", en, Scenario.MISS)

                    val viSeed = seedFrom(viMiss, vi.source, vi.target)
                    if (viSeed == null) {
                        gateFailures++
                        appendSystemRecord(logFile, "vi_seed_unavailable", "VI miss did not produce an accepted real translation")
                    } else {
                        execute("vi-repeat-store-failure", vi, Scenario.STORE_FAILURE, viSeed, compareWith = viMiss)
                        execute("vi-untrusted-hit", vi, Scenario.UNTRUSTED_HIT, viSeed)
                        execute("vi-trusted-hit", vi, Scenario.TRUSTED_HIT, viSeed)
                        execute("vi-correction-reuse", vi, Scenario.CORRECTION_REUSE, viSeed)
                    }

                    // Conflict needs a real speech sample whose *real ASR transcript* activates the
                    // safety frame. Preselect using references only to avoid neural scanning of a
                    // large corpus; final eligibility is always checked on the real transcript.
                    val safetyCandidate = rows.firstOrNull {
                        it.safetyBearing &&
                            !it.referenceSource.isNullOrBlank() &&
                            extractor.extract(it.referenceSource, it.source).hasSafetySignal
                    }
                    if (safetyCandidate != null) {
                        val safetyMiss = execute("safety-miss", safetyCandidate, Scenario.MISS)
                        val safetySeed = seedFrom(safetyMiss, safetyCandidate.source, safetyCandidate.target)
                        val realTranscript = safetyMiss.transcript
                        if (safetySeed != null && realTranscript != null && extractor.extract(realTranscript, safetyCandidate.source).hasSafetySignal) {
                            execute("safety-conflicting-hit", safetyCandidate, Scenario.SAFETY_CONFLICT, safetySeed)
                        } else {
                            appendSystemRecord(
                                logFile,
                                "safety_conflict_unavailable",
                                "candidate reference was safety-bearing but real ASR transcript was not eligible",
                            )
                            if (System.getenv("VOXBRIDGE_REQUIRE_CONFLICT") == "1") gateFailures++
                            Unit
                        }
                    } else {
                        appendSystemRecord(
                            logFile,
                            "safety_conflict_unavailable",
                            "manifest contains no real human-clean reference that activates SafetyFrameExtractor",
                        )
                        if (System.getenv("VOXBRIDGE_REQUIRE_CONFLICT") == "1") gateFailures++
                        Unit
                    }
                }
            }
        }

        val rate = if (memoryEligible == 0) 0.0 else memoryAccepted.toDouble() / memoryEligible
        val summary = JSONObject()
            .put("type", "summary")
            .put("memory_translation_acceptance_rate", rate)
            .put("memory_accepted", memoryAccepted)
            .put("memory_eligible", memoryEligible)
            .put("gate_failures", gateFailures)
        logFile.appendText(summary.toString() + "\n")
        println(
            "unified runner: gateFailures=$gateFailures memoryAcceptance=" +
                String.format(Locale.US, "%.3f", rate) + " log=${logFile.absolutePath}",
        )
        return UnifiedRunSummary(gateFailures, logFile)
    }

    private fun runSample(
        label: String,
        row: ManifestRow,
        scenario: Scenario,
        seed: Seed?,
        compareWith: SampleOutcome?,
        vad: SileroVad,
        asr: LanguageRoutedAsr,
        translator: RecordingTranslator,
        logFile: File,
    ): SampleOutcome {
        val sampleId = "$label|${row.source.name}->${row.target.name}"
        val problems = mutableListOf<String>()
        var transcript: String? = null
        var mtText: String? = null
        var finalText: String? = null
        var decision: TranslationDecision? = null
        var storeCalls = 0
        var translatorCalls = 0
        val timings = linkedMapOf<String, Long>()
        var rejectReason: String? = null
        var failure: String? = null
        var audioScore: Double? = null
        var speechMs: Long? = null
        val started = System.nanoTime()

        try {
            val readStart = System.nanoTime()
            val pcm = readMono16kPcm16Wav(row.wav)
            timings["read_ms"] = elapsedMs(readStart)

            val vadStart = System.nanoTime()
            val summary = synchronized(vad) {
                CaptureAnalysis.analyzeVad(
                    samples = pcm,
                    reset = vad::reset,
                    probability = vad::chunkProbability,
                )
            }
            timings["vad_ms"] = elapsedMs(vadStart)
            speechMs = summary.validSpeechDurationMs
            val trimmed = CaptureAnalysis.trimToSpeech(pcm, summary.startMs, summary.endMs)
            val metrics = CaptureAnalysis.signalMetrics(trimmed)
            val clipped = CaptureAnalysis.isMeaningfullyClipped(metrics.clippingCount, trimmed.size)
            val durationMs = pcm.size.toLong() * 1000L / CaptureAnalysis.TARGET_SAMPLE_RATE
            val silenceRatio = if (durationMs == 0L) 1.0
                else (1.0 - summary.validSpeechDurationMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            audioScore = CaptureAnalysis.audioQualityScore(pcm, summary)
            val audio = AudioEvidence(
                speechPresent = summary.speechPresent,
                audioScore = audioScore,
                durationMs = durationMs,
                clippingDetected = clipped,
                validSpeechDurationMs = summary.validSpeechDurationMs,
                silenceRatio = silenceRatio,
            )

            val store = scenarioStore(scenario, seed)
            val memory = LocalMemoryRetriever(store)
            translator.reset()
            val engine = DecisionEngine(translator = translator, memory = memory)

            val pre = engine.preAsrDecision(audio)
            if (pre != null) {
                decision = pre
                rejectReason = rejectReason(pre)
            } else {
                val asrStart = System.nanoTime()
                val detailed = asr.transcribeDetailed(trimmed, row.source, row.target)
                timings["asr_ms"] = elapsedMs(asrStart)
                transcript = detailed.result.text

                val decisionStart = System.nanoTime()
                decision = engine.decide(audio, detailed.result)
                timings["decision_ms"] = elapsedMs(decisionStart)
                mtText = translator.lastText
                timings["mt_ms"] = translator.lastMs
                rejectReason = rejectReason(decision)
            }
            storeCalls = store.calls
            translatorCalls = translator.calls
            finalText = decisionText(decision)

            validateScenario(scenario, decision, storeCalls, translatorCalls, transcript, problems)
            validateOutputPolicy(decision, row.source, row.target, problems)

            if (scenario == Scenario.STORE_FAILURE && compareWith != null) {
                if (transcript != compareWith.transcript) {
                    problems += "state_leak_asr: repeated VI transcript differs after EN switch"
                }
                if (mtText != compareWith.mtText) {
                    problems += "state_leak_mt: repeated VI MT differs after EN switch"
                }
            }
            if (scenario == Scenario.SAFETY_CONFLICT && transcript != null && !extractor.extract(transcript, row.source).hasSafetySignal) {
                problems += "safety_conflict_precondition: real transcript has no safety signal"
            }
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            problems += "sample_exception:$failure"
        }

        timings["total_ms"] = elapsedMs(started)
        val record = JSONObject()
            .put("type", "sample")
            .put("id", sampleId)
            .put("scenario", scenario.name.lowercase(Locale.ROOT))
            .put("wav", row.wav.absolutePath)
            .put("source", row.source.name)
            .put("target", row.target.name)
            .put("source_kind", row.sourceKind)
            .put("safety_bearing", row.safetyBearing)
            .put("sha256", row.sha256 ?: JSONObject.NULL)
            .put("provenance", row.provenance ?: JSONObject.NULL)
            .put("audio_score", audioScore ?: JSONObject.NULL)
            .put("valid_speech_ms", speechMs ?: JSONObject.NULL)
            .put("timings_ms", JSONObject(timings as Map<*, *>))
            .put("reject_reason", rejectReason ?: JSONObject.NULL)
            .put("raw_transcript", transcript ?: JSONObject.NULL)
            .put("mt_text", mtText ?: JSONObject.NULL)
            .put("final_text", finalText ?: JSONObject.NULL)
            .put("terminal_decision", decisionName(decision))
            .put("reference_source", row.referenceSource ?: JSONObject.NULL)
            .put("reference_slots", row.referenceSlots ?: JSONObject.NULL)
            .put("memory_lookup_calls", storeCalls)
            .put("mt_calls", translatorCalls)
            .put("failure", failure ?: JSONObject.NULL)
            .put("gate_failures", JSONArray(problems))
        logFile.appendText(record.toString() + "\n")

        val status = if (problems.isEmpty()) "PASS" else "FAIL"
        println("$status unified $sampleId decision=${decisionName(decision)} lookup=$storeCalls mt=$translatorCalls")
        problems.forEach { println("      - $it") }
        return SampleOutcome(sampleId, scenario, transcript, mtText, finalText, decision, storeCalls, translatorCalls, problems)
    }

    private fun scenarioStore(scenario: Scenario, seed: Seed?): ScenarioStore {
        if (scenario == Scenario.STORE_FAILURE) return ScenarioStore(emptyList(), fail = true)
        if (scenario == Scenario.MISS || scenario == Scenario.SILENCE) return ScenarioStore(emptyList())
        val s = requireNotNull(seed) { "$scenario requires a seed from a prior real translation" }
        val sourceFrame = extractor.extract(s.transcript, s.source)
        val targetFrame = extractor.extract(s.targetText, s.target)
        val example = when (scenario) {
            Scenario.UNTRUSTED_HIT -> LearningExample(
                normalizedSource = s.transcript,
                approvedTarget = s.targetText,
                sourceLanguage = s.source,
                targetLanguage = s.target,
                sourceFrame = sourceFrame,
                targetFrame = targetFrame,
                feedbackType = FeedbackType.APPROVED,
                approvalCount = 1,
            )
            Scenario.TRUSTED_HIT -> LearningExample(
                normalizedSource = s.transcript,
                approvedTarget = s.targetText,
                sourceLanguage = s.source,
                targetLanguage = s.target,
                sourceFrame = sourceFrame,
                targetFrame = targetFrame,
                feedbackType = FeedbackType.APPROVED,
                approvalCount = if (sourceFrame.hasSafetySignal) 3 else 2,
            )
            Scenario.CORRECTION_REUSE -> promotedCorrection(s, sourceFrame, targetFrame)
            Scenario.SAFETY_CONFLICT -> LearningExample(
                normalizedSource = s.transcript,
                approvedTarget = if (s.target == Language.EN) "okay" else "được rồi",
                sourceLanguage = s.source,
                targetLanguage = s.target,
                sourceFrame = sourceFrame,
                targetFrame = extractor.extract(if (s.target == Language.EN) "okay" else "được rồi", s.target),
                feedbackType = FeedbackType.EDITED_TRANSLATION,
                approvalCount = 3,
            )
            else -> error("unexpected memory scenario $scenario")
        }
        return ScenarioStore(listOf(example))
    }

    private fun promotedCorrection(
        seed: Seed,
        sourceFrame: com.voxbridge.core.safety.SafetyFrame,
        targetFrame: com.voxbridge.core.safety.SafetyFrame,
    ): LearningExample {
        val promoter = FeedbackPromoter()
        fun feedback(ts: Long) = UserFeedback(
            sourceText = seed.transcript,
            targetText = seed.targetText,
            approved = true,
            // The injected state is a user-approved correction record, but its text remains the
            // prior real MT output. This exercises the correction/promotion/reuse lifecycle without
            // manufacturing a replacement translation in the neural path.
            correctedTargetText = seed.targetText,
            sourceLanguage = seed.source,
            targetLanguage = seed.target,
            decisionBeforeFeedback = "MODEL_TRANSLATION",
            createdAtMs = ts,
        )
        val first = promoter.evaluate(feedback(1), sourceFrame, targetFrame, existing = null)
        val hint = when (first) {
            is FeedbackPromoter.PromotionDecision.HintOnly -> first.example
            is FeedbackPromoter.PromotionDecision.Promote -> first.example
            else -> error("unexpected first correction promotion: $first")
        }
        val second = promoter.evaluate(feedback(2), sourceFrame, targetFrame, existing = hint)
        return when (second) {
            is FeedbackPromoter.PromotionDecision.Promote -> second.example
            else -> error("correction did not promote on second approval: $second")
        }
    }

    private fun validateScenario(
        scenario: Scenario,
        decision: TranslationDecision?,
        storeCalls: Int,
        translatorCalls: Int,
        transcript: String?,
        problems: MutableList<String>,
    ) {
        when (scenario) {
            Scenario.SILENCE -> {
                if (decision !is TranslationDecision.NoSpeech) problems += "silence_not_no_speech"
                if (storeCalls != 0) problems += "silence_reached_memory:$storeCalls"
                if (translatorCalls != 0) problems += "silence_reached_mt:$translatorCalls"
            }
            Scenario.MISS -> {
                if (transcript.isNullOrBlank()) problems += "miss_blank_real_asr"
                if (storeCalls != 1) problems += "empty_memory_lookup_count:$storeCalls"
                if (translatorCalls != 1) problems += "miss_mt_calls:$translatorCalls"
                if (decision is TranslationDecision.NoSpeech || decision is TranslationDecision.AskRepeat || decision == null) {
                    problems += "miss_not_accepted:${decisionName(decision)}"
                }
            }
            Scenario.STORE_FAILURE -> {
                if (storeCalls != 1) problems += "store_failure_lookup_count:$storeCalls"
                if (translatorCalls != 1) problems += "store_failure_did_not_fall_through_to_mt:$translatorCalls"
                if (decision is TranslationDecision.MemoryTranslation) problems += "store_failure_reused_memory"
            }
            Scenario.UNTRUSTED_HIT -> {
                if (storeCalls != 1) problems += "untrusted_lookup_count:$storeCalls"
                if (translatorCalls != 1) problems += "untrusted_did_not_use_real_mt:$translatorCalls"
                if (decision is TranslationDecision.MemoryTranslation) problems += "untrusted_memory_was_reused"
            }
            Scenario.TRUSTED_HIT, Scenario.CORRECTION_REUSE -> {
                if (storeCalls != 1) problems += "trusted_lookup_count:$storeCalls"
                if (translatorCalls != 0) problems += "trusted_hit_called_mt:$translatorCalls"
                if (decision !is TranslationDecision.MemoryTranslation) problems += "trusted_hit_not_memory:${decisionName(decision)}"
            }
            Scenario.SAFETY_CONFLICT -> {
                if (storeCalls != 1) problems += "conflict_lookup_count:$storeCalls"
                if (translatorCalls != 1) problems += "conflict_did_not_fall_through_to_real_mt:$translatorCalls"
                if (decision is TranslationDecision.MemoryTranslation) problems += "safety_conflict_was_reused"
            }
        }
    }

    private fun validateOutputPolicy(
        decision: TranslationDecision?,
        source: Language,
        target: Language,
        problems: MutableList<String>,
    ) {
        val d = decision ?: return
        val session = outputPolicy.session(d, source, target)
        val initial = session.initialEvents()
        val initialSpeech = initial.filterIsInstance<SpeechOutputPolicy.Event.SpeakTranslation>()
        val prompts = initial.filterIsInstance<SpeechOutputPolicy.Event.Prompt>()
        when (d) {
            TranslationDecision.NoSpeech, is TranslationDecision.AskRepeat -> {
                if (initialSpeech.isNotEmpty()) problems += "reject_spoke_candidate_translation"
                if (prompts.size != 1 || prompts.single().language != source) problems += "prompt_not_source_language"
            }
            is TranslationDecision.RepairedTranslation -> {
                if (initialSpeech.isNotEmpty()) problems += "repaired_spoke_before_confirmation"
                val afterConfirm = session.confirmRepaired().filterIsInstance<SpeechOutputPolicy.Event.SpeakTranslation>()
                val duplicate = session.confirmRepaired()
                if (afterConfirm.size != 1 || afterConfirm.single().language != target) problems += "repaired_confirm_not_exactly_once"
                if (duplicate.isNotEmpty()) problems += "repaired_duplicate_confirmation_spoke_again"
            }
            else -> {
                if (initialSpeech.size != 1 || initialSpeech.single().language != target) problems += "accepted_translation_speech_policy"
            }
        }
    }

    private fun parseManifest(file: File): List<ManifestRow> = file.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isBlank()) return@mapNotNull null
        val json = JSONObject(line)
        val src = language(json.getString("source_lang"))
        val tgt = language(json.getString("target_lang"))
        val refSlots = json.opt("reference_slots").let { if (it == JSONObject.NULL) null else it }
        val provenance = json.opt("provenance").let { if (it == JSONObject.NULL) null else it }
        ManifestRow(
            id = json.getString("id"),
            wav = File(json.getString("wav")),
            source = src,
            target = tgt,
            sourceKind = json.optString("source_kind", ""),
            safetyBearing = json.optBoolean("safety_bearing", false),
            sha256 = json.optString("sha256", "").takeIf(String::isNotBlank),
            provenance = provenance,
            referenceSource = json.opt("reference_source").let { if (it == null || it == JSONObject.NULL) null else it.toString() },
            referenceSlots = refSlots,
        )
    }

    private fun appendSystemRecord(log: File, code: String, message: String) {
        log.appendText(JSONObject().put("type", "system").put("code", code).put("message", message).toString() + "\n")
        println("INFO unified $code — $message")
    }

    private fun seedFrom(outcome: SampleOutcome, source: Language, target: Language): Seed? {
        val text = outcome.transcript?.takeIf(String::isNotBlank) ?: return null
        val targetText = outcome.finalText?.takeIf(String::isNotBlank) ?: outcome.mtText?.takeIf(String::isNotBlank) ?: return null
        val d = outcome.decision ?: return null
        if (d is TranslationDecision.NoSpeech || d is TranslationDecision.AskRepeat) return null
        return Seed(text, source, target, targetText)
    }

    private fun rejectReason(decision: TranslationDecision?): String? = when (decision) {
        TranslationDecision.NoSpeech -> "no_speech"
        is TranslationDecision.AskRepeat -> decision.reason
        else -> null
    }

    private fun decisionText(decision: TranslationDecision?): String? = when (decision) {
        is TranslationDecision.MemoryTranslation -> decision.text
        is TranslationDecision.ModelTranslation -> decision.text
        is TranslationDecision.VerifiedTranslation -> decision.text
        is TranslationDecision.RepairedTranslation -> decision.text
        else -> null
    }

    private fun decisionName(decision: TranslationDecision?): String = when (decision) {
        null -> "NONE"
        TranslationDecision.NoSpeech -> "NO_SPEECH"
        is TranslationDecision.AskRepeat -> "ASK_REPEAT"
        is TranslationDecision.MemoryTranslation -> "MEMORY_TRANSLATION"
        is TranslationDecision.ModelTranslation -> "MODEL_TRANSLATION"
        is TranslationDecision.VerifiedTranslation -> "VERIFIED_TRANSLATION"
        is TranslationDecision.RepairedTranslation -> "REPAIRED_TRANSLATION"
    }

    private fun tag(language: Language): String = when (language) {
        Language.VI -> "vi"
        Language.EN -> "en"
        else -> error("Task 3 unified runner supports VI/EN only, got $language")
    }

    private companion object {
        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        fun language(tag: String): Language = when (tag.lowercase(Locale.ROOT)) {
            "vi" -> Language.VI
            "en" -> Language.EN
            else -> error("unsupported Task 3 language '$tag'")
        }
    }
}
