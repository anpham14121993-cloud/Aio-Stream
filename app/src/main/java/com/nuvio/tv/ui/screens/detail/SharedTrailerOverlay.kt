package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.monolith.DeviceFormFactor
import com.nuvio.tv.ui.monolith.LocalDeviceFormFactor
import com.nuvio.tv.ui.monolith.LocalFullscreenMediaOrientationSetter
import com.nuvio.tv.ui.monolith.isTvFocusMode
import kotlinx.coroutines.delay
import android.view.KeyEvent
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect

@Stable
class TrailerSeekOverlayState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SharedTrailerOverlay(
    title: String,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    var isPaused by remember { mutableStateOf(false) }
    var seekOverlayVisible by remember { mutableStateOf(false) }
    val seekOverlayState = remember { TrailerSeekOverlayState() }
    var seekToken by remember { mutableIntStateOf(0) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var seekToMs by remember { mutableStateOf<Long?>(null) }

    val overlayFocusRequester = remember { FocusRequester() }
    val canControlPlayback = !trailerUrl.isNullOrBlank() && !isLoading && errorMessage == null
    val isHandheldDevice = LocalDeviceFormFactor.current != DeviceFormFactor.TV
    val tvFocusMode = isTvFocusMode()
    val setFullscreenOrientation = LocalFullscreenMediaOrientationSetter.current

    DisposableEffect(isHandheldDevice) {
        if (isHandheldDevice) setFullscreenOrientation(true)
        onDispose { if (isHandheldDevice) setFullscreenOrientation(false) }
    }

    LaunchedEffect(trailerUrl, trailerAudioUrl, isLoading, errorMessage) {
        isPaused = false
        seekOverlayVisible = false
    }

    LaunchedEffect(seekOverlayVisible, canControlPlayback, seekToken) {
        if (seekOverlayVisible && canControlPlayback) {
            delay(3000)
            seekOverlayVisible = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AmbientDialogEffect(onDismiss)
        if (isHandheldDevice) {
            val view = LocalView.current
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(
                    if (tvFocusMode) Modifier.focusRequester(overlayFocusRequester).focusable()
                    else Modifier
                )
                // This surface exists only to capture remote keys (back/play/pause/seek) and has no
                // label of its own, so merge the overlay's own text (title + "press back" hint)
                // into it. Merging leaves exactly one named node on the overlay; a bare
                // contentDescription here would leave those texts as separate named siblings.
                .semantics(mergeDescendants = true) { }
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }

                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            onDismiss()
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = !isPaused
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = true
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = false
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                            seekToMs = null
                            seekDeltaMs = when {
                                repeatCount >= 12 -> -12_000L
                                repeatCount >= 6 -> -8_000L
                                repeatCount >= 2 -> -5_000L
                                else -> -3_000L
                            }
                            seekToken += 1
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                            seekToMs = null
                            seekDeltaMs = when {
                                repeatCount >= 12 -> 12_000L
                                repeatCount >= 6 -> 8_000L
                                repeatCount >= 2 -> 5_000L
                                else -> 3_000L
                            }
                            seekToken += 1
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            seekOverlayVisible = false
                            true
                        }

                        else -> false
                    }
                }
        ) {
            LaunchedEffect(canControlPlayback, errorMessage, tvFocusMode) {
                if (tvFocusMode) {
                    overlayFocusRequester.requestFocusAfterFrames()
                }
            }
            if (!trailerUrl.isNullOrBlank() && errorMessage == null) {
                TrailerPlayer(
                    trailerUrl = trailerUrl,
                    trailerAudioUrl = trailerAudioUrl,
                    isPlaying = true,
                    isPaused = isPaused,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    seekToMs = seekToMs,
                    onProgressChanged = { position, duration ->
                        seekOverlayState.positionMs = position
                        seekOverlayState.durationMs = duration
                    },
                    onEnded = onDismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isHandheldDevice || !tvFocusMode) {
                TrailerFullscreenTouchOverlay(
                    title = title,
                    isPaused = isPaused,
                    canControlPlayback = canControlPlayback,
                    overlayState = seekOverlayState,
                    onTogglePause = { isPaused = !isPaused },
                    onSeekDelta = { delta ->
                        seekToMs = null
                        seekDeltaMs = delta
                        seekToken += 1
                    },
                    onSeekTo = { targetMs ->
                        seekToMs = targetMs
                        seekToken += 1
                    },
                    onDismiss = onDismiss
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.hero_press_back_trailer),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = NuvioTheme.spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Button(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }

            if (!isHandheldDevice && tvFocusMode) {
                TrailerSeekOverlayHost(
                    visible = canControlPlayback && seekOverlayVisible,
                    overlayState = seekOverlayState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/** Fullscreen touch HUD shared by mobile hero trailers and the shared trailer dialog. */
@Composable
internal fun TrailerFullscreenTouchOverlay(
    title: String,
    isPaused: Boolean,
    canControlPlayback: Boolean,
    overlayState: TrailerSeekOverlayState,
    onTogglePause: () -> Unit,
    onSeekDelta: (Long) -> Unit,
    onSeekTo: ((Long) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hudVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var isScrubbingBar by remember { mutableStateOf(false) }

    LaunchedEffect(hudVisible, isPaused, canControlPlayback, interactionNonce, isScrubbingBar) {
        if (hudVisible && !isPaused && canControlPlayback && !isScrubbingBar) {
            delay(3500)
            hudVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                hudVisible = !hudVisible
                interactionNonce += 1
            }
    ) {
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_close),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (canControlPlayback) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.62f))
                                .clickable {
                                    interactionNonce += 1
                                    onSeekDelta(-10_000L)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Replay10,
                                contentDescription = "-10s",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.72f))
                                .clickable {
                                    interactionNonce += 1
                                    onTogglePause()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (isPaused) stringResource(R.string.player_pill_play) else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.62f))
                                .clickable {
                                    interactionNonce += 1
                                    onSeekDelta(10_000L)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Forward10,
                                contentDescription = "+10s",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp)
                    ) {
                        TrailerSeekOverlay(
                            currentPosition = overlayState.positionMs,
                            duration = overlayState.durationMs,
                            onSeekTo = onSeekTo?.let { seekHandler ->
                                { targetMs ->
                                    interactionNonce += 1
                                    overlayState.positionMs = targetMs
                                    seekHandler(targetMs)
                                }
                            },
                            onScrubbingChanged = { scrubbing ->
                                isScrubbingBar = scrubbing
                                if (!scrubbing) interactionNonce += 1
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrailerSeekOverlayHost(
    visible: Boolean,
    overlayState: TrailerSeekOverlayState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        TrailerSeekOverlay(
            currentPosition = overlayState.positionMs,
            duration = overlayState.durationMs
        )
    }
}

@Composable
fun TrailerSeekOverlay(
    currentPosition: Long,
    duration: Long,
    onSeekTo: ((Long) -> Unit)? = null,
    onScrubbingChanged: (Boolean) -> Unit = {}
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val latestDuration by rememberUpdatedState(duration)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)
    val baseProgress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedProgress = if (isScrubbing) scrubFraction else baseProgress
    val animatedProgress by animateFloatAsState(
        targetValue = displayedProgress,
        animationSpec = tween(if (isScrubbing) 0 else 100),
        label = "trailerSeekProgress"
    )
    val displayedPosition = if (isScrubbing && duration > 0L) {
        (scrubFraction * duration).toLong().coerceIn(0L, duration)
    } else {
        currentPosition
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl)
        ) {
            val density = LocalDensity.current
            val touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .onSizeChanged { trackWidthPx = it.width }
                    .then(
                        if (onSeekTo != null) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val dur = latestDuration
                                    val seekCb = latestOnSeekTo
                                    if (dur <= 0L || seekCb == null) return@awaitEachGesture
                                    val width = trackWidthPx.coerceAtLeast(1).toFloat()
                                    val startX = down.position.x
                                    var fraction = (startX / width).coerceIn(0f, 1f)
                                    scrubFraction = fraction
                                    isScrubbing = true
                                    latestOnScrubbingChanged(true)
                                    var dragged = false
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val active = event.changes.firstOrNull { it.id == down.id }
                                            if (active == null || !active.pressed) {
                                                if (active != null) {
                                                    fraction = (active.position.x / width).coerceIn(0f, 1f)
                                                    scrubFraction = fraction
                                                    active.consume()
                                                }
                                                val targetMs = (fraction * latestDuration).toLong().coerceIn(0L, latestDuration)
                                                isScrubbing = false
                                                latestOnScrubbingChanged(false)
                                                seekCb(targetMs)
                                                break
                                            }
                                            val delta = active.positionChange()
                                            if (!dragged && kotlin.math.abs(active.position.x - startX) > touchSlop * 0.5f) {
                                                dragged = true
                                            }
                                            if (delta.x != 0f || dragged) {
                                                fraction = (active.position.x / width).coerceIn(0f, 1f)
                                                scrubFraction = fraction
                                                active.consume()
                                            }
                                        }
                                    } finally {
                                        isScrubbing = false
                                        latestOnScrubbingChanged(false)
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isScrubbing) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NuvioTheme.colors.Secondary)
                    )
                }
                if (onSeekTo != null && trackWidthPx > 0 && duration > 0L) {
                    val thumbSize = if (isScrubbing) 18.dp else 14.dp
                    val thumbRadiusPx = with(density) { (thumbSize / 2).roundToPx() }
                    val thumbX = ((animatedProgress * trackWidthPx) - thumbRadiusPx)
                        .toInt()
                        .coerceIn(0, (trackWidthPx - thumbRadiusPx * 2).coerceAtLeast(0))
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(thumbX, 0) }
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatPlaybackTime(displayedPosition)} / ${formatPlaybackTime(duration)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
