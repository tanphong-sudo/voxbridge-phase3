package com.voxbridge.core.audio

/** Prototype values, NOT calibrated for ASR accuracy, device SPL, or factory acoustics. */
data class AudioQualityConfig(
    val speechVadThreshold: Float = .6f,
    val nonSpeechVadThreshold: Float = .2f,
    val quietSpeechDbfs: Double = -38.0,
    val loudNoiseDbfs: Double = -28.0,
    val minimumSnrProxyDb: Double = 6.0,
    val noiseWarmupMs: Long = 320,
    val noiseEmaTimeConstantMs: Double = 1000.0,
    val warningHoldMs: Long = 250,
    val goodHoldMs: Long = 160,
    val noSpeechHoldMs: Long = 200,
    val clippingAmplitude: Float = .99f,
    val clippingSampleRatio: Double = .005,
    val dbFloor: Double = -120.0,
) {
    init {
        require(speechVadThreshold.isFinite() && speechVadThreshold in 0f..1f)
        require(nonSpeechVadThreshold.isFinite() && nonSpeechVadThreshold >= 0f &&
            nonSpeechVadThreshold < speechVadThreshold)
        require(dbFloor.isFinite() && dbFloor < 0.0)
        require(quietSpeechDbfs.isFinite() && quietSpeechDbfs in dbFloor..0.0)
        require(loudNoiseDbfs.isFinite() && loudNoiseDbfs in dbFloor..0.0)
        require(minimumSnrProxyDb.isFinite() && minimumSnrProxyDb >= 0.0)
        require(noiseWarmupMs > 0)
        require(noiseEmaTimeConstantMs.isFinite() && noiseEmaTimeConstantMs > 0.0)
        require(warningHoldMs > 0 && goodHoldMs > 0 && noSpeechHoldMs > 0)
        require(clippingAmplitude.isFinite() && clippingAmplitude > 0f && clippingAmplitude <= 1f)
        require(clippingSampleRatio.isFinite() && clippingSampleRatio > 0.0 && clippingSampleRatio <= 1.0)
    }
}
