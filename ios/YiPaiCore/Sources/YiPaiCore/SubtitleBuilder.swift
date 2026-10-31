// 与安卓版 core/SubtitleBuilder.kt 一一对应，修改时两边同步。
import Foundation

/// 把保留下来的字切成字幕行：遇到标点、停顿或超长就换行，标点不显示。
enum SubtitleBuilder {
    /// 相邻字幕间隔小于该值时首尾相连，避免闪烁
    static let joinGap = 0.3
    static let minLineDuration = 0.5

    static func build(_ runs: [Run], timeline: Timeline, _ s: EditSettings) -> [SubtitleLine] {
        var raw: [SubtitleLine] = []
        var cur: [Token] = []
        func flush() {
            guard let first = cur.first, let last = cur.last else { return }
            let text = joinText(cur)
            if !text.isEmpty {
                raw.append(SubtitleLine(text: text, start: timeline.map(first.start), end: timeline.map(last.end)))
            }
            cur.removeAll()
        }
        for run in runs {
            if run.pauseBefore { flush() }
            for t in run.tokens {
                if !cur.isEmpty && width(cur) + width(t.text) > s.maxLineWidth { flush() }
                cur.append(t)
                if !t.punctAfter.isEmpty { flush() }
            }
        }
        flush()

        return raw.enumerated().map { i, line in
            var end = max(line.end, line.start + minLineDuration)
            if i + 1 < raw.count {
                let next = raw[i + 1]
                if next.start - end < joinGap { end = next.start }
                end = min(end, next.start)
            }
            var l = line
            l.end = end
            return l
        }
    }

    private static func joinText(_ tokens: [Token]) -> String {
        tokens.map(\.text).joined()
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: " {2,}", with: " ", options: .regularExpression)
    }

    private static func width(_ tokens: [Token]) -> Double {
        tokens.reduce(0) { $0 + width($1.text) }
    }

    private static func width(_ text: String) -> Double {
        text.unicodeScalars.reduce(0) { $0 + ($1.value < 128 ? 0.55 : 1) }
    }
}
