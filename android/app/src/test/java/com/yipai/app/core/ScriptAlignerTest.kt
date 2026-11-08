package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptAlignerTest {
    /** 每个字一个 token，句末标点挂到最后一个字 */
    private fun utt(text: String, start: Double) = Utterance(
        start, start + text.length,
        text.mapIndexed { i, c -> Token(c.toString(), start + i, start + i + 1, if (i == text.lastIndex) "。" else "") },
    )

    private fun text(u: Utterance) = u.tokens.joinToString("") { it.text }

    @Test fun wrongCharsFixedFromScript() {
        val us = listOf(utt("大家号我是小王", 0.0), utt("今天跟大家谈谈剪集", 10.0))
        val r = ScriptAligner.apply(us, "大家好，我是小王。今天跟大家聊聊剪辑。")
        assertEquals(listOf("大家好我是小王", "今天跟大家聊聊剪辑"), r.utterances.map(::text))
        assertEquals(setOf(0, 1), r.matched)
        assertEquals(2, r.changed)
        // 时间和识别原文不变
        assertEquals(us[1].tokens.map { it.start }, r.utterances[1].tokens.map { it.start })
        assertEquals(us[1].tokens.map { it.heard }, r.utterances[1].tokens.map { it.heard })
    }

    @Test fun unspokenScriptIsIgnored() {
        // 提词稿中间一段没念，结尾一段也没念
        val script = "大家好我是小王。这一段我临时决定不念了它很长很长。今天跟大家聊聊剪辑。最后这句也没念。"
        val us = listOf(utt("大家好我是小王", 0.0), utt("今天跟大家聊聊剪集", 10.0))
        val r = ScriptAligner.apply(us, script)
        assertEquals(listOf("大家好我是小王", "今天跟大家聊聊剪辑"), r.utterances.map(::text))
    }

    @Test fun adLibKeptAsSpoken() {
        // 第二句是临场发挥，提词稿里没有
        val script = "大家好我是小王。今天跟大家聊聊剪辑。"
        val us = listOf(utt("大家好我是小王", 0.0), utt("先说一下天气不错", 8.0), utt("今天跟大家聊聊剪集", 20.0))
        val r = ScriptAligner.apply(us, script)
        assertEquals(listOf("大家好我是小王", "先说一下天气不错", "今天跟大家聊聊剪辑"), r.utterances.map(::text))
        assertEquals(setOf(0, 2), r.matched)
    }

    @Test fun extraWordsInsideSentenceKept() {
        // 句中多说的“真的”保留，认错的字照样纠正
        val r = ScriptAligner.apply(listOf(utt("这个方法真的非常好拥", 0.0)), "这个方法非常好用。")
        assertEquals("这个方法真的非常好用", text(r.utterances[0]))
    }

    @Test fun missedCharFilledButSkippedPhraseNot() {
        // 漏识别一个字 → 补上；跳过一整句 → 不补
        val r = ScriptAligner.apply(
            listOf(utt("我们天来聊聊", 0.0), utt("首先打开软件然后导入视频", 10.0)),
            "我们今天来聊聊。首先打开软件，点击右上角的设置按钮，然后导入视频。",
        )
        assertEquals("我们今天来聊聊", text(r.utterances[0]))
        assertEquals("首先打开软件然后导入视频", text(r.utterances[1]))
    }

    @Test fun repeatedSentenceStaysInOrder() {
        // 同一句念了两遍（第一遍说错）：两遍都按提词稿纠正，不会对到别处
        val script = "第一点要注意光线。第二点要注意声音。"
        val us = listOf(utt("第一点要注意光现", 0.0), utt("第一点要注意光线", 10.0), utt("第二点要注意声因", 20.0))
        val r = ScriptAligner.apply(us, script)
        assertEquals("第二点要注意声音", text(r.utterances[2]))
        assertTrue(2 in r.matched)
    }

    @Test fun emptyScriptChangesNothing() {
        val us = listOf(utt("大家好", 0.0))
        val r = ScriptAligner.apply(us, "  \n ")
        assertEquals(us, r.utterances)
        assertTrue(r.matched.isEmpty())
    }
}
