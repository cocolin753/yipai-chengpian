// 与安卓版 core/EditModels.kt 一一对应，修改时两边同步。

/// 识别出的一个字（英文为一个词片，以空格开头表示新单词）。
/// 时间单位为秒，基于原始视频时间轴。`punctAfter` 为紧跟其后的标点。
public struct Token: Equatable {
    public var text: String
    public var start: Double
    public var end: Double
    public var punctAfter: String
    /// 识别原文。`text` 是字幕显示的文字（可能经大模型纠错，长度可变）；
    /// 删语气词、删重复这些剪辑决定都按 `heard` 来，纠错不影响剪辑。
    public var heard: String

    public init(text: String, start: Double, end: Double, punctAfter: String = "", heard: String? = nil) {
        self.text = text
        self.start = start
        self.end = end
        self.punctAfter = punctAfter
        self.heard = heard ?? text
    }
}

/// VAD 切出的一段连续语音及其中的字。段与段之间的空隙就是停顿。
public struct Utterance {
    public var start: Double
    public var end: Double
    public var tokens: [Token]

    public init(start: Double, end: Double, tokens: [Token]) {
        self.start = start
        self.end = end
        self.tokens = tokens
    }
}

public struct TimeRange: Equatable {
    public var start: Double
    public var end: Double
    public var duration: Double { end - start }

    public init(start: Double, end: Double) {
        self.start = start
        self.end = end
    }
}

/// 一行字幕，时间基于成品视频时间轴。
public struct SubtitleLine: Equatable {
    public var text: String
    public var start: Double
    public var end: Double

    public init(text: String, start: Double, end: Double) {
        self.text = text
        self.start = start
        self.end = end
    }
}

public struct EditSettings: Codable, Equatable {
    /// 段间静音超过该值才算停顿并压缩
    public var pauseThreshold = 0.4
    /// 普通停顿压缩后保留的长度
    public var keepGap = 0.15
    /// 句末（。！？）停顿压缩后保留的长度
    public var sentenceGap = 0.25
    /// 每个保留片段两端的安全边距，防止吃字
    public var margin = 0.04
    /// 第一个字之前保留的时长
    public var headKeep = 0.25
    /// 最后一个字之后保留的时长
    public var tailKeep = 0.4
    /// 相邻片段间隔小于该值时合并，避免切得太碎
    public var mergeGap = 0.08
    public var removeFillers = true
    public var removeRepeats = true
    /// 一定删除的语气词
    public var strongFillers: Set<String> = ["嗯", "呃", "唔", "呣"]
    /// 只在前后都有停顿或标点（孤立出现）时才删除的语气词
    public var weakFillers: Set<String> = ["啊", "额", "哦", "噢", "哎", "诶", "欸"]
    public var adjustSpeed = true
    /// 目标语速：字/秒
    public var targetCharsPerSecond = 4.2
    public var minSpeed = 0.9
    public var maxSpeed = 1.3
    /// 每行字幕最大宽度（汉字计 1，英文字母计 0.55）
    public var maxLineWidth = 14.0

    public init() {}
}

public struct EditPlan {
    /// 原视频中要保留的片段，按时间顺序
    public var keep: [TimeRange]
    /// 整体播放速度
    public var speed: Double
    public var subtitles: [SubtitleLine]
    public var removedFillers: Int
    public var removedRepeats: Int

    public var outputDuration: Double { keep.reduce(0) { $0 + $1.duration } / speed }
}

/// 原视频时间 → 成品视频时间
public struct Timeline {
    private let keep: [TimeRange]
    private let speed: Double
    private let offsets: [Double]

    public init(keep: [TimeRange], speed: Double) {
        self.keep = keep
        self.speed = speed
        var acc = 0.0
        var offsets: [Double] = []
        for r in keep {
            offsets.append(acc)
            acc += r.duration
        }
        self.offsets = offsets
    }

    public func map(_ t: Double) -> Double {
        if keep.isEmpty { return t / speed }
        var lo = 0
        var hi = keep.count - 1
        var idx = 0
        while lo <= hi {
            let mid = (lo + hi) / 2
            if keep[mid].start <= t { idx = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        let r = keep[idx]
        return (offsets[idx] + (min(max(t, r.start), r.end) - r.start)) / speed
    }
}
