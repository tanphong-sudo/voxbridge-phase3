import com.voxbridge.core.translate.TranslationCoverage
import com.voxbridge.core.translate.Translator
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import org.json.JSONObject

/** Runs production Kotlin diagnostics and the unchanged full safety decision path. */
fun main() {
    generateSequence(::readLine).forEach { line ->
        val r=JSONObject(line)
        val lang=if (r.getString("source_lang")=="vi") Language.VI else Language.EN
        val targetLang=if (lang==Language.VI) Language.EN else Language.VI
        val source=r.getString("source_text");val target=r.getString("target_text")
        val v=TranslationCoverage.evaluate(source,target,lang)
        val translator=Translator { text,s,t -> TranslationResult(text,target,s,t,"saved-output-replay") }
        val decision=DecisionEngine(translator).decide(AudioEvidence(speechPresent=true,audioScore=.95,durationMs=1200),TranscriptResult(lang,targetLang,source,.95))
        val baselineTokens=Regex("[\\p{L}\\p{N}']+").findAll(target.lowercase()).map { it.value }.toList()
        var run=0;var previous="";var repeated=false
        for (t in baselineTokens) { run=if(t==previous) run+1 else 1;previous=t;if(run>=6)repeated=true }
        println(JSONObject().put("id",r.getString("id")).put("baseline_failure",baselineTokens.isEmpty()||repeated)
            .put("hard_failure",v.hardFailure).put("reasons",v.reasons).put("missing_units",v.missingUnits)
            .put("retry_sentences",v.retrySentences).put("pipeline_decision",decision::class.simpleName)
            .put("pipeline_detail",decision.toString()).toString())
    }
}
