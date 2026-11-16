package com.voxbridge.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class RealFft400Test {
    @Test fun matchesIndependentDftForSilenceImpulseToneNoiseAndReuse() {
        val random = Random(42)
        val fft = RealFft400()
        val cases = listOf(FloatArray(400), FloatArray(400) { if (it == 199) 1f else 0f },
            FloatArray(400) { sin(2 * PI * 37 * it / 400).toFloat() },
            FloatArray(400) { random.nextFloat() * 2 - 1 })
        for (input in cases + cases.reversed()) {
            val actual = FloatArray(201)
            fft.power(input, actual)
            for (k in 0..200) {
                var real = 0.0; var imag = 0.0
                for (n in input.indices) {
                    real += input[n] * cos(2 * PI * k * n / 400)
                    imag -= input[n] * sin(2 * PI * k * n / 400)
                }
                val expected = real * real + imag * imag
                assertEquals("bin $k", expected, actual[k].toDouble(), max(1e-5, expected * 2e-6))
            }
        }
    }
}
