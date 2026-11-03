import SwiftUI
import UIKit
import YiPaiCore

enum Screen {
    case record
    case settings
    case processing(stage: String, progress: Float)
    case done(PipelineResult)
    case failed(message: String, input: URL)
}

@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .record
    @Published var titleText: String { didSet { defaults.set(titleText, forKey: "title") } }
    @Published var subTitleText: String { didSet { defaults.set(subTitleText, forKey: "subTitle") } }
    @Published var llm: LlmConfig { didSet { saveLlm() } }
    @Published private(set) var useTitleImage: Bool
    /// 每次换图片 +1，让预览刷新
    @Published private(set) var titleImageVersion = 0
    @Published var settings: EditSettings { didSet { saveSettings() } }
    @Published var toast: String?

    private let defaults = UserDefaults.standard

    let titleImageURL = AppModel.titleImageFile
    private static let titleImageFile = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("title.png")
    let recordingURL = FileManager.default.temporaryDirectory.appendingPathComponent("yipai_recording.mov")

    init() {
        let defaults = UserDefaults.standard
        titleText = defaults.string(forKey: "title") ?? ""
        subTitleText = defaults.string(forKey: "subTitle") ?? ""
        if let data = defaults.data(forKey: "llm"), let c = try? JSONDecoder().decode(LlmConfig.self, from: data) {
            llm = c
        } else {
            llm = LlmConfig()
        }
        useTitleImage = defaults.bool(forKey: "useTitleImage") &&
            FileManager.default.fileExists(atPath: Self.titleImageFile.path)
        if let data = defaults.data(forKey: "settings"),
           let s = try? JSONDecoder().decode(EditSettings.self, from: data) {
            settings = s
        } else {
            settings = EditSettings()
        }
    }

    private func saveSettings() {
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: "settings") }
    }

    private func saveLlm() {
        if let data = try? JSONEncoder().encode(llm) { defaults.set(data, forKey: "llm") }
    }

    /// 发布时复制到剪贴板的全部文字：标题 + 概要 + 关键字（带 # 号）
    func publishText(_ result: PipelineResult) -> String {
        var lines: [String] = []
        let head = (TitleArt.lines(titleText) + TitleArt.lines(subTitleText)).joined(separator: " ")
        if !head.isEmpty { lines.append(head) }
        if !result.summary.isEmpty { lines.append(result.summary) }
        if !result.keywords.isEmpty { lines.append(result.keywords.map { "#" + $0 }.joined(separator: " ")) }
        return lines.joined(separator: "\n")
    }

    func setTitleImage(data: Data) {
        guard UIImage(data: data) != nil else {
            toast = "图片读取失败，请选择 PNG 图片"
            return
        }
        do {
            try data.write(to: titleImageURL, options: .atomic)
            useTitleImage = true
            defaults.set(true, forKey: "useTitleImage")
            titleImageVersion += 1
        } catch {
            toast = "图片保存失败"
        }
    }

    func clearTitleImage() {
        useTitleImage = false
        defaults.set(false, forKey: "useTitleImage")
    }

    var titleSpec: TitleSpec {
        TitleSpec(text: titleText, subText: subTitleText, imageURL: useTitleImage ? titleImageURL : nil)
    }

    func process(_ input: URL) {
        let title = titleSpec
        let s = settings
        let llmConfig = llm
        screen = .processing(stage: "准备中", progress: 0)
        UIApplication.shared.isIdleTimerDisabled = true
        // 切到后台时多争取一点时间
        let bgTask = UIApplication.shared.beginBackgroundTask(withName: "yipai.process")
        Task {
            defer {
                UIApplication.shared.isIdleTimerDisabled = false
                UIApplication.shared.endBackgroundTask(bgTask)
            }
            do {
                let result = try await Pipeline.run(input: input, title: title, settings: s, llm: llmConfig) { stage, p in
                    Task { @MainActor in
                        if case .processing = self.screen { self.screen = .processing(stage: stage, progress: p) }
                    }
                }
                screen = .done(result)
            } catch {
                screen = .failed(message: error.localizedDescription, input: input)
            }
        }
    }
}
