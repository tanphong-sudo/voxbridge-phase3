package com.voxbridge.runtime

import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Host-side log-mel spectrogram following OpenAI Whisper's audio preprocessing.
 *
 * This is PURE, deterministic DSP — no model artifact is needed to make it correct, which is why
 * it is fully implemented here (not a seam). The Whisper encoder is trained on exactly this
 * 80x3000 log-mel; any deviation degrades accuracy, so the steps below mirror
 * `whisper/audio.py::log_mel_spectrogram`:
 *
 *   1. pad/trim the waveform to 30 s (480000 samples @ 16 kHz)
 *   2. STFT: n_fft=400, hop=160, Hann window, center=True (reflect-pad n_fft/2 each side); power
 *      spectrum |.|^2 over 201 bins (drop the final time frame, as Whisper does -> 3000 frames)
 *   3. apply the 80x201 mel filterbank (loaded from mel_80_201.f32.bin, written by
 *      export_whisper_onnx.py from Whisper's own filters)
 *   4. log10, clamp to 1e-10, floor at (max - 8.0), then (x + 4) / 4
 *
 * VERIFICATION STATUS (honest): tools/dsp-verify asserts the structural invariants (shape,
 * determinism, the (x+4)/4 range, silence->uniform floor, pad/trim) against a synthetic filterbank
 * — it does NOT yet assert numeric parity against real `whisper.log_mel_spectrogram` reference
 * vectors. So this is "implements Whisper's documented algorithm and passes its invariants", not a
 * proven bit-for-bit match. A parity test (max-abs-diff vs cached Whisper output on real WAVs) is
 * the remaining step to earn that stronger claim; see MODEL_MANIFEST §2.
 *
 * The CPU fallback path uses this. The QNN-NPU path may fold mel into the compiled graph; when it
 * does not, this same matrix feeds it. The mixed-radix FFT keeps exactly 400 points/201 bins.
 * Its power output is tested against an independent direct DFT; this does not claim device WER parity.
 */
class MelSpectrogram(melFilterFile: File) {

    private val melFilters: Array<FloatArray>  // [N_MELS][FREQ_BINS]
    private val fft = RealFft400()
    private val filterStart: IntArray
    private val filterEnd: IntArray

    init {
        val bytes = melFilterFile.readBytes()
        require(bytes.size == N_MELS * FREQ_BINS * 4) {
            "mel filter file ${melFilterFile.name} is ${bytes.size} bytes; expected " +
                "${N_MELS * FREQ_BINS * 4} (80x201 little-endian float32). Re-run export_whisper_onnx.py."
        }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        melFilters = Array(N_MELS) { FloatArray(FREQ_BINS) { buf.float } }
        // Whisper's triangular filters are sparse. Keep the original summation order, but
        // avoid multiplying the zero-only tails of each band for every frame.
        filterStart = IntArray(N_MELS) { m -> melFilters[m].indexOfFirst { it != 0f }.let { if (it < 0) 0 else it } }
        filterEnd = IntArray(N_MELS) { m -> melFilters[m].indexOfLast { it != 0f } + 1 }
    }

    /** @param audio mono 16 kHz, normalized [-1,1]. @return flat [N_MELS * N_FRAMES] row-major. */
    @Synchronized
    fun logMel(audio: FloatArray): FloatArray {
        val padded = FloatArray(N_SAMPLES)
        System.arraycopy(audio, 0, padded, 0, minOf(audio.size, N_SAMPLES))
        // (samples beyond 30 s are trimmed; shorter audio is zero-padded — exactly Whisper)

        // Every frame whose 400-sample window starts beyond the real signal sees only zero-pad
        // samples (or reflections of them): its power spectrum is all zeros and its log-mel is
        // one bin-independent constant. Compute the FFT only up to that boundary and constant-fill
        // the trailing frames — bit-identical output, but a 1–3 s PTT utterance costs a few percent
        // of the full 30 s FFT instead of 100%. (A full 30 s clip fills every frame: no change.)
        val firstSilent = if (audio.size >= N_SAMPLES) N_FRAMES
        else ceil((audio.size + N_FFT / 2.0) / HOP).toInt().coerceIn(0, N_FRAMES)
        val power = powerSpectrogram(padded, firstSilent)  // [firstSilent][FREQ_BINS]
        val out = FloatArray(N_MELS * N_FRAMES)
        var globalMax = Float.NEGATIVE_INFINITY

        // mel projection + log10, tracking the global max for the (max - 8) floor.
        for (m in 0 until N_MELS) {
            val filt = melFilters[m]
            val rowBase = m * N_FRAMES
            for (t in 0 until firstSilent) {
                val spec = power[t]
                var acc = 0f
                for (f in filterStart[m] until filterEnd[m]) acc += filt[f] * spec[f]
                val logv = (ln(max(acc, 1e-10f)) / LN10).toFloat()
                out[rowBase + t] = logv
                if (logv > globalMax) globalMax = logv
            }
            for (t in firstSilent until N_FRAMES) out[rowBase + t] = SILENT_LOGV
        }
        // floor at (max - 8), then normalize (x + 4) / 4.
        val floor = globalMax - 8f
        for (i in out.indices) {
            val v = max(out[i], floor)
            out[i] = (v + 4f) / 4f
        }
        return out
    }

    /** STFT power |.|^2 via a 400-point FFT of each Hann-windowed frame; drops the last frame.
     *  @param frames number of leading frames to compute (the caller constant-fills the rest). */
    private fun powerSpectrogram(audio: FloatArray, frames: Int): Array<FloatArray> {
        val spec = Array(frames) { FloatArray(FREQ_BINS) }
        val windowed = FloatArray(N_FFT)
        val pad = N_FFT / 2  // 200 — Whisper uses torch.stft(center=True): reflect-pad both sides
        for (t in 0 until frames) {
            // Center frame t on sample t*HOP (center=True). The window therefore starts at
            // t*HOP - n_fft/2; samples outside [0,len) are taken by REFLECTION, exactly as
            // torch.stft pads. Without this every frame was offset by 200 samples (12.5 ms) and the
            // leading frames lacked Whisper's reflected context — a constant time-shift into an
            // encoder with absolute positional encodings, not a negligible edge effect.
            val start = t * HOP - pad
            for (n in 0 until N_FFT) {
                windowed[n] = reflectSample(audio, start + n) * hann[n]
            }
            val row = spec[t]
            fft.power(windowed, row)
        }
        return spec
    }

    /** torch 'reflect' padding: index reflects about the edges without repeating them (period 2(L-1)). */
    private fun reflectSample(audio: FloatArray, i: Int): Float {
        val len = audio.size
        if (len == 0) return 0f
        if (len == 1) return audio[0]
        val period = 2 * (len - 1)
        var idx = ((i % period) + period) % period
        if (idx >= len) idx = period - idx
        return audio[idx]
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80
        const val FREQ_BINS = N_FFT / 2 + 1   // 201
        const val N_SAMPLES = 480000          // 30 s
        const val N_FRAMES = 3000             // N_SAMPLES / HOP, last frame dropped
        private const val LN10 = 2.302585092994046
        // The log-mel of an all-zero frame: log10(clamp(0, 1e-10)) — expressed exactly the way the
        // compute loop produces it (Float ln, Double divide, Float round), so the constant-fill
        // below is bit-identical to having DFT'd the silent frames.
        private val SILENT_LOGV = (ln(1e-10f) / LN10).toFloat()

        private val hann = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    }
}
