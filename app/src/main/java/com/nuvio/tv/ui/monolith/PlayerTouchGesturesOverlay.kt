package com.nuvio.tv.ui.monolith

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nuvio.tv.ui.screens.player.PlaybackTimelineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private sealed interface TouchHudFeedback {
    data class Volume(val percent: Int) : TouchHudFeedback
    data class Brightness(val percent: Int) : TouchHudFeedback
    data class SeekPreview(val deltaMs: Long, val targetMs: Long) : TouchHudFeedback
    data class DoubleTapAction(val icon: ImageVector, val label: String) : TouchHudFeedback
}

private enum class ActiveDragAxis {
    UNDECIDED,
    HORIZONTAL_SEEK,
    LEFT_BRIGHTNESS,
    RIGHT_VOLUME
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun formatSignedSeekTime(ms: Long): String {
    val totalSeconds = (abs(ms) / 1000L).toInt()
    val sign = if (ms >= 0) "+" else "-"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%s%d:%02d:%02d".format(sign, hours, minutes, seconds)
    } else {
        "%s%02d:%02d".format(sign, minutes, seconds)
    }
}

private fun formatAbsoluteTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = (safeMs / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Detects an unconsumed single tap on empty areas of an overlay without intercepting
 * child button/slider touches or full-screen swipe gestures beneath it.
 */
@Composable
fun Modifier.playerEmptySpaceTapToHide(
    enabled: Boolean,
    onHideControls: () -> Unit
): Modifier {
    val latestOnHideControls by rememberUpdatedState(onHideControls)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val tapAllowPx = maxOf(touchSlop * 2.5f, with(LocalDensity.current) { 28.dp.toPx() })
    return if (enabled) {
        pointerInput(tapAllowPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var anyConsumed = down.isConsumed
                var totalDx = 0f
                var totalDy = 0f
                var maxTravel = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val activeChange = event.changes.firstOrNull { it.id == down.id }
                    if (activeChange == null || !activeChange.pressed) {
                        if (!anyConsumed && activeChange?.isConsumed != true && maxTravel <= tapAllowPx) {
                            activeChange?.consume()
                            latestOnHideControls()
                        }
                        break
                    }
                    if (activeChange.isConsumed) {
                        anyConsumed = true
                    }
                    val delta = activeChange.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y
                    maxTravel = maxOf(maxTravel, hypot(totalDx, totalDy))
                }
            }
        }
    } else {
        this
    }
}

/**
 * Full touch-first gesture layer + screen-lock overlay for handhelds, tablets, and air-mice
 * inside the player while preserving TV D-Pad key navigation untouched.
 */
@Composable
fun BoxScope.PlayerTouchGesturesOverlay(
    touchModeActive: Boolean,
    gesturesAllowed: Boolean,
    controlsVisible: Boolean,
    timelineProvider: () -> PlaybackTimelineState,
    onToggleControls: () -> Unit,
    onHideControls: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current
    val touchSlop = viewConfiguration.touchSlop
    val dragSlopPx = remember(touchSlop, density) {
        maxOf(touchSlop * 1.5f, with(density) { 16.dp.toPx() })
    }
    val tapAllowPx = remember(touchSlop, dragSlopPx, density) {
        maxOf(touchSlop * 2.5f, dragSlopPx * 1.6f, with(density) { 28.dp.toPx() })
    }
    val doubleTapSlopPx = remember(touchSlop, density) {
        maxOf(touchSlop * 8f, with(density) { 120.dp.toPx() })
    }
    val doubleTapWindowMs = maxOf(viewConfiguration.doubleTapTimeoutMillis, 320L)
    val seekStreakWindowMs = 850L

    var screenLocked by remember { mutableStateOf(false) }
    var unlockButtonVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<TouchHudFeedback?>(null) }
    var feedbackSeq by remember { mutableLongStateOf(0L) }
    val latestScreenLocked by rememberUpdatedState(screenLocked)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestTimelineProvider by rememberUpdatedState(timelineProvider)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)
    val latestOnHideControls by rememberUpdatedState(onHideControls)
    val latestOnPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnSeekBy by rememberUpdatedState(onSeekBy)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnBack by rememberUpdatedState(onBack)
    val gestureScope = rememberCoroutineScope()
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapUpMs by remember { mutableLongStateOf(0L) }
    var lastTapX by remember { mutableFloatStateOf(0f) }
    var lastTapY by remember { mutableFloatStateOf(0f) }
    var lastSeekUpMs by remember { mutableLongStateOf(0L) }
    var lastSeekSide by remember { mutableIntStateOf(0) }
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    var accumulatedSeekSide by remember { mutableIntStateOf(0) }

    fun performDirectionalSeek(side: Int) {
        val stepMs = if (side < 0) -10_000L else 10_000L
        val nextTotalMs = if (accumulatedSeekSide == side) accumulatedSeekMs + stepMs else stepMs
        accumulatedSeekSide = side
        accumulatedSeekMs = nextTotalMs
        latestOnSeekBy(stepMs)
        val totalSeconds = abs(nextTotalMs / 1000L)
        feedback = TouchHudFeedback.DoubleTapAction(
            icon = if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
            label = if (side < 0) "-${totalSeconds} giây" else "+${totalSeconds} giây"
        )
        feedbackSeq++
    }

    DisposableEffect(Unit) {
        onDispose { pendingSingleTapJob?.cancel() }
    }

    LaunchedEffect(feedbackSeq) {
        if (feedbackSeq > 0L && feedback != null) {
            delay(950L)
            feedback = null
            accumulatedSeekMs = 0L
            accumulatedSeekSide = 0
        }
    }

    LaunchedEffect(unlockButtonVisible, screenLocked) {
        if (screenLocked && unlockButtonVisible) {
            delay(3200L)
            unlockButtonVisible = false
        }
    }

    fun readInitialBrightness(): Float {
        val winAttr = activity?.window?.attributes?.screenBrightness ?: -1f
        if (winAttr in 0f..1f) return winAttr
        return runCatching {
            val sysVal = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            (sysVal / 255f).coerceIn(0.05f, 1f)
        }.getOrDefault(0.5f)
    }

    fun applyWindowBrightness(fraction: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes
        lp.screenBrightness = fraction.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    if (!touchModeActive && !screenLocked) return

    // Unlocked gesture surface sits above the video (0f) and BELOW PlayerControlsOverlay (1.5f)
    // and modal panels (2.6f+), so every player button/slider/chip stays directly clickable.
    // When locked, it raises to 3.8f to absorb all touches except the bottom unlock pill.
    if (gesturesAllowed || screenLocked) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .zIndex(if (screenLocked) 3.8f else 0.2f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Cancel pending single-tap immediately so a 2nd tap or drag never flashes controls.
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null

                        val downTimeMs = down.uptimeMillis
                        val startX = down.position.x
                        val startY = down.position.y
                        val totalWidth = size.width.coerceAtLeast(1).toFloat()
                        val totalHeight = size.height.coerceAtLeast(1).toFloat()
                        val side = when {
                            startX / totalWidth < 0.40f -> -1
                            startX / totalWidth > 0.60f -> 1
                            else -> 0
                        }
                        val isDoubleTapPair = !latestScreenLocked && !latestControlsVisible &&
                            lastTapUpMs > 0L &&
                            (downTimeMs - lastTapUpMs) in 0L..doubleTapWindowMs &&
                            hypot(startX - lastTapX, startY - lastTapY) <= doubleTapSlopPx
                        val isSeekStreak = !latestScreenLocked && !latestControlsVisible &&
                            side != 0 &&
                            lastSeekSide == side &&
                            lastSeekUpMs > 0L &&
                            (downTimeMs - lastSeekUpMs) in 0L..seekStreakWindowMs

                        var anyConsumed = down.isConsumed
                        var totalDx = 0f
                        var totalDy = 0f
                        var maxTravel = 0f
                        var axis = ActiveDragAxis.UNDECIDED

                        var brightnessValue by mutableFloatStateOf(readInitialBrightness())
                        val maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                        val startVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVol / 2)
                        var volFraction = startVol.toFloat() / maxVol.toFloat()

                        val timeline = latestTimelineProvider()
                        var pendingSeekTargetMs: Long? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChange = event.changes.firstOrNull { it.id == down.id }
                            if (activeChange == null || !activeChange.pressed) {
                                if (!latestScreenLocked && axis == ActiveDragAxis.HORIZONTAL_SEEK && pendingSeekTargetMs != null && !timeline.isLive) {
                                    latestOnSeekTo(pendingSeekTargetMs!!)
                                }
                                if (axis != ActiveDragAxis.UNDECIDED) {
                                    feedbackSeq++
                                    lastTapUpMs = 0L
                                    lastSeekUpMs = 0L
                                    lastSeekSide = 0
                                } else if (maxTravel <= tapAllowPx) {
                                    val upConsumed = anyConsumed || (activeChange?.isConsumed == true)
                                    val upTimeMs = activeChange?.uptimeMillis ?: android.os.SystemClock.uptimeMillis()
                                    if (latestScreenLocked) {
                                        activeChange?.consume()
                                        unlockButtonVisible = !unlockButtonVisible
                                    } else if (!upConsumed) {
                                        activeChange?.consume()
                                        if (latestControlsVisible) {
                                            lastTapUpMs = 0L
                                            lastSeekUpMs = 0L
                                            lastSeekSide = 0
                                            latestOnHideControls()
                                        } else {
                                            val currentTimeline = latestTimelineProvider()
                                            when {
                                                isSeekStreak && !currentTimeline.isLive -> {
                                                    performDirectionalSeek(side)
                                                    lastTapUpMs = 0L
                                                    lastSeekSide = side
                                                    lastSeekUpMs = upTimeMs
                                                }
                                                isDoubleTapPair && side != 0 && !currentTimeline.isLive -> {
                                                    performDirectionalSeek(side)
                                                    lastTapUpMs = 0L
                                                    lastSeekSide = side
                                                    lastSeekUpMs = upTimeMs
                                                }
                                                isDoubleTapPair -> {
                                                    latestOnPlayPause()
                                                    feedback = TouchHudFeedback.DoubleTapAction(
                                                        Icons.Rounded.Pause,
                                                        "Phát / Tạm dừng"
                                                    )
                                                    feedbackSeq++
                                                    lastTapUpMs = 0L
                                                    lastSeekUpMs = 0L
                                                    lastSeekSide = 0
                                                }
                                                else -> {
                                                    lastSeekUpMs = 0L
                                                    lastSeekSide = 0
                                                    lastTapUpMs = upTimeMs
                                                    lastTapX = startX
                                                    lastTapY = startY
                                                    pendingSingleTapJob = gestureScope.launch {
                                                        delay(doubleTapWindowMs)
                                                        lastTapUpMs = 0L
                                                        if (!latestScreenLocked && !latestControlsVisible) {
                                                            latestOnToggleControls()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                break
                            }

                            if (activeChange.isConsumed) {
                                anyConsumed = true
                            }

                            val delta = activeChange.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            maxTravel = maxOf(maxTravel, hypot(totalDx, totalDy))

                            if (!latestScreenLocked && !anyConsumed) {
                                if (axis == ActiveDragAxis.UNDECIDED) {
                                    if (abs(totalDx) > dragSlopPx && abs(totalDx) > abs(totalDy) * 1.25f) {
                                        if (!timeline.isLive && timeline.duration > 0L) {
                                            axis = ActiveDragAxis.HORIZONTAL_SEEK
                                            activeChange.consume()
                                        }
                                    } else if (abs(totalDy) > dragSlopPx && abs(totalDy) > abs(totalDx) * 1.25f) {
                                        axis = if (startX < totalWidth * 0.5f) {
                                            ActiveDragAxis.LEFT_BRIGHTNESS
                                        } else {
                                            ActiveDragAxis.RIGHT_VOLUME
                                        }
                                        activeChange.consume()
                                    }
                                } else {
                                    activeChange.consume()
                                    when (axis) {
                                        ActiveDragAxis.LEFT_BRIGHTNESS -> {
                                            val fractionDelta = (-delta.y / (totalHeight * 0.65f))
                                            brightnessValue = (brightnessValue + fractionDelta).coerceIn(0.02f, 1f)
                                            applyWindowBrightness(brightnessValue)
                                            feedback = TouchHudFeedback.Brightness((brightnessValue * 100f).roundToInt())
                                        }
                                        ActiveDragAxis.RIGHT_VOLUME -> {
                                            val fractionDelta = (-delta.y / (totalHeight * 0.65f))
                                            volFraction = (volFraction + fractionDelta).coerceIn(0f, 1f)
                                            val targetVol = (volFraction * maxVol).roundToInt().coerceIn(0, maxVol)
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                            feedback = TouchHudFeedback.Volume(((targetVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt())
                                        }
                                        ActiveDragAxis.HORIZONTAL_SEEK -> {
                                            val maxSwipeWindowMs = timeline.duration.coerceAtMost(150_000L)
                                            val seekDeltaMs = ((totalDx / totalWidth) * maxSwipeWindowMs).toLong()
                                            val clampedTarget = (timeline.currentPosition + seekDeltaMs).coerceIn(0L, timeline.duration)
                                            pendingSeekTargetMs = clampedTarget
                                            feedback = TouchHudFeedback.SeekPreview(
                                                deltaMs = clampedTarget - timeline.currentPosition,
                                                targetMs = clampedTarget
                                            )
                                        }
                                        ActiveDragAxis.UNDECIDED -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
        )
    }

        // Floating HUD Feedback Pill (Brightness / Volume / Double-Tap / Horizontal Seek)
        AnimatedVisibility(
            visible = feedback != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .zIndex(2.8f)
        ) {
            feedback?.let { current ->
                TouchFeedbackCard(current)
            }
        }

        // Touch Back + Screen Lock Pills while controls HUD is open on handheld/touch devices
        AnimatedVisibility(
            visible = !screenLocked && controlsVisible && gesturesAllowed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(2.25f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, top = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xD90E1015))
                        .border(0.75.dp, Color(0x40F4F7FB), CircleShape)
                        .clickable { latestOnBack() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color(0xFFF4F7FB),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Quay lại",
                        color = Color(0xFFF4F7FB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xD90E1015))
                        .border(0.75.dp, Color(0x40F4F7FB), CircleShape)
                        .clickable {
                            screenLocked = true
                            unlockButtonVisible = true
                            latestOnHideControls()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Khóa cảm ứng",
                        tint = Color(0xFFF4F7FB),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Khóa màn hình",
                        color = Color(0xFFF4F7FB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Floating Unlock Pill while screen is locked
        AnimatedVisibility(
            visible = screenLocked && unlockButtonVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(3.9f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF01C2029), Color(0xF00E1015))
                        )
                    )
                    .border(1.dp, Color(0x66F4F7FB), CircleShape)
                    .clickable {
                        screenLocked = false
                        unlockButtonVisible = false
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = "Mở khóa cảm ứng",
                    tint = Color(0xFFF4F7FB),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Chạm để mở khóa",
                    color = Color(0xFFF4F7FB),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
}

@Composable
private fun TouchFeedbackCard(feedback: TouchHudFeedback) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE00C0E13))
            .border(0.75.dp, Color(0x4AF4F7FB), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (feedback) {
            is TouchHudFeedback.Volume -> {
                val icon = if (feedback.percent <= 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                MeterFeedbackContent(icon = icon, title = "Âm lượng", percent = feedback.percent)
            }
            is TouchHudFeedback.Brightness -> {
                MeterFeedbackContent(icon = Icons.Rounded.BrightnessHigh, title = "Độ sáng", percent = feedback.percent)
            }
            is TouchHudFeedback.SeekPreview -> {
                Text(
                    text = formatSignedSeekTime(feedback.deltaMs),
                    color = Color(0xFFF4F7FB),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatAbsoluteTime(feedback.targetMs),
                    color = Color(0xFFB7BFCC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            is TouchHudFeedback.DoubleTapAction -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = feedback.icon,
                        contentDescription = null,
                        tint = Color(0xFFF4F7FB),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = feedback.label,
                        color = Color(0xFFF4F7FB),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MeterFeedbackContent(
    icon: ImageVector,
    title: String,
    percent: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = Color(0xFFF4F7FB),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "$title • $percent%",
            color = Color(0xFFF4F7FB),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(2.dp))
    Box(
        modifier = Modifier
            .width(124.dp)
            .height(5.dp)
            .clip(CircleShape)
            .background(Color(0xFF282B34))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                .height(5.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFF4F7FB), Color(0xFFB7BFCC))
                    )
                )
        )
    }
}
