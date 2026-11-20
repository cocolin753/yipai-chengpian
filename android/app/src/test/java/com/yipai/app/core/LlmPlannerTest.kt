package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmPlannerTest {
    @Test fun skipsLockedAndEmptyLines() {
        assertEquals(listOf(listOf(0, 3)), LlmPlanner.batches(listOf("甲", "乙", "", "丁"), setOf(1)))
    }

    @Test fun allLockedMeansSummaryOnly() {
        assertEquals(emptyList<List<Int>>(), LlmPlanner.batches(listOf("甲", "乙"), setOf(0, 1)))
    }

    @Test fun splitsByChars() {
        val lines = listOf("一二三", "四五六", "七八", "九")
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), LlmPlanner.batches(lines, emptySet(), maxChars = 5))
    }

    @Test fun overlongLineGetsOwnBatch() {
        assertEquals(listOf(listOf(0), listOf(1)), LlmPlanner.batches(listOf("一二三四五六", "七"), emptySet(), maxChars = 5))
    }
}
