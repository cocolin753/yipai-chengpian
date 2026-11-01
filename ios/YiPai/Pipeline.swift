import AVFoundation
import Photos
import YiPaiCore

struct PipelineError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct PipelineResult {
    /// 成品文件（保留到下一次生成，供发布使用）
    let fileURL: URL
    let inputDuration: Double
    let plan: EditPlan
    /// 大模型给出的内容概要和关键字（没配置或失败时为空）
    var summary = ""
    var keywords: [String] = []
    var llmNote = ""
}

/// 一键成片：提取声音 → 识别 → 精剪决策 → 导出 → 存相册
enum Pipeline {
    static func run(
        input: URL,
        title: TitleSpec,
        settings: EditSettings,
        llm: LlmConfig,
        progress: @escaping (String, Float) -> Void
    ) async throws -> PipelineResult {
        let asset = AVURLAsset(url: input)
        let duration = try await asset.load(.duration).seconds

        progress("提取声音", 0)
        let pcm = try await AudioDecoder.decode(asset: asset)

        progress("识别语音（加载模型）", 0.05)
        let rawUtterances = try await Task.detached(priority: .userInitiated) {
            let asr = try SpeechRecognizer()
            return asr.recognize(pcm) { p in progress("识别语音", 0.05 + p * 0.4) }
        }.value

        var utterances = rawUtterances
        var summary = ""
        var keywords: [String] = []
        var note = ""
        if llm.enabled && !rawUtterances.isEmpty {
            progress("优化文字", 0.45)
            do {
                let r = try await refine(llm, rawUtterances)
                utterances = r.utterances
                summary = r.summary
                keywords = r.keywords
                note = "文字优化：改了 \(r.changed) 句" + (r.rejected > 0 ? "，\(r.rejected) 句字数不符已保留原文" : "")
            } catch {
                note = "文字优化失败，已用原识别结果：\(error.localizedDescription)"
            }
        }

        let plan = CutPlanner.plan(utterances, duration: duration, settings: settings)

        progress("生成视频", 0.5)
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("yipai_output.mp4")
        try? FileManager.default.removeItem(at: output)
        try await VideoRenderer.render(input: asset, output: output, plan: plan, title: title) { p in
            progress("生成视频", 0.5 + p * 0.45)
        }

        progress("保存到相册", 0.97)
        try await saveToPhotos(output)
        return PipelineResult(fileURL: output, inputDuration: duration, plan: plan,
                              summary: summary, keywords: keywords, llmNote: note)
    }

    /// 请大模型纠错：每句单独校验字数，不符的那句保留原文
    private static func refine(_ cfg: LlmConfig, _ src: [Utterance])
        async throws -> (utterances: [Utterance], summary: String, keywords: [String], changed: Int, rejected: Int) {
        let idx = src.indices.filter { !src[$0].tokens.isEmpty }
        let resp = try await DeepSeek.refine(cfg, lines: idx.map { TranscriptAligner.lineText(src[$0]) })
        var out = src
        var changed = 0
        var rejected = 0
        for (k, i) in idx.enumerated() {
            guard let text = resp.lines[k] else { continue }
            if let fixed = TranscriptAligner.apply(src[i], refined: text) {
                if fixed.tokens != src[i].tokens { changed += 1 }
                out[i] = fixed
            } else {
                rejected += 1
            }
        }
        return (out, resp.summary, resp.keywords, changed, rejected)
    }

    private static func saveToPhotos(_ url: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw PipelineError("没有相册权限：请在 设置 → 一拍成片 → 照片 中允许添加")
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: url)
        }
    }
}

/// 把视频里的声音解码成 16kHz 单声道 float PCM（由系统完成重采样）
enum AudioDecoder {
    static func decode(asset: AVAsset) async throws -> [Float] {
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { return [] }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: SpeechRecognizer.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        reader.add(output)
        guard reader.startReading() else { throw reader.error ?? PipelineError("无法读取音频") }

        var samples: [Float] = []
        while let buffer = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            var chunk = [Float](repeating: 0, count: length / MemoryLayout<Float>.size)
            chunk.withUnsafeMutableBytes { ptr in
                _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: ptr.baseAddress!)
            }
            samples.append(contentsOf: chunk)
        }
        if reader.status == .failed { throw reader.error ?? PipelineError("音频解码失败") }
        return samples
    }
}
