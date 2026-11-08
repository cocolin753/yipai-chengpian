package com.yipai.app.core

/**
 * 按提词稿校对字幕，**一切以实际口播为准**：
 * - 提词稿里没念的内容（跳过的句子、段落）不会出现在字幕里；
 * - 临场多说的话（提词稿里没有）保持识别原文；
 * - 只把“念的是提词稿”的句子里认错的字换成提词稿的字。
 *
 * 做法：
 * 1. 把全部识别文字与整篇提词稿做一次顺序对齐（编辑距离）。在两句之间（停顿处）跳过提词稿不算代价——
 *    没念的内容几乎都是整句整段跳过；句中跳过代价小；识别出的字对不上提词稿、或对上的字不同，代价大。
 * 2. 逐句检查：这句识别出的字有一半以上与提词稿完全相同，才认为这句念的是提词稿，
 *    按对齐结果换字；中间漏识别的一两个字从提词稿补上，跳过的长段落不补。
 * 3. 换字交给 [TranscriptAligner.apply]：每个字的时间和识别原文都不变，剪辑仍按实际声音来。
 */
object ScriptAligner {
    data class Result(
        val utterances: List<Utterance>,
        /** 对上提词稿的句子下标（之后大模型不再改这些句子） */
        val matched: Set<Int>,
        /** 实际改了字的句子数 */
        val changed: Int,
        /** 提词稿或识别结果太长，没有校对 */
        val tooLong: Boolean = false,
    )

    private const val SUB = 2
    private const val REC_GAP = 2
    private const val SCRIPT_GAP = 1
    /** 句中漏识别的字最多补几个；再多说明是跳过了提词稿的内容 */
    internal const val MAX_FILL = 2
    internal const val MIN_MATCH_RATIO = 0.5
    /** 对齐表格上限（约 4000 字口播 × 10000 字提词稿），防止内存不够 */
    private const val MAX_CELLS = 40_000_000L

    private fun Char.isContent() = isLetterOrDigit()

    fun apply(utterances: List<Utterance>, script: String): Result {
        val unchanged = Result(utterances, emptySet(), 0)
        // 识别出的每个字及其所在句子
        val rec = StringBuilder()
        val recUtt = ArrayList<Int>()
        utterances.forEachIndexed { ui, u ->
            for (t in u.tokens) for (c in t.text) if (c.isContent()) { rec.append(c); recUtt += ui }
        }
        val scr = script.filter { it.isContent() }
        val n = rec.length
        val m = scr.length
        if (n == 0 || m == 0) return unchanged
        if (n.toLong() * m > MAX_CELLS) return unchanged.copy(tooLong = true)

        // boundary[i]：识别的前 i 个字之后是句子边界（开头、结尾、两句之间）
        val boundary = BooleanArray(n + 1) { i -> i == 0 || i == n || recUtt[i - 1] != recUtt[i] }
        val recToScr = align(rec.toString().lowercase(), scr.lowercase(), boundary)

        val out = utterances.toMutableList()
        val matched = mutableSetOf<Int>()
        var changed = 0
        var k = 0
        for (ui in utterances.indices) {
            val from = k
            while (k < n && recUtt[k] == ui) k++
            if (k == from) continue
            val len = k - from
            val matches = (from until k).count { recToScr[it] >= 0 && rec[it].lowercaseChar() == scr[recToScr[it]].lowercaseChar() }
            if (matches < len * MIN_MATCH_RATIO) continue

            val target = StringBuilder()
            var prev = -1
            for (p in from until k) {
                val a = recToScr[p]
                if (a < 0) { target.append(rec[p]); continue } // 临场多说的字，保留原文
                if (prev >= 0 && a - prev - 1 in 1..MAX_FILL) target.append(scr, prev + 1, a)
                target.append(scr[a])
                prev = a
            }
            val fixed = TranscriptAligner.apply(utterances[ui], target.toString()) ?: continue
            matched += ui
            if (fixed != utterances[ui]) changed++
            out[ui] = fixed
        }
        return Result(out, matched, changed)
    }

    /** 顺序对齐：返回每个识别字对应的提词稿位置，没有对应为 -1 */
    private fun align(rec: String, scr: String, boundary: BooleanArray): IntArray {
        val n = rec.length
        val m = scr.length
        // 0：对角（相同或换字） 1：识别多出的字 2：跳过提词稿的字
        val trace = ByteArray(n * m)
        var prev = IntArray(m + 1) // 开头没念的部分不算代价
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i * REC_GAP
            val ci = rec[i - 1]
            val row = (i - 1) * m
            val skip = if (boundary[i]) 0 else SCRIPT_GAP
            for (j in 1..m) {
                val diag = prev[j - 1] + if (ci == scr[j - 1]) 0 else SUB
                val up = prev[j] + REC_GAP
                val left = cur[j - 1] + skip
                if (diag <= up && diag <= left) { cur[j] = diag; trace[row + j - 1] = 0 }
                else if (left <= up) { cur[j] = left; trace[row + j - 1] = 2 }
                else { cur[j] = up; trace[row + j - 1] = 1 }
            }
            val t = prev; prev = cur; cur = t
        }
        val result = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (trace[(i - 1) * m + j - 1].toInt()) {
                0 -> { result[i - 1] = j - 1; i--; j-- }
                1 -> i--
                else -> j--
            }
        }
        return result
    }
}
