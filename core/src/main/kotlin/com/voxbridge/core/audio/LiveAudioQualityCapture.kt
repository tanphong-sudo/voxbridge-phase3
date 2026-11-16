package com.voxbridge.core.audio

/**
 * Scalar-only tap on the existing capture/VAD lane. No I/O, waiting or new coroutine per frame.
 * The UI consumer must be nonblocking. Guidance failure disables only this tap, never capture.
 */
class LiveAudioQualityCapture(
    sampleRate: Int = CaptureAnalysis.TARGET_SAMPLE_RATE,
    private val onUpdate: ((AudioQualitySnapshot?) -> Unit)? = null,
    private val publishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
) {
    init { require(sampleRate > 0 && publishIntervalMs > 0) }
    private val monitor = if (onUpdate == null) null else AudioQualityMonitor(sampleRate)
    private var lastPublishedMs = -publishIntervalMs
    private var lastPublishedState: AudioQualityState? = null
    var snapshot: AudioQualitySnapshot? = null
        private set
    var failure: String? = null
        private set

    fun observe(
        samples: FloatArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
        vadProbability: Float? = null,
    ) {
        val active = monitor ?: return
        if (failure != null) return
        try {
            val next = active.observe(samples, offset, count, vadProbability)
            snapshot = next
            if (next.state != lastPublishedState || next.monitoredDurationMs - lastPublishedMs >= publishIntervalMs) {
                onUpdate?.invoke(next)
                lastPublishedMs = next.monitoredDurationMs
                lastPublishedState = next.state
            }
        } catch (error: RuntimeException) {
            disable(error)
        }
    }

    fun summary(): AudioQualitySummary? {
        if (failure != null) return null
        return try { monitor?.summary() } catch (error: RuntimeException) {
            disable(error)
            null
        }
    }

    private fun disable(error: RuntimeException) {
        failure = error.javaClass.simpleName
        snapshot = null
        try { onUpdate?.invoke(null) } catch (_: RuntimeException) {
            // Primary monitoring/publication failure is retained above and logged after capture.
        }
    }

    companion object {
        const val DEFAULT_PUBLISH_INTERVAL_MS = 100L
    }
}
