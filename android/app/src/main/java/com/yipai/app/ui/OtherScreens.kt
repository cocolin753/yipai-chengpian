@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yipai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun ProcessingScreen(state: Screen.Processing) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { /* 处理中不允许返回 */ }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在生成成品", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("${state.stage}  ${(state.progress * 100).toInt()}%")
        Spacer(Modifier.height(24.dp))
        Text("请保持在本页面，不要锁屏", color = Color.Gray)
    }
}

@Composable
fun DoneScreen(state: Screen.Done, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val result = state.result
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(result.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
        )
        Spacer(Modifier.height(12.dp))
        val plan = result.plan
        Text("已保存到相册：Movies/一拍成片")
        Text(
            "时长 %.0f 秒 → %.0f 秒　语速 %.2fx　去语气词 %d 处　去重复 %d 处".format(
                result.inputDuration, plan.outputDuration, plan.speed, plan.removedFillers, plan.removedRepeats,
            ),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Text("发布到", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Platform.entries.forEach { platform ->
                OutlinedButton(
                    onClick = { Publisher.publish(context, platform, result.uri, title) },
                    modifier = Modifier.weight(1f),
                ) { Text(platform.label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("再拍一条") }
    }
}

@Composable
fun FailedScreen(state: Screen.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("生成失败", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(state.message, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回拍摄") }
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    BackHandler(onBack = vm::backToRecord)
    val s = vm.settings
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SwitchRow("剪掉语气词（嗯、呃、啊…）", s.removeFillers) { vm.updateSettings(s.copy(removeFillers = it)) }
        SwitchRow("剪掉重复词（我们我们…）", s.removeRepeats) { vm.updateSettings(s.copy(removeRepeats = it)) }
        SwitchRow("自动调节语速", s.adjustSpeed) { vm.updateSettings(s.copy(adjustSpeed = it)) }

        SliderRow(
            "目标语速：%.1f 字/秒".format(s.targetCharsPerSecond),
            s.targetCharsPerSecond.toFloat(), 3.5f..5.5f, steps = 19,
        ) { vm.updateSettings(s.copy(targetCharsPerSecond = it.toDouble())) }
        SliderRow(
            "超过 %.2f 秒的停顿才剪".format(s.pauseThreshold),
            s.pauseThreshold.toFloat(), 0.25f..1.0f, steps = 14,
        ) { vm.updateSettings(s.copy(pauseThreshold = it.toDouble())) }
        SliderRow(
            "停顿剪完后保留 %.2f 秒".format(s.keepGap),
            s.keepGap.toFloat(), 0.05f..0.4f, steps = 6,
        ) { vm.updateSettings(s.copy(keepGap = it.toDouble())) }

        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::backToRecord, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
