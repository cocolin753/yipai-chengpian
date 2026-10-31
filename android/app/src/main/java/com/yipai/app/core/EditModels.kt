package com.yipai.app.core

// 本目录（core）只用纯 Kotlin，不依赖 Android，后续 iOS 版照此逻辑移植到 Swift。

/**
 * 识别出的一个字（英文为一个词片，以空格开头表示新单词）。
 * 时间单位为秒，基于原始视频时间轴。[punctAfter] 为紧跟其后的标点。
 */
data class Token(
    val text: String,
    val start: Double,
    val end: Double,
    val punctAfter: String = "",
)

/** VAD 切出的一段连续语音及其中的字。段与段之间的空隙就是停顿。 */
data class Utterance(val start: Double, val end: Double, val tokens: List<Token>)

data class TimeRange(val start: Double, val end: Double) {
    val duration: Double get() = end - start
}

/** 一行字幕，时间基于成品视频时间轴。 */
data class SubtitleLine(val text: String, val start: Double, val end: Double)

data class EditSettings(
    /** 段间静音超过该值才算停顿并压缩 */
    val pauseThreshold: Double = 0.4,
    /** 普通停顿压缩后保留的长度 */
    val keepGap: Double = 0.15,
    /** 句末（。！？）停顿压缩后保留的长度 */
    val sentenceGap: Double = 0.25,
    /** 每个保留片段两端的安全边距，防止吃字 */
    val margin: Double = 0.04,
    /** 第一个字之前保留的时长 */
    val headKeep: Double = 0.25,
    /** 最后一个字之后保留的时长 */
    val tailKeep: Double = 0.4,
    /** 相邻片段间隔小于该值时合并，避免切得太碎 */
    val mergeGap: Double = 0.08,
    val removeFillers: Boolean = true,
    val removeRepeats: Boolean = true,
    /** 一定删除的语气词 */
    val strongFillers: Set<String> = setOf("嗯", "呃", "唔", "呣"),
    /** 只在前后都有停顿或标点（孤立出现）时才删除的语气词 */
    val weakFillers: Set<String> = setOf("啊", "额", "哦", "噢", "哎", "诶", "欸"),
    val adjustSpeed: Boolean = true,
    /** 目标语速：字/秒 */
    val targetCharsPerSecond: Double = 4.2,
    val minSpeed: Double = 0.9,
    val maxSpeed: Double = 1.3,
    /** 每行字幕最大宽度（汉字计 1，英文字母计 0.55） */
    val maxLineWidth: Double = 14.0,
)

data class EditPlan(
    /** 原视频中要保留的片段，按时间顺序 */
    val keep: List<TimeRange>,
    /** 整体播放速度 */
    val speed: Double,
    val subtitles: List<SubtitleLine>,
    val removedFillers: Int,
    val removedRepeats: Int,
) {
    val outputDuration: Double get() = keep.sumOf { it.duration } / speed
}

/** 原视频时间 → 成品视频时间 */
class Timeline(private val keep: List<TimeRange>, private val speed: Double) {
    private val offsets = DoubleArray(keep.size)

    init {
        var acc = 0.0
        keep.forEachIndexed { i, r -> offsets[i] = acc; acc += r.duration }
    }

    fun map(t: Double): Double {
        if (keep.isEmpty()) return t / speed
        var lo = 0
        var hi = keep.lastIndex
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (keep[mid].start <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val r = keep[idx]
        return (offsets[idx] + (t.coerceIn(r.start, r.end) - r.start)) / speed
    }
}
