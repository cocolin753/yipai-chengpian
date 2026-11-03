package com.yipai.app.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yipai.app.render.CaptionPainter

private const val MAX_DURATION_MS = 10 * 60 * 1000L

@SuppressLint("MissingPermission") // 进入本页面前已申请相机和录音权限
@Composable
fun RecordScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var useFront by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
            )
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    LaunchedEffect(useFront) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
    }
    DisposableEffect(Unit) {
        onDispose { recording?.stop() }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setTitleImage(uri)
    }

    fun startRecording() {
        val file = vm.newRecordingFile()
        elapsedMs = 0
        recording = recorder
            .prepareRecording(context, FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_DURATION_MS).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        when {
                            !ok -> Toast.makeText(context, "录制失败（${event.error}）", Toast.LENGTH_LONG).show()
                            elapsedMs < 1500 -> Toast.makeText(context, "录制太短", Toast.LENGTH_SHORT).show()
                            else -> vm.process(file)
                        }
                    }
                }
            }
    }

    val isRecording = recording != null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 9:16 取景框，与成品画面一致；标题位置也按成品比例摆放
        BoxWithConstraints(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
                .aspectRatio(9f / 16f),
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            TitlePreview(
                vm = vm,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * CaptionPainter.TITLE_TOP)
                    .widthIn(max = maxWidth * 0.9f),
                imageMaxHeight = maxHeight * 0.2f,
            )
            if (isRecording) {
                Text(
                    formatTime(elapsedMs),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color(0xCCE8453C), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (!isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.useTitleImage) {
                        Text("标题：PNG 图片", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickImage.launch("image/png") }) { Text("换图") }
                        TextButton(onClick = vm::clearTitleImage) { Text("改用文字") }
                    } else {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = vm.titleText,
                                onValueChange = vm::updateTitle,
                                placeholder = { Text("主标题（大字黄色，回车换行）") },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = vm.subTitleText,
                                onValueChange = vm::updateSubTitle,
                                placeholder = { Text("副标题（白字，可不填，回车换行）") },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(onClick = { pickImage.launch("image/png") }) { Text("用图片") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::openSettings, enabled = !isRecording) { Text("设置") }
                RecordButton(isRecording) {
                    if (isRecording) recording?.stop() else startRecording()
                }
                TextButton(onClick = { useFront = !useFront }, enabled = !isRecording) { Text("翻转") }
            }
        }
    }
}

@Composable
private fun TitlePreview(vm: AppViewModel, modifier: Modifier, imageMaxHeight: Dp) {
    if (vm.useTitleImage) {
        val bitmap = remember(vm.titleImageVersion) {
            BitmapFactory.decodeFile(vm.titleImageFile.path)?.asImageBitmap()
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = "标题图片", contentScale = ContentScale.Fit, modifier = modifier.heightIn(max = imageMaxHeight))
        }
    } else if (vm.titleText.isNotBlank() || vm.subTitleText.isNotBlank()) {
        val main = CaptionPainter.titleLines(vm.titleText)
        val sub = CaptionPainter.titleLines(vm.subTitleText)
        // 字号：一个汉字约等于一个字号宽，按 9:16 取景框约 300dp 宽估算，与成品的“放得下就最大”一致；
        // 多行时按最长的一行算，同一标题各行字号相同
        val mainSize = minOf(44f, 310f / maxOf(main.maxOfOrNull { it.length } ?: 1, 1))
        val subSize = minOf(mainSize * 0.62f, 310f / maxOf(sub.maxOfOrNull { it.length } ?: 1, 1))
        val gradient = Brush.verticalGradient(listOf(Color(0xFFFFF46E), Color(0xFFFFCD14), Color(0xFFFF9600)))
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            main.forEach { OutlinedTitleText(it, mainSize.sp, gradient) }
            sub.forEach { OutlinedTitleText(it, subSize.sp, SolidColor(Color.White)) }
        }
    }
}

/** 黑色粗描边 + 填充（渐变或纯色），与成品里的标题样式一致 */
@Composable
private fun OutlinedTitleText(text: String, size: TextUnit, fill: Brush) {
    val strokePx = with(LocalDensity.current) { size.toPx() * 0.26f }
    val base = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
    Box {
        Text(
            text, maxLines = 1, softWrap = false,
            style = base.copy(color = Color.Black, drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round)),
        )
        Text(text, maxLines = 1, softWrap = false, style = base.copy(brush = fill))
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (isRecording) 28.dp else 56.dp)
                .background(Color(0xFFE8453C), if (isRecording) RoundedCornerShape(6.dp) else CircleShape),
        )
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d / 10:00".format(s / 60, s % 60)
}
