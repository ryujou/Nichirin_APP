package com.example.nichirin.dsp

import kotlin.math.cos
import kotlin.math.PI

/**
 * 原地 FFT：real/imag 长度必须是 2^n
 * 迭代 Cooley–Tukey radix-2，正变换（-j 方向）
 */
object Fft {

    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n == imag.size) { "real/imag size mismatch" }
        require(n and (n - 1) == 0) { "n must be power of 2" }

        bitReversePermute(real, imag)

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val ang = (-2.0 * PI / len).toFloat()

            for (i in 0 until n step len) {
                var wr = 1.0f
                var wi = 0.0f

                val wpr = cos(ang.toDouble()).toFloat()
                val wpi = kotlin.math.sin(ang.toDouble()).toFloat()

                for (j in 0 until halfLen) {
                    val i0 = i + j
                    val i1 = i0 + halfLen

                    val tr = wr * real[i1] - wi * imag[i1]
                    val ti = wr * imag[i1] + wi * real[i1]

                    real[i1] = real[i0] - tr
                    imag[i1] = imag[i0] - ti
                    real[i0] = real[i0] + tr
                    imag[i0] = imag[i0] + ti

                    // (wr,wi) *= (wpr,wpi)
                    val nwr = wr * wpr - wi * wpi
                    val nwi = wr * wpi + wi * wpr
                    wr = nwr
                    wi = nwi
                }
            }
            len *= 2
        }
    }

    private fun bitReversePermute(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
    }
}
