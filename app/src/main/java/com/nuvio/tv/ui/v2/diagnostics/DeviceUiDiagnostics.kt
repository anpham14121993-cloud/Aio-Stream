package com.nuvio.tv.ui.v2.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.nuvio.tv.BuildConfig
import java.util.Locale

/** Capture from the Activity view before opening a dialog, whose window has different bounds. */
fun readUiCanvasSnapshot(context: Context, view: View): UiCanvasSnapshot {
    val metrics = context.resources.displayMetrics
    val configuration = context.resources.configuration
    val root = view.rootView
    val mode = view.display?.mode
    val bounds = if (Build.VERSION.SDK_INT >= 30) {
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.currentWindowMetrics?.bounds
    } else null
    return UiCanvasSnapshot(
        windowWidthPx = root.width.takeIf { it > 0 } ?: bounds?.width() ?: metrics.widthPixels,
        windowHeightPx = root.height.takeIf { it > 0 } ?: bounds?.height() ?: metrics.heightPixels,
        baseDensity = metrics.density,
        densityDpi = metrics.densityDpi,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        modeId = mode?.modeId,
        outputWidthPx = mode?.physicalWidth,
        outputHeightPx = mode?.physicalHeight,
        refreshRateHz = mode?.refreshRate
    )
}

fun deviceUiDiagnosticReport(
    context: Context,
    view: View,
    scalePercent: Int,
    scaleReason: String = "Cổ điển"
): String {
    val canvas = readUiCanvasSnapshot(context, view)
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memory = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
    val frames = UiFrameDiagnostics.frames.snapshot(System.nanoTime())
    fun number(value: Number?): String = value?.let { String.format(Locale.US, "%.2f", it.toDouble()) } ?: "không khả dụng"
    return buildString {
        appendLine("${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
        appendLine("AioStream ${BuildConfig.VERSION_NAME} / ${BuildConfig.BUILD_TYPE}")
        appendLine("Cửa sổ ứng dụng: ${canvas.windowWidthPx} x ${canvas.windowHeightPx} px")
        appendLine("Mật độ điểm ảnh: ${number(canvas.baseDensity)} / ${canvas.densityDpi} dpi")
        appendLine("Màn hình hệ thống: ${canvas.screenWidthDp} x ${canvas.screenHeightDp} dp")
        appendLine("Khung gốc: ${number(canvas.logicalWidthDp)} x ${number(canvas.logicalHeightDp)} dp")
        appendLine("Tỉ lệ giao diện: $scalePercent% / $scaleReason")
        appendLine("Khung hiển thị thực: ${number(canvas.effectiveWidthDp(scalePercent))} x ${number(canvas.effectiveHeightDp(scalePercent))} dp")
        appendLine("Chế độ xuất ${canvas.modeId ?: "không khả dụng"}: ${canvas.outputWidthPx ?: "?"} x ${canvas.outputHeightPx ?: "?"} @ ${number(canvas.refreshRateHz)} Hz")
        appendLine("RAM: ${number(memory?.totalMem?.div(1_073_741_824.0))} GiB / heap: ${manager?.memoryClass ?: "?"} MiB / RAM thấp: ${manager?.isLowRamDevice ?: "?"}")
        appendLine("Tăng tốc phần cứng: ${view.isHardwareAccelerated}")
        appendLine("Khung hình gần đây (30s / 2048 frames): ${frames.frames}")
        appendLine("JankStats jank: ${number(frames.jankPercent)}%")
        appendLine("UI-thread ms P50 / P95 / P99: ${number(frames.p50UiMs)} / ${number(frames.p95UiMs)} / ${number(frames.p99UiMs)}")
        append("Dữ liệu đo trực tiếp trên thiết bị hiện tại.")
    }
}
