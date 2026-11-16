import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.model.Language
import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.ModelProfile
import com.voxbridge.runtime.SileroVad
import com.voxbridge.runtime.WhisperAsr
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args:Array<String>) {
    require(args.size==4) { "manifest model-root mode output-jsonl" }
    val result=File(args[3]);require(!result.exists()) { "Preserve prior output" };result.parentFile.mkdirs()
    val profile=ModelProfile("selected-phowhisper-small",mapOf("vi" to "phowhisper-small","en" to "whisper-small"))
    val paths=ModelPaths(File(args[1]),profile);val env=OrtEnvironment.getEnvironment()
    val rows=File(args[0]).readLines().filter(String::isNotBlank).map(::JSONObject).filter { it.getString("source_lang")=="vi" };require(rows.size==100)
    SileroVad.load(env,paths.vad).use { vad ->
        WhisperAsr.load(env,paths,preferCpu=true).use { asr ->
            rows.forEachIndexed { index,row ->
                val record=JSONObject().put("id",row.getString("id")).put("mode",args[2]).put("reference",row.getString("reference_source"))
                try {
                    val bytes=File(row.getString("wav")).readBytes();val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    require(String(bytes,0,4)=="RIFF" && String(bytes,8,4)=="WAVE")
                    var offset=12;var samples:FloatArray?=null;var rate=0;var channels=0;var bits=0
                    while(offset+8<=bytes.size) {
                        val tag=String(bytes,offset,4);val size=buffer.getInt(offset+4);require(size>=0 && offset+8L+size<=bytes.size)
                        if(tag=="fmt ") { channels=buffer.getShort(offset+10).toInt();rate=buffer.getInt(offset+12);bits=buffer.getShort(offset+22).toInt() }
                        if(tag=="data") samples=FloatArray(size/2) { buffer.getShort(offset+8+it*2)/32768f }
                        offset+=8+size+(size%2)
                    }
                    require(rate==16000 && channels==1 && bits==16)
                    val pcm=requireNotNull(samples);val summary=CaptureAnalysis.analyzeVad(samples=pcm,reset=vad::reset,probability={ chunk -> vad.chunkProbability(chunk) })
                    val trimmed=CaptureAnalysis.trimToSpeech(pcm,summary.startMs,summary.endMs)
                    val start=System.nanoTime();val detailed=asr.transcribeDetailed(trimmed,Language.VI,Language.EN);val transcript=detailed.result
                    record.put("hypothesis",transcript.text).put("elapsed_ms",(System.nanoTime()-start)/1_000_000.0)
                        .put("diagnostics",detailed.diagnostics.toString()).put("failure",JSONObject.NULL)
                } catch(t:Throwable) {record.put("failure",t.javaClass.simpleName+": "+t.message).put("hypothesis","")}
                result.appendText(record.toString()+"\n")
                if((index+1)%10==0) println("PHOSMALL ${args[2]} ${index+1}/100")
            }
        }
    }
}
