package com.voxbridge.runtime

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Exact 400-point mixed-radix FFT (4×4×5×5), not zero-padding to a different bin spacing.
 * Scratch/twiddles are allocated once per plan. Owner must serialize calls. */
internal class RealFft400 {
    private val root = Node(400)
    fun power(input: FloatArray, output: FloatArray) {
        require(input.size == 400 && output.size == 201)
        root.transform(input, 0, 1)
        for (k in output.indices) output[k] = (root.re[k] * root.re[k] + root.im[k] * root.im[k]).toFloat()
    }

    private class Node(private val size: Int) {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        private val radix = if (size % 4 == 0) 4 else if (size % 5 == 0) 5 else 1
        private val width = size / radix
        private val children = if (size > 1) Array(radix) { Node(width) } else emptyArray()
        private val cosines = DoubleArray(size * radix) { cos(2 * PI * (it / radix) * (it % radix) / size) }
        private val sines = DoubleArray(size * radix) { sin(2 * PI * (it / radix) * (it % radix) / size) }

        fun transform(input: FloatArray, offset: Int, stride: Int) {
            if (size == 1) { re[0] = input[offset].toDouble(); im[0] = 0.0; return }
            for (r in 0 until radix) children[r].transform(input, offset + r * stride, stride * radix)
            for (k in 0 until size) {
                var real = 0.0; var imag = 0.0
                for (r in 0 until radix) {
                    val child = children[r]
                    val c = cosines[k * radix + r]; val s = sines[k * radix + r]
                    val cr = child.re[k % width]; val ci = child.im[k % width]
                    real += cr * c + ci * s
                    imag += ci * c - cr * s
                }
                re[k] = real; im[k] = imag
            }
        }
    }
}
