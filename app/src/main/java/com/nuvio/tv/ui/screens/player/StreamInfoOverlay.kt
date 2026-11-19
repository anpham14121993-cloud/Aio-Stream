package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import com.nuvio.tv.ui.tvhybrid.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.keepLtr
import com.nuvio.tv.ui.util.languageCodeToName
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamInfoOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    data: StreamInfoData?,
    hudEnabled: Boolean,
    hudButtonShown: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
    statsSample: PlaybackStatsSample? = null
) {
    val hudFocusRequester = remember { FocusRequester() }
    var hudFocused by remember(visible) { mutableStateOf(false) }
    var hudPlaced by remember(visible) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val screenConfig = LocalConfiguration.current
    val isCompactScreen = screenConfig.screenWidthDp < 600
    val maxPanelHeight = (screenConfig.screenHeightDp - 44).coerceAtLeast(240).dp
    val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()

    LaunchedEffect(visible, hudButtonShown, hudPlaced, tvFocusMode) {
        if (!visible || !hudButtonShown || !hudPlaced || !tvFocusMode) return@LaunchedEffect
        hudFocusRequester.requestFocusAfterFrames(frames = 0)
    }
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        // Allow D-Pad Up/Down to scroll full technical details + stats comfortably without clipping.
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (scrollState.value > 0) {
                            coroutineScope.launch { scrollState.animateScrollBy(-180f) }
                            true
                        } else if (!hudFocused && hudButtonShown) {
                            runCatching { hudFocusRequester.requestFocus() }.isSuccess
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (scrollState.value < scrollState.maxValue) {
                            coroutineScope.launch { scrollState.animateScrollBy(180f) }
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!hudFocused && hudButtonShown) {
                            runCatching { hudFocusRequester.requestFocus() }.isSuccess
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        dismissOnCenter = !hudButtonShown,
        contentPadding = PaddingValues(
            horizontal = if (isCompactScreen) 14.dp else NuvioTheme.spacing.xxl,
            vertical = if (isCompactScreen) 14.dp else 20.dp
        )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE80E1015))
                .border(0.75.dp, Color(0x38F4F7FB), RoundedCornerShape(16.dp))
                .padding(horizontal = if (isCompactScreen) 16.dp else 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.cd_stream_info),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hudButtonShown) {
                        StreamInfoHudButton(
                            enabled = hudEnabled,
                            onClick = onToggleHud,
                            focusRequester = hudFocusRequester,
                            onFocusChanged = { hudFocused = it },
                            onLaidOut = { hudPlaced = true }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color.White.copy(alpha = 0.12f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
            ) {
                if (data != null) {
                    StreamInfoContent(data = data)
                }
                if (statsSample != null) {
                    if (data != null) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    }
                    SectionLabel(stringResource(R.string.cd_playback_stats).uppercase())
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    PlaybackStatsSectionsContent(sample = statsSample, compact = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamInfoContent(data: StreamInfoData) {
    // SOURCE section
    val hasSourceInfo = data.addonName != null || data.streamName != null
    if (hasSourceInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_source))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!data.addonLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(data.addonLogo)
                        .crossfade(true)
                        .build(),
                    contentDescription = data.addonName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column {
                if (data.addonName != null) {
                    Text(
                        text = data.addonName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (data.streamName != null && data.streamName != data.addonName) {
                    Text(
                        text = data.streamName.replace("\n", " · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!data.streamDescription.isNullOrBlank()) {
            Text(
                text = data.streamDescription.replace("\n", " · "),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }
        if (data.playerEngine != null || data.serverPlayback != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoItem(label = stringResource(R.string.stream_info_player_engine), value = data.playerEngine)
                InfoItem(
                    label = stringResource(R.string.stream_info_server),
                    value = data.serverPlayback
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // FILE section
    val hasFileInfo = data.filename != null || data.fileSize != null
    if (hasFileInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_file))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoItem(label = stringResource(R.string.stream_info_filename), value = data.filename)
            InfoItem(label = stringResource(R.string.stream_info_size), value = data.fileSize?.let { formatFileSize(it) })
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // VIDEO section
    val hasVideoInfo = data.videoCodec != null || data.videoWidth != null ||
        data.videoFrameRate != null || data.videoBitrate != null || data.fileBitrate != null
    if (hasVideoInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_video))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.videoCodec)
            InfoItem(
                label = stringResource(R.string.stream_info_resolution),
                value = if (data.videoWidth != null && data.videoHeight != null) {
                    formatResolution(data.videoWidth, data.videoHeight)
                } else null
            )
            InfoItem(
                label = stringResource(R.string.stream_info_frame_rate),
                value = data.videoFrameRate?.let { "%.3f fps".format(it) }
            )
            if (data.videoBitrate != null) {
                InfoItem(
                    label = stringResource(R.string.stream_info_bitrate),
                    value = formatBitrate(data.videoBitrate)
                )
            } else {
                InfoItem(
                    label = stringResource(R.string.stream_info_bitrate_file),
                    value = data.fileBitrate?.let { formatBitrate(it) }
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // AUDIO section
    val hasAudioInfo = data.audioCodec != null || data.audioChannels != null || data.audioLanguage != null || data.audioSampleRate != null
    if (hasAudioInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_audio))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.audioCodec)
            InfoItem(label = stringResource(R.string.stream_info_channels), value = data.audioChannels)
            InfoItem(
                label = stringResource(R.string.stream_info_sample_rate),
                value = data.audioSampleRate?.let { "${it / 1000} kHz" }
            )
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.audioLanguage?.let { languageCodeToName(it) }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // SUBTITLE section
    val hasSubtitleInfo = data.subtitleName != null || data.subtitleCodec != null || data.subtitleLanguage != null
    if (hasSubtitleInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_subtitle))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoItem(label = stringResource(R.string.stream_info_name), value = data.subtitleName)
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.subtitleCodec)
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.subtitleLanguage?.let { languageCodeToName(it) }
            )
            InfoItem(label = stringResource(R.string.stream_info_source), value = data.subtitleSource)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun InfoItem(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Text(
            text = value.keepLtr(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> stringResource(R.string.unit_size_gb, "%.1f".format(bytes / 1_073_741_824.0))
        bytes >= 1_048_576L -> stringResource(R.string.unit_size_mb, "%.1f".format(bytes / 1_048_576.0))
        bytes >= 1024L -> stringResource(R.string.unit_size_kb, "%.1f".format(bytes / 1024.0))
        else -> stringResource(R.string.unit_size_b, bytes)
    }
}

private fun formatBitrate(bps: Int): String {
    return when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f kbps".format(bps / 1_000.0)
        else -> "$bps bps"
    }
}

internal fun formatResolution(width: Int, height: Int): String {
    val maxDim = maxOf(width, height)
    val label = when {
        maxDim >= 3600 -> "4K"
        maxDim >= 2400 -> "1440p"
        maxDim >= 1800 -> "1080p"
        maxDim >= 1200 -> "720p"
        maxDim >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}

@Composable
private fun StreamInfoHudButton(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onLaidOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        // Every direction leads back to this button, so a stray press cannot drop focus onto the
        // transport controls still drawn behind the overlay.
        modifier = modifier
            .focusRequester(focusRequester)
            .onPlaced { onLaidOut() }
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = if (enabled) NuvioTheme.colors.Secondary else Color.White,
            focusedContentColor = if (enabled) NuvioTheme.colors.OnSecondary else Color.Black
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        )
    ) {
        val statusText = if (enabled) {
            stringResource(R.string.diag_value_on)
        } else {
            stringResource(R.string.diag_value_off)
        }
        val hudLabel = stringResource(R.string.stream_info_hud)

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        if (isFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.FocusRing
                    } else {
                        if (isFocused) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.4f)
                    }
                )
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(
            text = "$hudLabel: $statusText",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isFocused || enabled) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
