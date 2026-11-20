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
        script: String,
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
        var notes: [String] = []
        // 对上提词稿的句子以提词稿为准，大模型不再改
        var locked = Set<Int>()
        if !script.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !rawUtterances.isEmpty {
            progress("按提词稿校对", 0.45)
            let r = ScriptAligner.apply(rawUtterances, script: script)
            utterances = r.utterances
            locked = r.matched
            let total = rawUtterances.filter { !$0.tokens.isEmpty }.count
            notes.append(r.tooLong ? "提词稿太长，未按提词稿校对"
                         : "提词稿校对：\(r.matched.count)/\(total) 句对上提词稿，改了 \(r.changed) 句")
        }
        if llm.enabled && !rawUtterances.isEmpty {
            progress("优化文字", 0.46)
            let r = await refine(llm, utterances, locked: locked)
            utterances = r.utterances
            summary = r.summary
            keywords = r.keywords
            notes += r.notes
        }
        let note = notes.joined(separator: "\n")

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

    /// 请大模型纠错并生成概要：
    /// - 对上提词稿的句子（locked）不发去纠错；全对上时只生成概要
    /// - 其余句子每批不超过 1000 字，分批并发；全文作为上下文
    /// - 每句单独对齐，增删字太多的那句保留原文；某批失败只影响那一批
    private static func refine(_ cfg: LlmConfig, _ src: [Utterance], locked: Set<Int>)
        async -> (utterances: [Utterance], summary: String, keywords: [String], notes: [String]) {
        let texts = src.map { $0.tokens.isEmpty ? "" : TranscriptAligner.lineText($0) }
        let full = texts.filter { !$0.isEmpty }.joined(separator: "\n")
        let batches = LlmPlanner.batches(texts, locked: locked)
        let context: String? = (batches.count > 1 || !locked.isEmpty) ? full : nil

        async let summaryTask = summarizeResult(cfg, text: full)
        let results: [Int: Result<[String?], Error>] = await withTaskGroup(of: (Int, Result<[String?], Error>).self) { group in
            for (n, b) in batches.enumerated() {
                let lines = b.map { texts[$0] }
                group.addTask {
                    do { return (n, .success(try await DeepSeek.correct(cfg, lines: lines, context: context))) }
                    catch { return (n, .failure(error)) }
                }
            }
            var all: [Int: Result<[String?], Error>] = [:]
            for await (n, r) in group { all[n] = r }
            return all
        }

        var out = src
        var changed = 0
        var rejected = 0
        var failed = 0
        var firstError: String?
        for (n, b) in batches.enumerated() {
            guard case let .success(lines)? = results[n] else {
                failed += 1
                if firstError == nil, case let .failure(e)? = results[n] { firstError = e.localizedDescription }
                continue
            }
            for (k, i) in b.enumerated() {
                guard let text = lines[k] else { continue }
                if let fixed = TranscriptAligner.apply(src[i], refined: text) {
                    if fixed.tokens != src[i].tokens { changed += 1 }
                    out[i] = fixed
                } else {
                    rejected += 1
                }
            }
        }

        var notes: [String] = []
        let sent = batches.reduce(0) { $0 + $1.count }
        if batches.isEmpty {
            notes.append("文字优化：句子都已按提词稿校对，只生成概要")
        } else if failed == batches.count {
            notes.append("文字优化失败，已用\(locked.isEmpty ? "原识别" : "提词稿校对")结果：\(firstError ?? "")")
        } else {
            var line = "文字优化：校对 \(sent) 句"
            if batches.count > 1 { line += "（分 \(batches.count) 批）" }
            line += "，改了 \(changed) 句"
            if rejected > 0 { line += "，\(rejected) 句改动太大已保留原文" }
            if failed > 0 { line += "，\(failed) 批失败已保留原文：\(firstError ?? "")" }
            notes.append(line)
        }
        var summary = ""
        var keywords: [String] = []
        switch await summaryTask {
        case let .success(s): summary = s.summary; keywords = s.keywords
        case let .failure(e): notes.append("概要生成失败：\(e.localizedDescription)")
        }
        return (out, summary, keywords, notes)
    }

    private static func summarizeResult(_ cfg: LlmConfig, text: String) async -> Result<SummaryResponse, Error> {
        do {
            return .success(try await DeepSeek.summarize(cfg, text: text))
        } catch {
            return .failure(error)
        }
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
