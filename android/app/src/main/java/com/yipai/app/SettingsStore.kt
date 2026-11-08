package com.yipai.app

import android.content.Context
import com.yipai.app.core.EditSettings
import com.yipai.app.llm.LlmConfig
import java.io.File

/** 提词器 */
data class PrompterSettings(
    val script: String = "",
    /** 拍摄时在取景框上方滚动显示 */
    val show: Boolean = true,
    /** 按提词稿校对字幕 */
    val correctSubtitles: Boolean = true,
    /** 滚动速度：字/秒 */
    val speed: Float = 4.0f,
    /** 字号（sp） */
    val fontSize: Float = 26f,
)

/** 设置与标题的本地保存 */
class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 用户选的 PNG 标题图片复制到这里 */
    val titleImageFile: File get() = File(context.filesDir, "title.png")

    var title: String
        get() = prefs.getString("title", "") ?: ""
        set(v) = prefs.edit().putString("title", v).apply()

    var subTitle: String
        get() = prefs.getString("subTitle", "") ?: ""
        set(v) = prefs.edit().putString("subTitle", v).apply()

    fun loadPrompter(): PrompterSettings {
        val d = PrompterSettings()
        return PrompterSettings(
            script = prefs.getString("prompterScript", "") ?: "",
            show = prefs.getBoolean("prompterShow", d.show),
            correctSubtitles = prefs.getBoolean("prompterCorrect", d.correctSubtitles),
            speed = prefs.getFloat("prompterSpeed", d.speed),
            fontSize = prefs.getFloat("prompterFont", d.fontSize),
        )
    }

    fun savePrompter(p: PrompterSettings) {
        prefs.edit()
            .putString("prompterScript", p.script)
            .putBoolean("prompterShow", p.show)
            .putBoolean("prompterCorrect", p.correctSubtitles)
            .putFloat("prompterSpeed", p.speed)
            .putFloat("prompterFont", p.fontSize)
            .apply()
    }

    fun loadLlm(): LlmConfig {
        val d = LlmConfig()
        return LlmConfig(
            apiKey = prefs.getString("llmKey", "") ?: "",
            baseUrl = prefs.getString("llmBase", d.baseUrl) ?: d.baseUrl,
            model = prefs.getString("llmModel", d.model) ?: d.model,
        )
    }

    fun saveLlm(c: LlmConfig) {
        prefs.edit().putString("llmKey", c.apiKey).putString("llmBase", c.baseUrl).putString("llmModel", c.model).apply()
    }

    var useTitleImage: Boolean
        get() = prefs.getBoolean("useTitleImage", false) && titleImageFile.exists()
        set(v) = prefs.edit().putBoolean("useTitleImage", v).apply()

    fun load(): EditSettings {
        val d = EditSettings()
        return d.copy(
            pauseThreshold = prefs.getFloat("pauseThreshold", d.pauseThreshold.toFloat()).toDouble(),
            keepGap = prefs.getFloat("keepGap", d.keepGap.toFloat()).toDouble(),
            removeFillers = prefs.getBoolean("removeFillers", d.removeFillers),
            removeRepeats = prefs.getBoolean("removeRepeats", d.removeRepeats),
            adjustSpeed = prefs.getBoolean("adjustSpeed", d.adjustSpeed),
            targetCharsPerSecond = prefs.getFloat("targetCps", d.targetCharsPerSecond.toFloat()).toDouble(),
        )
    }

    fun save(s: EditSettings) {
        prefs.edit()
            .putFloat("pauseThreshold", s.pauseThreshold.toFloat())
            .putFloat("keepGap", s.keepGap.toFloat())
            .putBoolean("removeFillers", s.removeFillers)
            .putBoolean("removeRepeats", s.removeRepeats)
            .putBoolean("adjustSpeed", s.adjustSpeed)
            .putFloat("targetCps", s.targetCharsPerSecond.toFloat())
            .apply()
    }
}
