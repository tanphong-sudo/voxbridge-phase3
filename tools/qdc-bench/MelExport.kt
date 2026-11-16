import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.runtime.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Freeze the exact production VAD/trim/mel input, shared between CPU and QNN ASR tests. */
fun main(args:Array<String>) {
 require(args.size==3) {"manifest model-root output-dir"}
 val out=File(args[2]);out.mkdirs();val manifest=File(out,"asr100.jsonl");require(!manifest.exists())
 val paths=ModelPaths(File(args[1]),ModelProfile("selected-small",mapOf("vi" to "phowhisper-small","en" to "whisper-small")))
 val mel=MelSpectrogram(paths.melFilterFile)
 val rows=File(args[0]).readLines().filter(String::isNotBlank).map(::JSONObject).filter {it.getString("source_lang")=="vi"};require(rows.size==100)
 SileroVad.load(OrtEnvironment.getEnvironment(),paths.vad).use {vad ->
  rows.forEachIndexed { index,row ->
   val bytes=File(row.getString("wav")).readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
   require(String(bytes,0,4)=="RIFF" && String(bytes,8,4)=="WAVE")
   var offset=12;var pcm:FloatArray?=null;var rate=0;var channels=0;var bits=0
   while(offset+8<=bytes.size){val size=b.getInt(offset+4);require(size>=0 && offset+8L+size<=bytes.size)
    when(String(bytes,offset,4)){
     "fmt "->{channels=b.getShort(offset+10).toInt();rate=b.getInt(offset+12);bits=b.getShort(offset+22).toInt()}
     "data"->pcm=FloatArray(size/2){b.getShort(offset+8+it*2)/32768f}
    };offset+=8+size+(size%2)
   }
   require(rate==16000 && channels==1 && bits==16);val audio=requireNotNull(pcm)
   val speech=CaptureAnalysis.analyzeVad(samples=audio,reset=vad::reset,probability={vad.chunkProbability(it)})
   val trimmed=CaptureAnalysis.trimToSpeech(audio,speech.startMs,speech.endMs)
   val features=mel.logMel(trimmed);val output=ByteBuffer.allocate(features.size*4).order(ByteOrder.LITTLE_ENDIAN)
   features.forEach {output.putFloat(it)};val file=File(out,"mel-${index.toString().padStart(3,'0')}.f32");file.writeBytes(output.array())
   manifest.appendText(JSONObject().put("id",row.getString("id")).put("reference",row.getString("reference_source"))
    .put("mel",file.name).put("vad_start_ms",speech.startMs).put("vad_end_ms",speech.endMs).toString()+"\n")
  }
 }
 println("MEL_INPUTS_READY ${rows.size}")
}
