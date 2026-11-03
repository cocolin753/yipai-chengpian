@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yipai.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.effect.BitmapOverlay
import com.yipai.app.core.SubtitleLine

/** 标题：主标题 + 副标题文字，或 PNG 图片（图片优先）。 */
data class TitleSpec(val text: String, val subText: String, val imagePath: String?)

/**
 * 画面叠加层：标题全程显示 + 当前字幕。
 * 使用与视频画面等大的透明位图，标题和字幕画在同一张图上。
 */
class CaptionOverlay(
    width: Int,
    height: Int,
    title: TitleSpec,
    private val lines: List<SubtitleLine>,
) : BitmapOverlay() {
    private val painter = CaptionPainter(width, height, title)
    private val titleOnly = painter.newBitmap().also { painter.draw(it, null) }
    // 两张位图轮换使用：BitmapOverlay 只在位图对象变化时重新上传纹理
    private val buffers = arrayOf(painter.newBitmap(), painter.newBitmap())
    private var bufferIndex = 0
    private var currentLine = -1
    private var current = titleOnly

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val idx = lineAt(presentationTimeUs / 1_000_000.0)
        if (idx != currentLine) {
            currentLine = idx
            current = if (idx < 0) titleOnly else {
                bufferIndex = 1 - bufferIndex
                buffers[bufferIndex].also { painter.draw(it, lines[idx].text) }
            }
        }
        return current
    }

    private fun lineAt(t: Double): Int {
        var lo = 0
        var hi = lines.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val l = lines[mid]
            when {
                t < l.start -> hi = mid - 1
                t >= l.end -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }
}

/** 负责把标题和字幕画到位图上；拍摄预览不用它，成品导出时用。 */
class CaptionPainter(private val width: Int, private val height: Int, private val title: TitleSpec) {
    private val titleImage: Bitmap? = title.imagePath?.let { BitmapFactory.decodeFile(it) }

    /** 标题文字的画笔：每次按字号重新设置 */
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSkewX = -0.1f
        strokeJoin = Paint.Join.ROUND
    }

    private val subFill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = width * 0.058f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subStroke = TextPaint(subFill).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = subFill.textSize * 0.16f
        strokeJoin = Paint.Join.ROUND
    }

    fun newBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun draw(bitmap: Bitmap, subtitle: String?) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        drawTitle(canvas)
        if (!subtitle.isNullOrEmpty()) drawSubtitle(canvas, subtitle)
    }

    private fun drawTitle(canvas: Canvas) {
        val top = height * TITLE_TOP
        val image = titleImage
        if (image != null) {
            val maxW = width * 0.9f
            val maxH = height * 0.2f
            val scale = minOf(maxW / image.width, maxH / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (width - w) / 2
            canvas.drawBitmap(image, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            return
        }
        val main = titleLines(title.text)
        val sub = titleLines(title.subText)
        val maxW = width * 0.9f
        val mainSize = if (main.isEmpty()) width * MAIN_SIZE else fitSize(main, width * MAIN_SIZE, maxW)
        var y = top
        for (line in main) y = drawStyledLine(canvas, line, mainSize, y, yellow = true)
        if (sub.isNotEmpty()) {
            val size = fitSize(sub, mainSize * 0.62f, maxW)
            if (main.isNotEmpty()) y += size * 0.08f
            for (line in sub) y = drawStyledLine(canvas, line, size, y, yellow = false)
        }
    }

    /** 同一标题的多行共用一个字号：不超过 [maxSize]，且最长的一行放得下 [maxWidth] */
    private fun fitSize(lines: List<String>, maxSize: Float, maxWidth: Float): Float =
        lines.minOf { fitSize(it, maxSize, maxWidth) }

    /** 字号：不超过 [maxSize]，且一行放得下 [maxWidth] */
    private fun fitSize(text: String, maxSize: Float, maxWidth: Float): Float {
        titlePaint.textSize = maxSize
        val w = titlePaint.measureText(text)
        return if (w > maxWidth) maxSize * maxWidth / w else maxSize
    }

    /**
     * 参照 title.png：主标题为黄色渐变粗体，副标题为白色粗体，都带黑色粗描边和投影。
     * 返回这一行下沿的 y 坐标。
     */
    private fun drawStyledLine(canvas: Canvas, text: String, size: Float, top: Float, yellow: Boolean): Float {
        val p = titlePaint
        p.textSize = size
        val fm = p.fontMetrics
        val baseline = top - fm.ascent
        val x = (width - p.measureText(text)) / 2f
        val outline = size * 0.13f

        // 黑色描边 + 投影
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = outline * 2
        p.color = Color.BLACK
        p.setShadowLayer(size * 0.1f, 0f, size * 0.07f, Color.argb(200, 0, 0, 0))
        canvas.drawText(text, x, baseline, p)
        p.clearShadowLayer()

        // 填充
        p.style = Paint.Style.FILL
        if (yellow) {
            p.shader = LinearGradient(
                0f, baseline + fm.ascent, 0f, baseline + fm.descent,
                intArrayOf(Color.rgb(255, 244, 110), Color.rgb(255, 205, 20), Color.rgb(255, 150, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        } else {
            p.color = Color.WHITE
        }
        canvas.drawText(text, x, baseline, p)
        p.shader = null
        return top + (fm.descent - fm.ascent) + outline
    }

    private fun drawSubtitle(canvas: Canvas, text: String) {
        val maxW = (width * 0.9f).toInt()
        val fill = layout(text, subFill, maxW)
        val stroke = layout(text, subStroke, maxW)
        canvas.save()
        canvas.translate((width - maxW) / 2f, height * SUBTITLE_CENTER - fill.height / 2f)
        stroke.draw(canvas)
        fill.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    companion object {
        /** 输入里的换行把标题分成多行；去掉空行和首尾空白 */
        fun titleLines(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }


        /** 标题顶部位置（占画面高度比例），拍摄预览界面也用这个值 */
        const val TITLE_TOP = 0.08f
        const val SUBTITLE_CENTER = 0.8f
        /** 主标题字号（占画面宽度比例） */
        const val MAIN_SIZE = 0.15f
    }
}
