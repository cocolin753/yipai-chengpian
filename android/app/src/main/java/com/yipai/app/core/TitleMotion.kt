package com.yipai.app.core

/**
 * 标题位置随时间变化：开头 [HOLD] 秒停在画面正中（平台自动截封面时标题完整、醒目），
 * 之后用 [MOVE] 秒平滑移到顶部，给画面和字幕让位。时间为成品时间轴。
 */
object TitleMotion {
    const val HOLD = 5.0
    const val MOVE = 0.5

    /** 移动进度 0（正中）~ 1（顶部），先加速后减速 */
    fun progress(t: Double): Double {
        val x = ((t - HOLD) / MOVE).coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    /** 标题顶边的 y：[centerTop] 为居中时的顶边，[finalTop] 为最终的顶边 */
    fun top(t: Double, centerTop: Double, finalTop: Double): Double =
        centerTop + (finalTop - centerTop) * progress(t)
}
