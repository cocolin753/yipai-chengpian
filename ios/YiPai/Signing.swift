import SwiftUI
import UIKit
import UserNotifications

/// 签名有效期：免费 Apple ID 签名的 App 7 天后失效，需要连接电脑用 iloader 重新安装。
/// 有效期从 App 包内的 embedded.mobileprovision 里读取。
enum Signing {
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
            content.body = "请连接电脑，用 iloader 重新安装一次，拍摄的视频不受影响"
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
                } footer: {
                    Text("到期前一天会发通知提醒。过期后 App 打不开，重新安装后即可恢复。")
                }
                Section("续期步骤（每 7 天一次）") {
                    Text("1. 用数据线把手机连到电脑")
                    Text("2. 电脑上打开 iloader，确认已登录同一个 Apple ID")
                    Text("3. 选择「导入 IPA」，选最新的 YiPai.ipa，点安装")
                    Text("4. 装完有效期重新变为 7 天，设置和标题图片都会保留")
                }
            }
            .navigationTitle("签名续期")
            .toolbar { Button("完成") { dismiss() } }
        }
    }
}
