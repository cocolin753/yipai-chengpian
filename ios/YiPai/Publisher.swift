import SwiftUI
import UIKit

/// 发布到各平台。官方分享 SDK 都要注册开发者应用，自用 App 申请不了：
/// 抖音、小红书用系统分享面板（在面板里点对应 App 图标）；视频号直接打开微信并提示路径。
/// 标题会复制到剪贴板，在发布页直接粘贴。
enum Platform: String, CaseIterable, Identifiable {
    case douyin = "抖音"
    case xiaohongshu = "小红书"
    case channels = "视频号"

    var id: String { rawValue }
}

enum Publisher {
    @MainActor
    static func publish(_ platform: Platform, video: URL, title: String, toast: @escaping (String) -> Void) {
        if !title.trimmingCharacters(in: .whitespaces).isEmpty {
            UIPasteboard.general.string = title
        }
        switch platform {
        case .douyin, .xiaohongshu:
            presentShareSheet(video)
            toast("在分享面板里点「\(platform.rawValue)」，标题已复制")
        case .channels:
            UIApplication.shared.open(URL(string: "weixin://")!) { ok in
                if !ok { toast("没有安装微信") }
            }
            toast("视频已在相册。微信：发现 → 视频号 → 右上角发表 → 从相册选择。标题已复制")
        }
    }

    @MainActor
    private static func presentShareSheet(_ url: URL) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              var top = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = top.view
        top.present(sheet, animated: true)
    }
}
