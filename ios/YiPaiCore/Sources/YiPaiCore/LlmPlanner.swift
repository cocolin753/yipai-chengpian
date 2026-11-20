/// 决定哪些字幕行要请大模型纠错、怎么分批：
/// - 对上提词稿的行（locked）不发，以提词稿为准
/// - 剩下的行按字数分批，每批不超过 maxChars 字，避免输出超过 max_tokens、等待太久
/// 返回空数组表示没有要纠错的行（只需生成概要和关键字）。
public enum LlmPlanner {
    public static let batchChars = 1000

    /// lines 为每句的文字，空字符串表示该句没有文字（跳过）
    public static func batches(_ lines: [String], locked: Set<Int>, maxChars: Int = batchChars) -> [[Int]] {
        var out: [[Int]] = []
        var cur: [Int] = []
        var chars = 0
        for i in lines.indices where !lines[i].isEmpty && !locked.contains(i) {
            let len = lines[i].count
            if !cur.isEmpty && chars + len > maxChars {
                out.append(cur)
                cur = []
                chars = 0
            }
            cur.append(i)
            chars += len
        }
        if !cur.isEmpty { out.append(cur) }
        return out
    }
}
