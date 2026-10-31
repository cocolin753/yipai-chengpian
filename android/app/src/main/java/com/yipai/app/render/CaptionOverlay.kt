@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yipai.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.effect.BitmapOverlay
import com.yipai.app.core.SubtitleLine

/** 标题：文字或 PNG 图片（图片优先）。 */
data class TitleSpec(val text: String, val imagePath: String?)

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

    private val titleFill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = width * 0.068f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val titleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }

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
        if (title.text.isBlank()) return
        val maxW = (width * 0.84f).toInt()
        val layout = layout(title.text, titleFill, maxW)
        val textW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val padX = titleFill.textSize * 0.5f
        val padY = titleFill.textSize * 0.3f
        val left = (width - textW) / 2
        canvas.drawRoundRect(
            RectF(left - padX, top - padY, left + textW + padX, top + layout.height + padY),
            titleFill.textSize * 0.3f, titleFill.textSize * 0.3f, titleBg,
        )
        canvas.save()
        canvas.translate((width - maxW) / 2f, top)
        layout.draw(canvas)
        canvas.restore()
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
        /** 标题顶部位置（占画面高度比例），拍摄预览界面也用这个值 */
        const val TITLE_TOP = 0.08f
        const val SUBTITLE_CENTER = 0.8f
    }
}
