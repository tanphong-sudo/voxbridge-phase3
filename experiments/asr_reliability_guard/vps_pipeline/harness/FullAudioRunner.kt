package voxbridge.e2e

import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.SpokenTextNormalizer
import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.pipeline.DecisionConfig
import com.voxbridge.core.pipeline.RejectedCaptureDraft
import com.voxbridge.core.pipeline.TentativeOutput
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
    require(rows.all { it.getString("id").matches(Regex("[A-Za-z0-9_-]+")) &&
        it.getString("source_lang") in setOf("vi","en") &&
        it.getString("target_lang")==if(it.getString("source_lang")=="vi") "en" else "vi" })
    val environment=OrtEnvironment.getEnvironment()
    val mtWorker=LocalLineWorker(listOf(args[2],args[3],"--model",args[4],"--cache",
        System.getenv("VOXBRIDGE_MT_CACHE")?:"/home/opc/voxbridge-mt3-20261113/hf-cache",
        "--models",paths.root.path,"--out",File(out,"audio").path),out,"mt-tts")
    val asrWorker=try { LocalLineWorker(listOf(checkNotNull(System.getenv("VOXBRIDGE_PARAKEET_PYTHON")),
        checkNotNull(System.getenv("VOXBRIDGE_PARAKEET_WORKER")),"--audio-root",
        File(out,"asr-input").apply { mkdirs() }.absolutePath),out,"parakeet")
    } catch(failure:Throwable) { mtWorker.close();throw failure }
    fun request(r: JSONObject):JSONObject = mtWorker.request(r)
    try {
        File(out,"worker-provenance.json").writeText(JSONObject().put("mt_tts",mtWorker.ready)
            .put("asr",asrWorker.ready).toString(2))
        SileroVad.load(environment,paths.vad).use { vad ->
            LanguageRoutedAsr(environment,paths).use { asr ->
                rows.forEachIndexed { index,r ->
                    val id=r.getString("id");val source=if(r.getString("source_lang")=="vi") Language.VI else Language.EN
                    val target=if(source==Language.VI) Language.EN else Language.VI
                    val log=JSONObject().put("type","sample").put("id",id).put("source_lang",r.getString("source_lang")).put("target_lang",r.getString("target_lang")).put("mt_model",args[4])
                    val start=System.nanoTime();val ms={ (System.nanoTime()-start)/1_000_000.0 }
                    try {
                        val wav=File(r.getString("wav"));require(wav.isFile)
                        val digest=MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(digest==r.getString("sha256")) { "WAV checksum mismatch" }
                        val pcm=readMono16kPcm16Wav(wav);log.put("input_sha256",digest).put("input_duration_s",pcm.size/16000.0)
                        val vadStart=ms();val summary=CaptureAnalysis.analyzeVad(samples=pcm,reset=vad::reset,probability=vad::chunkProbability)
                        val trimmed=CaptureAnalysis.trimToSpeech(pcm,summary.startMs,summary.endMs)
                        val metrics=CaptureAnalysis.signalMetrics(trimmed)
                        val audio=AudioEvidence(speechPresent=summary.speechPresent,audioScore=CaptureAnalysis.audioQualityScore(pcm,summary),durationMs=trimmed.size.toLong()*1000/16000,
                            clippingDetected=CaptureAnalysis.isMeaningfullyClipped(metrics.clippingCount,trimmed.size),validSpeechDurationMs=summary.validSpeechDurationMs,
                            silenceRatio=(1.0-summary.validSpeechDurationMs.toDouble()/(pcm.size*1000.0/16000)).coerceIn(0.0,1.0))
                        log.put("vad_ms",ms()-vadStart).put("audio_score",audio.audioScore).put("valid_speech_ms",summary.validSpeechDurationMs)
                        var calls=0
                        val mt=Translator { text,s,t ->
                            calls++;val result=request(JSONObject().put("op","mt").put("text",text).put("source_lang",if(s==Language.VI)"vi" else "en").put("target_lang",if(t==Language.VI)"vi" else "en"))
                            log.put("raw_mt",result.getString("text")).put("mt_ms",result.getDouble("elapsed_ms")).put("mt_load_ms",result.getDouble("load_ms")).put("mt_revision",result.getString("model_revision"))
                            TranslationResult(text,result.getString("text"),s,t,args[4],hitTokenLimit=result.getBoolean("hit_token_cap"))
                        }
                        val engine=DecisionEngine(mt, config=DecisionConfig(tentativeOutputEnabled=true))
                        var actualTranscript: TranscriptResult? = null
                        fun decode(): TranscriptResult {
                            val asrStart=ms()
                            val result=if(source==Language.EN) {
                                val crop=File(out,"asr-input/$id.wav")
                                writePcm16Wav(crop,trimmed)
                                val cropHash=sha256(crop)
                                val decoded=asrWorker.request(JSONObject().put("op","asr").put("id",id)
                                    .put("wav",crop.absolutePath).put("sha256",cropHash))
                                check(decoded.getString("wav_sha256")==cropHash)
                                check(decoded.getInt("input_samples")==trimmed.size)
                                log.put("asr_model","parakeet-v2").put("asr_backend",decoded.getString("backend"))
                                    .put("asr_input_wav",crop.absolutePath).put("asr_input_sha256",cropHash)
                                    .put("asr_diagnostics",decoded)
                                TranscriptResult(source,target,decoded.getString("text"),0.0,asrScoreCalibrated=false)
                            } else {
                                val detailed=asr.transcribeDetailed(trimmed,source,target)
                                log.put("asr_model",paths.asrModelName("vi")).put("asr_backend",detailed.diagnostics.backend)
                                    .put("asr_diagnostics",detailed.diagnostics.toString())
                                detailed.result
                            }
                            actualTranscript=result
                            log.put("asr_ms",ms()-asrStart).put("raw_asr",result.text)
                            return result
                        }
                        val tooLong=pcm.size>=CaptureAnalysis.maxCaptureSamples(16000)
                        val gate=engine.preAsrDecision(audio,tooLong)
                        var decision:TranslationDecision=if(gate==null) {
                            val transcript=decode()
                            try { engine.decide(audio,transcript) }
                            catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled }
                            catch(error:Exception) {
                                log.put("runtime_failure",error.javaClass.simpleName+": "+error.message)
                                TranslationDecision.AskRepeat("runtime_error",RejectedCaptureDraft.sourceOnly(actualTranscript,"runtime_error"))
                            }
                        } else {
                            val salvage=RejectedCaptureDraft.attempt(gate,audio,trimmed.size,true,::decode,
                                { rejection,heard -> engine.withTentativeOutput(rejection,heard,sourceTruncated=tooLong) })
                            log.put("rejected_capture_draft_attempted",salvage.attempted)
                                .put("draft_failure",salvage.failure?:JSONObject.NULL)
                            salvage.decision
                        }
                        log.put("mt_calls",calls).put("decision",decision::class.simpleName)
                            .put("decision_detail",decision.toString()).put("tentative_output_enabled",true)
                            .put("tentative_output",draftJson((decision as? TranslationDecision.AskRepeat)?.draft))
                            .put("decision_reason",(decision as? TranslationDecision.AskRepeat)?.reason?:JSONObject.NULL)
                            .put("final_text",JSONObject.NULL)
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
                        log.put("translation_audio_generated",spokenTranslation).put("awaiting_confirmation",decision is TranslationDecision.RepairedTranslation).put("failure",log.opt("runtime_failure")?:JSONObject.NULL)
                    } catch(e:Exception) { log.put("failure",e.javaClass.simpleName+": "+e.message) }
                    log.put("pipeline_to_audio_ready_ms",ms())
                    // Scoring metadata is attached only after runtime processing has ended.
                    log.put("reference_source",r.optString("reference_source")).put("reference_target",r.optString("reference_target"))
                    resultFile.appendText(log.toString()+"\n")
                    println("audio_pipeline ${index+1}/${rows.size} $id ${log.optString("decision")} failure=${log.opt("failure")}")
                }
            }
        }
    } finally { asrWorker.close();mtWorker.close() }
}
