package com.yipai.app.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.yipai.app.core.Token
import com.yipai.app.core.Utterance

/**
 * 离线语音识别：Silero VAD 切出语音段，SenseVoice 逐段识别，输出带字级时间戳的结果。
 * 模型放在 assets/models 下。
 */
class SpeechRecognizer(private val assets: AssetManager) : AutoCloseable {
    private val rate = AudioDecoder.TARGET_RATE

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "models/model.int8.onnx",
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = "models/tokens.txt",
                numThreads = 4,
            ),
        ),
    )

    private fun newVad() = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.1f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 20f,
            ),
            sampleRate = rate,
            numThreads = 1,
        ),
    )

    fun recognize(samples: FloatArray, onProgress: (Float) -> Unit = {}): List<Utterance> {
        val vad = newVad()
        val result = mutableListOf<Utterance>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                decode(seg.samples, seg.start.toDouble() / rate)?.let { result += it }
            }
        }
        try {
            var i = 0
            while (i + VAD_WINDOW <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(i, i + VAD_WINDOW))
                i += VAD_WINDOW
                drain()
                if (i % (VAD_WINDOW * 100) == 0) onProgress(i.toFloat() / samples.size)
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        onProgress(1f)
        return result
    }

    private fun decode(samples: FloatArray, offset: Double): Utterance? {
        val stream = recognizer.createStream()
        val r = try {
            stream.acceptWaveform(samples, rate)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
        val segEnd = offset + samples.size.toDouble() / rate
        val n = minOf(r.tokens.size, r.timestamps.size)

        // 先收集（文字, 开始时间），标点挂到前一个字上
        val texts = mutableListOf<String>()
        val starts = mutableListOf<Double>()
        val puncts = mutableListOf<StringBuilder>()
        for (k in 0 until n) {
            val raw = r.tokens[k]
            if (raw.isBlank() || raw.startsWith("<|")) continue
            if (PUNCT.matches(raw)) {
                puncts.lastOrNull()?.append(raw)
                continue
            }
            texts += raw.replace('▁', ' ')
            starts += offset + r.timestamps[k]
            puncts += StringBuilder()
        }
        if (texts.isEmpty()) return null

        // 段内语音是连续的：每个字结束于下一个字开始，最后一个字结束于段尾
        val tokens = texts.indices.map { k ->
            val end = if (k + 1 < texts.size) starts[k + 1] else segEnd
            Token(texts[k], starts[k], maxOf(end, starts[k]), puncts[k].toString())
        }
        return Utterance(offset, segEnd, tokens)
    }

    override fun close() = recognizer.release()

    private companion object {
        const val VAD_WINDOW = 512
        val PUNCT = Regex("^[\\p{P}]+$")
    }
}
