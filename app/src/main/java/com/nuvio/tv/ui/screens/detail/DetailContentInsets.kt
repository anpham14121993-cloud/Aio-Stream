package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

internal val detailStartInset: Dp
    @Composable get() = when {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600 -> 16.dp
        LocalV2Appearance.current != null -> 24.dp
        else -> NuvioTheme.spacing.xxxl
    }
