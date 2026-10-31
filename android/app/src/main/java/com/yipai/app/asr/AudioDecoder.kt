package com.yipai.app.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 把视频里的声音解码成 16kHz 单声道 float PCM（语音识别的输入格式）。 */
object AudioDecoder {
    const val TARGET_RATE = 16000

    fun decode(path: String, onProgress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return FloatArray(0) }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = Resampler(sampleRate, TARGET_RATE)
        val out = FloatBuffer(((durationUs / 1e6 + 1) * TARGET_RATE).toInt())

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != sampleRate) {
                        sampleRate = rate
                        resampler = Resampler(sampleRate, TARGET_RATE)
                    }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = buf.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += fb.get() }
                            resampler.push(sum / channels, out)
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += sb.get() / 32768f }
                            resampler.push(sum / channels, out)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return out.toArray()
    }

    /** 可增长的 float 数组 */
    class FloatBuffer(initial: Int) {
        private var data = FloatArray(maxOf(initial, 1024))
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** 低通滤波（防混叠）+ 线性插值重采样，流式处理，省内存。 */
    private class Resampler(private val fromRate: Int, private val toRate: Int) {
        private val step = fromRate.toDouble() / toRate
        private var pos = 0.0 // 下一个输出样本在输入序列中的位置
        private var index = 0L
        private var prev = 0f

        // RBJ 二阶低通，截止 7kHz
        private val b0: Float; private val b1: Float; private val b2: Float
        private val a1: Float; private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        init {
            val w0 = 2 * PI * minOf(7000.0, fromRate * 0.45) / fromRate
            val alpha = sin(w0) / (2 * sqrt(0.5))
            val a0 = 1 + alpha
            b0 = ((1 - cos(w0)) / 2 / a0).toFloat()
            b1 = ((1 - cos(w0)) / a0).toFloat()
            b2 = b0
            a1 = (-2 * cos(w0) / a0).toFloat()
            a2 = ((1 - alpha) / a0).toFloat()
        }

        fun push(sample: Float, out: FloatBuffer) {
            val x = if (fromRate == toRate) sample else filter(sample)
            if (fromRate == toRate) { out.add(x); return }
            while (pos <= index) {
                val frac = (pos - (index - 1)).toFloat()
                out.add(prev + (x - prev) * frac)
                pos += step
            }
            prev = x
            index++
        }

        private fun filter(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
}
