package com.voxbridge.audio

import android.util.Log
import com.voxbridge.core.audio.PcmAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Remote-device demo input. A phone in a Qualcomm Device Cloud rack has no one speaking into its
 * microphone, so when `files/demo/clips/` exists each hold-to-talk press reads the next WAV there
 * (sorted by name) in real time instead of the silent microphone; everything after capture is the
 * normal pipeline. Each spoken output is also saved to `files/demo/out/<epoch ms>.wav`, because the
 * remote screen stream carries no sound. Without that folder this object does nothing.
 */
object DemoFeed {
    private const val TAG = "VoxDemo"
    @Volatile private var root: File? = null
    private var nextIndex = 0

    fun init(externalFilesDir: File?) {
        root = externalFilesDir?.resolve("demo")?.takeIf { File(it, "clips").isDirectory }
        if (root != null) Log.i(TAG, "demo feed enabled: ${clips().size} clips")
    }

    private fun clips(): List<File> =
        root?.resolve("clips")?.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()

    /** The next clip as a real-time reader, or null to use the microphone. */
    @Synchronized fun nextReader(sampleRate: Int): Reader? {
        if (root == null) return null
        val list = clips()
        if (nextIndex >= list.size) return null
        val file = list[nextIndex++]
        val wav = AudioDebugStore.readMono16Wav(file)
        require(wav.sampleRate == sampleRate) { "${file.name} is ${wav.sampleRate} Hz, recorder is $sampleRate Hz" }
        Log.i(TAG, "clip ${file.name} startMs=${System.currentTimeMillis()} samples=${wav.pcm.size}")
        return Reader(ShortArray(wav.pcm.size) { (wav.pcm[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, sampleRate)
    }

    fun saveOutput(pcm: PcmAudio) {
        val dir = root?.resolve("out") ?: return
        try {
            dir.mkdirs()
            val file = File(dir, "${System.currentTimeMillis()}.wav")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.bytes.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(pcm.sampleRate); putInt(pcm.sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.bytes.size)
            }
            file.outputStream().use { it.write(header.array()); it.write(pcm.bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "could not save demo output", e)
        }
    }

    /** Hands out the clip at the pace a microphone would, then silence until the button is released. */
    class Reader(private val clip: ShortArray, private val sampleRate: Int) {
        private var position = 0
        private var startNs = 0L
        private var delivered = 0L

        fun read(buf: ShortArray): Int {
            if (startNs == 0L) startNs = System.nanoTime()
            for (i in buf.indices) buf[i] = if (position < clip.size) clip[position++] else 0
            delivered += buf.size
            val dueNs = startNs + delivered * 1_000_000_000L / sampleRate
            val waitMs = (dueNs - System.nanoTime()) / 1_000_000
            if (waitMs > 0) Thread.sleep(waitMs)
            return buf.size
        }
    }
}
