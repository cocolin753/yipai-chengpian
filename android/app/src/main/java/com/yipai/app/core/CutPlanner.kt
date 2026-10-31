package com.yipai.app.core

/** 一段连续保留的字。[pauseBefore] 表示它与前一段之间是停顿（而不是删词造成的切口）。 */
internal class Run(val tokens: List<Token>, val pauseBefore: Boolean) {
    val start: Double get() = tokens.first().start
    val end: Double get() = tokens.last().end
}

/**
 * 精剪决策：根据识别结果算出要保留的片段、整体速度和字幕。
 * 规则刻意保守——宁可少剪，不要误剪。
 */
object CutPlanner {
    private const val SENTENCE_END = "。！？!?…"
    private const val MAX_REPEAT_LEN = 4

    private class Item(val token: Token, val seg: Int) {
        var removed = false
    }

    fun plan(utterances: List<Utterance>, duration: Double, s: EditSettings = EditSettings()): EditPlan {
        val items = utterances.flatMapIndexed { seg, u -> u.tokens.map { Item(it, seg) } }
        val fillers = if (s.removeFillers) markFillers(items, s) else 0
        val repeats = if (s.removeRepeats) markRepeats(items) else 0
        val runs = buildRuns(items)
        if (runs.isEmpty()) {
            return EditPlan(listOf(TimeRange(0.0, duration)), 1.0, emptyList(), 0, 0)
        }
        // 开头/结尾的留白不能把已删掉的字带回来
        val firstKept = items.indexOfFirst { !it.removed }
        val lastKept = items.indexOfLast { !it.removed }
        val headLimit = items.getOrNull(firstKept - 1)?.token?.end?.minus(s.margin) ?: 0.0
        val tailLimit = items.getOrNull(lastKept + 1)?.token?.start?.plus(s.margin) ?: duration
        val keep = buildRanges(runs, duration, headLimit, tailLimit, s)
        val chars = runs.sumOf { it.tokens.size }
        val speed = if (s.adjustSpeed) computeSpeed(chars, keep, s) else 1.0
        val subtitles = SubtitleBuilder.build(runs, Timeline(keep, speed), s)
        return EditPlan(keep, speed, subtitles, fillers, repeats)
    }

    private fun isFiller(text: String, s: EditSettings) = text in s.strongFillers || text in s.weakFillers

    private fun markFillers(items: List<Item>, s: EditSettings): Int {
        var count = 0
        for (i in items.indices) {
            val item = items[i]
            val text = item.token.text
            val remove = when (text) {
                in s.strongFillers -> true
                in s.weakFillers -> {
                    val prev = items.getOrNull(i - 1)
                    val next = items.getOrNull(i + 1)
                    val isolatedBefore = prev == null || prev.seg != item.seg ||
                        prev.token.punctAfter.isNotEmpty() || prev.removed
                    val isolatedAfter = next == null || next.seg != item.seg ||
                        item.token.punctAfter.isNotEmpty() || isFiller(next.token.text, s)
                    isolatedBefore && isolatedAfter
                }
                else -> false
            }
            if (remove) {
                item.removed = true
                count++
            }
        }
        return count
    }

    /**
     * 删除相邻重复：
     * - 2~4 字重复（"我们我们"）删前一遍，但 "一点一点" 这类叠词保留；
     * - 单字重复只在中间有停顿或连续 3 次以上时删（"谢谢"、"看看" 保留）；
     * - 第一遍以句号等结尾时不算重复（"是我。我今天"）。
     */
    private fun markRepeats(items: List<Item>): Int {
        var count = 0
        var changed = true
        while (changed) {
            changed = false
            val kept = items.filter { !it.removed }
            search@ for (n in MAX_REPEAT_LEN downTo 1) {
                for (i in 0..kept.size - 2 * n) {
                    if ((0 until n).any { k -> kept[i + k].token.text != kept[i + n + k].token.text }) continue
                    // 第一遍内部不能被标点打断，结尾不能是句末标点
                    if ((0 until n - 1).any { k -> kept[i + k].token.punctAfter.isNotEmpty() }) continue
                    if (kept[i + n - 1].token.punctAfter.any { it in SENTENCE_END }) continue

                    val toRemove = if (n == 1) {
                        var len = 1
                        while (i + len < kept.size && kept[i + len].token.text == kept[i].token.text) len++
                        val acrossPause = kept[i].seg != kept[i + 1].seg
                        when {
                            len >= 3 -> kept.subList(i, i + len - 1)
                            acrossPause -> kept.subList(i, i + 1)
                            else -> continue
                        }
                    } else {
                        if (kept[i].token.text.startsWith("一")) continue
                        kept.subList(i, i + n)
                    }
                    toRemove.forEach { it.removed = true }
                    count++
                    changed = true
                    break@search
                }
            }
        }
        return count
    }

    private fun buildRuns(items: List<Item>): List<Run> {
        val runs = mutableListOf<Run>()
        val cur = mutableListOf<Token>()
        var curSeg = -1
        var lastKeptSeg = -1
        var pauseBefore = false
        fun flush() {
            if (cur.isNotEmpty()) runs += Run(cur.toList(), pauseBefore)
            cur.clear()
        }
        for (item in items) {
            if (item.removed) { flush(); continue }
            if (cur.isNotEmpty() && item.seg != curSeg) flush()
            if (cur.isEmpty()) {
                curSeg = item.seg
                pauseBefore = lastKeptSeg != -1 && lastKeptSeg != item.seg
            }
            cur += item.token
            lastKeptSeg = item.seg
        }
        flush()
        return runs
    }

    private fun buildRanges(
        runs: List<Run>,
        duration: Double,
        headLimit: Double,
        tailLimit: Double,
        s: EditSettings,
    ): List<TimeRange> {
        val starts = DoubleArray(runs.size) { runs[it].start - s.margin }
        val ends = DoubleArray(runs.size) { runs[it].end + s.margin }
        for (k in 0 until runs.lastIndex) {
            val next = runs[k + 1]
            if (!next.pauseBefore) continue
            val silence = next.start - runs[k].end
            if (silence <= s.pauseThreshold) {
                // 短停顿原样保留
                ends[k] = maxOf(ends[k], starts[k + 1])
            } else {
                val gap = if (runs[k].tokens.last().punctAfter.any { it in SENTENCE_END }) s.sentenceGap else s.keepGap
                val ext = (gap / 2 - s.margin).coerceAtLeast(0.0)
                ends[k] += ext
                starts[k + 1] -= ext
            }
        }
        starts[0] = maxOf(runs.first().start - s.headKeep, minOf(headLimit, starts[0]))
        ends[runs.lastIndex] = minOf(runs.last().end + s.tailKeep, maxOf(tailLimit, ends[runs.lastIndex]))

        val out = mutableListOf<TimeRange>()
        for (k in runs.indices) {
            val a = starts[k].coerceIn(0.0, duration)
            val b = ends[k].coerceIn(0.0, duration)
            if (b <= a) continue
            val last = out.lastOrNull()
            if (last != null && a - last.end < s.mergeGap) {
                out[out.lastIndex] = TimeRange(last.start, maxOf(last.end, b))
            } else {
                out += TimeRange(a, b)
            }
        }
        return out
    }

    private fun computeSpeed(chars: Int, keep: List<TimeRange>, s: EditSettings): Double {
        val total = keep.sumOf { it.duration }
        if (chars < 10 || total <= 0) return 1.0
        val speed = (s.targetCharsPerSecond / (chars / total)).coerceIn(s.minSpeed, s.maxSpeed)
        return Math.round(speed * 100) / 100.0
    }
}
