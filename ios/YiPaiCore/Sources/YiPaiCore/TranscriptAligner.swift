import Foundation
// 与安卓版 core/TranscriptAligner.kt 一一对应。

/// 把大模型纠错后的文字对回逐字时间戳。
/// 硬性规则：字（字母/数字/汉字）的总数必须与原文一致，否则整段作废、保留原文，
/// 所以大模型无论怎么改，都不会让字幕与声音错位。
public enum TranscriptAligner {
    private static func isContent(_ c: Character) -> Bool { c.isLetter || c.isNumber }

    /// 一段语音转成给大模型看的文本：字 + 识别出的标点
    public static func lineText(_ u: Utterance) -> String {
        u.tokens.map { $0.text + $0.punctAfter }.joined().trimmingCharacters(in: .whitespaces)
    }

    /// 返回替换文字后的新语段；字数对不上则返回 nil
    public static func apply(_ u: Utterance, refined: String) -> Utterance? {
        let chars = Array(refined.filter(isContent))
        let originalCount = u.tokens.reduce(0) { $0 + $1.text.filter(isContent).count }
        guard chars.count == originalCount, originalCount > 0 else { return nil }
        var pos = 0
        var tokens: [Token] = []
        for t in u.tokens {
            var text = ""
            for c in t.text {
                if isContent(c) { text.append(chars[pos]); pos += 1 } else { text.append(c) }
            }
            var copy = t
            copy.text = text
            tokens.append(copy)
        }
        return Utterance(start: u.start, end: u.end, tokens: tokens)
    }
}
