import com.voxbridge.core.model.Language
import com.voxbridge.core.safety.SafetyFrameExtractor
import java.io.File
import java.util.Locale

private data class ScanResult(val total: Int, val active: Int)

private fun scan(path: File, language: Language, limit: Int): ScanResult {
    val extractor = SafetyFrameExtractor()
    val maxExamples = System.getenv("VOXBRIDGE_FLEURS_EXAMPLES")?.toIntOrNull() ?: 12
    var total = 0
    var active = 0
    val examples = mutableListOf<String>()
    val signatures = linkedMapOf<String, Int>()

    path.useLines { lines ->
        for (line in lines) {
            if (total >= limit) break
            val columns = line.split('\t')
            if (columns.size < 3) continue
            val text = columns[2]
            val frame = extractor.extract(text, language)
            total++
            if (frame.hasSafetySignal) {
                active++
                val signature = listOfNotNull(
                    frame.action?.let { "action=$it" },
                    frame.objectType?.let { "object=$it" },
                    frame.objectId?.let { "id" },
                    if (frame.negation == true) "neg" else null,
                    if (frame.urgency?.name == "HIGH") "urgent" else null,
                    frame.hazard?.let { "hazard=$it" },
                    frame.quantity?.let { "quantity" },
                ).joinToString(" ")
                signatures[signature] = (signatures[signature] ?: 0) + 1
                if (examples.size < maxExamples) examples += "$frame :: $text"
            }
        }
    }

    val rate = if (total == 0) 0.0 else active * 100.0 / total
    println("$language total=$total active=$active rate=${String.format(Locale.US, "%.1f", rate)}%")
    signatures.entries.sortedByDescending { it.value }.take(20).forEach {
        println("  ${it.value} x ${it.key}")
    }
    println("  examples:")
    examples.forEach { println("  $it") }
    return ScanResult(total, active)
}

fun main(args: Array<String>) {
    require(args.size in 2..3) {
        "usage: ScanKt <vi-test.tsv> <en-test.tsv> [limit]"
    }
    val limit = args.getOrNull(2)?.toInt() ?: 500
    val vi = scan(File(args[0]), Language.VI, limit)
    val en = scan(File(args[1]), Language.EN, limit)
    val maxActive = System.getenv("VOXBRIDGE_FLEURS_MAX_ACTIVE")?.toIntOrNull() ?: 0
    val totalActive = vi.active + en.active
    check(totalActive <= maxActive) {
        "FLEURS narrative safety activations=$totalActive exceed allowed=$maxActive"
    }
    println("RESULT: ${vi.total + en.total} FLEURS test rows, false safety activations=$totalActive")
}
