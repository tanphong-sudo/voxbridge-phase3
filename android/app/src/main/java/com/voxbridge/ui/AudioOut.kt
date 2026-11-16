package com.voxbridge.ui

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.audio.writePcmFully
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Writer owns release; cancellation unblocks writes without racing native release. */
object AudioOut {
    private class Playback(val track: AudioTrack) {
        @Volatile var cancelled = false
        val completion = CompletableDeferred<Unit>()
    }
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voxbridge-playback").apply { isDaemon = true }
    }
    private var current: Playback? = null

    /**
     * [onStarted] fires once, on the playback thread, right after the first PCM bytes are
     * accepted by the started AudioTrack — the closest observable proxy for "first audio" without
     * an acoustic loopback. It never fires for cancelled or empty playback.
     */
    @Synchronized fun play(pcm: PcmAudio?, onStarted: (() -> Unit)? = null): Deferred<Unit> {
        if (pcm == null || pcm.bytes.isEmpty()) return CompletableDeferred(Unit)
        com.voxbridge.audio.DemoFeed.saveOutput(pcm)
        stop()
        val min = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Unsupported playback sample rate: ${pcm.sampleRate}" }
        val track = AudioTrack(AudioManager.STREAM_MUSIC, pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), AudioTrack.MODE_STREAM)
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); error("AudioTrack initialization failed") }
        val playback = Playback(track)
        current = playback
        worker.execute {
            var failure: Throwable? = null
            try {
                if (!playback.cancelled) {
                    synchronized(this) { if (!playback.cancelled) track.play() }
                    var startNotified = onStarted == null
                    writePcmFully(pcm.bytes, { !playback.cancelled }) { bytes, offset, count ->
                        var written = 0
                        val deadline = System.nanoTime() + 2_000_000_000L
                        while (written == 0 && !playback.cancelled) {
                            written = track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                            if (written == 0) {
                                check(System.nanoTime() < deadline) { "Playback write stalled" }
                                Thread.sleep(5)
                            }
                        }
                        if (written > 0 && !startNotified) {
                            startNotified = true
                            try { onStarted?.invoke() } catch (e: Exception) {
                                Log.e("VoxBridgeAudio", "onStarted callback failed", e)
                            }
                        }
                        written
                    }
                    val frames = pcm.bytes.size / 2L
                    val deadline = System.nanoTime() + (frames * 1000 / pcm.sampleRate + 5000) * 1_000_000
                    while (!playback.cancelled && (track.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
                        check(System.nanoTime() < deadline) { "Playback did not drain" }
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                failure = e
                if (!playback.cancelled) Log.e("VoxBridgeAudio", "Playback failed", e)
            } finally {
                synchronized(this) {
                    if (current === playback) current = null
                    try { track.stop() } catch (e: Exception) { failure = failure ?: e }
                    finally {
                        try { track.release() } catch (e: Exception) { failure = failure ?: e }
                    }
                    if (failure != null) playback.completion.completeExceptionally(checkNotNull(failure))
                    else playback.completion.complete(Unit)
                }
            }
        }
        return playback.completion
    }

    fun playFloatMono(samples: FloatArray, sampleRate: Int) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        play(PcmAudio(bytes, sampleRate))
    }

    @Synchronized fun stop() {
        current?.let {
            it.cancelled = true
            it.completion.cancel()
            try { it.track.pause(); it.track.flush() } catch (_: IllegalStateException) { }
        }
        current = null
    }
}
