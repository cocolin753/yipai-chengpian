package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptAlignerTest {
    private fun utt(vararg chars: String) = Utterance(
        0.0, chars.size.toDouble(),
        chars.mapIndexed { i, c -> Token(c, i.toDouble(), i + 1.0, if (i == chars.lastIndex) "。" else "") },
    )

    @Test fun sameLengthReplacesCharsAndKeepsTiming() {
        val u = utt("我", "们", "今", "天", "来", "谈", "谈")
        val r = TranscriptAligner.apply(u, "我们今天来聊聊。")!!
        assertEquals("我们今天来聊聊", r.tokens.joinToString("") { it.text })
        assertEquals(u.tokens.map { it.start }, r.tokens.map { it.start })
        assertEquals("。", r.tokens.last().punctAfter)
    }

    @Test fun lengthMismatchRejected() {
        assertNull(TranscriptAligner.apply(utt("我", "们", "好"), "大家好啊"))
    }
}
