package com.yipai.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleMotionTest {
    @Test fun holdsAtCenterForFirstFiveSeconds() {
        assertEquals(800.0, TitleMotion.top(0.0, 800.0, 150.0), 1e-9)
        assertEquals(800.0, TitleMotion.top(4.99, 800.0, 150.0), 1e-9)
    }

    @Test fun movesSmoothlyThenStaysAtTop() {
        assertEquals(475.0, TitleMotion.top(5.25, 800.0, 150.0), 1e-9)
        assertEquals(150.0, TitleMotion.top(5.5, 800.0, 150.0), 1e-9)
        assertEquals(150.0, TitleMotion.top(600.0, 800.0, 150.0), 1e-9)
    }
}
