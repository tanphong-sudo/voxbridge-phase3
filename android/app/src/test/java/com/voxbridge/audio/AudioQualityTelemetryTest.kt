package com.voxbridge.audio

import com.voxbridge.core.audio.AudioQualityMonitor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioQualityTelemetryTest {
    @Test fun capturesOnlyScalarsAndDistinguishesCapturedFromAnalyzedDuration() {
        val monitor = AudioQualityMonitor()
        repeat(10) { monitor.observe(FloatArray(512) { .001f }, vadProbability = .1f) }
        repeat(10) { monitor.observe(FloatArray(512) { .1f }, vadProbability = .9f) }
        val json = JSONObject(AudioQualityTelemetry.encode(monitor.summary(), 653, 7, 16000))
        assertEquals(653, json.getLong("duration_ms"))
        assertEquals(640, json.getLong("monitored_duration_ms"))
        assertEquals("GOOD", json.getString("final_audio_quality"))
        assertEquals(7, json.getLong("utterance_id"))
        assertTrue(json.getBoolean("prototype_uncalibrated"))
        assertTrue(json.has("snr_proxy_mean"))
        assertFalse(json.has("pcm"))
        assertFalse(json.has("transcript"))
    }

    @Test fun missingVadAndNoiseRemainNullInJson() {
        val monitor = AudioQualityMonitor(sampleRate = 48000)
        monitor.observe(FloatArray(1024) { .1f })
        val json = JSONObject(AudioQualityTelemetry.encode(monitor.summary(), 21, 1, 48000))
        assertTrue(json.isNull("speech_rms_mean"))
        assertTrue(json.isNull("noise_rms_mean"))
        assertTrue(json.isNull("snr_proxy_mean"))
        assertFalse(json.getBoolean("vad_available"))
    }

    @Test fun failedMonitorIsReportedWithoutInventingQuality() {
        val json = JSONObject(AudioQualityTelemetry.encode(null, 400, 2, 16000, "IllegalArgumentException"))
        assertTrue(json.isNull("final_audio_quality"))
        assertTrue(json.isNull("clipping_ratio"))
        assertEquals("IllegalArgumentException", json.getString("monitoring_error"))
    }
}
