package com.yipai.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yipai.app.Pipeline
import com.yipai.app.PipelineResult
import com.yipai.app.SettingsStore
import com.yipai.app.core.EditSettings
import com.yipai.app.llm.LlmConfig
import com.yipai.app.render.TitleSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Record : Screen
    data object Settings : Screen
    data class Processing(val stage: String, val progress: Float) : Screen
    data class Done(val result: PipelineResult) : Screen
    data class Failed(val message: String, val input: File) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var screen by mutableStateOf<Screen>(Screen.Record)
        private set
    var titleText by mutableStateOf(store.title)
        private set
    var subTitleText by mutableStateOf(store.subTitle)
        private set
    var llm by mutableStateOf(store.loadLlm())
        private set
    var useTitleImage by mutableStateOf(store.useTitleImage)
        private set
    /** 每次换图片 +1，让预览重新加载 */
    var titleImageVersion by mutableIntStateOf(0)
        private set
    var settings by mutableStateOf(store.load())
        private set
    var message by mutableStateOf<String?>(null)

    val titleImageFile: File get() = store.titleImageFile

    fun updateTitle(text: String) {
        titleText = text
        store.title = text
    }

    fun updateSubTitle(text: String) {
        subTitleText = text
        store.subTitle = text
    }

    fun updateLlm(c: LlmConfig) {
        llm = c
        store.saveLlm(c)
    }

    /** 发布时复制到剪贴板的全部文字：标题 + 概要 + 关键字（带 # 号） */
    fun publishText(result: PipelineResult): String = buildString {
        val head = listOf(titleText, subTitleText).filter { it.isNotBlank() }.joinToString(" ")
        if (head.isNotBlank()) append(head)
        if (result.summary.isNotBlank()) { if (isNotEmpty()) append('\n'); append(result.summary) }
        if (result.keywords.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(result.keywords.joinToString(" ") { "#$it" })
        }
    }

    fun setTitleImage(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(titleImageFile.path + ".tmp")
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.path, opts)
                    if (opts.outWidth <= 0) { tmp.delete(); false } else tmp.renameTo(titleImageFile)
                }.getOrDefault(false)
            }
            if (ok) {
                useTitleImage = true
                store.useTitleImage = true
                titleImageVersion++
            } else {
                message = "图片读取失败，请选择 PNG 图片"
            }
        }
    }

    fun clearTitleImage() {
        useTitleImage = false
        store.useTitleImage = false
    }

    fun updateSettings(s: EditSettings) {
        settings = s
        store.save(s)
    }

    fun openSettings() { screen = Screen.Settings }

    fun backToRecord() { screen = Screen.Record }

    /** 开始新录制前清理旧的原始录像 */
    fun newRecordingFile(): File {
        val dir = getApplication<Application>().cacheDir
        dir.listFiles { f -> f.name.startsWith("rec_") }?.forEach { it.delete() }
        return File(dir, "rec_${System.currentTimeMillis()}.mp4")
    }

    fun process(input: File) {
        val title = TitleSpec(titleText, subTitleText, if (useTitleImage) titleImageFile.path else null)
        val s = settings
        val llmConfig = llm
        screen = Screen.Processing("准备中", 0f)
        viewModelScope.launch {
            screen = try {
                val result = Pipeline(getApplication()).run(input, title, s, llmConfig) { stage, p ->
                    screen = Screen.Processing(stage, p)
                }
                Screen.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Screen.Failed(e.message ?: e.toString(), input)
            }
        }
    }
}
