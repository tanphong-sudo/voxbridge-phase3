import com.voxbridge.core.pipeline.SpeechChunker
import com.voxbridge.core.pipeline.CachedPhraseIndex
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer
import org.json.JSONObject

fun main() {
    generateSequence(::readLine).forEach { line ->
        val row = JSONObject(line)
        val lang = Language.valueOf(row.getString("target_lang").uppercase())
        val text = row.getString("tts_text")
        row.put("chunks", SpeechChunker.split(text).map { SpokenTextNormalizer.normalize(it, lang) })
        row.put("spoken_full", SpokenTextNormalizer.normalize(text, lang))
        row.put("command_cache_id", CachedPhraseIndex.lookup(text, lang) ?: JSONObject.NULL)
        println(row)
    }
}
