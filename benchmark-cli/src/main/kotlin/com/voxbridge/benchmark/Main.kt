package com.voxbridge.benchmark

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import java.io.File

/**
 * Offline benchmark runner. It feeds a CSV manifest of utterances through the SAME model-first
 * [DecisionEngine] that runs on Android, so the decision logic can be validated on a laptop
 * before any model is integrated.
 *
 * Because this skeleton has no on-device model, the neural translation is simulated from the
 * manifest: an optional `model_translation` column lets a row specify what the model produced
 * (so the safety verify/repair path can be exercised); otherwise it falls back to
 * `expected_translation`, then to an echo.
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args.toList())
    val manifest = parsed["--manifest"] ?: error("Missing --manifest")
    val out = parsed["--out"] ?: "reports/benchmark.jsonl"

    val output = File(out)
    require(File(manifest).canonicalFile != output.canonicalFile) { "Output must not overwrite input manifest" }
    output.parentFile?.mkdirs()
    output.printWriter().use { writer ->
      File(manifest).bufferedReader().use { reader ->
        val records = csvRows(reader).iterator()
        require(records.hasNext()) { "Empty manifest: $manifest" }
        val header = records.next().map { it.trim().removePrefix("\uFEFF") }
        require(header.distinct().size == header.size && header.all { it.isNotEmpty() }) { "Duplicate/empty CSV headers" }
        records.forEach { fields ->
            require(fields.size == header.size) { "CSV field count ${fields.size} != header ${header.size}" }
            val row = header.zip(fields).toMap()
            val engine = DecisionEngine(translator = ManifestTranslator(row))
            val decision = engine.decide(buildAudio(row), buildTranscript(row))
            writer.println(toJsonLine(row, decision))
        }
      }
    }
    println("SYNTHETIC decision harness only — NOT model inference, accuracy or latency. Report: ${output.path}")
}

private class ManifestTranslator(private val row: Map<String, String>) : Translator {
    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        val simulated = row["model_translation"]?.takeIf { it.isNotBlank() }
            ?: row["expected_translation"]?.takeIf { it.isNotBlank() }
            ?: text
        return TranslationResult(text, simulated, sourceLanguage, targetLanguage, "manifest-sim")
    }
}

private fun buildAudio(row: Map<String, String>): AudioEvidence {
    val asrText = row["asr_text"].orEmpty()
    val type = row["type"]
    val speechPresent = asrText.isNotBlank() && type != "nonspeech"
    val audioScore = row["audio_score"]?.toDoubleOrNull() ?: if (asrText.isBlank()) 0.0 else 0.8
    return AudioEvidence(
        speechPresent = speechPresent,
        audioScore = audioScore,
        durationMs = 1200,
        tooNoisy = type == "noisy" && audioScore < 0.5
    )
}

private fun buildTranscript(row: Map<String, String>): TranscriptResult = TranscriptResult(
    sourceLanguage = languageOf(row["source_lang"]),
    targetLanguage = languageOf(row["target_lang"]),
    text = row["asr_text"].orEmpty(),
    asrScore = row["asr_score"]?.toDoubleOrNull() ?: if (row["asr_text"].isNullOrBlank()) 0.0 else 0.8
)

private fun languageOf(code: String?): Language = when (code?.lowercase()) {
    "vi" -> Language.VI
    "en" -> Language.EN
    "zh" -> Language.ZH
    "ko" -> Language.KO
    else -> Language.VI
}

private fun parseArgs(args: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        val value = args.getOrNull(i + 1) ?: error("Missing value for $key")
        result[key] = value
        i += 2
    }
    return result
}

private fun toJsonLine(row: Map<String, String>, decision: TranslationDecision): String {
    val decisionName = when (decision) {
        is TranslationDecision.NoSpeech -> "NO_SPEECH"
        is TranslationDecision.AskRepeat -> "ASK_REPEAT"
        is TranslationDecision.MemoryTranslation -> "MEMORY_TRANSLATION"
        is TranslationDecision.ModelTranslation -> "MODEL_TRANSLATION"
        is TranslationDecision.VerifiedTranslation -> "VERIFIED_TRANSLATION"
        is TranslationDecision.RepairedTranslation -> "REPAIRED_TRANSLATION"
    }
    val outputText = when (decision) {
        is TranslationDecision.MemoryTranslation -> decision.text
        is TranslationDecision.ModelTranslation -> decision.text
        is TranslationDecision.VerifiedTranslation -> decision.text
        is TranslationDecision.RepairedTranslation -> decision.text
        else -> ""
    }
    val reason = when (decision) {
        is TranslationDecision.AskRepeat -> decision.reason
        is TranslationDecision.RepairedTranslation -> decision.repaired.joinToString("|")
        else -> ""
    }

    return buildString {
        append("{")
        appendJson("provenance", "synthetic-manifest-decision-harness"); append(",")
        append("\"real_model_inference\":false,")
        appendJson("file", row["file"].orEmpty()); append(",")
        appendJson("type", row["type"].orEmpty()); append(",")
        appendJson("source_lang", row["source_lang"].orEmpty()); append(",")
        appendJson("target_lang", row["target_lang"].orEmpty()); append(",")
        appendJson("asr_text", row["asr_text"].orEmpty()); append(",")
        appendJson("decision", decisionName); append(",")
        appendJson("output", outputText); append(",")
        appendJson("reason", reason)
        append("}")
    }
}

private fun StringBuilder.appendJson(key: String, value: String) {
    append('"').append(escape(key)).append('"').append(':')
    append('"').append(escape(value)).append('"')
}

private fun escape(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
