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
import com.yipai.app.core.LlmPlanner
import com.yipai.app.core.ScriptAligner
import com.yipai.app.core.TranscriptAligner
import com.yipai.app.core.Utterance
import com.yipai.app.llm.DeepSeek
import com.yipai.app.llm.LlmConfig
import com.yipai.app.render.TitleSpec
import com.yipai.app.render.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        /** 提词稿；为空则不按提词稿校对 */
        script: String,
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
        val notes = mutableListOf<String>()
        // 对上提词稿的句子以提词稿为准，大模型不再改
        var locked = emptySet<Int>()
        if (script.isNotBlank() && rawUtterances.isNotEmpty()) {
            onProgress("按提词稿校对", 0.45f)
            val r = ScriptAligner.apply(rawUtterances, script)
            utterances = r.utterances
            locked = r.matched
            val total = rawUtterances.count { it.tokens.isNotEmpty() }
            notes += if (r.tooLong) "提词稿太长，未按提词稿校对"
            else "提词稿校对：${r.matched.size}/$total 句对上提词稿，改了 ${r.changed} 句"
        }
        if (llm.enabled && rawUtterances.isNotEmpty()) {
            onProgress("优化文字", 0.46f)
            val r = refine(llm, utterances, locked)
            utterances = r.utterances
            summary = r.summary
            keywords = r.keywords
            notes += r.notes
        }
        val note = notes.joinToString("\n")

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

    private class Refined(val utterances: List<Utterance>, val summary: String, val keywords: List<String>, val notes: List<String>)

    /**
     * 请大模型纠错并生成概要：
     * - 对上提词稿的句子（[locked]）不发去纠错；全对上时只生成概要
     * - 其余句子每批不超过 1000 字，分批并发；全文作为上下文
     * - 每句单独对齐，增删字太多的那句保留原文；某批失败只影响那一批
     */
    private suspend fun refine(cfg: LlmConfig, src: List<Utterance>, locked: Set<Int>): Refined = coroutineScope {
        val texts = src.map { if (it.tokens.isEmpty()) "" else TranscriptAligner.lineText(it) }
        val full = texts.filter { it.isNotEmpty() }.joinToString("\n")
        val batches = LlmPlanner.batches(texts, locked)
        val context = if (batches.size > 1 || locked.isNotEmpty()) full else null

        val summaryJob = async(Dispatchers.IO) { runCatching { DeepSeek.summarize(cfg, full) } }
        val batchJobs = batches.map { b ->
            async(Dispatchers.IO) { runCatching { DeepSeek.correct(cfg, b.map { texts[it] }, context) } }
        }

        val out = src.toMutableList()
        var changed = 0
        var rejected = 0
        var failed = 0
        var error: String? = null
        batches.zip(batchJobs.awaitAll()).forEach { (b, res) ->
            val lines = res.getOrElse { failed++; error = error ?: it.message; return@forEach }
            b.forEachIndexed { k, i ->
                val text = lines[k] ?: return@forEachIndexed
                val fixed = TranscriptAligner.apply(src[i], text)
                if (fixed == null) rejected++
                else {
                    if (fixed != src[i]) changed++
                    out[i] = fixed
                }
            }
        }

        val notes = mutableListOf<String>()
        val sent = batches.sumOf { it.size }
        notes += when {
            batches.isEmpty() -> "文字优化：句子都已按提词稿校对，只生成概要"
            failed == batches.size -> "文字优化失败，已用${if (locked.isEmpty()) "原识别" else "提词稿校对"}结果：$error"
            else -> "文字优化：校对 $sent 句" + (if (batches.size > 1) "（分 ${batches.size} 批）" else "") +
                "，改了 $changed 句" +
                (if (rejected > 0) "，$rejected 句改动太大已保留原文" else "") +
                (if (failed > 0) "，$failed 批失败已保留原文：$error" else "")
        }
        val sum = summaryJob.await()
        sum.exceptionOrNull()?.let { notes += "概要生成失败：${it.message}" }
        val s = sum.getOrNull()
        Refined(out, s?.summary.orEmpty(), s?.keywords.orEmpty(), notes)
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
