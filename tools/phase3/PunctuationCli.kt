import com.voxbridge.core.normalize.ViPunctuationRestorer
import java.util.Base64

/** One base64 UTF-8 text per line; runs the exact production restorer for paired MT evaluation. */
fun main() {
    val decoder = Base64.getDecoder()
    val encoder = Base64.getEncoder()
    generateSequence(::readLine).forEach {
        val text = String(decoder.decode(it), Charsets.UTF_8)
        println(encoder.encodeToString(ViPunctuationRestorer.restore(text).toByteArray(Charsets.UTF_8)))
    }
}
