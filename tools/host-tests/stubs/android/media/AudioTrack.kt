package android.media
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
object AudioFormat { const val CHANNEL_OUT_MONO=4; const val ENCODING_PCM_16BIT=2 }
object AudioManager { const val STREAM_MUSIC=3 }
class AudioTrack(stream: Int, rate: Int, channel: Int, encoding: Int, buffer: Int, mode: Int) {
    val state=STATE_INITIALIZED
    @Volatile var playbackHeadPosition=0
    @Volatile var releaseCount=0
    @Volatile var writeResult: Int?=nextWriteResult.also { nextWriteResult=null }
    private val stopFailure=nextStopFailure.also { nextStopFailure=null }
    val started=CountDownLatch(1)
    init { tracks.add(this) }
    fun play() { started.countDown() }
    fun write(bytes: ByteArray, offset: Int, count: Int, mode: Int): Int = writeResult ?: count
    fun pause() {}
    fun flush() {}
    fun stop() { stopFailure?.let { throw it } }
    fun release() { releaseCount++ }
    companion object {
        const val STATE_INITIALIZED=1; const val MODE_STREAM=1; const val WRITE_NON_BLOCKING=1
        val tracks=CopyOnWriteArrayList<AudioTrack>()
        @Volatile var nextWriteResult: Int?=null
        @Volatile var nextStopFailure: RuntimeException?=null
        fun getMinBufferSize(rate: Int, channel: Int, encoding: Int)=8192
    }
}
