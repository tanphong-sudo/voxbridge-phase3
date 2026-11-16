package voxbridge.e2e

import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyFrame
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

data class CorpusRunSummary(
    val samples: Int,
    val gateFailures: Int,
    val logFile: File,
)

/**
 * Sequential Mac-CPU benchmark runner over an audio JSONL manifest.
 *
 * The primary path is the shipping-equivalent path used by Task 3:
 * WAV -> Silero/CaptureAnalysis -> routed Whisper -> empty Local Memory -> OPUS-MT -> safety/output policy.
 * Manifest references are never consulted until that primary path has reached its terminal decision.
 * A separate reference-transcript OPUS-MT pass is then recorded for diagnostic attribution only.
 */
class CorpusRealRunner(
    private val env: OrtEnvironment,
    private val paths: ModelPaths,
) {
    private val extractor = SafetyFrameExtractor()
    private val outputPolicy = SpeechOutputPolicy()
    private val minAudioScore = System.getenv("VOXBRIDGE_CORPUS_MIN_AUDIO_SCORE")
        ?.takeIf(String::isNotBlank)
        ?.toDoubleOrNull()
        ?.takeIf { it in 0.0..1.0 }
        ?: DecisionConfig().minAudioScore
    private val askRepeatAudioScore = System.getenv("VOXBRIDGE_CORPUS_ASK_REPEAT_AUDIO_SCORE")
        ?.takeIf(String::isNotBlank)
        ?.toDoubleOrNull()
        ?.takeIf { it in 0.0..1.0 }
        ?: DecisionConfig().askRepeatAudioScore

    private data class ManifestRow(
        val id: String,
        val wav: File,
        val source: Language,
        val target: Language,
        val sourceKind: String,
        val snrDb: Double?,
        val sha256: String?,
        val referenceSource: String?,
    )

    private class CountingEmptyStore : LearningStore {
        var calls: Int = 0
            private set

        override fun all(): List<com.voxbridge.core.learn.LearningExample> {
            calls++
            return emptyList()
        }
    }

    private class RecordingTranslator(private val delegate: Translator) : Translator {
        var calls: Int = 0
            private set
        var lastText: String? = null
            private set
        var elapsedMs: Long = 0
            private set

        override fun translate(
            text: String,
            sourceLanguage: Language,
            targetLanguage: Language,
        ): TranslationResult {
            calls++
            val started = System.nanoTime()
            val result = delegate.translate(text, sourceLanguage, targetLanguage)
            elapsedMs += elapsedMs(started)
            lastText = result.translatedText
            return result
        }
    }

    fun run(manifest: File): CorpusRunSummary {
        require(manifest.isFile) { "corpus manifest not found: ${manifest.absolutePath}" }
        val configuredLimit = System.getenv("VOXBRIDGE_CORPUS_LIMIT")
            ?.takeIf(String::isNotBlank)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val configuredIds = System.getenv("VOXBRIDGE_CORPUS_IDS")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        var rows = parseManifest(manifest)
        if (configuredIds.isNotEmpty()) rows = rows.filter { it.id in configuredIds }
        if (configuredLimit != null) rows = rows.take(configuredLimit)
        require(rows.isNotEmpty()) { "corpus selection is empty" }

        val logFile = System.getenv("VOXBRIDGE_CORPUS_LOG")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File("reports/mac-cpu/corpus-e2e-results.jsonl")
        logFile.parentFile?.mkdirs()
        logFile.writeText("")

        var gateFailureCount = 0
        val referenceMtCache = mutableMapOf<String, String>()

        SileroVad.load(env, paths.vad).use { vad ->
            LanguageRoutedAsr(env, paths).use { asr ->
                SelectedDirectionTranslator { src, tgt ->
                    OnnxTranslator.load(env, paths.translationDir(tag(src), tag(tgt)), src, tgt)
                }.use { realMt ->
                    rows.forEachIndexed { index, row ->
                        val failures = runSample(row, vad, asr, realMt, referenceMtCache, logFile)
                        gateFailureCount += failures
                        println("corpus ${index + 1}/${rows.size} ${row.id} gate_failures=$failures")
                    }
                }
            }
        }

        logFile.appendText(
            JSONObject()
                .put("type", "summary")
                .put("samples", rows.size)
                .put("gate_failures", gateFailureCount)
                .toString() + "\n",
        )
        return CorpusRunSummary(rows.size, gateFailureCount, logFile)
    }

    private fun runSample(
        row: ManifestRow,
        vad: SileroVad,
        asr: LanguageRoutedAsr,
        realMt: Translator,
        referenceMtCache: MutableMap<String, String>,
        logFile: File,
    ): Int {
        val problems = mutableListOf<String>()
        val timings = linkedMapOf<String, Long>()
        val started = System.nanoTime()
        var failure: String? = null
        var diagnosticFailure: String? = null
        var audioScore: Double? = null
        var validSpeechMs: Long? = null
        var transcript: String? = null
        var mtFromAsr: String? = null
        var mtFromReference: String? = null
        var referenceMtCacheHit = false
        var finalText: String? = null
        var decision: TranslationDecision? = null
        var finalSlots: JSONObject? = null
        var preRepairSlots: JSONObject? = null
        var sourceSlots: JSONObject? = null
        var spokenAllowed = false
        var memoryLookupCalls = 0
        var mtCalls = 0

        try {
            val readStarted = System.nanoTime()
            val pcm = readMono16kPcm16Wav(row.wav)
            timings["read_ms"] = elapsedMs(readStarted)

            val vadStarted = System.nanoTime()
            val summary = synchronized(vad) {
                CaptureAnalysis.analyzeVad(
                    samples = pcm,
                    reset = vad::reset,
                    probability = vad::chunkProbability,
                )
            }
            timings["vad_ms"] = elapsedMs(vadStarted)
            validSpeechMs = summary.validSpeechDurationMs
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

            val store = CountingEmptyStore()
            val recordingMt = RecordingTranslator(realMt)
            val engine = DecisionEngine(
                translator = recordingMt,
                memory = LocalMemoryRetriever(store),
                config = DecisionConfig(
                    minAudioScore = minAudioScore,
                    askRepeatAudioScore = askRepeatAudioScore,
                ),
            )

            decision = engine.preAsrDecision(audio)
            if (decision == null) {
                val asrStarted = System.nanoTime()
                val detailed = asr.transcribeDetailed(trimmed, row.source, row.target)
                timings["asr_ms"] = elapsedMs(asrStarted)
                transcript = detailed.result.text
                sourceSlots = frameJson(extractor.extract(transcript.orEmpty(), row.source))

                val decisionStarted = System.nanoTime()
                decision = engine.decide(audio, detailed.result)
                timings["decision_ms"] = elapsedMs(decisionStarted)
                mtFromAsr = when (val terminal = decision) {
                    is TranslationDecision.RepairedTranslation -> terminal.modelText
                    else -> recordingMt.lastText
                }
            }
            memoryLookupCalls = store.calls
            mtCalls = recordingMt.calls
            timings["mt_ms"] = recordingMt.elapsedMs

            finalText = decisionText(decision)
            if (!finalText.isNullOrBlank()) {
                finalSlots = frameJson(extractor.extract(finalText, row.target))
            }
            if (decision is TranslationDecision.RepairedTranslation) {
                preRepairSlots = frameJson(extractor.extract(decision.modelText, row.target))
            } else if (!mtFromAsr.isNullOrBlank()) {
                preRepairSlots = frameJson(extractor.extract(mtFromAsr, row.target))
            }

            decision?.let { terminal ->
                val initial = outputPolicy.session(terminal, row.source, row.target).initialEvents()
                spokenAllowed = initial.any { it is SpeechOutputPolicy.Event.SpeakTranslation }
            }

            if (decision is TranslationDecision.NoSpeech || decision is TranslationDecision.AskRepeat) {
                if (spokenAllowed) problems += "reject_spoke_translation"
            } else if (decision is TranslationDecision.RepairedTranslation) {
                if (spokenAllowed) problems += "repaired_spoke_before_confirmation"
            } else if (decision != null && finalText.isNullOrBlank()) {
                problems += "accepted_decision_missing_final_text"
            }
            if (decision is TranslationDecision.MemoryTranslation) {
                problems += "empty_memory_returned_memory_translation"
            }

            // Diagnostic-only MT from ground-truth source. This runs strictly after primary
            // inference and output-policy evaluation, so the reference cannot alter the decision.
            val reference = row.referenceSource?.takeIf(String::isNotBlank)
            if (reference != null) {
                val cacheKey = "${row.source.name}->${row.target.name}\u0000$reference"
                val cached = referenceMtCache[cacheKey]
                if (cached != null) {
                    referenceMtCacheHit = true
                    mtFromReference = cached
                    timings["reference_mt_ms"] = 0L
                } else {
                    try {
                        val referenceStarted = System.nanoTime()
                        mtFromReference = realMt.translate(reference, row.source, row.target).translatedText
                        timings["reference_mt_ms"] = elapsedMs(referenceStarted)
                        referenceMtCache[cacheKey] = mtFromReference!!
                    } catch (t: Throwable) {
                        diagnosticFailure = "${t.javaClass.simpleName}: ${t.message}"
                        problems += "reference_mt_exception:$diagnosticFailure"
                    }
                }
            }
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            problems += "sample_exception:$failure"
        }

        timings["total_ms"] = elapsedMs(started)
        val record = JSONObject()
            .put("type", "sample")
            .put("manifest_id", row.id)
            .put("wav", row.wav.absolutePath)
            .put("source_lang", tag(row.source))
            .put("target_lang", tag(row.target))
            .put("source_kind", row.sourceKind)
            .put("snr_db", row.snrDb ?: JSONObject.NULL)
            .put("sha256", row.sha256 ?: JSONObject.NULL)
            .put("audio_score", audioScore ?: JSONObject.NULL)
            .put("valid_speech_ms", validSpeechMs ?: JSONObject.NULL)
            .put("raw_transcript", transcript ?: JSONObject.NULL)
            .put("mt_from_asr_text", mtFromAsr ?: JSONObject.NULL)
            .put("mt_from_reference_text", mtFromReference ?: JSONObject.NULL)
            .put("reference_mt_cache_hit", referenceMtCacheHit)
            .put("final_text", finalText ?: JSONObject.NULL)
            .put("terminal_decision", decisionName(decision))
            .put("source_slots", sourceSlots ?: JSONObject())
            .put("pre_repair_slots", preRepairSlots ?: JSONObject())
            .put("final_slots", finalSlots ?: JSONObject())
            .put("spoken_allowed", spokenAllowed)
            .put("memory_eligible", false)
            .put("memory_accepted", false)
            .put("memory_lookup_calls", memoryLookupCalls)
            .put("mt_calls", mtCalls)
            .put("timings_ms", JSONObject(timings as Map<*, *>))
            .put("failure", failure ?: JSONObject.NULL)
            .put("diagnostic_failure", diagnosticFailure ?: JSONObject.NULL)
            .put("gate_failures", JSONArray(problems))
        logFile.appendText(record.toString() + "\n")
        return problems.size
    }

    private fun parseManifest(file: File): List<ManifestRow> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@mapNotNull null
            val json = JSONObject(line)
            ManifestRow(
                id = json.getString("id"),
                wav = File(json.getString("wav")),
                source = language(json.getString("source_lang")),
                target = language(json.getString("target_lang")),
                sourceKind = json.optString("source_kind", ""),
                snrDb = json.opt("snr_db").let {
                    if (it == null || it == JSONObject.NULL) null else (it as Number).toDouble()
                },
                sha256 = json.optString("sha256", "").takeIf(String::isNotBlank),
                referenceSource = json.opt("reference_source").let {
                    if (it == null || it == JSONObject.NULL) null else it.toString()
                },
            )
        }.toList()
    }

    private fun frameJson(frame: SafetyFrame): JSONObject = JSONObject()
        .put("action", frame.action?.name ?: JSONObject.NULL)
        .put("objectType", frame.objectType?.name ?: JSONObject.NULL)
        .put("objectId", frame.objectId ?: JSONObject.NULL)
        .put("location", frame.location ?: JSONObject.NULL)
        .put("negation", frame.negation ?: JSONObject.NULL)
        .put("urgency", frame.urgency?.name ?: JSONObject.NULL)
        .put("hazard", frame.hazard?.name ?: JSONObject.NULL)
        .put("quantity", frame.quantity ?: JSONObject.NULL)
        .put("confidence", frame.confidence)
        .put("activationEligible", frame.activationEligible ?: JSONObject.NULL)

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
        else -> error("Task 5 corpus runner supports VI/EN only, got $language")
    }

    private fun language(tag: String): Language = when (tag.lowercase(Locale.ROOT)) {
        "vi" -> Language.VI
        "en" -> Language.EN
        else -> error("unsupported Task 5 language '$tag'")
    }

    private companion object {
        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
    }
}
