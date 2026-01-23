package com.example.nichirin.dsp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 读取媒体文件并输出频谱（实时节奏），不负责播放音频。
 */
class FileSpectrumEngine(
    private val context: Context,
    private val uri: Uri,
    private val fftSize: Int,
    private val hopSizeProvider: (sampleRate: Int) -> Int,
    private val processorFactory: (sampleRate: Int) -> Spectrum12Processor,
    private val onBands: (IntArray) -> Unit,
    private val onInfo: (sampleRate: Int, channelCount: Int) -> Unit = { _, _ -> },
    private val playAudio: Boolean = true,
    private val onProgress: (posUs: Long, durUs: Long) -> Unit = { _, _ -> }
) {
    private var th: Thread? = null
    private val running = AtomicBoolean(false)
    private val seekToUs = AtomicLong(-1L)
    @Volatile private var extractorRef: MediaExtractor? = null
    @Volatile private var codecRef: MediaCodec? = null
    @Volatile private var trackRef: AudioTrack? = null

    fun start(): Boolean {
        if (running.get()) return true
        running.set(true)
        th = Thread { loop() }.apply {
            name = "FileSpectrumEngine"
            priority = Thread.NORM_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running.set(false)
        seekToUs.set(-1L)
        // 尝试主动释放资源，避免切换时阻塞或状态异常
        try { trackRef?.pause() } catch (_: Exception) {}
        try { trackRef?.stop() } catch (_: Exception) {}
        try { trackRef?.flush() } catch (_: Exception) {}
        try { trackRef?.release() } catch (_: Exception) {}
        try { codecRef?.stop() } catch (_: Exception) {}
        try { codecRef?.release() } catch (_: Exception) {}
        try { extractorRef?.release() } catch (_: Exception) {}
        try { th?.interrupt() } catch (_: Exception) {}
        try { th?.join(1500) } catch (_: Exception) {}
        th = null
    }

    fun seekTo(positionUs: Long) {
        seekToUs.set(positionUs.coerceAtLeast(0L))
    }

    private fun loop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor = MediaExtractor()
            extractorRef = extractor
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            codec = MediaCodec.createDecoderByType(mime)
            codecRef = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var hopSize = max(1, hopSizeProvider(sampleRate))
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            onInfo(sampleRate, channelCount)

            if (playAudio) {
                track = buildAudioTrack(sampleRate, channelCount, pcmEncoding)
                trackRef = track
                track?.play()
            }

            val processor = processorFactory(sampleRate)

            // ring buffer
            val ring = FloatArray(fftSize)
            var ringPos = 0
            var filled = 0
            var hopCounter = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false
            var lastProgressNs = 0L

            var t0 = System.nanoTime()
            var totalFrames = 0L

            while (running.get() && !outputEOS) {
                val seekReq = seekToUs.getAndSet(-1L)
                if (seekReq >= 0L) {
                    extractor.seekTo(seekReq, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    inputEOS = false
                    outputEOS = false

                    ringPos = 0
                    filled = 0
                    hopCounter = 0

                    totalFrames = ((seekReq / 1_000_000.0) * max(1, sampleRate)).toLong()
                    t0 = System.nanoTime() - (totalFrames.toDouble() / max(1, sampleRate) * 1_000_000_000.0).toLong()

                    try {
                        track?.pause()
                        track?.flush()
                        track?.play()
                    } catch (_: Exception) {}
                }

                if (!inputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                        }

                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            if (playAudio) {
                                writeToAudioTrack(track, outBuf, bufferInfo.size, channelCount, pcmEncoding)
                                outBuf.position(bufferInfo.offset)
                            }

                            val frames = decodePcmToMonoFloat(
                                outBuf,
                                bufferInfo.size,
                                channelCount,
                                pcmEncoding
                            )

                            for (f in frames) {
                                ring[ringPos] = f
                                ringPos = (ringPos + 1) % fftSize
                                filled = min(fftSize, filled + 1)
                                hopCounter++

                                if (filled >= fftSize && hopCounter >= hopSize) {
                                    hopCounter = 0
                                    val frame = FloatArray(fftSize)
                                    var p = ringPos
                                    for (i in 0 until fftSize) {
                                        frame[i] = ring[p]
                                        p++
                                        if (p == fftSize) p = 0
                                    }
                                    onBands(processor.process(frame))
                                }
                            }

                            totalFrames += frames.size.toLong()
                            // real-time pacing
                            val expectedSec = totalFrames.toDouble() / max(1, sampleRate).toDouble()
                            val elapsedSec = (System.nanoTime() - t0) / 1_000_000_000.0
                            val sleepSec = expectedSec - elapsedSec
                            if (sleepSec > 0) {
                                Thread.sleep((sleepSec * 1000.0).toLong().coerceAtMost(20L))
                            }

                            val nowNs = System.nanoTime()
                            if (nowNs - lastProgressNs > 100_000_000L) {
                                lastProgressNs = nowNs
                                onProgress(bufferInfo.presentationTimeUs, durationUs)
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = of.getInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        hopSize = max(1, hopSizeProvider(sampleRate))
                        onInfo(sampleRate, channelCount)

                        if (playAudio) {
                            track?.stop()
                            track?.release()
                            track = buildAudioTrack(sampleRate, channelCount, pcmEncoding)
                            trackRef = track
                            track?.play()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            trackRef = null
            codecRef = null
            extractorRef = null
        }
    }

    private fun buildAudioTrack(
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int
    ): AudioTrack? {
        val ch = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, ch, pcmEncoding)
        if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) return null

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(pcmEncoding)
                    .setChannelMask(ch)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeToAudioTrack(
        track: AudioTrack?,
        buf: ByteBuffer,
        sizeBytes: Int,
        channels: Int,
        pcmEncoding: Int
    ) {
        if (track == null) return
        try {
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val total = sizeBytes / 4
                val arr = FloatArray(total)
                fb.get(arr)
                track.write(arr, 0, arr.size, AudioTrack.WRITE_BLOCKING)
            } else {
                val arr = ByteArray(sizeBytes)
                buf.get(arr)
                track.write(arr, 0, arr.size)
            }
        } catch (_: Exception) {
            // 忽略音轨写入异常，避免切换时崩溃
        }
    }

    private fun decodePcmToMonoFloat(
        buf: ByteBuffer,
        sizeBytes: Int,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val ch = max(1, channels)
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val total = sizeBytes / 4
            val frames = total / ch
            val out = FloatArray(frames)
            var idx = 0
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    sum += fb.get(idx++)
                }
                out[i] = (sum / ch).coerceIn(-1f, 1f)
            }
            out
        } else {
            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val total = sizeBytes / 2
            val frames = total / ch
            val out = FloatArray(frames)
            var idx = 0
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    sum += sb.get(idx++).toFloat() / 32768f
                }
                out[i] = (sum / ch).coerceIn(-1f, 1f)
            }
            out
        }
    }
}
