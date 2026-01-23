package com.example.nichirin.dsp

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * AudioRecord 采集麦克风：
 * - sampleRate=16000
 * - fftSize=1024
 * - hopSize=160 (10ms)
 * 回调 onBands：IntArray(12) 频带 0..255
 */
class MicSpectrumEngine(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val hopSize: Int,
    private val processor: Spectrum12Processor,
    private val onBands: (IntArray) -> Unit
) {
    private var record: AudioRecord? = null
    private var th: Thread? = null
    private val running = AtomicBoolean(false)

    // 环形缓冲，累积到 fftSize 才跑一次 FFT
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var filled = 0

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running.get()) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, hopSize * 4) // 给点余量

        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (_: Exception) {
            return false
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            try { ar.release() } catch (_: Exception) {}
            return false
        }

        record = ar
        running.set(true)

        th = Thread { loop() }.apply {
            name = "MicSpectrumEngine"
            priority = Thread.NORM_PRIORITY
            start()
        }

        return true
    }

    fun stop() {
        running.set(false)
        try { th?.join(300) } catch (_: Exception) {}
        th = null

        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null

        ringPos = 0
        filled = 0
    }

    private fun loop() {
        val ar = record ?: return
        val pcm = ShortArray(hopSize)

        try {
            ar.startRecording()
        } catch (_: Exception) {
            running.set(false)
            return
        }

        while (running.get()) {
            val n = ar.read(pcm, 0, pcm.size)
            if (n <= 0) continue

            // 写入 ring
            for (i in 0 until n) {
                val f = (pcm[i].toInt() / 32768f).coerceIn(-1f, 1f)
                ring[ringPos] = f
                ringPos = (ringPos + 1) % fftSize
                filled = min(fftSize, filled + 1)
            }

            if (filled >= fftSize) {
                // 拷贝出一个连续 frame（从 ringPos 开始是最旧数据）
                val frame = FloatArray(fftSize)
                var p = ringPos
                for (i in 0 until fftSize) {
                    frame[i] = ring[p]
                    p++
                    if (p == fftSize) p = 0
                }

                val bands = processor.process(frame)
                onBands(bands)
            }
        }
    }
}
