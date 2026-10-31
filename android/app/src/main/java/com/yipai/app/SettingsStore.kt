package com.yipai.app

import android.content.Context
import com.yipai.app.core.EditSettings
import java.io.File

/** 设置与标题的本地保存 */
class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 用户选的 PNG 标题图片复制到这里 */
    val titleImageFile: File get() = File(context.filesDir, "title.png")

    var title: String
        get() = prefs.getString("title", "") ?: ""
        set(v) = prefs.edit().putString("title", v).apply()

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
