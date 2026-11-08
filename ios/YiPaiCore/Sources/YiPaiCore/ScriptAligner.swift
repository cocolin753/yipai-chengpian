// 与安卓版 core/ScriptAligner.kt 一一对应，修改时两边同步。

/// 按提词稿校对字幕，**一切以实际口播为准**：
/// - 提词稿里没念的内容（跳过的句子、段落）不会出现在字幕里；
/// - 临场多说的话（提词稿里没有）保持识别原文；
/// - 只把“念的是提词稿”的句子里认错的字换成提词稿的字。
///
/// 做法：
/// 1. 把全部识别文字与整篇提词稿做一次顺序对齐（编辑距离）。在两句之间（停顿处）跳过提词稿不算代价——
///    没念的内容几乎都是整句整段跳过；句中跳过代价小；识别出的字对不上提词稿、或对上的字不同，代价大。
/// 2. 逐句检查：这句识别出的字有一半以上与提词稿完全相同，才认为这句念的是提词稿，
///    按对齐结果换字；中间漏识别的一两个字从提词稿补上，跳过的长段落不补。
/// 3. 换字交给 `TranscriptAligner.apply`：每个字的时间和识别原文都不变，剪辑仍按实际声音来。
public enum ScriptAligner {
    public struct Result {
        public var utterances: [Utterance]
        /// 对上提词稿的句子下标（之后大模型不再改这些句子）
        public var matched: Set<Int>
        /// 实际改了字的句子数
        public var changed: Int
        /// 提词稿或识别结果太长，没有校对
        public var tooLong = false
    }

    private static let sub = 2
    private static let recGap = 2
    private static let scriptGap = 1
    /// 句中漏识别的字最多补几个；再多说明是跳过了提词稿的内容
    static let maxFill = 2
    static let minMatchRatio = 0.5
    /// 对齐表格上限（约 4000 字口播 × 10000 字提词稿），防止内存不够
    private static let maxCells = 40_000_000

    private static func isContent(_ c: Character) -> Bool { c.isLetter || c.isNumber }
    private static func key(_ c: Character) -> UInt32 { c.lowercased().unicodeScalars.first?.value ?? 0 }

    public static func apply(_ utterances: [Utterance], script: String) -> Result {
        let unchanged = Result(utterances: utterances, matched: [], changed: 0)
        // 识别出的每个字及其所在句子
        var rec: [Character] = []
        var recUtt: [Int] = []
        for (ui, u) in utterances.enumerated() {
            for t in u.tokens { for c in t.text where isContent(c) { rec.append(c); recUtt.append(ui) } }
        }
        let scr = Array(script.filter(isContent))
        let n = rec.count, m = scr.count
        if n == 0 || m == 0 { return unchanged }
        if n * m > maxCells { var r = unchanged; r.tooLong = true; return r }

        // boundary[i]：识别的前 i 个字之后是句子边界（开头、结尾、两句之间）
        let boundary = (0...n).map { i in i == 0 || i == n || recUtt[i - 1] != recUtt[i] }
        let recKeys = rec.map(key)
        let scrKeys = scr.map(key)
        let recToScr = align(recKeys, scrKeys, boundary)

        var out = utterances
        var matched = Set<Int>()
        var changed = 0
        var k = 0
        for ui in utterances.indices {
            let from = k
            while k < n && recUtt[k] == ui { k += 1 }
            if k == from { continue }
            let len = k - from
            let matches = (from..<k).filter { recToScr[$0] >= 0 && recKeys[$0] == scrKeys[recToScr[$0]] }.count
            if Double(matches) < Double(len) * minMatchRatio { continue }

            var target = ""
            var prev = -1
            for p in from..<k {
                let a = recToScr[p]
                if a < 0 { target.append(rec[p]); continue } // 临场多说的字，保留原文
                if prev >= 0, (1...maxFill).contains(a - prev - 1) { target += String(scr[(prev + 1)..<a]) }
                target.append(scr[a])
                prev = a
            }
            guard let fixed = TranscriptAligner.apply(utterances[ui], refined: target) else { continue }
            matched.insert(ui)
            if fixed.tokens != utterances[ui].tokens { changed += 1 }
            out[ui] = fixed
        }
        return Result(utterances: out, matched: matched, changed: changed)
    }

    /// 顺序对齐：返回每个识别字对应的提词稿位置，没有对应为 -1
    private static func align(_ rec: [UInt32], _ scr: [UInt32], _ boundary: [Bool]) -> [Int] {
        let n = rec.count, m = scr.count
        // 0：对角（相同或换字） 1：识别多出的字 2：跳过提词稿的字
        var trace = [UInt8](repeating: 0, count: n * m)
        var prev = [Int](repeating: 0, count: m + 1) // 开头没念的部分不算代价
        var cur = [Int](repeating: 0, count: m + 1)
        for i in 1...n {
            cur[0] = i * recGap
            let ci = rec[i - 1]
            let row = (i - 1) * m
            let skip = boundary[i] ? 0 : scriptGap
            for j in 1...m {
                let diag = prev[j - 1] + (ci == scr[j - 1] ? 0 : sub)
                let up = prev[j] + recGap
                let left = cur[j - 1] + skip
                if diag <= up && diag <= left { cur[j] = diag; trace[row + j - 1] = 0 }
                else if left <= up { cur[j] = left; trace[row + j - 1] = 2 }
                else { cur[j] = up; trace[row + j - 1] = 1 }
            }
            swap(&prev, &cur)
        }
        var result = [Int](repeating: -1, count: n)
        var i = n, j = m
        while i > 0 && j > 0 {
            switch trace[(i - 1) * m + j - 1] {
            case 0: result[i - 1] = j - 1; i -= 1; j -= 1
            case 1: i -= 1
            default: j -= 1
            }
        }
        return result
    }
}
