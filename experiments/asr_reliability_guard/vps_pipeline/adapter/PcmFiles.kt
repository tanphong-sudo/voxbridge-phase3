package voxbridge.e2e
import java.io.*
internal fun readMono16kPcm16Wav(file: File): FloatArray {
    DataInputStream(FileInputStream(file).buffered()).use { input ->
        fun readAscii4(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun readLeInt(): Int = Integer.reverseBytes(input.readInt())
        fun readLeShort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff

        require(readAscii4() == "RIFF") { "${file.name}: missing RIFF header" }
        readLeInt() // container size
        require(readAscii4() == "WAVE") { "${file.name}: missing WAVE signature" }

        var audioFormat: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var data: ByteArray? = null

        while (input.available() >= 8) {
            val chunkId = readAscii4()
            val chunkSize = readLeInt()
            require(chunkSize >= 0) { "${file.name}: invalid chunk size $chunkSize" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "${file.name}: fmt chunk too short ($chunkSize)" }
                    audioFormat = readLeShort()
                    channels = readLeShort()
                    sampleRate = readLeInt()
                    readLeInt()   // byte rate
                    readLeShort() // block align
                    bitsPerSample = readLeShort()
                    val remaining = chunkSize - 16
                    if (remaining > 0) input.skipBytes(remaining)
                }
                "data" -> data = ByteArray(chunkSize).also(input::readFully)
                else -> input.skipBytes(chunkSize)
            }
            if (chunkSize and 1 == 1 && input.available() > 0) input.skipBytes(1) // RIFF word alignment
        }

        require(audioFormat == 1) { "${file.name}: expected PCM format 1, got $audioFormat" }
        require(channels == 1) { "${file.name}: expected mono, got $channels channels" }
        require(sampleRate == 16_000) { "${file.name}: expected 16000 Hz, got $sampleRate" }
        require(bitsPerSample == 16) { "${file.name}: expected PCM16, got $bitsPerSample bits" }
        val bytes = requireNotNull(data) { "${file.name}: missing data chunk" }
        require(bytes.size % 2 == 0) { "${file.name}: PCM16 data has odd byte length ${bytes.size}" }
        return FloatArray(bytes.size / 2) { i ->
            val lo = bytes[i * 2].toInt() and 0xff
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sample.toShort() / 32768.0f
        }
    }
}
