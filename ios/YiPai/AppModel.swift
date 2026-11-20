import SwiftUI
import UIKit
import YiPaiCore

enum Screen {
    case record
    case settings
    case prompter
    case processing(stage: String, progress: Float)
    case done(PipelineResult)
    case failed(message: String, input: URL)
}

/// 提词器
struct PrompterSettings: Codable {
    var script = ""
    /// 拍摄时在取景框上方滚动显示
    var show = true
    /// 按提词稿校对字幕
    var correctSubtitles = true
    /// 滚动速度：字/秒
    var speed = 4.0
    /// 字号（点）
    var fontSize = 26.0
}

@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .record
    @Published var titleText: String { didSet { defaults.set(titleText, forKey: "title") } }
    @Published var subTitleText: String { didSet { defaults.set(subTitleText, forKey: "subTitle") } }
    @Published var llm: LlmConfig { didSet { saveLlm() } }
    @Published var prompter: PrompterSettings { didSet { savePrompter() } }
    @Published private(set) var useTitleImage: Bool
    /// 每次换图片 +1，让预览刷新
    @Published private(set) var titleImageVersion = 0
    @Published var settings: EditSettings { didSet { saveSettings() } }
    @Published var toast: String?
    @Published var fillLight: FillLight { didSet { defaults.set(fillLight.rawValue, forKey: "fillLight") } }

    private let defaults = UserDefaults.standard

    let titleImageURL = AppModel.titleImageFile
    private static let titleImageFile = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("title.png")
    let recordingURL = FileManager.default.temporaryDirectory.appendingPathComponent("yipai_recording.mov")

    init() {
        let defaults = UserDefaults.standard
        titleText = defaults.string(forKey: "title") ?? ""
        subTitleText = defaults.string(forKey: "subTitle") ?? ""
        fillLight = FillLight(rawValue: defaults.string(forKey: "fillLight") ?? "") ?? .auto
        if let data = defaults.data(forKey: "llm"), let c = try? JSONDecoder().decode(LlmConfig.self, from: data) {
            llm = c
        } else {
            llm = LlmConfig()
        }
        if let data = defaults.data(forKey: "prompter"),
           let p = try? JSONDecoder().decode(PrompterSettings.self, from: data) {
            prompter = p
        } else {
            prompter = PrompterSettings()
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

    private func savePrompter() {
        if let data = try? JSONEncoder().encode(prompter) { defaults.set(data, forKey: "prompter") }
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
        let script = prompter.correctSubtitles ? prompter.script : ""
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
                let result = try await Pipeline.run(input: input, title: title, settings: s, llm: llmConfig, script: script) { stage, p in
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
