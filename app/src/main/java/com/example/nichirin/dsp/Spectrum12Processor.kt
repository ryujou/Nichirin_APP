package com.example.nichirin.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 输入：一帧 PCM（float, [-1,1]），长度=fftSize
 * 输出：12 频带强度（0..255）
 *
 * Floor（dB）解释：
 *  - 先把每个频带的平均幅度转为 dB：db = 20*log10(mag + eps)
 *  - 再做一个“显示偏移/灵敏度偏移”：v = max(0, db + floorDb)
 *    floorDb 越大，整体越容易“抬起来”（更敏感）
 *  - 最后用固定量程 rangeDb 做归一化，避免每帧都拉满
 */
class Spectrum12Processor(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val bands: Int,
    private val fMin: Float,
    private val fMax: Float,
    private var floorDb: Float = 27f,
    private var rangeDb: Float = 60f
) {
    // 允许外部动态设置（MainActivity 可直接调用，或用你之前的反射也能命中）
    fun setFloorDb(db: Float) {
        floorDb = db
    }

    fun setRangeDb(db: Float) {
        rangeDb = db.coerceAtLeast(1f)
    }

    private val window = FloatArray(fftSize) { i ->
        val x = (2.0 * Math.PI * i / (fftSize - 1)).toFloat()
        (0.5f - 0.5f * kotlin.math.cos(x))
    }

    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)

    private val bandRanges = LogBands.makeLogBands(sampleRate, fftSize, bands, fMin, fMax)

    // 简单平滑（避免抖动）
    private val smooth = FloatArray(bands) { 0f }

    fun process(frame: FloatArray): IntArray {
        require(frame.size == fftSize) { "frame size must be fftSize=$fftSize" }

        // window + copy
        for (i in 0 until fftSize) {
            real[i] = frame[i] * window[i]
            imag[i] = 0f
        }

        // FFT
        Fft.fft(real, imag)

        // half spectrum magnitude
        val mags = FloatArray(fftSize / 2 + 1)
        for (k in 1 until mags.size) {
            val r = real[k]
            val im = imag[k]
            mags[k] = sqrt(r * r + im * im)
        }

        // band energy (avg magnitude -> dB -> +floor -> smoothing -> normalize)
        val out = IntArray(bands)

        // 数值稳定项，避免 log10(0)
        val eps = 1e-12f

        for (i in 0 until bands) {
            val r = bandRanges[i]
            var sum = 0f
            var cnt = 0
            for (k in r.startBin until r.endBin) {
                sum += mags[k]
                cnt++
            }
            val avgMag = if (cnt > 0) sum / cnt else 0f

            // dB 映射（未做严格 dBFS 标定，作为显示/控制量足够稳定）
            val db = 20f * log10(avgMag + eps)

            // floor 偏移 + 截断：保证非负，便于后续 0..255 映射
            val v = max(0f, db + floorDb)

            // smoothing
            val a = 0.35f
            smooth[i] = smooth[i] * (1f - a) + v * a

        }

        // 固定量程归一化到 0..255
        val inv = 255f / rangeDb
        for (i in 0 until bands) {
            val n = smooth[i] * inv
            out[i] = n.toInt().coerceIn(0, 255)
        }
        return out
    }
}
