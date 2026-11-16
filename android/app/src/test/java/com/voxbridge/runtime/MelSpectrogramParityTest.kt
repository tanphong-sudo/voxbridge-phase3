package com.voxbridge.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class MelSpectrogramParityTest {
    @Test fun fftMelMatchesLegacyFloatDftIncludingReflectedBoundaryAndPadding() {
        val filters = Array(80) { m -> FloatArray(201) { k -> max(0f, 1f - abs(k - (m * 2 + 2)) / 4f) } }
        val file = Files.createTempFile("mel-test", ".bin").toFile()
        try {
            val bytes = ByteBuffer.allocate(80 * 201 * 4).order(ByteOrder.LITTLE_ENDIAN)
            filters.forEach { row -> row.forEach { bytes.putFloat(it) } }
            file.writeBytes(bytes.array())
            val mel = MelSpectrogram(file)
            val random = Random(9)
            val signals = listOf(FloatArray(0), FloatArray(1600),
                FloatArray(1600) { if (it == 0 || it == 1599) 1f else 0f },
                FloatArray(1600) { 0.5f * sin(2 * PI * 437 * it / 16000).toFloat() },
                FloatArray(1600) { (random.nextFloat() - 0.5f) * 0.001f })
            signals.forEach { signal ->
                val actual = mel.logMel(signal)
                val expected = legacy(signal, filters)
                for (i in actual.indices) assertEquals("mel index $i", expected[i], actual[i], 0.0002f)
            }
        } finally { file.delete() }
    }

    private fun legacy(audio: FloatArray, filters: Array<FloatArray>): FloatArray {
        val padded = audio.copyOf(480000)
        val frames = ceil((audio.size + 200.0) / 160).toInt()
        val out = FloatArray(80 * 3000) { -10f }
        val cosines = Array(201) { k -> FloatArray(400) { n -> cos(2 * PI * k * n / 400).toFloat() } }
        val sines = Array(201) { k -> FloatArray(400) { n -> sin(2 * PI * k * n / 400).toFloat() } }
        for (t in 0 until frames) {
            val frame = FloatArray(400) { n ->
                val i = t * 160 - 200 + n
                padded[abs(i)] * (0.5 - 0.5 * cos(2 * PI * n / 400)).toFloat()
            }
            val power = FloatArray(201) { k ->
                var re = 0f; var im = 0f
                for (n in 0 until 400) { re += frame[n] * cosines[k][n]; im -= frame[n] * sines[k][n] }
                re * re + im * im
            }
            for (m in 0 until 80) {
                var sum = 0f
                for (k in 0..200) sum += filters[m][k] * power[k]
                out[m * 3000 + t] = (ln(max(sum, 1e-10f)) / ln(10.0)).toFloat()
            }
        }
        val floor = out.max() - 8f
        return FloatArray(out.size) { (max(out[it], floor) + 4f) / 4f }
    }
}
