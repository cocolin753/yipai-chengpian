import Foundation
// 与安卓版 core/TranscriptAligner.kt 一一对应。

/// 把大模型纠错后的文字对回逐字时间戳。
///
/// 允许大模型少量增删字（口音重时识别常常多字、漏字）：用编辑距离把新旧文字逐字对齐，
/// 换掉的字沿用原字的时间，多出的字跟在前一个字后面，删掉的字只是不显示。
/// 每个字原本的时间和识别原文（`Token.heard`）都不变，剪辑仍按实际声音来，所以字幕不会与声音错位。
/// 增删的字太多（说明模型在改写句子而不是纠错）时整句作废、保留原文。
public enum TranscriptAligner {
    private static func isContent(_ c: Character) -> Bool { c.isLetter || c.isNumber }

    /// 一句最多允许增删的字数
    static func maxEdits(_ length: Int) -> Int { max(2, length / 4) }

    /// 一段语音转成给大模型看的文本：字 + 识别出的标点
    public static func lineText(_ u: Utterance) -> String {
        u.tokens.map { $0.text + $0.punctAfter }.joined().trimmingCharacters(in: .whitespaces)
    }

    /// 返回替换文字后的新语段；改动太大则返回 nil
    public static func apply(_ u: Utterance, refined: String) -> Utterance? {
        let old = u.tokens.flatMap { Array($0.text.filter(isContent)) }
        let new = Array(refined.filter(isContent))
        guard !old.isEmpty, !new.isEmpty, let assigned = align(old, new) else { return nil }
        var pos = 0
        var tokens: [Token] = []
        for t in u.tokens {
            var text = ""
            for c in t.text {
                if isContent(c) { text += assigned[pos]; pos += 1 } else { text.append(c) }
            }
            var copy = t
            copy.text = text
            tokens.append(copy)
        }
        return Utterance(start: u.start, end: u.end, tokens: tokens)
    }

    /// 每个原字对应的新文字（可能为空或多个字）；增删超过 maxEdits 返回 nil
    private static func align(_ old: [Character], _ new: [Character]) -> [String]? {
        let n = old.count, m = new.count
        // d[i][j]：old 前 i 个字变成 new 前 j 个字的最少编辑次数
        var d = [[Int]](repeating: [Int](repeating: 0, count: m + 1), count: n + 1)
        for i in 0...n { d[i][0] = i }
        for j in 0...m { d[0][j] = j }
        for i in stride(from: 1, through: n, by: 1) {
            for j in stride(from: 1, through: m, by: 1) {
                let sub = d[i - 1][j - 1] + (old[i - 1] == new[j - 1] ? 0 : 1)
                d[i][j] = min(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
            }
        }
        // 回溯：同样代价时优先“一个字换一个字”
        var out = [String](repeating: "", count: n)
        var inserted: [Character] = [] // 倒序收集，遇到原字时挂到它后面
        var indels = 0
        var i = n, j = m
        while i > 0 || j > 0 {
            if i > 0, j > 0, d[i][j] == d[i - 1][j - 1] + (old[i - 1] == new[j - 1] ? 0 : 1) {
                out[i - 1] = String(new[j - 1]) + String(inserted.reversed())
                inserted.removeAll()
                i -= 1; j -= 1
            } else if i > 0, d[i][j] == d[i - 1][j] + 1 {
                out[i - 1] = String(inserted.reversed()) // 删掉的字：只留下跟在它后面新增的字
                inserted.removeAll()
                indels += 1
                i -= 1
            } else {
                inserted.append(new[j - 1])
                indels += 1
                j -= 1
            }
        }
        guard indels <= maxEdits(n) else { return nil }
        // 开头新增的字挂到第一个字前面
        out[0] = String(inserted.reversed()) + out[0]
        return out
    }
}
