package com.yipai.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yipai.app.asr.AudioDecoder
import com.yipai.app.asr.SpeechRecognizer
import com.yipai.app.core.CutPlanner
import com.yipai.app.core.EditPlan
import com.yipai.app.core.EditSettings
import com.yipai.app.core.TranscriptAligner
import com.yipai.app.core.Utterance
import com.yipai.app.llm.DeepSeek
import com.yipai.app.llm.LlmConfig
import com.yipai.app.render.TitleSpec
import com.yipai.app.render.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PipelineResult(
    val uri: Uri,
    val inputDuration: Double,
    val plan: EditPlan,
    /** 大模型给出的内容概要和关键字（没配置或失败时为空） */
    val summary: String = "",
    val keywords: List<String> = emptyList(),
    /** 大模型纠错后实际改动的字幕行数；失败原因见 [llmNote] */
    val llmNote: String = "",
)

/** 一键成片：提取声音 → 识别 → 精剪决策 → 导出 → 存相册 */
class Pipeline(private val context: Context) {

    suspend fun run(
        input: File,
        title: TitleSpec,
        settings: EditSettings,
        llm: LlmConfig,
        onProgress: (stage: String, progress: Float) -> Unit,
    ): PipelineResult = withContext(Dispatchers.Default) {
        val info = probe(input)

        onProgress("提取声音", 0f)
        val pcm = AudioDecoder.decode(input.path) { onProgress("提取声音", it * 0.05f) }

        onProgress("识别语音（加载模型）", 0.05f)
        val rawUtterances = SpeechRecognizer(context.assets).use { asr ->
            asr.recognize(pcm) { onProgress("识别语音", 0.05f + it * 0.4f) }
        }

        var utterances = rawUtterances
        var summary = ""
        var keywords = emptyList<String>()
        var note = ""
        if (llm.enabled && rawUtterances.isNotEmpty()) {
            onProgress("优化文字", 0.45f)
            try {
                val r = refine(llm, rawUtterances)
                utterances = r.utterances
                summary = r.summary
                keywords = r.keywords
                note = "文字优化：改了 ${r.changed} 句" + if (r.rejected > 0) "，${r.rejected} 句字数不符已保留原文" else ""
            } catch (e: Exception) {
                note = "文字优化失败，已用原识别结果：${e.message}"
            }
        }

        val plan = CutPlanner.plan(utterances, info.duration, settings)

        onProgress("生成视频", 0.5f)
        val output = File(context.cacheDir, "out_${System.currentTimeMillis()}.mp4")
        try {
            VideoRenderer(context).render(input, output, plan, title, info.width, info.height) {
                onProgress("生成视频", 0.5f + it * 0.45f)
            }
            onProgress("保存到相册", 0.97f)
            val uri = saveToGallery(output)
            PipelineResult(uri, info.duration, plan, summary, keywords, note)
        } finally {
            output.delete()
        }
    }

    private class Refined(val utterances: List<Utterance>, val summary: String, val keywords: List<String>, val changed: Int, val rejected: Int)

    /** 请大模型纠错：每句单独校验字数，不符的那句保留原文 */
    private fun refine(cfg: LlmConfig, src: List<Utterance>): Refined {
        val idx = src.indices.filter { src[it].tokens.isNotEmpty() }
        val resp = DeepSeek.refine(cfg, idx.map { TranscriptAligner.lineText(src[it]) })
        val out = src.toMutableList()
        var changed = 0
        var rejected = 0
        idx.forEachIndexed { k, i ->
            val text = resp.lines[k] ?: return@forEachIndexed
            val fixed = TranscriptAligner.apply(src[i], text)
            if (fixed == null) rejected++
            else {
                if (fixed != src[i]) changed++
                out[i] = fixed
            }
        }
        return Refined(out, resp.summary, resp.keywords, changed, rejected)
    }

    private data class VideoInfo(val duration: Double, val width: Int, val height: Int)

    /** 读取时长和“摆正后”的画面尺寸（手机竖拍的视频通常带 90° 旋转标记） */
    private fun probe(file: File): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            return if (rot % 180 == 0) VideoInfo(ms / 1000.0, w, h) else VideoInfo(ms / 1000.0, h, w)
        } finally {
            r.release()
        }
    }

    private fun saveToGallery(file: File): Uri {
        val name = "一拍成片_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/一拍成片")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("无法写入相册")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
