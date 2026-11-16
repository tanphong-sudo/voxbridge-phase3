package com.voxbridge.ui

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import ai.onnxruntime.OrtEnvironment
import com.voxbridge.BuildConfig
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.SpeechAudioPlan
import com.voxbridge.core.pipeline.SpeechOutputPolicy
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.runtime.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Device corpus test using the SAME ASR/MT/core/TTS/AudioTrack classes as the worker UI.
 * WAV is staged before the synthetic release clock; the timed region includes VAD/mel and all
 * processing through first AudioTrack.write. No transport, reference input or auto-confirmation.
 */
class PipelineBenchActivity : Activity() {
    @Volatile private var stopped=false
    private var wake:PowerManager.WakeLock?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status=TextView(this).apply { textSize=20f;setPadding(24,48,24,24);text="Preparing full offline pipeline…" }
        setContentView(status)
        wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoxBridge:PipelineCorpus").also{it.acquire(110*60*1000L)}
        val name=intent.getStringExtra("manifest")?:"corpus/manifest200.jsonl"
        val output=intent.getStringExtra("output")?:"results/pipeline-${BuildConfig.MODEL_PROFILE}.jsonl"
        val limit=intent.getIntExtra("limit",200)
        Thread({
            try { runCorpus(name,output,limit){s->runOnUiThread{status.text=s}} }
            catch(t:Throwable){android.util.Log.e("VoxCorpus","aborted",t);runOnUiThread{status.text="Benchmark stopped: ${t.message}"}}
            finally { wake?.let{if(it.isHeld)it.release()} }
        },"voxbridge-corpus").start()
    }
    override fun onDestroy(){stopped=true;AudioOut.stop();super.onDestroy()}
    private fun inside(root:File,relative:String):File=File(root,relative).canonicalFile.also{require(it.toPath().startsWith(root.canonicalFile.toPath())){"Path escapes app files"}}
    private fun sha(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun runCorpus(manifestName:String,outName:String,limit:Int,show:(String)->Unit)=runBlocking {
        require(limit in 1..200)
        val files=checkNotNull(getExternalFilesDir(null));val manifest=inside(files,manifestName)
        require(manifest.isFile && manifest.length()<4*1024*1024)
        val rows=manifest.readLines().filter(String::isNotBlank).map(::JSONObject).take(limit)
        require(rows.isNotEmpty() && rows.map{it.getString("id")}.toSet().size==rows.size)
        val out=inside(files,outName);out.parentFile?.mkdirs()
        val candidate = intent.getStringExtra("mt") ?: ModelProfile.fromId(BuildConfig.MODEL_PROFILE).mtBackend
        val profile = when(candidate) {
            "envit5" -> ModelProfile.VI_EN_QNN_ENVIT5
            else -> error("Unknown translation candidate")
        }
        val freeze=JSONObject().put("profile",profile.id).put("manifest_sha256",sha(manifest)).put("limit",limit)
            .put("apk_sha256",sha(File(applicationInfo.sourceDir))).put("backend","QNN HTP ASR/MT/vocoder no CPU fallback; Piper dynamic frontend CPU")
            .put("scope","WAV preloaded then synthetic release → VAD/mel/ASR/MT/safety/TTS/first AudioTrack.write; first write is acoustic proxy")
        val frozen=File(out.path+".run.json")
        if(frozen.exists()) {
            val old=JSONObject(frozen.readText())
            check(freeze.keys().asSequence().all { old.opt(it)==freeze.opt(it) } && old.length()==freeze.length()){
                "Changed run contract; select a new output"
            }
        }
        else frozen.writeText(freeze.toString(2))
        val previous=if(out.exists())out.readLines().filter(String::isNotBlank).map(::JSONObject) else emptyList()
        check(previous.map{it.getString("id")}.toSet().size==previous.size){"Duplicate result IDs"}
        check(previous.none{!it.isNull("failure")}){"Previous failed row; preserve log and select a fresh run"}
        val done=previous.map{it.getString("id")}.toSet()
        val paths=ModelPaths(File(files,"models"),profile)
        val missing=paths.missing();check(missing.isEmpty()){missing.joinToString("\n")}
        android.system.Os.setenv("ADSP_LIBRARY_PATH",applicationInfo.nativeLibraryDir+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/dsp",true)
        val env=OrtEnvironment.getEnvironment()
        SileroVad.load(env,paths.vad).use{vad->
          LanguageRoutedAsr(env,paths,preferCpu=false,requireQnn=true).use{asr->
            StaticMtTranslator(env,File(paths.root,"translation/qnn"),applicationInfo.nativeLibraryDir,profile.mtBackend).use{mt->
              PiperTts(paths, vocoder = QnnPiperVocoder(env, paths)).use{tts->
                mt.prepare();val engine=DecisionEngine(mt)
                for((index,row) in rows.withIndex()) {
                    if(stopped)break
                    val id=row.getString("id");if(id in done)continue
                    val src=Language.valueOf(row.getString("source_lang").uppercase());val tgt=if(src==Language.VI)Language.EN else Language.VI
                    val wav=inside(files,row.getString("wav"));check(sha(wav)==row.getString("sha256")){"WAV checksum mismatch: $id"}
                    val pcm=readWav(wav)
                    // Every direction load is outside the release timer and separately recorded.
                    val loadBegin=SystemClock.elapsedRealtimeNanos();asr.preload(src);mt.preload(src,tgt);tts.preload(tgt)
                    val loadMs=(SystemClock.elapsedRealtimeNanos()-loadBegin)/1e6
                    val log=JSONObject().put("id",id).put("source_lang",src.name.lowercase()).put("target_lang",tgt.name.lowercase()).put("preload_ms",loadMs).put("failure",JSONObject.NULL)
                    val began=SystemClock.elapsedRealtimeNanos();fun elapsed()=(SystemClock.elapsedRealtimeNanos()-began)/1e6
                    try {
                        val vBegin=elapsed();val summary=CaptureAnalysis.analyzeVadSlice(
                            samples = pcm,
                            reset = vad::reset,
                            probability = { samples, offset, length -> vad.chunkProbability(samples, offset, length) },
                        )
                        val trimmed=CaptureAnalysis.trimToSpeech(pcm,summary.startMs,summary.endMs)
                        val signal=CaptureAnalysis.signalMetrics(trimmed)
                        val audio=AudioEvidence(summary.speechPresent,CaptureAnalysis.audioQualityScore(pcm,summary),pcm.size.toLong()*1000/16000,
                            clippingDetected=CaptureAnalysis.isMeaningfullyClipped(signal.clippingCount,trimmed.size),validSpeechDurationMs=summary.validSpeechDurationMs,
                            silenceRatio=(1.0-summary.validSpeechDurationMs.toDouble()/(pcm.size*1000.0/16000)).coerceIn(0.0,1.0))
                        log.put("vad_ms",elapsed()-vBegin)
                        var decision=engine.preAsrDecision(audio)
                        if(decision==null){val aBegin=elapsed();val detailed=asr.transcribeDetailed(trimmed,src,tgt);log.put("asr_ms",elapsed()-aBegin).put("asr_text",detailed.result.text).put("asr_backend",detailed.diagnostics.backend).put("asr_mel_ms",detailed.diagnostics.melLatencyMs).put("asr_encoder_ms",detailed.diagnostics.encoderMs).put("asr_decoder_run_ms",detailed.diagnostics.decoderRunMs).put("asr_steps",detailed.diagnostics.decodeSteps).put("asr_stop",detailed.diagnostics.stopReason)
                            val dBegin=elapsed();decision=engine.decide(audio,detailed.result);log.put("mt_safety_ms",elapsed()-dBegin)}
                        val result=checkNotNull(decision);log.put("decision",result.javaClass.simpleName).put("decision_detail",result.toString()).put("awaiting_confirmation",result is TranslationDecision.RepairedTranslation)
                        val events=SpeechOutputPolicy().session(result,src,tgt).initialEvents();var translated=false
                        for(event in events){
                            val producers: List<() -> com.voxbridge.core.audio.PcmAudio> = when(event){
                                is SpeechOutputPolicy.Event.SpeakTranslation->{translated=true;log.put("output_text",event.text);SpeechAudioPlan.producers(event.text,event.language,tts::cachedPhrase,tts::synthesize)}
                                is SpeechOutputPolicy.Event.Prompt->{val stem=if(event.kind==SpeechOutputPolicy.PromptKind.NO_SPEECH)"no_speech" else "ask_repeat";listOf({checkNotNull(tts.cachedPhrase("${stem}_${event.language.name.lowercase()}"))})}
                            }
                            playChunked(producers.map{p->suspend{p()}},startPlayback={data,callback->AudioOut.play(data,callback)},onFirstAudio={log.put("first_write_ms",elapsed())})
                        }
                        check(!(result is TranslationDecision.RepairedTranslation && translated)){"Repair autoplay"}
                        log.put("translation_audio",translated).put("audio_kind",if(events.isEmpty())"none" else if(translated)"translation" else "prompt")
                        log.put("within_2s",if(translated&&log.has("first_write_ms"))log.getDouble("first_write_ms")<2000.0 else JSONObject.NULL)
                    }catch(t:Throwable){log.put("failure",t.javaClass.simpleName+": "+t.message)}
                    log.put("complete_audio_ms",elapsed())
                    // References enter the result after all runtime inference/speech decisions.
                    log.put("reference_source",row.optString("reference_source")).put("reference_target",row.optString("reference_target"))
                    out.appendText(log.toString()+"\n")
                    show("${profile.id}\n${index+1}/${rows.size} $id\nFirst audio: ${log.opt("first_write_ms")?:"none"} ms")
                    android.util.Log.i("VoxCorpus","case=$id index=${index+1} firstWriteMs=${log.opt("first_write_ms")} failure=${log.opt("failure")}")
                    check(log.isNull("failure")){"Device case failed; stop instead of silently changing backend"}
                }
              }
            }
          }
        }
        show("Finished. Results: ${out.name}")
    }
    private fun readWav(file:File):FloatArray{
        val b=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun label():String{val a=ByteArray(4);b.get(a);return String(a,Charsets.US_ASCII)}
        require(label()=="RIFF");b.int;require(label()=="WAVE");var valid=false
        while(b.remaining()>=8){val tag=label();val size=b.int;require(size>=0 && size<=b.remaining());val end=b.position()+size
            if(tag=="fmt "){require(size>=16);valid=b.short.toInt()==1 && b.short.toInt()==1 && b.int==16000;b.int;b.short;valid=valid&&b.short.toInt()==16}
            if(tag=="data"){require(valid&&size%2==0);return FloatArray(size/2){b.short/32768f}}
            b.position(end+(size and 1))
        };error("Missing WAV samples")
    }
}
