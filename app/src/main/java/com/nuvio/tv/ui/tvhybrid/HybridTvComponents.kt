@file:OptIn(ExperimentalTvMaterial3Api::class)
@file:Suppress("unused")

package com.nuvio.tv.ui.tvhybrid

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonGlow
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.OutlinedIconButtonDefaults
import androidx.tv.material3.SelectableChipBorder
import androidx.tv.material3.SelectableChipColors
import androidx.tv.material3.SelectableChipGlow
import androidx.tv.material3.SelectableChipScale
import androidx.tv.material3.SelectableChipShape
import androidx.tv.material3.SelectableSurfaceBorder
import androidx.tv.material3.SelectableSurfaceColors
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.SelectableSurfaceGlow
import androidx.tv.material3.SelectableSurfaceScale
import androidx.tv.material3.SelectableSurfaceShape
import androidx.tv.material3.SurfaceColors
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.TabColors
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRowScope
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.FilterChip as TvFilterChip
import androidx.tv.material3.IconButton as TvIconButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.OutlinedIconButton as TvOutlinedIconButton
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Tab as TvTab

/**
 * Adds touchscreen and mouse tap/long-press handling to androidx.tv.material3 clickable
 * components (which natively only react to D-Pad key events via tvClickable/tvSelectable),
 * while preserving D-Pad focus rings, key handling, and parent scroll cancellation.
 */
@Composable
private fun rememberHybridInteractionAndModifier(
    baseModifier: Modifier,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null
): Pair<MutableInteractionSource, Modifier> {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnLongClick = rememberUpdatedState(onLongClick)
    val hasClick = onClick != null
    val hasLongClick = onLongClick != null
    val finalModifier = if (!enabled || (!hasClick && !hasLongClick)) {
        baseModifier
    } else {
        baseModifier.tvTouchPointerInput(
            enabled = enabled,
            interactionSource = resolvedInteractionSource,
            hasClick = hasClick,
            hasLongClick = hasLongClick,
            onClickState = currentOnClick,
            onLongClickState = currentOnLongClick
        )
    }
    return resolvedInteractionSource to finalModifier
}

private fun Modifier.tvTouchPointerInput(
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    hasClick: Boolean,
    hasLongClick: Boolean,
    onClickState: State<(() -> Unit)?>,
    onLongClickState: State<(() -> Unit)?>
): Modifier = pointerInput(enabled, interactionSource, hasClick, hasLongClick) {
    detectTapGestures(
        onPress = { offset ->
            val press = PressInteraction.Press(offset)
            interactionSource.emit(press)
            val released = tryAwaitRelease()
            interactionSource.emit(
                if (released) PressInteraction.Release(press)
                else PressInteraction.Cancel(press)
            )
        },
        onTap = if (hasClick) {
            { onClickState.value?.invoke() }
        } else {
            null
        },
        onLongPress = if (hasLongClick) {
            { onLongClickState.value?.invoke() }
        } else {
            null
        }
    )
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(),
    glow: ButtonGlow = ButtonDefaults.glow(),
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    border: ButtonBorder = ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvButton(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        tonalElevation = tonalElevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = OutlinedButtonDefaults.scale(),
    glow: ButtonGlow = OutlinedButtonDefaults.glow(),
    shape: ButtonShape = OutlinedButtonDefaults.shape(),
    colors: ButtonColors = OutlinedButtonDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    border: ButtonBorder = OutlinedButtonDefaults.border(),
    contentPadding: PaddingValues = OutlinedButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvOutlinedButton(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        tonalElevation = tonalElevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: CardShape = CardDefaults.shape(),
    colors: CardColors = CardDefaults.colors(),
    scale: CardScale = CardDefaults.scale(),
    border: CardBorder = CardDefaults.border(),
    glow: CardGlow = CardDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = true,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvCard(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@NonRestartableComposable
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shape: Shape = SurfaceDefaults.shape,
    colors: SurfaceColors = SurfaceDefaults.colors(),
    border: Border = SurfaceDefaults.border,
    glow: Glow = SurfaceDefaults.glow,
    content: @Composable (BoxScope.() -> Unit),
) {
    TvSurface(
        modifier = modifier,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        border = border,
        glow = glow,
        content = content
    )
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (BoxScope.() -> Unit),
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvSurface(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun Surface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    tonalElevation: Dp = 0.dp,
    shape: SelectableSurfaceShape = SelectableSurfaceDefaults.shape(),
    colors: SelectableSurfaceColors = SelectableSurfaceDefaults.colors(),
    scale: SelectableSurfaceScale = SelectableSurfaceDefaults.scale(),
    border: SelectableSurfaceBorder = SelectableSurfaceDefaults.border(),
    glow: SelectableSurfaceGlow = SelectableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (BoxScope.() -> Unit),
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvSurface(
        selected = selected,
        onClick = onClick,
        modifier = touchModifier,
        enabled = enabled,
        onLongClick = onLongClick,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = IconButtonDefaults.scale(),
    glow: ButtonGlow = IconButtonDefaults.glow(),
    shape: ButtonShape = IconButtonDefaults.shape(),
    colors: ButtonColors = IconButtonDefaults.colors(),
    border: ButtonBorder = IconButtonDefaults.border(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvIconButton(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        border = border,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = OutlinedIconButtonDefaults.scale(),
    glow: ButtonGlow = OutlinedIconButtonDefaults.glow(),
    shape: ButtonShape = OutlinedIconButtonDefaults.shape(),
    colors: ButtonColors = OutlinedIconButtonDefaults.colors(),
    border: ButtonBorder = OutlinedIconButtonDefaults.border(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvOutlinedIconButton(
        onClick = onClick,
        modifier = touchModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        border = border,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: SelectableChipShape = FilterChipDefaults.shape(),
    colors: SelectableChipColors = FilterChipDefaults.colors(),
    scale: SelectableChipScale = FilterChipDefaults.scale(),
    border: SelectableChipBorder = FilterChipDefaults.border(),
    glow: SelectableChipGlow = FilterChipDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        onLongClick = onLongClick
    )
    TvFilterChip(
        selected = selected,
        onClick = onClick,
        modifier = touchModifier,
        enabled = enabled,
        onLongClick = onLongClick,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = resolvedInteraction,
        content = content
    )
}

@Composable
fun TabRowScope.Tab(
    selected: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    colors: TabColors = TabDefaults.pillIndicatorTabColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val combinedTouchClick = remember(onFocus, onClick) {
        {
            onFocus()
            onClick()
        }
    }
    val (resolvedInteraction, touchModifier) = rememberHybridInteractionAndModifier(
        baseModifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = combinedTouchClick,
        onLongClick = null
    )
    TvTab(
        selected = selected,
        onFocus = onFocus,
        modifier = touchModifier,
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        interactionSource = resolvedInteraction,
        content = content
    )
}
