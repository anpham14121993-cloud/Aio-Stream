@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import kotlinx.coroutines.delay
import java.util.Locale

private const val SUBTITLE_DELAY_HOLD_MS = 500L

/** Formats subtitle delay matching the SubtitleDelayOverlay (+0.000s / -0.100s). */
internal fun formatSubtitleDelaySeconds(delayMs: Int): String {
    return String.format(Locale.US, "%+.3fs", delayMs / 1000f)
}

internal data class SubtitleDelayDeckConfig(
    val delayMs: Int,
    val onAdjustDelay: (Int) -> Unit,
    val onResetDelay: () -> Unit,
    val onOpenTimingDialog: (fromKeyDown: Boolean) -> Unit,
    val onInteraction: () -> Unit,
    val onFocusedChanged: (Boolean) -> Unit = {}
)

/**
 * Compact 3-part subtitle delay quick control (`- | +0.000s | +`) placed in the player control bar:
 * - `-` / `+` step by [SUBTITLE_DELAY_STEP_MS] (100ms)
 * - Short press / click on the center delay value resets delay to 0
 * - Long press on the center delay value (touch or TV D-pad hold) opens the line sync dialog
 */
@Composable
internal fun SubtitleDelayQuickControl(
    delayMs: Int,
    onAdjustDelay: (Int) -> Unit,
    onResetDelay: () -> Unit,
    onOpenTimingDialog: (fromKeyDown: Boolean) -> Unit,
    onInteraction: () -> Unit,
    enabled: Boolean = true,
    upFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    onBottom: () -> Unit = {},
    minusFocusRequester: FocusRequester = remember { FocusRequester() },
    valueFocusRequester: FocusRequester = remember { FocusRequester() },
    plusFocusRequester: FocusRequester = remember { FocusRequester() },
    onFocusedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val v2 = LocalV2Appearance.current != null
    val haptic = LocalHapticFeedback.current
    val delayText = formatSubtitleDelaySeconds(delayMs)
    val valueDescription = stringResource(R.string.player_subtitle_delay_value_cd, delayText)

    var minusFocused by remember { mutableStateOf(false) }
    var valueFocused by remember { mutableStateOf(false) }
    var plusFocused by remember { mutableStateOf(false) }
    LaunchedEffect(minusFocused, valueFocused, plusFocused) {
        onFocusedChanged(minusFocused || valueFocused || plusFocused)
    }

    var confirmPressed by remember { mutableStateOf(false) }
    var confirmLongPressFired by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            confirmPressed = false
            confirmLongPressFired = false
        }
    }
    LaunchedEffect(confirmPressed) {
        if (confirmPressed) {
            delay(SUBTITLE_DELAY_HOLD_MS)
            if (confirmPressed && !confirmLongPressFired) {
                confirmPressed = false
                confirmLongPressFired = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenTimingDialog(true)
            }
        }
    }

    val shape = RoundedCornerShape(50)
    val containerSurface = if (v2) {
        Modifier.nuvioGlass(GlassRole.CONTROL, shape = shape)
    } else {
        Modifier.background(Color.White.copy(alpha = 0.14f), shape)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .height(42.dp)
                .then(containerSurface)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            SubtitleDelayStepSegment(
                icon = Icons.Default.Remove,
                contentDescription = stringResource(R.string.cd_decrease),
                enabled = enabled,
                v2 = v2,
                focusRequester = minusFocusRequester,
                leftTarget = leftFocusRequester,
                rightTarget = valueFocusRequester,
                upTarget = upFocusRequester,
                onBottom = onBottom,
                onFocusStateChanged = {
                    minusFocused = it
                    if (it) onInteraction()
                },
                onClick = {
                    onInteraction()
                    onAdjustDelay(-SUBTITLE_DELAY_STEP_MS)
                }
            )

            val valueInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(min = 54.dp)
                    .focusRequester(valueFocusRequester)
                    .focusProperties {
                        canFocus = enabled
                        left = minusFocusRequester
                        right = plusFocusRequester
                        up = upFocusRequester ?: FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                    .onFocusChanged {
                        valueFocused = it.isFocused
                        if (!it.isFocused) {
                            confirmPressed = false
                            confirmLongPressFired = false
                        } else {
                            onInteraction()
                        }
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        val native = keyEvent.nativeKeyEvent
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (native.action == KeyEvent.ACTION_DOWN) onBottom()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (!enabled) return@onPreviewKeyEvent false
                                when (native.action) {
                                    KeyEvent.ACTION_DOWN -> {
                                        if (native.repeatCount == 0) {
                                            confirmPressed = true
                                            confirmLongPressFired = false
                                            onInteraction()
                                        } else if (!confirmLongPressFired &&
                                            (native.isLongPress || native.eventTime - native.downTime >= SUBTITLE_DELAY_HOLD_MS)
                                        ) {
                                            confirmPressed = false
                                            confirmLongPressFired = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onOpenTimingDialog(true)
                                        }
                                        true
                                    }
                                    KeyEvent.ACTION_UP -> {
                                        val alreadyOpened = confirmLongPressFired
                                        confirmPressed = false
                                        confirmLongPressFired = false
                                        if (!alreadyOpened && !native.isCanceled) {
                                            onInteraction()
                                            onResetDelay()
                                        }
                                        true
                                    }
                                    else -> true
                                }
                            }
                            else -> false
                        }
                    }
                    .nuvioV2Focus(valueFocused, shape, hardwareShadow = false)
                    .clip(shape)
                    .background(
                        when {
                            !valueFocused -> Color.Transparent
                            v2 -> Color.White.copy(alpha = 0.24f)
                            else -> Color.White
                        }
                    )
                    .combinedClickable(
                        enabled = enabled,
                        interactionSource = valueInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            onInteraction()
                            onResetDelay()
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onInteraction()
                            onOpenTimingDialog(false)
                        }
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = valueDescription
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = delayText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = when {
                        valueFocused && !v2 -> Color.Black
                        delayMs != 0 && !valueFocused -> NuvioTheme.colors.Secondary
                        else -> Color.White
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }

            SubtitleDelayStepSegment(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_increase),
                enabled = enabled,
                v2 = v2,
                focusRequester = plusFocusRequester,
                leftTarget = valueFocusRequester,
                rightTarget = rightFocusRequester,
                upTarget = upFocusRequester,
                onBottom = onBottom,
                onFocusStateChanged = {
                    plusFocused = it
                    if (it) onInteraction()
                },
                onClick = {
                    onInteraction()
                    onAdjustDelay(SUBTITLE_DELAY_STEP_MS)
                }
            )
        }
    }
}

@Composable
private fun SubtitleDelayStepSegment(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    v2: Boolean,
    focusRequester: FocusRequester,
    leftTarget: FocusRequester?,
    rightTarget: FocusRequester?,
    upTarget: FocusRequester?,
    onBottom: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = CircleShape

    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 38.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftTarget ?: FocusRequester.Cancel
                right = rightTarget ?: FocusRequester.Cancel
                up = upTarget ?: FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusStateChanged(it.isFocused)
            }
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (native.action == KeyEvent.ACTION_DOWN) onBottom()
                    true
                } else {
                    false
                }
            }
            .nuvioV2Focus(focused, shape, hardwareShadow = false)
            .clip(shape)
            .background(
                when {
                    !focused -> Color.Transparent
                    v2 -> Color.White.copy(alpha = 0.24f)
                    else -> Color.White
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused && !v2) Color.Black else Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}
