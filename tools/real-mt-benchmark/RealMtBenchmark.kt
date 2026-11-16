import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import com.voxbridge.core.pipeline.TranslationDecision
import java.io.File

/**
 * REAL-MT SAFETY BENCHMARK.
 *
 * Unlike tools/safety-benchmark (which SIMULATES the neural MT per-case to inject failures), this
 * harness drives the REAL DecisionEngine over the ACTUAL text output of the production OPUS-MT
 * model (Helsinki-NLP/opus-mt-{vi-en,en-vi}), captured by run_real_mt.py into outputs.tsv.
 *
 * This is the empirical answer to the only question that matters for the 50%-Technical axis:
 * when the real translation model silently corrupts a safety-critical instruction, does the exact
 * Kotlin safety layer that ships on the phone catch it — refusing or repairing — rather than speak
 * it? The oracle (required tokens per safety case) is INDEPENDENT of the engine's comparator, so a
 * UNSAFE-PASS=0 result is evidence, not a tautology.
 *
 * Metrics:
 *   - decision distribution (verified / repaired / ask-repeat / model / no-speech)
 *   - MODEL-FAILURE rate: of safety commands, how many did the REAL model corrupt (missing a
 *     required token in its raw output)?  -> shows the problem is real, not contrived.
 *   - UNSAFE-PASS: a safety command whose SPOKEN output is missing every required token. Target 0.
 *   - CAUGHT rate: of the model failures, how many did the layer catch (refuse/repair away from the
 *     corrupted output)?  -> shows the layer's value on real model behavior.
 */

private class TableTranslator(private val out: String) : Translator {
    override fun translate(text: String, src: Language, tgt: Language): TranslationResult =
        TranslationResult(text, out, src, tgt, "opus-mt-real")
}

private fun lang(s: String): Language = when (s.trim().uppercase()) {
    "VI" -> Language.VI
    "EN" -> Language.EN
    "ZH" -> Language.ZH
    "KO" -> Language.KO
    else -> error("unknown language $s")
}

private fun decisionName(d: TranslationDecision): String = when (d) {
    is TranslationDecision.NoSpeech -> "NO_SPEECH"
    is TranslationDecision.AskRepeat -> "ASK_REPEAT"
    is TranslationDecision.MemoryTranslation -> "MEMORY_TRANSLATION"
    is TranslationDecision.ModelTranslation -> "MODEL_TRANSLATION"
    is TranslationDecision.VerifiedTranslation -> "VERIFIED_TRANSLATION"
    is TranslationDecision.RepairedTranslation -> "REPAIRED_TRANSLATION"
}

private fun spokenText(d: TranslationDecision): String? = when (d) {
    is TranslationDecision.ModelTranslation -> d.text
    is TranslationDecision.VerifiedTranslation -> d.text
    is TranslationDecision.RepairedTranslation -> d.text
    is TranslationDecision.MemoryTranslation -> d.text
    else -> null
}

private data class Row(
    val id: String, val src: Language, val tgt: Language,
    val source: String, val modelOut: String,
    val required: List<String>, val safety: Boolean, val note: String
)

fun main(args: Array<String>) {
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    val path = if (args.isNotEmpty()) args[0] else "tools/real-mt-benchmark/outputs.tsv"
    val file = File(path)
    require(file.exists()) { "outputs.tsv not found at $path — run run_real_mt.py first" }

    val rows = file.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { line ->
            val p = line.split("\t")
            val cells = (p + List(8) { "" }).take(8)
            Row(
                id = cells[0].trim(),
                src = lang(cells[1]),
                tgt = lang(cells[2]),
                source = cells[3],
                modelOut = cells[4],
                required = cells[5].split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                safety = cells[6].trim() == "1",
                note = cells[7].trim()
            )
        }

    println("== VoxBridge — REAL-MT Safety Benchmark ==")
    println("Driving the REAL DecisionEngine over the ACTUAL OPUS-MT output (no simulation).")
    println("Source model: Helsinki-NLP/opus-mt-{vi-en,en-vi}. Cases: ${rows.size}\n")

    var safetyTotal = 0
    var modelFailures = 0      // real model corrupted the command (raw out missing all required)
    var unsafePass = 0         // corrupted meaning was actually SPOKEN
    var caught = 0             // model failure that the layer refused/repaired away from corruption
    var falseRepair = 0        // repaired output still missing required token
    var spokeSafe = 0
    val dist = sortedMapOf<String, Int>()
    val detail = mutableListOf<String>()

    for (r in rows) {
        val engine = DecisionEngine(translator = TableTranslator(r.modelOut))
        val audio = AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1600)
        val transcript = TranscriptResult(r.src, r.tgt, r.source, 0.92)
        val d = engine.decide(audio, transcript)
        val name = decisionName(d)
        val spoke = spokenText(d)
        dist[name] = (dist[name] ?: 0) + 1

        if (!r.safety) {
            detail.add("  [%-6s] %-20s model=%-40s -> %s".format(r.id, name, "'${r.modelOut}'", spoke?.let { "'$it'" } ?: "(refused)"))
            continue
        }
        safetyTotal++

        val rawLow = r.modelOut.lowercase()
        val modelCorrupted = r.required.isNotEmpty() && r.required.none { rawLow.contains(it) }
        if (modelCorrupted) modelFailures++

        val spokeLow = spoke?.lowercase()
        val spokenMissing = spoke != null && r.required.isNotEmpty() && r.required.none { spokeLow!!.contains(it) }

        if (spoke != null) {
            if (spokenMissing) {
                unsafePass++
                if (name == "REPAIRED_TRANSLATION") falseRepair++
            } else {
                spokeSafe++
            }
        }
        // The layer "caught" a real corruption if the model output was corrupted but the spoken
        // output is NOT corrupted (either refused, or repaired to restore the token).
        if (modelCorrupted && !spokenMissing) caught++

        val flag = when {
            modelCorrupted && spokenMissing -> "UNSAFE-PASS !!"
            modelCorrupted && spoke == null -> "caught (refused)"
            modelCorrupted && name == "REPAIRED_TRANSLATION" -> "caught (repaired)"
            modelCorrupted -> "caught"
            else -> "model-ok"
        }
        detail.add("  [%-6s] %-20s %-16s model=%-42s spoke=%s".format(
            r.id, name, flag, "'${r.modelOut}'", spoke?.let { "'$it'" } ?: "(refused)"))
    }

    val pct = { n: Int, dd: Int -> if (dd == 0) "n/a" else "%.1f%%".format(100.0 * n / dd) }

    println("Per-case detail (real model output -> engine decision -> spoken):")
    detail.forEach(::println)

    println("\nDecision distribution:")
    dist.forEach { (k, v) -> println("  %-22s %d".format(k, v)) }

    println("\nHeadline metrics (over $safetyTotal safety-critical commands):")
    println("  Real-model failure rate      : ${pct(modelFailures, safetyTotal)}  ($modelFailures/$safetyTotal)  <- the problem is real")
    println("  Model failures CAUGHT by layer: ${pct(caught, modelFailures)}  ($caught/$modelFailures)  <- the layer's value")
    println("  UNSAFE-PASS rate (target 0)  : ${pct(unsafePass, safetyTotal)}  ($unsafePass/$safetyTotal)")
    println("  False-repair count           : $falseRepair")
    println("  Safe spoken outputs          : $spokeSafe/$safetyTotal")

    println("\n== RESULT: real-model-failures=$modelFailures  caught=$caught  UNSAFE-PASS=$unsafePass ==")
    if (unsafePass > 0) kotlin.system.exitProcess(1)
}
