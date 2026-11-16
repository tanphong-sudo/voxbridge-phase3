package com.voxbridge.core.audio

/** Same complete-frame semantics as analyzeVad; callbacks consume the reusable frame synchronously. */
class StreamingVadAnalysis(
    reset: () -> Unit,
    private val probability: (FloatArray) -> Float,
    /** Optional read-only tap; consume synchronously, never retain or change the reusable frame. */
    private val onFrame: (FloatArray, Float) -> Unit = { _, _ -> },
) {
    private val frame = FloatArray(CaptureAnalysis.VAD_CHUNK_SAMPLES)
    private var filled = 0
    private val frames = ArrayList<CaptureAnalysis.VadFrame>()
    init { reset() }
    fun append(samples: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= samples.size - count)
        var source = offset
        val end = offset + count
        while (source < end) {
            val n = minOf(frame.size - filled, end - source)
            samples.copyInto(frame, filled, source, source + n)
            filled += n
            source += n
            if (filled == frame.size) {
                val p = probability(frame)
                frames += CaptureAnalysis.VadFrame(frames.size * 32L, p, p >= CaptureAnalysis.SPEECH_THRESHOLD)
                onFrame(frame, p)
                filled = 0
            }
        }
    }
    fun summary() = CaptureAnalysis.VadSummary(
        frames.toList(), frames.count { it.speech }, frames.firstOrNull { it.speech }?.startMs,
        frames.lastOrNull { it.speech }?.let { it.startMs + 32L }, 16000, 512,
    )
}
