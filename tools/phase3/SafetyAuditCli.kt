import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.*
import com.voxbridge.core.safety.*
import com.voxbridge.core.translate.*
import org.json.JSONObject

/** Recorded text-pair replay only; never invokes or substitutes a model. */
fun main() {
    val extractor=SafetyFrameExtractor()
    generateSequence(::readLine).forEach { line ->
        val r=JSONObject(line)
        val source=r.getString("source");val target=r.getString("hypothesis")
        val lang=Language.valueOf(r.getString("source_lang").uppercase())
        val other=if(lang==Language.VI)Language.EN else Language.VI
        val engine=DecisionEngine(Translator { _,_,_ -> TranslationResult(source,target,lang,other,"recorded-output") })
        val audio=AudioEvidence(speechPresent=true,audioScore=0.95,durationMs=2000)
        val transcript=TranscriptResult(sourceLanguage=lang,targetLanguage=other,text=source,asrScore=0.95,asrScoreCalibrated=false)
        var d:TranslationDecision=engine.decide(audio,transcript)
        val start=System.nanoTime();repeat(5){d=engine.decide(audio,transcript)}
        val nanos=(System.nanoTime()-start)/5
        val src=extractor.extract(source,lang);val dst=extractor.extract(target,other)
        val events=SpeechOutputPolicy().session(d,lang,other).initialEvents()
        val result=JSONObject().put("id",r.getString("id")).put("model",r.getString("model"))
            .put("decision",d.javaClass.simpleName).put("speakable",events.any {it is SpeechOutputPolicy.Event.SpeakTranslation})
            .put("reason",if(d is TranslationDecision.AskRepeat)(d as TranslationDecision.AskRepeat).reason else JSONObject.NULL)
            .put("source_active",src.hasSafetySignal).put("target_active",dst.hasSafetySignal)
            .put("source_frame",src.toString()).put("target_frame",dst.toString())
            .put("violations",SafetyFrameComparator().compare(src,dst).violations.map {it.name})
            .put("core_ms",nanos/1e6)
        println(result)
    }
}
