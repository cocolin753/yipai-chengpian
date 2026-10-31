// 与安卓版 core/CutPlanner.kt 一一对应，修改时两边同步。

/// 一段连续保留的字。`pauseBefore` 表示它与前一段之间是停顿（而不是删词造成的切口）。
struct Run {
    let tokens: [Token]
    let pauseBefore: Bool
    var start: Double { tokens[0].start }
    var end: Double { tokens[tokens.count - 1].end }
}

/// 精剪决策：根据识别结果算出要保留的片段、整体速度和字幕。
/// 规则刻意保守——宁可少剪，不要误剪。
public enum CutPlanner {
    static let sentenceEnd: Set<Character> = ["。", "！", "？", "!", "?", "…"]
    static let maxRepeatLen = 4

    private final class Item {
        let token: Token
        let seg: Int
        var removed = false

        init(token: Token, seg: Int) {
            self.token = token
            self.seg = seg
        }
    }

    public static func plan(_ utterances: [Utterance], duration: Double, settings s: EditSettings = EditSettings()) -> EditPlan {
        var items: [Item] = []
        for (seg, u) in utterances.enumerated() {
            for t in u.tokens { items.append(Item(token: t, seg: seg)) }
        }
        let fillers = s.removeFillers ? markFillers(items, s) : 0
        let repeats = s.removeRepeats ? markRepeats(items) : 0
        let runs = buildRuns(items)
        guard !runs.isEmpty,
              let firstKept = items.firstIndex(where: { !$0.removed }),
              let lastKept = items.lastIndex(where: { !$0.removed }) else {
            return EditPlan(keep: [TimeRange(start: 0, end: duration)], speed: 1, subtitles: [], removedFillers: 0, removedRepeats: 0)
        }
        // 开头/结尾的留白不能把已删掉的字带回来
        let headLimit = firstKept > 0 ? items[firstKept - 1].token.end - s.margin : 0
        let tailLimit = lastKept < items.count - 1 ? items[lastKept + 1].token.start + s.margin : duration
        let keep = buildRanges(runs, duration: duration, headLimit: headLimit, tailLimit: tailLimit, s)
        let chars = runs.reduce(0) { $0 + $1.tokens.count }
        let speed = s.adjustSpeed ? computeSpeed(chars: chars, keep: keep, s) : 1
        let subtitles = SubtitleBuilder.build(runs, timeline: Timeline(keep: keep, speed: speed), s)
        return EditPlan(keep: keep, speed: speed, subtitles: subtitles, removedFillers: fillers, removedRepeats: repeats)
    }

    private static func isFiller(_ text: String, _ s: EditSettings) -> Bool {
        s.strongFillers.contains(text) || s.weakFillers.contains(text)
    }

    private static func markFillers(_ items: [Item], _ s: EditSettings) -> Int {
        var count = 0
        for i in items.indices {
            let item = items[i]
            let text = item.token.text
            var remove = false
            if s.strongFillers.contains(text) {
                remove = true
            } else if s.weakFillers.contains(text) {
                let prev = i > 0 ? items[i - 1] : nil
                let next = i + 1 < items.count ? items[i + 1] : nil
                let isolatedBefore = prev == nil || prev!.seg != item.seg ||
                    !prev!.token.punctAfter.isEmpty || prev!.removed
                let isolatedAfter = next == nil || next!.seg != item.seg ||
                    !item.token.punctAfter.isEmpty || isFiller(next!.token.text, s)
                remove = isolatedBefore && isolatedAfter
            }
            if remove {
                item.removed = true
                count += 1
            }
        }
        return count
    }

    /// 删除相邻重复：
    /// - 2~4 字重复（"我们我们"）删前一遍，但 "一点一点" 这类叠词保留；
    /// - 单字重复只在中间有停顿或连续 3 次以上时删（"谢谢"、"看看" 保留）；
    /// - 第一遍以句号等结尾时不算重复（"是我。我今天"）。
    private static func markRepeats(_ items: [Item]) -> Int {
        var count = 0
        var changed = true
        while changed {
            changed = false
            let kept = items.filter { !$0.removed }
            search: for n in stride(from: maxRepeatLen, through: 1, by: -1) {
                if kept.count < 2 * n { continue }
                for i in 0...(kept.count - 2 * n) {
                    if (0..<n).contains(where: { kept[i + $0].token.text != kept[i + n + $0].token.text }) { continue }
                    // 第一遍内部不能被标点打断，结尾不能是句末标点
                    if (0..<(n - 1)).contains(where: { !kept[i + $0].token.punctAfter.isEmpty }) { continue }
                    if kept[i + n - 1].token.punctAfter.contains(where: { sentenceEnd.contains($0) }) { continue }

                    let toRemove: ArraySlice<Item>
                    if n == 1 {
                        var len = 1
                        while i + len < kept.count && kept[i + len].token.text == kept[i].token.text { len += 1 }
                        let acrossPause = kept[i].seg != kept[i + 1].seg
                        if len >= 3 {
                            toRemove = kept[i..<(i + len - 1)]
                        } else if acrossPause {
                            toRemove = kept[i..<(i + 1)]
                        } else {
                            continue
                        }
                    } else {
                        if kept[i].token.text.hasPrefix("一") { continue }
                        toRemove = kept[i..<(i + n)]
                    }
                    toRemove.forEach { $0.removed = true }
                    count += 1
                    changed = true
                    break search
                }
            }
        }
        return count
    }

    private static func buildRuns(_ items: [Item]) -> [Run] {
        var runs: [Run] = []
        var cur: [Token] = []
        var curSeg = -1
        var lastKeptSeg = -1
        var pauseBefore = false
        func flush() {
            if !cur.isEmpty { runs.append(Run(tokens: cur, pauseBefore: pauseBefore)) }
            cur.removeAll()
        }
        for item in items {
            if item.removed { flush(); continue }
            if !cur.isEmpty && item.seg != curSeg { flush() }
            if cur.isEmpty {
                curSeg = item.seg
                pauseBefore = lastKeptSeg != -1 && lastKeptSeg != item.seg
            }
            cur.append(item.token)
            lastKeptSeg = item.seg
        }
        flush()
        return runs
    }

    private static func buildRanges(
        _ runs: [Run],
        duration: Double,
        headLimit: Double,
        tailLimit: Double,
        _ s: EditSettings
    ) -> [TimeRange] {
        var starts = runs.map { $0.start - s.margin }
        var ends = runs.map { $0.end + s.margin }
        for k in 0..<(runs.count - 1) {
            let next = runs[k + 1]
            if !next.pauseBefore { continue }
            let silence = next.start - runs[k].end
            if silence <= s.pauseThreshold {
                // 短停顿原样保留
                ends[k] = max(ends[k], starts[k + 1])
            } else {
                let isSentenceEnd = runs[k].tokens.last!.punctAfter.contains(where: { sentenceEnd.contains($0) })
                let gap = isSentenceEnd ? s.sentenceGap : s.keepGap
                let ext = max(gap / 2 - s.margin, 0)
                ends[k] += ext
                starts[k + 1] -= ext
            }
        }
        let last = runs.count - 1
        starts[0] = max(runs[0].start - s.headKeep, min(headLimit, starts[0]))
        ends[last] = min(runs[last].end + s.tailKeep, max(tailLimit, ends[last]))

        var out: [TimeRange] = []
        for k in runs.indices {
            let a = min(max(starts[k], 0), duration)
            let b = min(max(ends[k], 0), duration)
            if b <= a { continue }
            if let prev = out.last, a - prev.end < s.mergeGap {
                out[out.count - 1] = TimeRange(start: prev.start, end: max(prev.end, b))
            } else {
                out.append(TimeRange(start: a, end: b))
            }
        }
        return out
    }

    private static func computeSpeed(chars: Int, keep: [TimeRange], _ s: EditSettings) -> Double {
        let total = keep.reduce(0) { $0 + $1.duration }
        if chars < 10 || total <= 0 { return 1 }
        let speed = min(max(s.targetCharsPerSecond / (Double(chars) / total), s.minSpeed), s.maxSpeed)
        return (speed * 100).rounded() / 100
    }
}
