package com.voxbridge.audio

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

/** Device-local debug artifacts. No network permission is needed or used. */
object AudioDebugStore {
    data class Artifacts(
        val rawWav: File,
        val asrWav: File,
        val vadCsv: File,
        val report: File,
    )

    data class Wav(val pcm: FloatArray, val sampleRate: Int)

    fun saveCapture(context: Context, capture: AudioRecordSource.Capture): Artifacts {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug-audio").apply { mkdirs() }
        val raw = File(dir, "raw_${capture.rawSampleRate}.wav")
        val asr = File(dir, "asr_input_16000.wav")
        val vad = File(dir, "vad_trace.csv")
        val report = File(dir, "debug_report.txt")
        writeMono16Wav(raw, capture.rawPcm, capture.rawSampleRate)
        writeMono16Wav(asr, capture.pcm16k, AudioRecordSource.TARGET_SAMPLE_RATE)
        vad.bufferedWriter().use { out ->
            out.appendLine("timestamp_ms,speech_probability,speech")
            for (f in capture.debug.vadFrames) {
                out.appendLine("${f.startMs},${String.format(Locale.US, "%.6f", f.probability)},${f.speech}")
            }
        }
        return Artifacts(raw, asr, vad, report)
    }

    fun writeReport(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun readMono16Wav(file: File): Wav {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV too short: ${bytes.size} bytes" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, n: Int) = String(bytes, offset, n, Charsets.US_ASCII)
        require(ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE") { "Not a RIFF/WAVE file" }

        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = ascii(offset, 4)
            val size = bb.getInt(offset + 4)
            val payload = offset + 8
            require(size >= 0 && payload + size <= bytes.size) { "Corrupt WAV chunk $id size=$size" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "WAV fmt chunk too short" }
                    format = bb.getShort(payload).toInt() and 0xffff
                    channels = bb.getShort(payload + 2).toInt() and 0xffff
                    sampleRate = bb.getInt(payload + 4)
                    bits = bb.getShort(payload + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                    break
                }
            }
            offset = payload + size + (size and 1)
        }
        require(format == 1) { "WAV must be PCM (format=$format)" }
        require(channels == 1) { "WAV must be mono (channels=$channels)" }
        require(bits == 16) { "WAV must be PCM16 (bits=$bits)" }
        require(sampleRate > 0 && dataOffset >= 0) { "WAV missing sample rate or data chunk" }
        val samples = FloatArray(dataSize / 2)
        for (i in samples.indices) {
            samples[i] = bb.getShort(dataOffset + i * 2) / 32768f
        }
        return Wav(samples, sampleRate)
    }

    private fun writeMono16Wav(file: File, pcm: FloatArray, sampleRate: Int) {
        require(sampleRate > 0) { "invalid sample rate $sampleRate" }
        file.parentFile?.mkdirs()
        val dataBytes = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(1) // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        for (v in pcm) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        file.writeBytes(bb.array())
    }
}
