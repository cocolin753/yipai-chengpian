package com.yipai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yipai.app.PrompterSettings
import kotlinx.coroutines.delay

/** 提词器设置页：输入提词稿、滚动速度、字号 */
@Composable
fun PrompterScreen(vm: AppViewModel) {
    BackHandler(onBack = vm::backToRecord)
    val p = vm.prompter
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提词器", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.updatePrompter(p.copy(script = "")) }, enabled = p.script.isNotEmpty()) { Text("清空") }
        }
        Text(
            "${p.script.count { it.isLetterOrDigit() }} 字，约 ${estimateTime(p)}。可以只念其中一部分，也可以临场加话，字幕以实际说的为准。",
            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            p.script, { vm.updatePrompter(p.copy(script = it)) },
            placeholder = { Text("粘贴或输入口播稿") },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
        )
        PrompterSwitch("拍摄时显示提词器", p.show) { vm.updatePrompter(p.copy(show = it)) }
        PrompterSwitch("按提词稿校对字幕", p.correctSubtitles) { vm.updatePrompter(p.copy(correctSubtitles = it)) }
        Text("滚动速度：%.1f 字/秒".format(p.speed))
        Slider(p.speed, { vm.updatePrompter(p.copy(speed = it)) }, valueRange = 2f..7f, steps = 49)
        Text("字号：${p.fontSize.toInt()}")
        Slider(p.fontSize, { vm.updatePrompter(p.copy(fontSize = it)) }, valueRange = 18f..40f, steps = 21)
        Button(onClick = vm::backToRecord, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}

private fun estimateTime(p: PrompterSettings): String {
    val sec = (p.script.count { it.isLetterOrDigit() } / p.speed).toInt()
    return if (sec < 60) "$sec 秒" else "${sec / 60} 分 ${sec % 60} 秒"
}

@Composable
private fun PrompterSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 拍摄时的提词器：按设定的“字/秒”匀速滚动。点文字暂停/继续，手指拖动可前后调整，
 * 下方按钮调快慢（会保存）。只显示在屏幕上，不会录进视频。
 */
@Composable
fun PrompterOverlay(p: PrompterSettings, onSpeedChange: (Float) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    var paused by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    /** 每个字对应的滚动距离（像素），由排版结果算出 */
    var pxPerChar by remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(p.speed)
    val chars = remember(p.script) { p.script.count { it.isLetterOrDigit() }.coerceAtLeast(1) }

    LaunchedEffect(Unit) {
        delay(1500) // 开拍后留一点时间再开始滚
        started = true
    }
    LaunchedEffect(started, paused, pxPerChar) {
        if (!started || paused || pxPerChar <= 0f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                scroll.dispatchRawDelta(speed * pxPerChar * (now - last) / 1e9f)
                last = now
            }
        }
    }

    Column(modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp))) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clickable { paused = !paused }) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    p.script.trim(),
                    color = Color.White,
                    fontSize = p.fontSize.sp,
                    lineHeight = (p.fontSize * 1.45f).sp,
                    fontWeight = FontWeight.Medium,
                    onTextLayout = { pxPerChar = it.size.height.toFloat() / chars },
                )
                Spacer(Modifier.height(viewport)) // 最后一行也能滚到顶部
            }
            // 阅读线：看这一行附近念即可
            Box(
                Modifier.align(Alignment.TopStart).padding(top = 8.dp + (p.fontSize * 0.6f).dp)
                    .height(3.dp).fillMaxWidth(0.03f).background(Color(0xFFFFCD14)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { onSpeedChange((p.speed - 0.2f).coerceAtLeast(2f)) }) { Text("慢", color = Color.White) }
            Text(
                if (paused) "已暂停 · 点文字继续" else "%.1f 字/秒 · 点文字暂停".format(p.speed),
                color = Color.LightGray, fontSize = 12.sp,
            )
            TextButton(onClick = { onSpeedChange((p.speed + 0.2f).coerceAtMost(7f)) }) { Text("快", color = Color.White) }
        }
    }
}
