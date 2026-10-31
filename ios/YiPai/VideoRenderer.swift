import AVFoundation
import UIKit
import YiPaiCore

/// 标题：文字或 PNG 图片（图片优先）
struct TitleSpec {
    var text: String
    var imageURL: URL?
}

/// 版式参数，与安卓版 CaptionPainter 一致；拍摄预览也用这些值
enum CaptionLayout {
    /// 标题顶部位置（占画面高度比例）
    static let titleTop: CGFloat = 0.08
    static let titleMaxHeight: CGFloat = 0.2
    static let subtitleCenter: CGFloat = 0.8
}

/// 用 AVFoundation 一次导出：裁剪拼接 + 变速（不变调）+ 标题 + 字幕 + 硬件编码
enum VideoRenderer {
    static func render(
        input asset: AVAsset,
        output: URL,
        plan: EditPlan,
        title: TitleSpec,
        progress: @escaping (Float) -> Void
    ) async throws {
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw PipelineError("视频没有画面")
        }
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first
        let (naturalSize, transform, trackRange) = try await videoTrack.load(.naturalSize, .preferredTransform, .timeRange)

        // 1. 按保留片段拼接
        let composition = AVMutableComposition()
        guard let compVideo = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw PipelineError("无法创建视频轨道")
        }
        let compAudio = audioTrack == nil ? nil :
            composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        var cursor = CMTime.zero
        let scale: CMTimeScale = 600
        for r in plan.keep {
            let start = CMTime(seconds: r.start, preferredTimescale: scale)
            let end = CMTimeMinimum(CMTime(seconds: r.end, preferredTimescale: scale), trackRange.end)
            guard end > start else { continue }
            let range = CMTimeRange(start: start, end: end)
            try compVideo.insertTimeRange(range, of: videoTrack, at: cursor)
            if let audioTrack, let compAudio {
                try? compAudio.insertTimeRange(range, of: audioTrack, at: cursor)
            }
            cursor = cursor + range.duration
        }

        // 2. 整体变速（导出时用 spectral 算法保持音调）
        if abs(plan.speed - 1) > 0.01 {
            let full = CMTimeRange(start: .zero, duration: composition.duration)
            composition.scaleTimeRange(full, toDuration: CMTimeMultiplyByFloat64(composition.duration, multiplier: 1 / plan.speed))
        }

        // 3. 画面摆正（手机竖拍的视频带旋转标记）
        let rotated = CGRect(origin: .zero, size: naturalSize).applying(transform)
        let renderSize = CGSize(width: abs(rotated.width), height: abs(rotated.height))
        let fixTransform = transform.concatenating(CGAffineTransform(translationX: -rotated.minX, y: -rotated.minY))

        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compVideo)
        layerInstruction.setTransform(fixTransform, at: .zero)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: composition.duration)
        instruction.layerInstructions = [layerInstruction]

        let videoComposition = AVMutableVideoComposition()
        videoComposition.renderSize = renderSize
        videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
        videoComposition.instructions = [instruction]

        // 4. 标题 + 字幕叠加层
        let parent = CALayer()
        parent.frame = CGRect(origin: .zero, size: renderSize)
        let videoLayer = CALayer()
        videoLayer.frame = parent.frame
        parent.addSublayer(videoLayer)
        if let titleLayer = CaptionRenderer.titleLayer(title, size: renderSize) {
            parent.addSublayer(titleLayer)
        }
        for line in plan.subtitles {
            parent.addSublayer(CaptionRenderer.subtitleLayer(line, size: renderSize))
        }
        videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
            postProcessingAsVideoLayer: videoLayer, in: parent)

        // 5. 导出
        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw PipelineError("无法创建导出任务")
        }
        export.outputURL = output
        export.outputFileType = .mp4
        export.videoComposition = videoComposition
        export.audioTimePitchAlgorithm = .spectral
        export.shouldOptimizeForNetworkUse = true

        let poll = Task {
            while !Task.isCancelled {
                progress(export.progress)
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
        defer { poll.cancel() }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { cont.resume() }
        }
        if export.status != .completed {
            throw export.error ?? PipelineError("导出失败")
        }
    }
}

/// 画标题和字幕。Core Animation 坐标原点在左下角，所以 y 要倒过来算。
enum CaptionRenderer {
    static func titleLayer(_ title: TitleSpec, size: CGSize) -> CALayer? {
        let top = size.height * CaptionLayout.titleTop
        if let url = title.imageURL, let image = UIImage(contentsOfFile: url.path), let cg = image.cgImage {
            let maxW = size.width * 0.9
            let maxH = size.height * CaptionLayout.titleMaxHeight
            let s = min(maxW / image.size.width, maxH / image.size.height)
            let w = image.size.width * s
            let h = image.size.height * s
            let layer = CALayer()
            layer.contents = cg
            layer.contentsGravity = .resizeAspect
            layer.frame = CGRect(x: (size.width - w) / 2, y: size.height - top - h, width: w, height: h)
            return layer
        }
        let text = title.text.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return nil }

        // 文字标题先画成图片（半透明圆角底 + 白字，最多两行）
        let fontSize = size.width * 0.068
        let font = UIFont.boldSystemFont(ofSize: fontSize)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byTruncatingTail
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white, .paragraphStyle: paragraph]
        let maxTextW = size.width * 0.84
        let maxTextH = font.lineHeight * 2.2
        var textRect = (text as NSString).boundingRect(
            with: CGSize(width: maxTextW, height: maxTextH),
            options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: attrs, context: nil)
        textRect.size = CGSize(width: ceil(min(textRect.width, maxTextW)), height: ceil(min(textRect.height, maxTextH)))
        let padX = fontSize * 0.5
        let padY = fontSize * 0.3
        let boxSize = CGSize(width: textRect.width + padX * 2, height: textRect.height + padY * 2)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: boxSize, format: format).image { _ in
            UIColor(white: 0, alpha: 0.45).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: boxSize), cornerRadius: fontSize * 0.3).fill()
            (text as NSString).draw(
                with: CGRect(x: padX, y: padY, width: textRect.width, height: textRect.height),
                options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: attrs, context: nil)
        }
        let layer = CALayer()
        layer.contents = image.cgImage
        layer.frame = CGRect(
            x: (size.width - boxSize.width) / 2, y: size.height - top - boxSize.height + padY,
            width: boxSize.width, height: boxSize.height)
        return layer
    }

    /// 每行字幕一个文字层，只在自己的时间段内显示
    static func subtitleLayer(_ line: SubtitleLine, size: CGSize) -> CALayer {
        let fontSize = size.width * 0.058
        let font = UIFont.boldSystemFont(ofSize: fontSize)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let text = NSAttributedString(string: line.text, attributes: [
            .font: font,
            .foregroundColor: UIColor.white,
            .strokeColor: UIColor.black,
            .strokeWidth: -4.0, // 负值 = 描边 + 填充
            .paragraphStyle: paragraph,
        ])
        let width = size.width * 0.9
        let height = font.lineHeight * 2.4
        let layer = CATextLayer()
        layer.string = text
        layer.isWrapped = true
        layer.alignmentMode = .center
        layer.contentsScale = 1
        layer.frame = CGRect(
            x: (size.width - width) / 2,
            y: size.height * (1 - CaptionLayout.subtitleCenter) - font.lineHeight / 2 - (height - font.lineHeight),
            width: width, height: height)
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.8
        layer.shadowRadius = 2
        layer.shadowOffset = .zero
        layer.opacity = 0

        let show = CABasicAnimation(keyPath: "opacity")
        show.fromValue = 1
        show.toValue = 1
        show.beginTime = AVCoreAnimationBeginTimeAtZero + line.start
        show.duration = max(line.end - line.start, 0.05)
        show.isRemovedOnCompletion = false
        show.fillMode = .removed
        layer.add(show, forKey: "show")
        return layer
    }
}
