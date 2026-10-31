@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yipai.app.render

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.yipai.app.core.EditPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** 用 Media3 Transformer 一次导出：裁剪拼接 + 变速（不变调）+ 标题 + 字幕 + 硬件编码。 */
class VideoRenderer(private val context: Context) {

    suspend fun render(
        input: File,
        output: File,
        plan: EditPlan,
        title: TitleSpec,
        frameWidth: Int,
        frameHeight: Int,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val composition = buildComposition(input, plan, title, frameWidth, frameHeight)
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)

            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    handler.postDelayed(this, 300)
                }
            }
            handler.post(poll)
            cont.invokeOnCancellation { handler.post { transformer.cancel() } }
        }
    }

    private fun buildComposition(input: File, plan: EditPlan, title: TitleSpec, w: Int, h: Int): Composition {
        val uri = Uri.fromFile(input)
        val speed = plan.speed.toFloat()
        val items = plan.keep.map { r ->
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs((r.start * 1e6).toLong())
                        .setEndPositionUs((r.end * 1e6).toLong())
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(mediaItem).apply {
                if (abs(speed - 1f) > 0.01f) setSpeed(SpeedParameters(ConstantSpeed(speed), true))
            }.build()
        }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        val overlay = OverlayEffect(listOf(CaptionOverlay(w, h, title, plan.subtitles)))
        return Composition.Builder(sequence)
            .setEffects(Effects(emptyList(), listOf(overlay)))
            .build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }
}
