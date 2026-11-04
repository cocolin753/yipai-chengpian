package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptAlignerTest {
    private fun utt(vararg chars: String) = Utterance(
        0.0, chars.size.toDouble(),
        chars.mapIndexed { i, c -> Token(c, i.toDouble(), i + 1.0, if (i == chars.lastIndex) "。" else "") },
    )

    private fun text(u: Utterance) = u.tokens.joinToString("") { it.text }

    @Test fun sameLengthReplacesCharsAndKeepsTiming() {
        val u = utt("我", "们", "今", "天", "来", "谈", "谈")
        val r = TranscriptAligner.apply(u, "我们今天来聊聊。")!!
        assertEquals("我们今天来聊聊", text(r))
        assertEquals(u.tokens.map { it.start }, r.tokens.map { it.start })
        assertEquals("。", r.tokens.last().punctAfter)
        assertEquals(listOf("我", "们", "今", "天", "来", "谈", "谈"), r.tokens.map { it.heard })
    }

    @Test fun insertedCharJoinsPreviousToken() {
        val u = utt("我", "们", "天", "来", "聊", "聊")
        val r = TranscriptAligner.apply(u, "我们今天来聊聊。")!!
        assertEquals("我们今天来聊聊", text(r))
        assertEquals(u.tokens.size, r.tokens.size)
        assertEquals(u.tokens.map { it.start }, r.tokens.map { it.start })
    }

    @Test fun insertedCharAtStart() {
        val r = TranscriptAligner.apply(utt("家", "好", "啊"), "大家好啊")!!
        assertEquals("大家好啊", text(r))
        assertEquals("大家", r.tokens[0].text)
    }

    @Test fun deletedCharBecomesEmptyButKeepsTiming() {
        val u = utt("我", "们", "们", "今", "天", "好")
        val r = TranscriptAligner.apply(u, "我们今天好")!!
        assertEquals("我们今天好", text(r))
        assertEquals(6, r.tokens.size)
        assertEquals(1, r.tokens.count { it.text.isEmpty() })
    }

    @Test fun rewriteRejected() {
        val u = utt("我", "们", "今", "天", "来", "聊", "聊")
        assertNull(TranscriptAligner.apply(u, "今天我们一起来好好地聊一聊吧"))
    }

    @Test fun cutPlanUsesHeardText() {
        // 纠错把语气词“嗯”改成了别的字，剪辑时仍按识别原文把它删掉
        val u = Utterance(0.0, 3.0, listOf(
            Token("嗯", 0.0, 1.0, "，"), Token("好", 1.0, 2.0), Token("的", 2.0, 3.0, "。"),
        ))
        val fixed = u.copy(tokens = u.tokens.mapIndexed { i, t -> if (i == 0) t.copy(text = "恩") else t })
        val a = CutPlanner.plan(listOf(u), 3.0)
        val b = CutPlanner.plan(listOf(fixed), 3.0)
        assertEquals(a.keep, b.keep)
        assertEquals(a.removedFillers, b.removedFillers)
    }
}
