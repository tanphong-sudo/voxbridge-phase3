import com.voxbridge.runtime.MelSpectrogram
import java.io.File

/**
 * DSP self-verification for MelSpectrogram — the pure-Kotlin log-mel front end.
 *
 * MelSpectrogram has NO Android dependency (java.nio + kotlin.math only), so it is verified the
 * same way as the decision core: compiled with kotlinc and asserted against deterministic DSP
 * invariants. No model, no device. This keeps the "measured, not narrated" discipline for the new
 * audio code, not just the safety logic.
 *
 * A synthetic 80x201 mel filterbank is written so the test is self-contained (the real filterbank
 * from export_whisper_onnx.py would only change the numbers, not the invariants checked here).
 */

private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name  $detail") }
}

fun main() {
    println("== MelSpectrogram DSP self-verification ==\n")

    // Synthetic triangular 80x201 filterbank (each mel bin sums a contiguous band of freq bins),
    // written as little-endian float32 [80][201] — same layout export_whisper_onnx.py emits.
    val tmp = File.createTempFile("mel_80_201", ".f32.bin")
    tmp.deleteOnExit()
    run {
        val nMels = 80; val freqBins = 201
        val buf = java.nio.ByteBuffer.allocate(nMels * freqBins * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (m in 0 until nMels) {
            val center = (m + 1) * freqBins / (nMels + 1)
            for (f in 0 until freqBins) {
                val w = (1.0f - kotlin.math.abs(f - center) / 8.0f).coerceAtLeast(0f)  // triangle, width ~8 bins
                buf.putFloat(w)
            }
        }
        tmp.writeBytes(buf.array())
    }

    val mel = MelSpectrogram(tmp)

    println("[Shape & determinism]")
    run {
        val audio = FloatArray(16000) { kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 16000).toFloat() * 0.5f }
        val out = mel.logMel(audio)
        check("output length == 80 * 3000", out.size == MelSpectrogram.N_MELS * MelSpectrogram.N_FRAMES, "got ${out.size}")
        val out2 = mel.logMel(audio)
        check("deterministic: same input -> identical output", out.contentEquals(out2))
        check("all values finite", out.all { it.isFinite() })
    }

    println("\n[Normalization range — Whisper's (x+4)/4 after (max-8) floor]")
    run {
        // After the floor at (globalMax - 8) and normalize (x+4)/4, every value lies in
        // [(max-8+4)/4, (max+4)/4] = a window of width exactly 8/4 = 2.0.
        val audio = FloatArray(16000) { kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 16000).toFloat() * 0.5f }
        val out = mel.logMel(audio)
        val lo = out.min(); val hi = out.max()
        check("dynamic range spans exactly 2.0 (the 8 dB floor / 4)", kotlin.math.abs((hi - lo) - 2.0f) < 1e-3f, "range=${hi - lo}")
    }

    println("\n[Silence -> uniform floor]")
    run {
        // All-zero audio: power ~0 -> log10(1e-10) = -10 everywhere -> globalMax = -10,
        // floor = -18, every value = max(-10,-18) = -10 -> (-10+4)/4 = -1.5. Uniform.
        val out = mel.logMel(FloatArray(16000))
        check("silence -> all bins == -1.5 (uniform)", out.all { kotlin.math.abs(it - (-1.5f)) < 1e-4f }, "min=${out.min()} max=${out.max()}")
    }

    println("\n[Pad / trim to 30 s]")
    run {
        val short = mel.logMel(FloatArray(8000))            // 0.5 s -> zero-padded
        check("short input still 80x3000 (zero-padded)", short.size == MelSpectrogram.N_MELS * MelSpectrogram.N_FRAMES)
        val long = mel.logMel(FloatArray(16000 * 40))        // 40 s -> trimmed to 30 s
        check("over-long input trimmed, still 80x3000", long.size == MelSpectrogram.N_MELS * MelSpectrogram.N_FRAMES)
    }

    println("\n[Loud vs quiet differ]")
    run {
        val quiet = mel.logMel(FloatArray(16000) { kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 16000).toFloat() * 0.01f })
        val loud = mel.logMel(FloatArray(16000) { kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 16000).toFloat() * 0.9f })
        check("a louder tone yields a different spectrogram", !quiet.contentEquals(loud))
    }

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
