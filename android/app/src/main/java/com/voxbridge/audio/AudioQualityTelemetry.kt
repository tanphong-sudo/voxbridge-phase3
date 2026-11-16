package com.voxbridge.audio

import com.voxbridge.core.audio.AudioQualitySummary
import org.json.JSONObject

/** Scalar benchmark trace only. Call after capture; never serializes samples or transcript. */
object AudioQualityTelemetry {
    fun encode(
        summary: AudioQualitySummary?,
        durationMs: Long,
        utteranceId: Int,
        actualSampleRate: Int,
        error: String? = null,
    ): String = JSONObject().apply {
        put("utterance_id", utteranceId)
        put("duration_ms", durationMs)
        put("monitored_duration_ms", summary?.monitoredDurationMs ?: JSONObject.NULL)
        put("actual_sample_rate", actualSampleRate)
        put("speech_rms_mean", summary?.speechRmsMean ?: JSONObject.NULL)
        put("noise_rms_mean", summary?.noiseRmsMean ?: JSONObject.NULL)
        put("snr_proxy_mean", summary?.snrProxyMean ?: JSONObject.NULL)
        put("clipping_ratio", summary?.clippingRatio ?: JSONObject.NULL)
        put("good_ratio", summary?.goodRatio ?: JSONObject.NULL)
        put("quiet_ratio", summary?.quietRatio ?: JSONObject.NULL)
        put("noisy_ratio", summary?.noisyRatio ?: JSONObject.NULL)
        put("clipping_state_ratio", summary?.clippingStateRatio ?: JSONObject.NULL)
        put("no_speech_ratio", summary?.noSpeechRatio ?: JSONObject.NULL)
        put("final_audio_quality", summary?.finalAudioQuality?.name ?: JSONObject.NULL)
        put("vad_available", summary?.vadAvailable ?: false)
        put("monitoring_error", error ?: JSONObject.NULL)
        put("prototype_uncalibrated", true)
        put("guidance_only", true)
    }.toString()
}
