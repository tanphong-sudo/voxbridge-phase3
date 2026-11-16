package com.voxbridge.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Shared, platform-neutral capture analysis used by Android and the Mac/JVM harness.
 *
 * The Android adapter still owns AudioRecord and the real Silero runtime. This object owns the
 * deterministic parts around them: reset-before-scan ordering, 512-sample VAD framing, speech
 * boundaries, 300/400 ms trim, signal/clipping metrics, the 30 s capture limit, and the cheap
 * pre-ASR gate. Keeping these here prevents the desktop harness from reimplementing a slightly
 * different gate order from the app.
 */
object CaptureAnalysis {
    const val TARGET_SAMPLE_RATE = 16_000
    const val VAD_CHUNK_SAMPLES = 512
    const val VAD_CHUNK_MS = 32L
    const val SPEECH_THRESHOLD = 0.5f
    const val PRE_ROLL_MS = 300L
    const val POST_ROLL_MS = 400L
    const val MAX_CAPTURE_MS = 30_000L

    private const val CLIP_LEVEL = 0.99f
    private const val MIN_CLIPPED_SAMPLES = 16
    private const val MAX_CLIPPED_SAMPLE_RATIO = 0.005
    private const val DB_FLOOR = -120.0

    data class VadFrame(
        val startMs: Long,
        val probability: Float,
        val speech: Boolean,
    )

    data class VadSummary(
        val frames: List<VadFrame>,
        val speechFrames: Int,
        val startMs: Long?,
        val endMs: Long?,
        val sampleRate: Int,
        val chunkSamples: Int,
    ) {
        val speechPresent: Boolean get() = speechFrames > 0
        val validSpeechDurationMs: Long
            get() = speechFrames.toLong() * chunkSamples * 1000L / sampleRate
    }

    data class SignalMetrics(
        val peak: Float,
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val clippingCount: Int,
    )

    data class AudioQualityEvidence(
        val score: Double,
        val meanSpeechVadProbability: Double,
        val speechRmsDbfs: Double,
        val backgroundRmsDbfs: Double?,
        val speechToBackgroundDb: Double?,
    )

    sealed interface PreAsrGate {
        data object RunAsr : PreAsrGate
        data object NoSpeech : PreAsrGate
        data class AskRepeat(val reason: String) : PreAsrGate
    }

    /**
     * Run one logical VAD sequence. [reset] is guaranteed to execute exactly once before the first
     * probability call, including for an empty capture. The caller may synchronize around this
     * function when the concrete VAD has mutable recurrent state.
     */
    fun analyzeVad(
        samples: FloatArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        chunkSamples: Int = VAD_CHUNK_SAMPLES,
        speechThreshold: Float = SPEECH_THRESHOLD,
        reset: () -> Unit,
        probability: (FloatArray) -> Float,
    ): VadSummary = analyzeVadSlice(samples, sampleRate, chunkSamples, speechThreshold, reset) { pcm, offset, length ->
        probability(pcm.copyOfRange(offset, offset + length))
    }

    /** Zero-copy callback: samples are read-only and must not be retained by the callback. */
    fun analyzeVadSlice(
        samples: FloatArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        chunkSamples: Int = VAD_CHUNK_SAMPLES,
        speechThreshold: Float = SPEECH_THRESHOLD,
        reset: () -> Unit,
        probability: (FloatArray, Int, Int) -> Float,
    ): VadSummary {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        reset()

        val frames = ArrayList<VadFrame>(samples.size / chunkSamples)
        var speechFrames = 0
        var firstSpeechMs: Long? = null
        var lastSpeechEndMs: Long? = null
        var offset = 0
        while (offset + chunkSamples <= samples.size) {
            val p = probability(samples, offset, chunkSamples)
            val startMs = offset.toLong() * 1000L / sampleRate
            val speech = p >= speechThreshold
            frames += VadFrame(startMs, p, speech)
            if (speech) {
                speechFrames++
                if (firstSpeechMs == null) firstSpeechMs = startMs
                lastSpeechEndMs = (offset + chunkSamples).toLong() * 1000L / sampleRate
            }
            offset += chunkSamples
        }
        return VadSummary(
            frames = frames,
            speechFrames = speechFrames,
            startMs = firstSpeechMs,
            endMs = lastSpeechEndMs,
            sampleRate = sampleRate,
            chunkSamples = chunkSamples,
        )
    }

    /** Trim silence retaining 300/400 ms context. Returns the read-only input when no trim is needed. */
    fun trimToSpeech(
        samples: FloatArray,
        startMs: Long?,
        endMs: Long?,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        preRollMs: Long = PRE_ROLL_MS,
        postRollMs: Long = POST_ROLL_MS,
    ): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (samples.isEmpty() || startMs == null || endMs == null) return samples
        val preRoll = (preRollMs * sampleRate / 1000L).toInt()
        val postRoll = (postRollMs * sampleRate / 1000L).toInt()
        val start = (startMs * sampleRate / 1000L).toInt() - preRoll
        val end = (endMs * sampleRate / 1000L).toInt() + postRoll
        val from = start.coerceAtLeast(0)
        val to = end.coerceAtMost(samples.size)
        if (to <= from || (from == 0 && to == samples.size)) return samples
        return samples.copyOfRange(from, to)
    }

    fun signalMetrics(samples: FloatArray): SignalMetrics {
        if (samples.isEmpty()) return SignalMetrics(0f, DB_FLOOR, DB_FLOOR, 0)
        var sumSquares = 0.0
        var peak = 0f
        var clipping = 0
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
            if (a >= CLIP_LEVEL) clipping++
            sumSquares += v.toDouble() * v.toDouble()
        }
        val rms = sqrt(sumSquares / samples.size)
        return SignalMetrics(
            peak = peak,
            rmsDbfs = amplitudeDbfs(rms),
            peakDbfs = amplitudeDbfs(peak.toDouble()),
            clippingCount = clipping,
        )
    }

    /**
     * Capture-quality evidence that is invariant to recording gain.
     *
     * Absolute RMS cannot distinguish quiet but intelligible speech from silence/noise. Instead we
     * combine two pieces of evidence already produced by the real capture path:
     *  - Silero confidence on frames it classified as speech;
     *  - speech-vs-background RMS separation for the same VAD framing.
     *
     * A low-gain recording can therefore remain high quality when speech is consistently detected
     * and rises above its local background. Weak VAD false positives on stationary noise remain low
     * quality. Clipping, duration and explicit noise rejection stay separate hard gates.
     */
    fun audioQualityEvidence(samples: FloatArray, vad: VadSummary): AudioQualityEvidence {
        if (!vad.speechPresent || vad.frames.isEmpty()) {
            return AudioQualityEvidence(0.0, 0.0, DB_FLOOR, null, null)
        }

        var speechSquares = 0.0
        var speechSamples = 0
        var backgroundSquares = 0.0
        var backgroundSamples = 0
        var speechProbabilitySum = 0.0
        var speechProbabilityCount = 0

        vad.frames.forEachIndexed { frameIndex, frame ->
            val from = frameIndex * vad.chunkSamples
            val to = minOf(from + vad.chunkSamples, samples.size)
            if (from >= to) return@forEachIndexed
            var squares = 0.0
            for (i in from until to) {
                val value = samples[i].toDouble()
                squares += value * value
            }
            if (frame.speech) {
                speechSquares += squares
                speechSamples += to - from
                speechProbabilitySum += frame.probability.toDouble()
                speechProbabilityCount++
            } else {
                backgroundSquares += squares
                backgroundSamples += to - from
            }
        }

        if (speechSamples == 0 || speechProbabilityCount == 0) {
            return AudioQualityEvidence(0.0, 0.0, DB_FLOOR, null, null)
        }

        val speechRms = sqrt(speechSquares / speechSamples)
        val speechDb = amplitudeDbfs(speechRms)
        val meanVad = speechProbabilitySum / speechProbabilityCount
        // 0.50 is the speech decision boundary. By 0.90, Silero support is treated as strong.
        val vadSupport = ((meanVad - SPEECH_THRESHOLD) / (0.90 - SPEECH_THRESHOLD)).coerceIn(0.0, 1.0)

        val backgroundDb = if (backgroundSamples > 0) {
            amplitudeDbfs(sqrt(backgroundSquares / backgroundSamples))
        } else null
        val separationDb = backgroundDb?.let { speechDb - it }
        // 12 dB of local speech/background separation is sufficient evidence; 0 dB contributes none.
        val separationSupport = separationDb?.let { (it / 12.0).coerceIn(0.0, 1.0) } ?: 0.5
        val score = (0.65 * vadSupport + 0.35 * separationSupport).coerceIn(0.0, 1.0)

        return AudioQualityEvidence(
            score = score,
            meanSpeechVadProbability = meanVad,
            speechRmsDbfs = speechDb,
            backgroundRmsDbfs = backgroundDb,
            speechToBackgroundDb = separationDb,
        )
    }

    fun audioQualityScore(samples: FloatArray, vad: VadSummary): Double =
        audioQualityEvidence(samples, vad).score

    /** Preserve the app's existing sustained-clipping semantics. */
    fun isMeaningfullyClipped(clippingCount: Int, sampleCount: Int): Boolean {
        if (clippingCount < MIN_CLIPPED_SAMPLES || sampleCount <= 0) return false
        return clippingCount.toDouble() / sampleCount.toDouble() >= MAX_CLIPPED_SAMPLE_RATIO
    }

    fun maxCaptureSamples(sampleRate: Int): Int {
        require(sampleRate > 0) { "sample rate must be positive" }
        return (sampleRate.toLong() * MAX_CAPTURE_MS / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Cheap capture gate that is safe to run before ASR. The ordering mirrors DecisionEngine's
     * audio branch, with the adapter's existing 30 s overflow rejection kept first.
     */
    fun preAsrGate(
        audio: AudioEvidence,
        captureLimitReached: Boolean = false,
        minAudioScore: Double = 0.15,
        askRepeatAudioScore: Double = 0.20,
        minSpeechDurationMs: Long = 250L,
    ): PreAsrGate {
        if (captureLimitReached) return PreAsrGate.AskRepeat("speech_too_long")
        if (!audio.speechPresent || audio.audioScore < minAudioScore) return PreAsrGate.NoSpeech
        if (audio.clippingDetected) return PreAsrGate.AskRepeat("audio_clipped")
        if (audio.tooNoisy) return PreAsrGate.AskRepeat("audio_too_noisy")
        if (audio.audioScore < askRepeatAudioScore) return PreAsrGate.AskRepeat("audio_low_quality")
        val effectiveSpeech = audio.validSpeechDurationMs ?: audio.durationMs
        if (effectiveSpeech < minSpeechDurationMs) return PreAsrGate.AskRepeat("speech_too_short")
        return PreAsrGate.RunAsr
    }

    fun amplitudeDbfs(amplitude: Double): Double =
        if (amplitude <= 1e-6) DB_FLOOR else 20.0 * log10(amplitude.coerceAtMost(1.0))
}
