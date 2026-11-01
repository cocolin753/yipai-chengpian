package com.yipai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 发布到各平台。各平台的官方分享 SDK 都需要注册开发者应用，自用 App 无法申请，
 * 所以用系统分享把视频直接交给目标 App；做不到的（视频号）就打开微信并提示操作路径。
 * 标题会复制到剪贴板，在发布页直接粘贴即可。
 */
enum class Platform(val label: String, val packageName: String, val sendDirectly: Boolean, val hint: String) {
    DOUYIN("抖音", "com.ss.android.ugc.aweme", true, "已打开抖音，文案已复制，可直接粘贴"),
    XIAOHONGSHU("小红书", "com.xingin.xhs", true, "已打开小红书，文案已复制，可直接粘贴"),
    CHANNELS(
        "视频号", "com.tencent.mm", false,
        "视频已在相册。在微信：发现 → 视频号 → 右上角发表 → 从相册选择。文案已复制",
    ),
}

object Publisher {
    fun publish(context: Context, platform: Platform, video: Uri, title: String) {
        if (title.isNotBlank()) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("发布文案", title))
        }
        val launched = (platform.sendDirectly && sendTo(context, platform.packageName, video, title)) ||
            openApp(context, platform.packageName)
        val msg = if (launched) platform.hint else "没有安装${platform.label}"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun sendTo(context: Context, pkg: String, video: Uri, title: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, video)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_TEXT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun openApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
