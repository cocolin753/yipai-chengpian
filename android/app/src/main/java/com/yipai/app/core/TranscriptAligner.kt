package com.yipai.app.core

/**
 * 把大模型纠错后的文字对回逐字时间戳。
 *
 * 允许大模型少量增删字（口音重时识别常常多字、漏字）：用编辑距离把新旧文字逐字对齐，
 * 换掉的字沿用原字的时间，多出的字跟在前一个字后面，删掉的字只是不显示。
 * 每个字原本的时间和识别原文（[Token.heard]）都不变，剪辑仍按实际声音来，所以字幕不会与声音错位。
 * 增删的字太多（说明模型在改写句子而不是纠错）时整句作废、保留原文。
 */
object TranscriptAligner {
    private fun Char.isContent() = isLetterOrDigit()

    /** 一句最多允许增删的字数 */
    internal fun maxEdits(length: Int) = maxOf(2, length / 4)

    /** 一段语音转成给大模型看的文本：字 + 识别出的标点 */
    fun lineText(u: Utterance): String = u.tokens.joinToString("") { it.text + it.punctAfter }.trim()

    /** 返回替换文字后的新语段；改动太大则返回 null */
    fun apply(u: Utterance, refined: String): Utterance? {
        val old = u.tokens.flatMap { t -> t.text.filter { it.isContent() }.toList() }
        val new = refined.filter { it.isContent() }
        if (old.isEmpty() || new.isEmpty()) return null
        val assigned = align(old, new) ?: return null
        var pos = 0
        val tokens = u.tokens.map { t ->
            val sb = StringBuilder()
            for (c in t.text) if (c.isContent()) sb.append(assigned[pos++]) else sb.append(c)
            t.copy(text = sb.toString())
        }
        return u.copy(tokens = tokens)
    }

    /** 每个原字对应的新文字（可能为空或多个字）；增删超过 [maxEdits] 返回 null */
    private fun align(old: List<Char>, new: String): Array<String>? {
        val n = old.size
        val m = new.length
        // d[i][j]：old 前 i 个字变成 new 前 j 个字的最少编辑次数
        val d = Array(n + 1) { i -> IntArray(m + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..n) for (j in 1..m) {
            val sub = d[i - 1][j - 1] + if (old[i - 1] == new[j - 1]) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        // 回溯：同样代价时优先“一个字换一个字”
        val out = Array(n) { StringBuilder() }
        val inserted = StringBuilder() // 倒序收集，遇到原字时挂到它后面
        var indels = 0
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (old[i - 1] == new[j - 1]) 0 else 1) {
                out[i - 1].append(new[j - 1]).append(inserted.reverse())
                inserted.clear()
                i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                out[i - 1].append(inserted.reverse()) // 删掉的字：只留下跟在它后面新增的字
                inserted.clear()
                indels++
                i--
            } else {
                inserted.append(new[j - 1])
                indels++
                j--
            }
        }
        if (indels > maxEdits(n)) return null
        // 开头新增的字挂到第一个字前面
        if (inserted.isNotEmpty()) out[0].insert(0, inserted.reverse())
        return Array(n) { out[it].toString() }
    }
}
