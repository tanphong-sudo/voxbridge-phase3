package voxbridge.e2e

import com.voxbridge.core.pipeline.TentativeOutput
import org.json.JSONObject
import java.io.File
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Existing VPS model workers stay isolated in their existing Python environments. */
internal class LocalLineWorker(command: List<String>, out: File, name: String) : AutoCloseable {
    private val process = ProcessBuilder(command).redirectError(File(out,"$name-worker.stderr.log")).start()
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()
    private val reader = Executors.newSingleThreadExecutor { job -> Thread(job,"$name-jsonl").apply { isDaemon=true } }
    private val extraOutput = File(out,"$name-worker.stdout.log")
    val ready: JSONObject = try {
        readResponse().also { check(it.optBoolean("ready")) { "Worker not ready: $it" } }
    } catch(failure:Exception) { close();throw failure }

    private fun readResponse(): JSONObject = try {
        reader.submit<JSONObject> {
            var result: JSONObject? = null
            while(result == null) {
                val line = output.readLine() ?: error("Worker exited with code ${if(process.isAlive) "running" else process.exitValue()}")
                if(line.trim().startsWith("{")) result=JSONObject(line) else extraOutput.appendText(line+"\n")
            }
            result
        }.get(180,TimeUnit.SECONDS)
    } catch(failure:Exception) {
        close()
        throw IllegalStateException("VPS worker response failed",failure)
    }

    fun request(request:JSONObject):JSONObject {
        input.write(request.toString());input.newLine();input.flush()
        return readResponse().also { check(it.getBoolean("ok")) { it.optString("error") } }
    }

    override fun close() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroy()
        if(!process.waitFor(1,TimeUnit.SECONDS)) process.destroyForcibly()
        reader.shutdownNow()
    }
}

internal fun sha256(file:File):String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Exactly reconstruct PCM16 read from the original WAV; no gain, resampling or guessed audio. */
internal fun writePcm16Wav(file:File, samples:FloatArray) {
    require(samples.isNotEmpty() && samples.size<=480000 && samples.all { it.isFinite() && it >= -1f && it <= 1f })
    file.parentFile.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { output ->
        fun int(value:Int)=output.writeInt(Integer.reverseBytes(value))
        fun short(value:Int)=output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        output.writeBytes("RIFF");int(36+samples.size*2);output.writeBytes("WAVEfmt ");int(16)
        short(1);short(1);int(16000);int(32000);short(2);short(16)
        output.writeBytes("data");int(samples.size*2)
        samples.forEach { short((it*32768).toInt().coerceIn(-32768,32767)) }
    }
}

internal fun draftJson(draft:TentativeOutput?):Any = if(draft==null) JSONObject.NULL else JSONObject()
    .put("source_text",draft.sourceText).put("translated_text",draft.translatedText?:JSONObject.NULL)
    .put("raw_source_text",draft.rawSourceText).put("contains_unknown_spans",draft.containsUnknownSpans)
    .put("source_truncated",draft.sourceTruncated).put("whole_utterance_uncertain",draft.wholeUtteranceUncertain)
    .put("unavailable_reason",draft.unavailableReason?:JSONObject.NULL)
