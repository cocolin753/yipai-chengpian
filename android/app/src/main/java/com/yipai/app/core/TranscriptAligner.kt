package com.yipai.app.core

/**
 * 把大模型纠错后的文字对回逐字时间戳。
 * 硬性规则：字（字母/数字/汉字）的总数必须与原文一致，否则整段作废、保留原文，
 * 所以大模型无论怎么改，都不会让字幕与声音错位。
 */
object TranscriptAligner {
    private fun Char.isContent() = isLetterOrDigit()

    /** 一段语音转成给大模型看的文本：字 + 识别出的标点 */
    fun lineText(u: Utterance): String = u.tokens.joinToString("") { it.text + it.punctAfter }.trim()

    /** 返回替换文字后的新语段；字数对不上则返回 null */
    fun apply(u: Utterance, refined: String): Utterance? {
        val chars = refined.filter { it.isContent() }
        val originalCount = u.tokens.sumOf { t -> t.text.count { it.isContent() } }
        if (chars.length != originalCount || originalCount == 0) return null
        var pos = 0
        val tokens = u.tokens.map { t ->
            val sb = StringBuilder()
            for (c in t.text) sb.append(if (c.isContent()) chars[pos++] else c)
            t.copy(text = sb.toString())
        }
        return u.copy(tokens = tokens)
    }
}
