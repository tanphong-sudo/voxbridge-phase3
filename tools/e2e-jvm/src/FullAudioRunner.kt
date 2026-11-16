package voxbridge.e2e

import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import com.voxbridge.runtime.LanguageRoutedAsr
import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.ModelProfile
import com.voxbridge.runtime.SileroVad
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** VPS functional harness: production Kotlin VAD/ASR/core plus local HF MT/Piper worker.
 * References are logged only after the terminal policy. No microphone/playback/device latency claim.
 */
fun main(args: Array<String>) {
    require(args.size in 6..7) { "manifest models-root python worker-script mt-model out-dir [limit]" }
    val manifest=File(args[0]);
    val paths=ModelPaths(File(args[1]), profile = ModelProfile.VI_EN_PHO_SMALL)
    val out=File(args[5]);out.mkdirs()
    val resultFile=File(out,"results.jsonl");require(!resultFile.exists()) { "Preserve earlier run" }
    val rows=manifest.readLines().filter(String::isNotBlank).map(::JSONObject).let { if(args.size==7) it.take(args[6].toInt()) else it }
    require(rows.isNotEmpty() && rows.map { it.getString("id") }.toSet().size==rows.size)
    val proc=ProcessBuilder(args[2],args[3],"--model",args[4],"--cache",System.getenv("VOXBRIDGE_MT_CACHE")?:"/home/opc/voxbridge-mt3-20261113/hf-cache","--models",paths.root.path,"--out",File(out,"audio").path)
        .redirectError(File(out,"worker.log")).start()
    val input=proc.outputStream.bufferedWriter();val output=proc.inputStream.bufferedReader()
    fun response():JSONObject {
        while(true) {
            val line=output.readLine()?:error("Local worker exited: ${proc.isAlive}")
            if(line.trim().startsWith("{")) return JSONObject(line)
            File(out,"worker-stdout.log").appendText(line+"\n")
        }
    }
    require(response().getBoolean("ready"))
    fun request(r: JSONObject):JSONObject {
        input.write(r.toString());input.newLine();input.flush()
        val result=response();check(result.getBoolean("ok")) { result.optString("error") };return result
    }
    val environment=OrtEnvironment.getEnvironment()
    try {
        SileroVad.load(environment,paths.vad).use { vad ->
            LanguageRoutedAsr(environment,paths).use { asr ->
                rows.forEachIndexed { index,r ->
                    val id=r.getString("id");val source=if(r.getString("source_lang")=="vi") Language.VI else Language.EN
                    val target=if(source==Language.VI) Language.EN else Language.VI
                    val log=JSONObject().put("id",id).put("source_lang",r.getString("source_lang")).put("target_lang",r.getString("target_lang")).put("mt_model",args[4])
                    val start=System.nanoTime();val ms={ (System.nanoTime()-start)/1_000_000.0 }
                    try {
                        val wav=File(r.getString("wav"));require(wav.isFile)
                        val digest=MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(digest==r.getString("sha256")) { "WAV checksum mismatch" }
                        val pcm=readMono16kPcm16Wav(wav);log.put("input_sha256",digest).put("input_duration_s",pcm.size/16000.0)
                        val vadStart=ms();val summary=CaptureAnalysis.analyzeVad(samples=pcm,reset=vad::reset,probability=vad::chunkProbability)
                        val trimmed=CaptureAnalysis.trimToSpeech(pcm,summary.startMs,summary.endMs)
                        val metrics=CaptureAnalysis.signalMetrics(trimmed)
                        val audio=AudioEvidence(speechPresent=summary.speechPresent,audioScore=CaptureAnalysis.audioQualityScore(pcm,summary),durationMs=pcm.size.toLong()*1000/16000,
                            clippingDetected=CaptureAnalysis.isMeaningfullyClipped(metrics.clippingCount,trimmed.size),validSpeechDurationMs=summary.validSpeechDurationMs,
                            silenceRatio=(1.0-summary.validSpeechDurationMs.toDouble()/(pcm.size*1000.0/16000)).coerceIn(0.0,1.0))
                        log.put("vad_ms",ms()-vadStart).put("audio_score",audio.audioScore).put("valid_speech_ms",summary.validSpeechDurationMs)
                        var calls=0
                        val mt=Translator { text,s,t ->
                            calls++;val result=request(JSONObject().put("op","mt").put("text",text).put("source_lang",if(s==Language.VI)"vi" else "en").put("target_lang",if(t==Language.VI)"vi" else "en"))
                            log.put("raw_mt",result.getString("text")).put("mt_ms",result.getDouble("elapsed_ms")).put("mt_load_ms",result.getDouble("load_ms")).put("mt_revision",result.getString("model_revision"))
                            TranslationResult(text,result.getString("text"),s,t,args[4],hitTokenLimit=result.getBoolean("hit_token_cap"))
                        }
                        val engine=DecisionEngine(mt)
                        var decision=engine.preAsrDecision(audio)
                        if(decision==null) {
                            val asrStart=ms();val detailed=asr.transcribeDetailed(trimmed,source,target)
                            log.put("asr_ms",ms()-asrStart).put("raw_asr",detailed.result.text).put("asr_model",paths.asrModelName(if(source==Language.VI)"vi" else "en")).put("asr_diagnostics",detailed.diagnostics.toString())
                            decision=engine.decide(audio,detailed.result)
                        }
                        log.put("mt_calls",calls).put("decision",decision!!::class.simpleName).put("decision_detail",decision.toString())
                        val events=SpeechOutputPolicy().session(decision,source,target).initialEvents()
                        var spokenTranslation=false
                        events.forEach { event ->
                            val req=JSONObject().put("op","tts").put("id",id)
                            when(event) {
                                is SpeechOutputPolicy.Event.SpeakTranslation -> {
                                    spokenTranslation=true
                                    val text=SpokenTextNormalizer.normalize(event.text,event.language)
                                    req.put("text",text).put("language",if(event.language==Language.VI)"vi" else "en")
                                    log.put("final_text",event.text).put("tts_text",text)
                                }
                                is SpeechOutputPolicy.Event.Prompt -> req.put("prompt",event.kind.name).put("language",if(event.language==Language.VI)"vi" else "en")
                            }
                            val result=request(req);log.put("tts",result)
                        }
                        if(decision is TranslationDecision.RepairedTranslation) check(events.isEmpty()) { "Repair spoken without confirmation" }
                        if(decision is TranslationDecision.AskRepeat || decision is TranslationDecision.NoSpeech) check(!spokenTranslation)
                        log.put("translation_audio_generated",spokenTranslation).put("awaiting_confirmation",decision is TranslationDecision.RepairedTranslation).put("failure",JSONObject.NULL)
                    } catch(e:Exception) { log.put("failure",e.javaClass.simpleName+": "+e.message) }
                    log.put("pipeline_to_audio_ready_ms",ms())
                    // Scoring metadata is attached only after runtime processing has ended.
                    log.put("reference_source",r.optString("reference_source")).put("reference_target",r.optString("reference_target"))
                    resultFile.appendText(log.toString()+"\n")
                    println("audio_pipeline ${index+1}/${rows.size} $id ${log.optString("decision")} failure=${log.opt("failure")}")
                }
            }
        }
    } finally { input.close();proc.destroy();output.close() }
}
