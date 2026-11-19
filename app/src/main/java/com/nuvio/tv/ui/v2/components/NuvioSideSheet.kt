package com.nuvio.tv.ui.v2.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Activity-local modal focus boundary: opening a player sheet never creates a display-mode window. */
@Composable
fun NuvioSideSheet(
    onDismiss: () -> Unit,
    width: Dp = 460.dp,
    requestInitialFocus: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val focus = remember { FocusRequester() }
    val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()
    BackHandler(onBack = onDismiss)
    LaunchedEffect(tvFocusMode) {
        if (!requestInitialFocus || !tvFocusMode) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        focus.requestFocus()
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.CenterEnd
    ) {
        val compact = maxWidth < 600.dp
        val outerHoriz = if (compact) 12.dp else 48.dp
        val outerVert = if (compact) 16.dp else 28.dp
        val sheetWidth = minOf(width, (maxWidth - outerHoriz * 2).coerceAtLeast(260.dp))
        Box(
            Modifier.fillMaxSize().padding(horizontal = outerHoriz, vertical = outerVert),
            contentAlignment = Alignment.CenterEnd
        ) {
            NuvioGlassSurface(
                GlassRole.PANEL,
                Modifier
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    Modifier.fillMaxSize().padding(if (compact) 16.dp else 24.dp).focusRequester(focus)
                        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                    content = content
                )
            }
        }
    }
}
