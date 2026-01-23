package com.example.nichirin.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 生成 12 个对数频带的 bin 范围（半谱 0..fftSize/2）
 */
object LogBands {

    data class BandBinRange(val startBin: Int, val endBin: Int)

    fun makeLogBands(
        sampleRate: Int,
        fftSize: Int,
        bands: Int,
        fMin: Float,
        fMax: Float
    ): Array<BandBinRange> {
        val nyquist = sampleRate / 2f
        val f0 = max(1f, fMin)
        val f1 = min(nyquist, fMax)

        val logMin = ln(f0.toDouble())
        val logMax = ln(f1.toDouble())
        val ranges = ArrayList<BandBinRange>(bands)

        fun hzToBin(hz: Float): Int {
            val bin = (hz / sampleRate * fftSize).toInt()
            return bin.coerceIn(1, fftSize / 2) // 跳过 DC
        }

        for (i in 0 until bands) {
            val t0 = i.toDouble() / bands
            val t1 = (i + 1).toDouble() / bands
            val fhz0 = kotlin.math.exp((logMin + (logMax - logMin) * t0)).toFloat()
            val fhz1 = kotlin.math.exp((logMin + (logMax - logMin) * t1)).toFloat()

            val b0 = hzToBin(fhz0)
            val b1 = hzToBin(fhz1).coerceAtLeast(b0 + 1)

            ranges.add(BandBinRange(b0, b1))
        }
        return ranges.toTypedArray()
    }
}
