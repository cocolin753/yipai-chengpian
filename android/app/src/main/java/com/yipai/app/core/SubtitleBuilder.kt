package com.yipai.app.core

/** 把保留下来的字切成字幕行：遇到标点、停顿或超长就换行，标点不显示。 */
internal object SubtitleBuilder {
    /** 相邻字幕间隔小于该值时首尾相连，避免闪烁 */
    private const val JOIN_GAP = 0.3
    private const val MIN_LINE_DURATION = 0.5

    fun build(runs: List<Run>, timeline: Timeline, s: EditSettings): List<SubtitleLine> {
        val raw = mutableListOf<SubtitleLine>()
        val cur = mutableListOf<Token>()
        fun flush() {
            if (cur.isEmpty()) return
            val text = joinText(cur)
            if (text.isNotEmpty()) {
                raw += SubtitleLine(text, timeline.map(cur.first().start), timeline.map(cur.last().end))
            }
            cur.clear()
        }
        for (run in runs) {
            if (run.pauseBefore) flush()
            for (t in run.tokens) {
                if (cur.isNotEmpty() && width(cur) + width(t.text) > s.maxLineWidth) flush()
                cur += t
                if (t.punctAfter.isNotEmpty()) flush()
            }
        }
        flush()

        return raw.mapIndexed { i, line ->
            val next = raw.getOrNull(i + 1)
            var end = maxOf(line.end, line.start + MIN_LINE_DURATION)
            if (next != null) {
                if (next.start - end < JOIN_GAP) end = next.start
                end = minOf(end, next.start)
            }
            line.copy(end = end)
        }
    }

    private fun joinText(tokens: List<Token>) =
        tokens.joinToString("") { it.text }.trim().replace(Regex(" {2,}"), " ")

    private fun width(tokens: List<Token>) = tokens.sumOf { width(it.text) }

    private fun width(text: String) = text.sumOf { c -> if (c.code < 128) 0.55 else 1.0 }
}
