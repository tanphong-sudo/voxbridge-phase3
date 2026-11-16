import com.voxbridge.core.normalize.ViPunctuationRestorer
import java.util.Base64

fun main() {
    val texts = generateSequence(::readLine).map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.toList()
    require(texts.isNotEmpty())
    var checksum = 0L
    repeat(200) { texts.forEach { checksum += ViPunctuationRestorer.restore(it).length } }
    val timings = ArrayList<Double>()
    repeat(100) { texts.forEach {
        val start = System.nanoTime()
        checksum += ViPunctuationRestorer.restore(it).length
        timings += (System.nanoTime() - start) / 1_000_000.0
    } }
    timings.sort()
    println("{\"n\":${timings.size},\"p50_ms\":${timings[timings.size / 2]},\"p95_ms\":${timings[(timings.size * .95).toInt()]},\"max_ms\":${timings.last()},\"checksum\":$checksum,\"scope\":\"warm JVM rule only; excludes startup and MT\"}")
}
