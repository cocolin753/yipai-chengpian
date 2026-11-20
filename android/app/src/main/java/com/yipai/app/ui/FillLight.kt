package com.yipai.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.Camera
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 环境光低于这个值（lux）算偏暗，自动模式录制时补光。普通客厅晚上开灯约 50~150 lux */
const val DARK_LUX = 30f

/** 屏幕补光颜色：暖光约 4000K（肤色更柔和），白光最亮 */
fun fillLightColor(warm: Boolean) = if (warm) Color(0xFFFFD1A3) else Color.White

/** 屏幕补光光带宽度 */
val FillLightRingWidth = 40.dp

/** 环境光（lux），手机正面的光线传感器；没有传感器或还没读到时为 NaN */
@Composable
fun rememberAmbientLux(): MutableFloatState {
    val context = LocalContext.current
    val lux = remember { mutableFloatStateOf(Float.NaN) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux.floatValue = e.values[0] }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }
    return lux
}

/**
 * 把补光状态应用到相机：
 * - 后置：闪光灯常亮，亮度用最大值的 60%，避免太刺眼、太烫
 * - 系统暗光增强（Android 15 部分机型）：补光模式不是“关”且没开闪光灯时打开；两者同时用部分机型会冲突
 */
fun applyFillLight(camera: Camera, torch: Boolean, lowLightBoost: Boolean) {
    val info = camera.cameraInfo
    val control = camera.cameraControl
    val useTorch = torch && info.hasFlashUnit()
    if (info.isLowLightBoostSupported) {
        // 先关增强再开灯，先关灯再开增强
        if (useTorch) control.enableLowLightBoostAsync(false)
    }
    if (info.hasFlashUnit()) {
        control.enableTorch(useTorch)
        if (useTorch && info.isTorchStrengthSupported) {
            control.setTorchStrengthLevel(maxOf(1, (info.maxTorchStrengthLevel * 0.6f).toInt()))
        }
    }
    if (info.isLowLightBoostSupported && !useTorch) {
        control.enableLowLightBoostAsync(lowLightBoost)
    }
}

/** 前置屏幕补光：取景框四周一圈光带（只在屏幕上，不会录进视频） */
@Composable
fun ScreenFillLightRing(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().border(FillLightRingWidth, color))
}

/** [enabled] 时把屏幕亮度调到最高，离开时恢复 */
@Composable
fun MaxScreenBrightness(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = context.findActivity()?.window
        val old = window?.attributes?.screenBrightness
        if (enabled && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (enabled && window != null && old != null) {
                window.attributes = window.attributes.apply { screenBrightness = old }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
