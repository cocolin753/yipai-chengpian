import SwiftUI
import UIKit
import UserNotifications

/// 签名有效期：免费 Apple ID 签名的 App 7 天后失效，需要用 SideStore 续签。
/// 有效期从 App 包内的 embedded.mobileprovision 里读取。
enum Signing {
    /// 一键续期用的快捷指令名称（安装说明见文档）
    static let shortcutName = "一拍成片续期"

    static let expirationDate: Date? = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8)) else { return nil }
        let plistData = data.subdata(in: start.lowerBound..<end.upperBound)
        let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any]
        return plist?["ExpirationDate"] as? Date
    }()

    static var daysLeft: Int? {
        guard let date = expirationDate else { return nil }
        return Calendar.current.dateComponents([.day], from: Date(), to: date).day
    }

    /// 运行快捷指令：连接 VPN → SideStore 刷新全部 App
    static func renew() {
        let name = shortcutName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? shortcutName
        if let url = URL(string: "shortcuts://run-shortcut?name=\(name)") {
            UIApplication.shared.open(url)
        }
    }

    /// 到期前一天提醒续期
    static func scheduleReminder() {
        guard let expiry = expirationDate else { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            center.removePendingNotificationRequests(withIdentifiers: ["renew"])
            let fire = expiry.addingTimeInterval(-24 * 3600)
            guard fire > Date() else { return }
            let content = UNMutableNotificationContent()
            content.title = "一拍成片明天到期"
            content.body = "打开 App 点「一键续期」，或运行快捷指令「\(shortcutName)」"
            content.sound = .default
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            center.add(UNNotificationRequest(identifier: "renew", content: content, trigger: trigger))
        }
    }
}

/// 拍摄页左上角的有效期小标签
struct SigningBadge: View {
    @State private var showSheet = false

    var body: some View {
        if let days = Signing.daysLeft {
            Button { showSheet = true } label: {
                Text(days <= 1 ? "今天到期 · 续期" : "剩 \(days) 天")
                    .font(.caption.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(days <= 2 ? Color.red : Color.black.opacity(0.5), in: Capsule())
                    .foregroundStyle(.white)
            }
            .sheet(isPresented: $showSheet) { RenewView() }
        }
    }
}

struct RenewView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let date = Signing.expirationDate {
                        LabeledContent("到期时间", value: date.formatted(date: .abbreviated, time: .shortened))
                    }
                    Button {
                        Signing.renew()
                    } label: {
                        Label("一键续期", systemImage: "arrow.clockwise.circle.fill").font(.headline)
                    }
                } footer: {
                    Text("会运行快捷指令「\(Signing.shortcutName)」：连接 LocalDevVPN 后让 SideStore 刷新全部 App，有效期重新变为 7 天。")
                }
                Section("第一次使用前（只需设置一次）") {
                    Text("1. 在快捷指令 App 新建快捷指令，命名为「\(Signing.shortcutName)」")
                    Text("2. 添加操作「设置 VPN」→ 选 LocalDevVPN → 连接")
                    Text("3. 添加 SideStore 的操作「Refresh All Apps」（刷新所有 App）")
                    Text("4. 建议在「自动化」里新建每天定时运行这个快捷指令，并关闭「运行前询问」，这样完全不用管")
                }
            }
            .navigationTitle("签名续期")
            .toolbar { Button("完成") { dismiss() } }
        }
    }
}
