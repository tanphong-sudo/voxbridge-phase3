package com.voxbridge.core.audio

import com.voxbridge.core.asr.AsrEvidenceKind
import com.voxbridge.core.asr.AsrReliabilityEvidence
import com.voxbridge.core.asr.TranscriptResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One optional signal-conditioning observation, never an independent decoder or confidence.
 * Both exact waveform hashes are retained: a changed signal is not described as the same input.
 * This does not reconstruct missing words; only the actual ASR result may supply an alternative.
 */
object AsrSignalVariant {
    private const val TARGET_RMS = .06309573444801933 // -24 dBFS
    private const val MAX_GAIN = 8.0
    private const val MAX_PEAK = .98
    private const val MIN_USEFUL_GAIN = 1.05
    private val maxSamples get() = CaptureAnalysis.maxCaptureSamples(CaptureAnalysis.TARGET_SAMPLE_RATE)

    data class Variant(
        val pcm: FloatArray,
        val gain: Double,
        val originalInputId: String,
        val variantInputId: String,
        val configurationId: String,
    )

    data class Observation(
        val transcript: TranscriptResult,
        val variant: Variant? = null,
        val attempted: Boolean = false,
        val failure: String? = null,
    )

    /** Executes at most one real callback; a failed/cancelled pass cannot manufacture agreement. */
    fun observe(
        primary: TranscriptResult,
        pcm: FloatArray,
        speechPresent: Boolean,
        primaryConfigurationId: String,
        checkActive: () -> Unit = {},
        decodeVariant: (FloatArray) -> TranscriptResult,
    ): Observation {
        checkActive()
        val variant = create(pcm, speechPresent) ?: return Observation(primary)
        return try {
            checkActive()
            val secondary = decodeVariant(variant.pcm)
            checkActive()
            require(primary.sourceLanguage == secondary.sourceLanguage && primary.targetLanguage == secondary.targetLanguage)
            val evidence = AsrReliabilityEvidence(
                primaryText = primary.text,
                primaryConfigurationId = primaryConfigurationId,
                originalAudioId = variant.originalInputId,
                primaryInputId = variant.originalInputId,
                kind = AsrEvidenceKind.SIGNAL_VARIANT_COMPARISON,
                alternativeText = secondary.text,
                alternativeConfigurationId = "$primaryConfigurationId:${variant.configurationId}:gain=${variant.gain}",
                alternativeInputId = variant.variantInputId,
                alternativeOriginalAudioId = variant.originalInputId,
                wordConfidencesCalibrated = false,
                provider = "native-asr-signal-variant-v1",
            )
            Observation(primary.copy(reliability = evidence), variant, attempted = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Observation(primary, variant, attempted = true, failure = error.javaClass.simpleName)
        }
    }

    /** Null means no actual useful/bounded alternative waveform, so no agreement evidence exists. */
    fun create(pcm: FloatArray, speechPresent: Boolean): Variant? {
        if (!speechPresent || pcm.isEmpty() || pcm.size > maxSamples) return null
        if (pcm.any { !it.isFinite() || it < -1f || it > 1f }) return null
        var power = 0.0
        var peak = 0.0
        for (sample in pcm) {
            power += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample.toDouble()))
        }
        if (peak == 0.0) return null
        val rms = sqrt(power / pcm.size)
        val gain = minOf(MAX_GAIN, TARGET_RMS / rms, MAX_PEAK / peak)
        if (!gain.isFinite() || gain < MIN_USEFUL_GAIN) return null
        val variant = FloatArray(pcm.size) { (pcm[it] * gain).toFloat() }
        return Variant(
            variant, gain, inputId(pcm), inputId(variant),
            "signal-gain-rms24-peak098-max8-v1",
        )
    }

    /** Stable SHA256 over exact IEEE754 float bits, little endian; no PCM quantization occurs. */
    fun inputId(pcm: FloatArray): String {
        require(pcm.size <= maxSamples) { "ASR input exceeds the capture limit" }
        require(pcm.all { it.isFinite() && it >= -1f && it <= 1f }) { "ASR input must be finite normalized PCM" }
        val bytes = ByteBuffer.allocate(pcm.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) bytes.putInt(sample.toRawBits())
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
