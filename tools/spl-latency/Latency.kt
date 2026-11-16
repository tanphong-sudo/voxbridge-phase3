import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import java.io.File

/**
 * SAFETY-PRESERVATION-LAYER LATENCY micro-benchmark.
 *
 * SCOPE — read this before quoting any number:
 *   This measures ONLY the wall-clock cost of the pure-Kotlin decision path — SafetyFrame
 *   extraction (x2) + comparison + (when a slot is lost) constrained repair + re-verify — driven by
 *   the REAL DecisionEngine. The neural MT is a constant-time table lookup, so the model's own
 *   inference cost is deliberately EXCLUDED. This is the layer's *added* overhead on top of ASR+MT,
 *   NOT an end-to-end turnaround and NOT the NPU/QNN path. On-device end-to-end + RTF remain
 *   design-time targets to be measured on real Snapdragon hardware in Phase 3 (docs 04, 13).
 *
 * Why it is still worth measuring: doc 06 budgets the layer at "< 20 ms in the common verify-only
 * case". That budget is a claim about OUR code, not the model — so it can and should be measured on
 * a laptop today rather than asserted. This harness does exactly that, over the real safety corpus.
 */

private class TableTranslator(private val out: String) : Translator {
    override fun translate(text: String, src: Language, tgt: Language): TranslationResult =
        TranslationResult(text, out, src, tgt, "bench")
}

private fun lang(s: String): Language = when (s.trim().uppercase()) {
    "VI" -> Language.VI; "EN" -> Language.EN; "ZH" -> Language.ZH; "KO" -> Language.KO
    else -> error("unknown language $s")
}

private data class Row(val id: String, val src: Language, val tgt: Language, val source: String, val modelOut: String)

fun main(args: Array<String>) {
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    val path = if (args.isNotEmpty()) args[0] else "tools/real-mt-benchmark/outputs.tsv"
    val file = File(path)
    require(file.exists()) { "corpus not found at $path" }

    val rows = file.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { line ->
            val p = (line.split("\t") + List(8) { "" }).take(8)
            Row(p[0].trim(), lang(p[1]), lang(p[2]), p[3], p[4])
        }
    require(rows.isNotEmpty()) { "no rows parsed from $path" }

    // Pre-build engines so per-row construction cost is out of the timed region (the engine is
    // constructed once per app session in production, not per utterance).
    val cases = rows.map { r ->
        Triple(
            DecisionEngine(translator = TableTranslator(r.modelOut)),
            AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1600),
            TranscriptResult(r.src, r.tgt, r.source, 0.92)
        )
    }

    println("== VoxBridge — Safety-Preservation-Layer latency (pure-Kotlin overhead only) ==")
    println("Cases: ${rows.size}  |  measures extract+compare+repair, EXCLUDES model inference (table lookup).")
    println("This is the layer's added overhead, NOT end-to-end turnaround and NOT the NPU path.\n")

    // Warm up the JIT so we report steady-state, not first-call class-loading cost.
    repeat(2000) { i -> val (e, a, t) = cases[i % cases.size]; e.decide(a, t) }

    val ITER = 200
    val perCaseNanos = LongArray(cases.size)
    for ((idx, c) in cases.withIndex()) {
        val (e, a, t) = c
        var best = Long.MAX_VALUE
        // Take the min over ITER runs per case: min is the cleanest estimate of pure compute,
        // least polluted by GC pauses / scheduler noise (standard micro-bench practice).
        repeat(ITER) {
            val t0 = System.nanoTime()
            e.decide(a, t)
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
        }
        perCaseNanos[idx] = best
    }

    val micros = perCaseNanos.map { it / 1000.0 }.sorted()
    val mean = micros.average()
    val p50 = micros[micros.size / 2]
    val p90 = micros[(micros.size * 90) / 100]
    val max = micros.last()

    val ms = { us: Double -> us / 1000.0 }
    println("Per-decision wall-clock over the safety corpus (microseconds; min-of-$ITER per case):")
    println("  mean = %.1f µs (%.3f ms)".format(mean, ms(mean)))
    println("  p50  = %.1f µs (%.3f ms)".format(p50, ms(p50)))
    println("  p90  = %.1f µs (%.3f ms)".format(p90, ms(p90)))
    println("  max  = %.1f µs (%.3f ms)".format(max, ms(max)))
    println()
    val budgetMs = 20.0
    val maxMs = ms(max)
    println("doc 06 budget for the layer: < %.0f ms (verify-only common case).".format(budgetMs))
    println(if (maxMs < budgetMs)
        "RESULT: worst-case %.3f ms is well inside the %.0f ms layer budget (laptop JVM; on-device TBD Phase 3)."
            .format(maxMs, budgetMs)
    else
        "RESULT: worst-case %.3f ms EXCEEDS the %.0f ms layer budget — investigate.".format(maxMs, budgetMs))
    println("\nHONEST SCOPE: laptop JVM, SPL overhead only. Not e2e, not RTF, not NPU. Those are Phase-3 device measurements.")
    if (maxMs >= budgetMs) kotlin.system.exitProcess(1)
}
