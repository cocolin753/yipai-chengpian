package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutPlannerTest {

    /** 按每字 0.2 秒生成一段话；文本中的标点会挂到前一个字上 */
    private fun utter(text: String, start: Double, charDur: Double = 0.2): Utterance {
        val tokens = mutableListOf<Token>()
        var t = start
        for (c in text) {
            if (c in "，。！？、") {
                val last = tokens.removeAt(tokens.lastIndex)
                tokens += last.copy(punctAfter = c.toString())
            } else {
                tokens += Token(c.toString(), t, t + charDur)
                t += charDur
            }
        }
        return Utterance(start, t, tokens)
    }

    private fun keptText(utterances: List<Utterance>, plan: EditPlan): String =
        utterances.flatMap { it.tokens }
            .filter { tk -> plan.keep.any { tk.start >= it.start - 1e-9 && tk.end <= it.end + 1e-9 } }
            .joinToString("") { it.text }

    private val noSpeed = EditSettings(adjustSpeed = false)

    @Test
    fun longPauseIsCompressed() {
        val u = listOf(utter("你好", 0.5), utter("世界", 3.0))
        val plan = CutPlanner.plan(u, 5.0, noSpeed)
        assertEquals(2, plan.keep.size)
        val kept = (plan.keep[0].end - 0.9) + (3.0 - plan.keep[1].start)
        assertEquals(0.15, kept, 1e-6)
        assertEquals(0.25, plan.keep[0].start, 1e-6)
    }

    @Test
    fun shortPauseIsKept() {
        val u = listOf(utter("你好", 0.5), utter("世界", 1.2))
        val plan = CutPlanner.plan(u, 3.0, noSpeed)
        assertEquals(1, plan.keep.size)
    }

    @Test
    fun strongFillerRemoved() {
        val u = listOf(utter("我觉得嗯这个好", 0.0))
        val plan = CutPlanner.plan(u, 2.0, noSpeed)
        assertEquals("我觉得这个好", keptText(u, plan))
        assertEquals(1, plan.removedFillers)
    }

    @Test
    fun weakFillerOnlyWhenIsolated() {
        val u = listOf(utter("啊，我们开始。", 0.0), utter("是啊。额外的", 3.0))
        val plan = CutPlanner.plan(u, 6.0, noSpeed)
        assertEquals("我们开始是啊额外的", keptText(u, plan))
    }

    @Test
    fun repeatedWordsRemoved() {
        val u = listOf(utter("我们我们今天讲", 0.0))
        val plan = CutPlanner.plan(u, 3.0, noSpeed)
        assertEquals("我们今天讲", keptText(u, plan))
    }

    @Test
    fun reduplicationKept() {
        val u = listOf(utter("谢谢大家一点一点来", 0.0))
        val plan = CutPlanner.plan(u, 3.0, noSpeed)
        assertEquals("谢谢大家一点一点来", keptText(u, plan))
    }

    @Test
    fun stutterRemoved() {
        val u = listOf(utter("我我我觉得", 0.0), utter("是", 2.0), utter("是对的", 3.0))
        val plan = CutPlanner.plan(u, 5.0, noSpeed)
        assertEquals("我觉得是对的", keptText(u, plan))
    }

    @Test
    fun sentenceEndIsNotRepeat() {
        val u = listOf(utter("这是我。", 0.0), utter("我来讲", 1.5))
        val plan = CutPlanner.plan(u, 3.0, noSpeed)
        assertEquals("这是我我来讲", keptText(u, plan))
    }

    @Test
    fun speedIsClamped() {
        val fast = listOf(utter("一二三四五六七八九十一二三四五六七八九十", 0.0, charDur = 0.1))
        assertEquals(0.9, CutPlanner.plan(fast, 3.0).speed, 1e-9)
        val slow = listOf(utter("一二三四五六七八九十一二三四五六七八九十", 0.0, charDur = 0.5))
        assertEquals(1.3, CutPlanner.plan(slow, 11.0).speed, 1e-9)
    }

    @Test
    fun subtitlesFollowOutputTimeline() {
        val u = listOf(utter("大家好，", 0.5), utter("今天讲剪辑。", 5.0))
        val plan = CutPlanner.plan(u, 8.0, noSpeed)
        assertEquals(listOf("大家好", "今天讲剪辑"), plan.subtitles.map { it.text })
        assertEquals(0.25, plan.subtitles[0].start, 1e-6)
        assertTrue(plan.subtitles[1].start < 1.5)
        assertTrue(plan.subtitles[1].end <= plan.outputDuration + 1e-6)
    }

    @Test
    fun longLineIsSplit() {
        val u = listOf(utter("一二三四五六七八九十一二三四五六七八九十", 0.0))
        val plan = CutPlanner.plan(u, 5.0, noSpeed)
        assertEquals(listOf(14, 6), plan.subtitles.map { it.text.length })
    }

    @Test
    fun noSpeechKeepsWholeVideo() {
        val plan = CutPlanner.plan(emptyList(), 4.0)
        assertEquals(listOf(TimeRange(0.0, 4.0)), plan.keep)
    }

    @Test
    fun timelineMapsAcrossCuts() {
        val tl = Timeline(listOf(TimeRange(1.0, 2.0), TimeRange(5.0, 7.0)), 2.0)
        assertEquals(0.0, tl.map(1.0), 1e-9)
        assertEquals(0.5, tl.map(2.0), 1e-9)
        assertEquals(1.0, tl.map(6.0), 1e-9)
    }
}
