import com.voxbridge.core.translate.TranslationCoverage
import com.voxbridge.core.model.Language
import org.json.JSONObject
fun main() {
    val rows=generateSequence(::readLine).map { JSONObject(it) }.toList()
    fun evaluate(r: JSONObject)=TranslationCoverage.evaluate(r.getString("source_text"),r.getString("target_text"),if(r.getString("source_lang")=="vi") Language.VI else Language.EN)
    repeat(3) { rows.forEach { evaluate(it) } }
    val times=mutableListOf<Double>();var check=0
    repeat(10) { rows.forEach { r -> val start=System.nanoTime();val v=evaluate(r);times+=(System.nanoTime()-start)/1_000_000.0;check+=v.reasons.size } }
    times.sort()
    println(JSONObject().put("n",times.size).put("p50_ms",times[times.size/2]).put("p95_ms",times[(times.size*.95).toInt()]).put("max_ms",times.last()).put("checksum",check).put("scope","VPS warm Kotlin evaluator only; no MT or safety; not Snapdragon").toString())
}
