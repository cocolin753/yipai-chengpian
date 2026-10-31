import Foundation
import SherpaOnnx
import YiPaiCore

/// 离线语音识别：Silero VAD 切出语音段，SenseVoice 逐段识别，输出带字级时间戳的结果。
/// 与安卓版 asr/SpeechRecognizer.kt 逻辑一致。模型文件打包在 App 里。
final class SpeechRecognizer {
    static let sampleRate = 16_000
    private static let vadWindow = 512

    private let recognizer: SherpaOnnxOfflineRecognizer
    private let vadModel: String

    init() throws {
        guard let model = Bundle.main.path(forResource: "model.int8", ofType: "onnx"),
              let tokens = Bundle.main.path(forResource: "tokens", ofType: "txt"),
              let vad = Bundle.main.path(forResource: "silero_vad", ofType: "onnx") else {
            throw PipelineError("识别模型缺失，请重新安装 App")
        }
        vadModel = vad
        let senseVoice = sherpaOnnxOfflineSenseVoiceModelConfig(
            model: model, language: "zh", useInverseTextNormalization: true)
        let modelConfig = sherpaOnnxOfflineModelConfig(tokens: tokens, numThreads: 4, senseVoice: senseVoice)
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: Self.sampleRate, featureDim: 80),
            modelConfig: modelConfig)
        recognizer = SherpaOnnxOfflineRecognizer(config: &config)
    }

    func recognize(_ samples: [Float], progress: (Float) -> Void) -> [Utterance] {
        let silero = sherpaOnnxSileroVadModelConfig(
            model: vadModel, threshold: 0.5, minSilenceDuration: 0.25,
            minSpeechDuration: 0.1, windowSize: Self.vadWindow, maxSpeechDuration: 20)
        var vadConfig = sherpaOnnxVadModelConfig(sileroVad: silero, sampleRate: Int32(Self.sampleRate), numThreads: 1)
        let vad = SherpaOnnxVoiceActivityDetectorWrapper(config: &vadConfig, buffer_size_in_seconds: 60)

        var result: [Utterance] = []
        func drain() {
            while !vad.isEmpty() {
                let seg = vad.front()
                vad.pop()
                if let u = decode(seg.samples, offset: Double(seg.start) / Double(Self.sampleRate)) {
                    result.append(u)
                }
            }
        }
        var i = 0
        while i + Self.vadWindow <= samples.count {
            vad.acceptWaveform(samples: Array(samples[i..<(i + Self.vadWindow)]))
            i += Self.vadWindow
            drain()
            if i % (Self.vadWindow * 100) == 0 { progress(Float(i) / Float(samples.count)) }
        }
        vad.flush()
        drain()
        progress(1)
        return result
    }

    private func decode(_ samples: [Float], offset: Double) -> Utterance? {
        let stream = recognizer.createStream()
        stream.acceptWaveform(samples: samples, sampleRate: Self.sampleRate)
        recognizer.decode(stream: stream)
        let r = recognizer.getResult(stream: stream)

        // Swift 封装没有暴露逐字 tokens，直接读 C 结构体
        let count = Int(r.result.pointee.count)
        var rawTokens: [String] = []
        if let arr = r.result.pointee.tokens_arr {
            for k in 0..<count {
                rawTokens.append(arr[k].map { String(cString: $0) } ?? "")
            }
        }
        let timestamps = r.timestamps
        let n = min(rawTokens.count, timestamps.count)
        let segEnd = offset + Double(samples.count) / Double(Self.sampleRate)

        // 先收集（文字, 开始时间），标点挂到前一个字上
        var texts: [String] = []
        var starts: [Double] = []
        var puncts: [String] = []
        for k in 0..<n {
            let raw = rawTokens[k]
            if raw.trimmingCharacters(in: .whitespaces).isEmpty || raw.hasPrefix("<|") { continue }
            if raw.unicodeScalars.allSatisfy({ CharacterSet.punctuationCharacters.contains($0) }) {
                if !puncts.isEmpty { puncts[puncts.count - 1] += raw }
                continue
            }
            texts.append(raw.replacingOccurrences(of: "▁", with: " "))
            starts.append(offset + Double(timestamps[k]))
            puncts.append("")
        }
        if texts.isEmpty { return nil }

        // 段内语音是连续的：每个字结束于下一个字开始，最后一个字结束于段尾
        let tokens = texts.indices.map { k in
            let end = k + 1 < texts.count ? starts[k + 1] : segEnd
            return Token(text: texts[k], start: starts[k], end: max(end, starts[k]), punctAfter: puncts[k])
        }
        return Utterance(start: offset, end: segEnd, tokens: tokens)
    }
}
