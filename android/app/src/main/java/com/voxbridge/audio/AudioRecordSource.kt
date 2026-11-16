package com.voxbridge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxbridge.core.audio.CaptureAnalysis
import com.voxbridge.core.audio.FloatCaptureBuffer
import com.voxbridge.core.audio.StreamingVadAnalysis
import com.voxbridge.core.audio.AudioQualitySnapshot
import com.voxbridge.core.audio.AudioQualitySummary
import com.voxbridge.core.audio.LiveAudioQualityCapture
import com.voxbridge.runtime.SileroVad
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Push-to-talk capture with enough evidence to diagnose the real microphone -> ASR path. */
class AudioRecordSource(private val vad: SileroVad) {

    data class VadFrameTrace(
        val startMs: Long,
        val probability: Float,
        val speech: Boolean,
    )

    data class Debug(
        val requestedSampleRate: Int,
        val actualSampleRate: Int,
        val audioSource: Int,
        val channelCount: Int,
        val encoding: Int,
        val bufferSizeBytes: Int,
        val rawSampleCount: Int,
        val asrSampleCount: Int,
        val capturedDurationMs: Long,
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val clippingCount: Int,
        val vadFrames: List<VadFrameTrace>,
        val speechStartMs: Long?,
        val speechEndMs: Long?,
        val preRollAvailableMs: Long,
        val postRollAvailableMs: Long,
        val silenceRatio: Double,
        val captureLimitReached: Boolean,
        val readError: Int? = null,
    )

    data class Capture(
        /** Exact 16 kHz mono float PCM passed to Whisper. */
        val pcm16k: FloatArray,
        /** Raw mono AudioRecord capture before any sample-rate conversion. */
        val rawPcm: FloatArray,
        val rawSampleRate: Int,
        val speechPresent: Boolean,
        val validSpeechMs: Long,
        val audioScore: Double,
        val peak: Float,
        val debug: Debug,
        /** Complete live VAD frames only; null when this optional guidance feature is disabled. */
        val liveAudioQuality: AudioQualitySummary? = null,
        val liveAudioQualityError: String? = null,
    )

    @SuppressLint("MissingPermission")
    fun record(
        isHeld: () -> Boolean,
        onLevelDbfs: (Double) -> Unit = {},
        retainRaw: Boolean = false,
        onAudioQuality: ((AudioQualitySnapshot?) -> Unit)? = null,
    ): Capture = synchronized(vad) {
        val minBuf = AudioRecord.getMinBufferSize(
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "AudioRecord.getMinBufferSize failed: $minBuf" }
        val bufferBytes = maxOf(minBuf, CaptureAnalysis.VAD_CHUNK_SAMPLES * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        var started = false
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            val actualRate = recorder.sampleRate
            val channelCount = recorder.channelCount
            val encoding = recorder.audioFormat
            val audioSource = recorder.audioSource
            require(channelCount == 1) { "AudioRecord returned $channelCount channels; mono required" }
            require(actualRate > 0) { "AudioRecord returned invalid sample rate $actualRate" }

            val buf = ShortArray(READ_SAMPLES)
            val maxRawSamples = maxCaptureSamples(actualRate)
            val raw = FloatCaptureBuffer(maxRawSamples)
            val liveQuality = LiveAudioQualityCapture(sampleRate = actualRate, onUpdate = onAudioQuality)
            val streamingVad = if (actualRate == TARGET_SAMPLE_RATE)
                StreamingVadAnalysis(vad::reset, vad::chunkProbability) { frame, probability ->
                    liveQuality.observe(frame, vadProbability = probability)
                } else null
            var readError: Int? = null
            var captureLimitReached = false
            var levelSquareSum = 0.0
            var levelSampleCount = 0
            val demo = DemoFeed.nextReader(actualRate)
            recorder.startRecording()
            started = true
            while (isHeld()) {
                val n = demo?.read(buf) ?: recorder.read(buf, 0, buf.size)
                if (n < 0) {
                    readError = n
                    break
                }
                if (n == 0) continue
                val remaining = maxRawSamples - raw.size
                if (remaining <= 0) {
                    captureLimitReached = true
                    break
                }
                val accepted = minOf(n, remaining)
                raw.append(buf, accepted)
                streamingVad?.let { stream -> raw.visitTail(accepted, stream::append) }
                if (streamingVad == null) {
                    // No live 16k VAD exists at a different recorder rate. Observe amplitude only;
                    // null VAD cannot train a noise floor or claim GOOD. Existing resampling stays.
                    raw.visitTail(accepted) { samples, offset, count -> liveQuality.observe(samples, offset, count) }
                }
                for (i in 0 until accepted) {
                    val value = buf[i] / 32768f
                    levelSquareSum += value.toDouble() * value.toDouble()
                    levelSampleCount++
                }
                // ~10 updates/s: responsive enough for a meter without flooding StateFlow/UI.
                if (levelSampleCount >= actualRate / 10) {
                    onLevelDbfs(CaptureAnalysis.amplitudeDbfs(sqrt(levelSquareSum / levelSampleCount)))
                    levelSquareSum = 0.0
                    levelSampleCount = 0
                }
                if (accepted < n || raw.size >= maxRawSamples) {
                    // If the user is still holding when the 30 s Whisper window fills, continuing
                    // would either grow memory without bound or be silently truncated downstream.
                    captureLimitReached = accepted < n || isHeld()
                    break
                }
            }
            val rawPcm = raw.finish()
            val asrPcm = if (actualRate == TARGET_SAMPLE_RATE) rawPcm
            else resampleLinear(rawPcm, actualRate, TARGET_SAMPLE_RATE)

            // VAD sees the exact signal contract Whisper receives: mono normalized float PCM @ 16 kHz.
            // Native 16 kHz frames were consumed during capture. Non-16 kHz devices retain the safe
            // post-resampling fallback and never feed their native rate into a 16 kHz VAD.
            val vadSummary = streamingVad?.summary() ?: runVad(asrPcm)
            val validSpeechMs = vadSummary.validSpeechDurationMs
            val trimmedPcm = CaptureAnalysis.trimToSpeech(
                samples = asrPcm,
                startMs = vadSummary.startMs,
                endMs = vadSummary.endMs,
                sampleRate = TARGET_SAMPLE_RATE,
            )
            val durationMs = asrPcm.size.toLong() * 1000 / TARGET_SAMPLE_RATE
            val metrics = signalMetrics(trimmedPcm)
            val audioScore = CaptureAnalysis.audioQualityScore(asrPcm, vadSummary)
            val speechEndMs = vadSummary.endMs
            val silenceRatio = if (durationMs <= 0) 1.0
            else (1.0 - validSpeechMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)

            val qualitySummary = liveQuality.summary()
            return Capture(
                // Whisper receives speech-focused audio with a small context window. This avoids
                // feeding seconds of silence into the decoder while preserving consonant onsets.
                pcm16k = trimmedPcm,
                rawPcm = if (retainRaw) rawPcm else FloatArray(0),
                rawSampleRate = actualRate,
                speechPresent = vadSummary.speechFrames > 0,
                validSpeechMs = validSpeechMs,
                audioScore = audioScore,
                peak = metrics.peak,
                liveAudioQuality = qualitySummary,
                liveAudioQualityError = liveQuality.failure,
                debug = Debug(
                    requestedSampleRate = TARGET_SAMPLE_RATE,
                    actualSampleRate = actualRate,
                    audioSource = audioSource,
                    channelCount = channelCount,
                    encoding = encoding,
                    bufferSizeBytes = bufferBytes,
                    rawSampleCount = rawPcm.size,
                    asrSampleCount = trimmedPcm.size,
                    capturedDurationMs = durationMs,
                    rmsDbfs = metrics.rmsDbfs,
                    peakDbfs = metrics.peakDbfs,
                    clippingCount = metrics.clippingCount,
                    vadFrames = vadSummary.frames.map { VadFrameTrace(it.startMs, it.probability, it.speech) },
                    speechStartMs = vadSummary.startMs,
                    speechEndMs = speechEndMs,
                    preRollAvailableMs = vadSummary.startMs ?: durationMs,
                    postRollAvailableMs = if (speechEndMs == null) 0
                        else (durationMs - speechEndMs).coerceAtLeast(0),
                    silenceRatio = silenceRatio,
                    captureLimitReached = captureLimitReached,
                    readError = readError,
                ),
            )
        } finally {
            try { if (started) recorder.stop() } catch (_: IllegalStateException) { }
            finally { recorder.release() }
        }
    }

    private fun runVad(pcm16k: FloatArray): CaptureAnalysis.VadSummary {
        // reset()+all frames are one logical recurrent sequence. SileroVad synchronizes each
        // method individually, but two rapidly released PTT captures could otherwise interleave
        // between frames and reset one another's recurrent state.
        synchronized(vad) {
            return CaptureAnalysis.analyzeVadSlice(
                samples = pcm16k,
                sampleRate = TARGET_SAMPLE_RATE,
                reset = vad::reset,
                probability = { samples, offset, length -> vad.chunkProbability(samples, offset, length) },
            )
        }
    }

    private fun signalMetrics(samples: FloatArray): CaptureAnalysis.SignalMetrics =
        CaptureAnalysis.signalMetrics(samples)

    companion object {
        const val TARGET_SAMPLE_RATE = CaptureAnalysis.TARGET_SAMPLE_RATE
        const val SPEECH_THRESHOLD = CaptureAnalysis.SPEECH_THRESHOLD
        const val MAX_CAPTURE_MS = CaptureAnalysis.MAX_CAPTURE_MS
        private const val READ_SAMPLES = 1024

        fun maxCaptureSamples(sampleRate: Int): Int {
            return CaptureAnalysis.maxCaptureSamples(sampleRate)
        }

        fun resampleLinear(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
            require(fromHz > 0 && toHz > 0) { "sample rates must be positive" }
            if (input.isEmpty() || fromHz == toHz) return input.copyOf()
            val outSize = (input.size.toDouble() * toHz / fromHz).roundToInt().coerceAtLeast(1)
            val out = FloatArray(outSize)
            val step = fromHz.toDouble() / toHz.toDouble()
            for (i in out.indices) {
                val src = i * step
                val lo = floor(src).toInt().coerceIn(0, input.lastIndex)
                val hi = (lo + 1).coerceAtMost(input.lastIndex)
                val frac = (src - lo).toFloat()
                out[i] = input[lo] + (input[hi] - input[lo]) * frac
            }
            return out
        }
    }
}
