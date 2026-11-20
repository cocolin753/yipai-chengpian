package com.yipai.app.core

/**
 * 决定哪些字幕行要请大模型纠错、怎么分批：
 * - 对上提词稿的行（locked）不发，以提词稿为准
 * - 剩下的行按字数分批，每批不超过 [maxChars] 字，避免输出超过 max_tokens、等待太久
 * 返回空列表表示没有要纠错的行（只需生成概要和关键字）。
 */
object LlmPlanner {
    const val BATCH_CHARS = 1000

    /** [lines] 为每句的文字，空字符串表示该句没有文字（跳过） */
    fun batches(lines: List<String>, locked: Set<Int>, maxChars: Int = BATCH_CHARS): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        var chars = 0
        for (i in lines.indices) {
            if (lines[i].isEmpty() || i in locked) continue
            val len = lines[i].length
            if (cur.isNotEmpty() && chars + len > maxChars) {
                out += cur
                cur = mutableListOf()
                chars = 0
            }
            cur += i
            chars += len
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
