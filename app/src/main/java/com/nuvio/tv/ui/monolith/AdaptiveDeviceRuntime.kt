package com.nuvio.tv.ui.monolith

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.nuvio.tv.domain.model.FormFactorOverride

/**
 * Runtime form factor resolved for the single Universal APK (ARMv7 + ARM64)
 * across Phones, Tablets, and Android TV / TV Box hardware.
 */
enum class DeviceFormFactor {
    MOBILE,
    TABLET,
    TV
}

/**
 * Live input interaction mode so the same binary smoothly adjusts between
 * touch targets/gestures and D-Pad directional focus highlights.
 */
enum class InputInteractionMode {
    TOUCH,
    DPAD
}

@Immutable
data class AdaptiveDeviceState(
    val formFactor: DeviceFormFactor = DeviceFormFactor.TV,
    val inputMode: InputInteractionMode = InputInteractionMode.DPAD,
    val isLowRam32Bit: Boolean = false
)

val LocalDeviceFormFactor = compositionLocalOf { DeviceFormFactor.TV }
val LocalInputInteractionMode = compositionLocalOf { InputInteractionMode.DPAD }
val LocalAdaptiveDeviceState = compositionLocalOf { AdaptiveDeviceState() }

object AdaptiveDeviceDetector {

    fun resolveFormFactor(
        context: Context,
        configuration: Configuration,
        override: FormFactorOverride
    ): DeviceFormFactor {
        return when (override) {
            FormFactorOverride.MOBILE -> DeviceFormFactor.MOBILE
            FormFactorOverride.TABLET -> DeviceFormFactor.TABLET
            FormFactorOverride.TV -> DeviceFormFactor.TV
            FormFactorOverride.AUTO -> detectHardwareFormFactor(context, configuration)
        }
    }

    fun detectHardwareFormFactor(
        context: Context,
        configuration: Configuration
    ): DeviceFormFactor {
        val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val pm = context.packageManager
        val isTvUiMode = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
            pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH)

        if (isTvUiMode || hasLeanback || !hasTouchscreen) {
            return DeviceFormFactor.TV
        }

        val smallestWidthDp = configuration.smallestScreenWidthDp
        return if (smallestWidthDp >= 600) {
            DeviceFormFactor.TABLET
        } else {
            DeviceFormFactor.MOBILE
        }
    }

    fun defaultInputMode(formFactor: DeviceFormFactor): InputInteractionMode {
        return when (formFactor) {
            DeviceFormFactor.TV -> InputInteractionMode.DPAD
            DeviceFormFactor.MOBILE, DeviceFormFactor.TABLET -> InputInteractionMode.TOUCH
        }
    }

    fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        return am.isLowRamDevice || totalMb in 1..2200
    }

    fun isDpadOrControllerKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B -> true
            else -> false
        }
    }

    fun isDirectTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        return toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS
    }
}

fun Activity.applyAioOrientation(
    formFactor: DeviceFormFactor,
    inPlayer: Boolean
) {
    val target = when {
        formFactor == DeviceFormFactor.TV -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        inPlayer -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    if (requestedOrientation != target) {
        requestedOrientation = target
    }
}
