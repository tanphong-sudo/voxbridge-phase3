package com.voxbridge.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One monitor per capture, called by the existing VAD frame observer. Work is one bounded PCM
 * scan plus scalar math; there is no VAD/ASR invocation, I/O, waiting, or retained audio here.
 * Call from one capture lane. Output snapshots and accumulator updates are immutable values.
 */
class AudioQualityMonitor(
    private val sampleRate: Int = CaptureAnalysis.TARGET_SAMPLE_RATE,
    private val config: AudioQualityConfig = AudioQualityConfig(),
) {
    init { require(sampleRate > 0) { "sampleRate must be positive" } }

    private data class Signal(val rms: Double, val peak: Float, val clipped: Long, val samples: Int)
    private data class Noise(val power: Double? = null, val samples: Long = 0, val rmsSum: Double = 0.0)
    private data class Display(
        val state: AudioQualityState = AudioQualityState.NO_SPEECH,
        val pending: AudioQualityState = AudioQualityState.NO_SPEECH,
        val pendingSamples: Long = 0,
    )
    private data class Counts(
        val good: Long = 0, val quiet: Long = 0, val noisy: Long = 0,
        val clipping: Long = 0, val noSpeech: Long = 0,
    ) {
        fun add(quality: AudioQualityState, count: Int) = when (quality) {
            AudioQualityState.GOOD -> copy(good = good + count)
            AudioQualityState.TOO_QUIET -> copy(quiet = quiet + count)
            AudioQualityState.TOO_NOISY -> copy(noisy = noisy + count)
            AudioQualityState.CLIPPING -> copy(clipping = clipping + count)
            AudioQualityState.NO_SPEECH -> copy(noSpeech = noSpeech + count)
        }
    }
    private data class Accumulator(
        val samples: Long = 0,
        val vadSamples: Long = 0,
        val speechSamples: Long = 0,
        val speechRmsSum: Double = 0.0,
        val snrSamples: Long = 0,
        val snrSum: Double = 0.0,
        val clipped: Long = 0,
        val noise: Noise = Noise(),
        val counts: Counts = Counts(),
        val display: Display = Display(),
    )
    private var total = Accumulator()

    fun observe(
        samples: FloatArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
        vadProbability: Float? = null,
    ): AudioQualitySnapshot {
        require(vadProbability == null || vadProbability.isFinite() && vadProbability in 0f..1f)
        val signal = signal(samples, offset, count)
        val speech = vadProbability != null && vadProbability >= config.speechVadThreshold
        val noise = updateNoise(signal, vadProbability)
        val noiseReady = noise.power != null && durationMs(noise.samples) >= config.noiseWarmupMs
        val noiseDbfs = noise.power?.let { if (noiseReady) dbfs(sqrt(it)) else null }
        val rmsDbfs = dbfs(signal.rms)
        val contrast = if (speech && noiseDbfs != null) rmsDbfs - noiseDbfs else null
        val quality = classify(signal, vadProbability, rmsDbfs, noiseDbfs, contrast)
        total = total.copy(
            samples = total.samples + count,
            vadSamples = total.vadSamples + if (vadProbability != null) count else 0,
            speechSamples = total.speechSamples + if (speech) count else 0,
            speechRmsSum = total.speechRmsSum + if (speech) signal.rms * count else 0.0,
            snrSamples = total.snrSamples + if (contrast != null) count else 0,
            snrSum = total.snrSum + (contrast ?: 0.0) * count,
            clipped = total.clipped + signal.clipped,
            noise = noise,
            counts = total.counts.add(quality, count),
            display = smooth(quality, count),
        )
        return AudioQualitySnapshot(
            total.display.state, rmsDbfs, noiseDbfs, contrast, signal.peak,
            signal.clipped.toDouble() / count, vadProbability, durationMs(total.samples), noiseReady,
        )
    }

    fun summary(): AudioQualitySummary {
        fun mean(sum: Double, count: Long) = if (count == 0L) null else sum / count
        fun ratio(count: Long) = if (total.samples == 0L) 0.0 else count.toDouble() / total.samples
        return AudioQualitySummary(
            monitoredDurationMs = durationMs(total.samples),
            speechRmsMean = mean(total.speechRmsSum, total.speechSamples),
            noiseRmsMean = mean(total.noise.rmsSum, total.noise.samples),
            snrProxyMean = mean(total.snrSum, total.snrSamples),
            clippingRatio = ratio(total.clipped),
            goodRatio = ratio(total.counts.good), quietRatio = ratio(total.counts.quiet),
            noisyRatio = ratio(total.counts.noisy), clippingStateRatio = ratio(total.counts.clipping),
            noSpeechRatio = ratio(total.counts.noSpeech),
            finalAudioQuality = total.display.state, vadAvailable = total.vadSamples > 0,
            analyzedSamples = total.samples,
        )
    }

    private fun signal(samples: FloatArray, offset: Int, count: Int): Signal {
        require(offset >= 0 && offset <= samples.size && count > 0 && count <= samples.size - offset)
        var sum = 0.0
        var peak = 0f
        var clipped = 0L
        for (i in offset until offset + count) {
            val value = samples[i]
            require(value.isFinite() && value in -1f..1f) { "PCM must be finite normalized samples" }
            val magnitude = abs(value)
            sum += value.toDouble() * value
            if (magnitude > peak) peak = magnitude
            if (magnitude >= config.clippingAmplitude) clipped++
        }
        return Signal(sqrt(sum / count), peak, clipped, count)
    }

    private fun updateNoise(signal: Signal, probability: Float?): Noise {
        val previous = total.noise
        if (probability == null || probability > config.nonSpeechVadThreshold ||
            signal.clipped.toDouble() / signal.samples >= config.clippingSampleRatio) return previous
        val power = signal.rms * signal.rms
        val alpha = 1.0 - exp(-signal.samples.toDouble() * 1000 / sampleRate / config.noiseEmaTimeConstantMs)
        return Noise(
            power = previous.power?.let { it + alpha * (power - it) } ?: power,
            samples = previous.samples + signal.samples,
            rmsSum = previous.rmsSum + signal.rms * signal.samples,
        )
    }

    private fun classify(
        signal: Signal,
        probability: Float?,
        rmsDbfs: Double,
        noiseDbfs: Double?,
        contrast: Double?,
    ): AudioQualityState = when {
        signal.clipped.toDouble() / signal.samples >= config.clippingSampleRatio -> AudioQualityState.CLIPPING
        probability == null -> AudioQualityState.NO_SPEECH
        probability >= config.speechVadThreshold -> when {
            rmsDbfs < config.quietSpeechDbfs -> AudioQualityState.TOO_QUIET
            contrast != null && contrast < config.minimumSnrProxyDb -> AudioQualityState.TOO_NOISY
            else -> AudioQualityState.GOOD
        }
        probability <= config.nonSpeechVadThreshold && noiseDbfs != null && noiseDbfs >= config.loudNoiseDbfs ->
            AudioQualityState.TOO_NOISY
        else -> AudioQualityState.NO_SPEECH
    }

    private fun smooth(quality: AudioQualityState, count: Int): Display {
        val previous = total.display
        if (quality == previous.state) return Display(quality, quality)
        val accumulated = if (quality == previous.pending) previous.pendingSamples + count else count.toLong()
        val hold = when (quality) {
            AudioQualityState.GOOD -> config.goodHoldMs
            AudioQualityState.NO_SPEECH -> config.noSpeechHoldMs
            else -> config.warningHoldMs
        }
        return if (durationMs(accumulated) >= hold) Display(quality, quality)
        else previous.copy(pending = quality, pendingSamples = accumulated)
    }

    private fun dbfs(rms: Double) = if (rms <= 0.0) config.dbFloor
        else maxOf(config.dbFloor, 20.0 * log10(rms))

    private fun durationMs(samples: Long): Long = samples * 1000 / sampleRate
}
