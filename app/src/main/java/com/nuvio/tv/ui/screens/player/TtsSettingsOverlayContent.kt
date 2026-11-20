@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tts.SpeakerGender
import com.nuvio.tv.core.tts.TtsBackendType
import com.nuvio.tv.core.tts.TtsDiagnosticsState
import com.nuvio.tv.core.tts.TtsDownloadState
import com.nuvio.tv.core.tts.TtsModelManager
import com.nuvio.tv.core.tts.TtsSettingsState
import com.nuvio.tv.core.tts.TtsVoiceCatalog
import com.nuvio.tv.core.tts.TtsVoicePreviewPlayer
import com.nuvio.tv.core.tts.TtsVoicePreviewState
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.tvhybrid.Button
import com.nuvio.tv.ui.tvhybrid.IconButton
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.player.v2PlayerPanel
import java.util.Locale
import kotlin.math.roundToInt

private enum class VoicePickerTarget {
    PRIMARY,
    MALE,
    FEMALE
}

/**
 * Dedicated player overlay for quick Subtitle Dubbing (TTS) controls triggered from the player deck.
 */
@Composable
internal fun TtsPlayerOverlay(
    visible: Boolean,
    ttsSettings: TtsSettingsState,
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    onBackgroundDismiss: () -> Unit = onDismiss,
    modifier: Modifier = Modifier
) {
    val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()
    val panelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(visible, tvFocusMode) {
        if (!visible) {
            TtsVoicePreviewPlayer.stop()
            return@LaunchedEffect
        }
        if (!tvFocusMode) return@LaunchedEffect
        panelFocusRequester.requestFocusAfterFrames()
    }
    DisposableEffect(Unit) {
        onDispose { TtsVoicePreviewPlayer.stop() }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxPanelWidth = (screenWidthDp - 32).coerceAtLeast(240).dp
    val compactOverlay = screenWidthDp < 600
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        dismissOnBackgroundClick = true,
        onBackgroundDismiss = onBackgroundDismiss,
        contentPadding = if (compactOverlay) {
            PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        } else {
            PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 28.dp)
        }
    ) {
        Column(
            modifier = Modifier
                .width(minOf(if (LocalV2Appearance.current != null) 400.dp else 320.dp, maxPanelWidth))
                .align(Alignment.BottomEnd)
                .heightIn(max = 620.dp)
                .v2PlayerPanel()
                .focusGroup()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            PanelEyebrow(text = stringResource(R.string.cd_tts_dubbing))
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            TtsSettingsPanel(
                ttsSettings = ttsSettings,
                onEvent = onEvent,
                initialFocusRequester = if (tvFocusMode) panelFocusRequester else null
            )
        }
    }
}

/**
 * TV D-Pad & Mobile/Tablet touch compatible Subtitle Dubbing (TTS) settings & voice manager panel.
 */
@Composable
internal fun TtsSettingsPanel(
    ttsSettings: TtsSettingsState,
    onEvent: (PlayerEvent) -> Unit,
    initialFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()
    val installedIds by TtsModelManager.installedIds.collectAsState()
    val downloadStates by TtsModelManager.downloadStates.collectAsState()
    val previewState by TtsVoicePreviewPlayer.state.collectAsState()
    val runtimeStatus by TtsDiagnosticsState.statusMessage.collectAsState()

    var activePicker by remember { mutableStateOf<VoicePickerTarget?>(null) }
    var lastOpenedPicker by remember { mutableStateOf<VoicePickerTarget?>(null) }
    var pendingRestoreFromPicker by remember { mutableStateOf(false) }

    val fallbackEnableRowFocusRequester = remember { FocusRequester() }
    val enableRowFocusRequester = initialFocusRequester ?: fallbackEnableRowFocusRequester
    val primaryRowFocusRequester = remember { FocusRequester() }
    val maleRowFocusRequester = remember { FocusRequester() }
    val femaleRowFocusRequester = remember { FocusRequester() }
    val pickerBackFocusRequester = remember { FocusRequester() }
    val pickerSelectedFocusRequester = remember { FocusRequester() }

    val mainListState = rememberLazyListState()
    val pickerListState = rememberLazyListState()

    BackHandler(enabled = activePicker != null) {
        pendingRestoreFromPicker = true
        activePicker = null
    }

    LaunchedEffect(Unit) {
        TtsModelManager.refreshInstalled(context)
    }

    DisposableEffect(activePicker) {
        onDispose { TtsVoicePreviewPlayer.stop() }
    }

    val currentTarget = activePicker
    val sortedPickerVoices = remember(currentTarget) {
        when (currentTarget) {
            VoicePickerTarget.MALE -> TtsVoiceCatalog.voices.sortedByDescending {
                it.gender == SpeakerGender.MALE
            }
            VoicePickerTarget.FEMALE -> TtsVoiceCatalog.voices.sortedByDescending {
                it.gender == SpeakerGender.FEMALE
            }
            VoicePickerTarget.PRIMARY, null -> TtsVoiceCatalog.voices
        }
    }
    val selectedVoiceIdForTarget = when (currentTarget) {
        VoicePickerTarget.PRIMARY -> ttsSettings.primaryVoiceId
        VoicePickerTarget.MALE -> ttsSettings.maleVoiceId
        VoicePickerTarget.FEMALE -> ttsSettings.femaleVoiceId
        null -> null
    }

    LaunchedEffect(currentTarget, tvFocusMode) {
        if (!tvFocusMode) return@LaunchedEffect
        if (currentTarget != null) {
            lastOpenedPicker = currentTarget
            val selectedIndex = sortedPickerVoices.indexOfFirst { it.id == selectedVoiceIdForTarget }
            val scrollItemIndex = if (selectedIndex >= 0) selectedIndex else 0
            pickerListState.scrollToItem(scrollItemIndex)
            val requester = if (selectedIndex >= 0) pickerSelectedFocusRequester else pickerBackFocusRequester
            requester.requestFocusAfterFrames()
        } else if (pendingRestoreFromPicker) {
            pendingRestoreFromPicker = false
            val restoreRequester = when (lastOpenedPicker) {
                VoicePickerTarget.PRIMARY -> primaryRowFocusRequester
                VoicePickerTarget.MALE -> maleRowFocusRequester
                VoicePickerTarget.FEMALE -> femaleRowFocusRequester
                null -> enableRowFocusRequester
            }
            if (!restoreRequester.requestFocusAfterFrames()) {
                enableRowFocusRequester.requestFocusAfterFrames()
            }
        }
    }

    if (currentTarget != null) {
        val selectedVoiceId = selectedVoiceIdForTarget.orEmpty()
        val title = when (currentTarget) {
            VoicePickerTarget.PRIMARY -> "‹ Quay lại · Chọn giọng đọc"
            VoicePickerTarget.MALE -> "‹ Quay lại · Chọn giọng Nam"
            VoicePickerTarget.FEMALE -> "‹ Quay lại · Chọn giọng Nữ"
        }

        val closePicker: () -> Unit = {
            pendingRestoreFromPicker = true
            activePicker = null
        }

        val applySelectedVoice: (String) -> Unit = { voiceId ->
            when (currentTarget) {
                VoicePickerTarget.PRIMARY -> onEvent(PlayerEvent.OnSelectTtsPrimaryVoice(voiceId))
                VoicePickerTarget.MALE -> onEvent(PlayerEvent.OnSelectTtsMaleVoice(voiceId))
                VoicePickerTarget.FEMALE -> onEvent(PlayerEvent.OnSelectTtsFemaleVoice(voiceId))
            }
        }

        LazyColumn(
            state = pickerListState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
            modifier = modifier
                .heightIn(max = 460.dp)
                .fillMaxWidth()
                .focusGroup()
        ) {
            item(key = "tts_picker_back") {
                PlayerPanelRow(
                    title = title,
                    subtitle = "Bấm 'Nghe thử' trước khi tải về máy",
                    selected = false,
                    onClick = closePicker,
                    focusRequester = pickerBackFocusRequester
                )
            }
            previewState.errorMessage?.let { errMsg ->
                item(key = "tts_preview_error") {
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.Secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            items(items = sortedPickerVoices, key = { "voice_${it.id}" }) { voice ->
                val isDownloaded = voice.backend != TtsBackendType.PIPER_ONNX || voice.id in installedIds
                val dlState: TtsDownloadState? = downloadStates[voice.id]
                val statusLabel = when {
                    dlState?.isDownloading == true -> "Đang tải ${dlState.progressPercent}%"
                    voice.backend != TtsBackendType.PIPER_ONNX -> voice.regionLabel
                    isDownloaded -> "${voice.regionLabel} • Đã tải"
                    else -> "${voice.regionLabel} • ${voice.sizeMb} MB"
                }
                val isSelected = selectedVoiceId == voice.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PlayerPanelRow(
                            title = voice.displayName,
                            subtitle = dlState?.errorMessage ?: statusLabel,
                            titleEnd = if (isSelected) "Đang chọn" else if (!isDownloaded) "Tải về" else null,
                            selected = isSelected,
                            focusRequester = if (isSelected) pickerSelectedFocusRequester else null,
                            onClick = {
                                if (isDownloaded) {
                                    applySelectedVoice(voice.id)
                                    closePicker()
                                } else if (dlState?.isDownloading != true) {
                                    TtsModelManager.startDownload(context, voice.id) { ok ->
                                        if (ok) {
                                            applySelectedVoice(voice.id)
                                        }
                                    }
                                }
                            }
                        )
                    }
                    TtsOverlayPreviewButton(
                        voiceId = voice.id,
                        previewState = previewState,
                        tvFocusMode = tvFocusMode,
                        onClick = {
                            TtsVoicePreviewPlayer.togglePreview(
                                context = context,
                                voiceId = voice.id,
                                speechSpeed = ttsSettings.speechSpeed
                            )
                        }
                    )
                }
            }
            // Allow deleting downloaded Piper model when selected voice is an installed Piper voice
            val removableVoice = TtsVoiceCatalog.findVoice(selectedVoiceId)
            if (removableVoice.backend == TtsBackendType.PIPER_ONNX && removableVoice.id in installedIds) {
                item(key = "tts_delete_model") {
                    PlayerPanelRow(
                        title = "Xóa model ${removableVoice.displayName}",
                        subtitle = "Giải phóng ${removableVoice.sizeMb} MB bộ nhớ trong",
                        selected = false,
                        onClick = {
                            TtsModelManager.deleteVoice(context, removableVoice.id)
                        }
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        state = mainListState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = modifier
            .heightIn(max = 460.dp)
            .fillMaxWidth()
            .focusGroup()
    ) {
        item(key = "tts_toggle_enable") {
            PlayerPanelRow(
                title = "Bật thuyết minh phụ đề",
                subtitle = when {
                    !ttsSettings.enabled -> "Đang tắt"
                    !runtimeStatus.isNullOrBlank() -> runtimeStatus
                    else -> "Đang bật • Chờ phụ đề..."
                },
                titleEnd = if (ttsSettings.enabled) "Bật" else "Tắt",
                selected = ttsSettings.enabled,
                onClick = { onEvent(PlayerEvent.OnSetTtsEnabled(!ttsSettings.enabled)) },
                focusRequester = enableRowFocusRequester
            )
        }

        if (ttsSettings.enabled) {
            item(key = "tts_toggle_dual_voice") {
                PlayerPanelRow(
                    title = "Lồng tiếng 2 giọng (Nam / Nữ)",
                    subtitle = "Tự phân tích cao độ giọng gốc & thẻ [M]/[F]",
                    titleEnd = if (ttsSettings.dualVoiceEnabled) "2 giọng" else "1 giọng",
                    selected = ttsSettings.dualVoiceEnabled,
                    onClick = { onEvent(PlayerEvent.OnSetTtsDualVoiceEnabled(!ttsSettings.dualVoiceEnabled)) }
                )
            }

            if (!ttsSettings.dualVoiceEnabled) {
                val primary = TtsVoiceCatalog.findVoice(ttsSettings.primaryVoiceId)
                val ready = primary.backend != TtsBackendType.PIPER_ONNX || primary.id in installedIds
                item(key = "tts_primary_voice") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PlayerPanelRow(
                                title = "Giọng đọc chính",
                                subtitle = if (ready) primary.displayName else "${primary.displayName} (Chưa tải - chạm để tải)",
                                titleEnd = "Chọn ›",
                                selected = false,
                                focusRequester = primaryRowFocusRequester,
                                onClick = { activePicker = VoicePickerTarget.PRIMARY }
                            )
                        }
                        TtsOverlayPreviewButton(
                            voiceId = primary.id,
                            previewState = previewState,
                            tvFocusMode = tvFocusMode,
                            onClick = {
                                TtsVoicePreviewPlayer.togglePreview(
                                    context = context,
                                    voiceId = primary.id,
                                    speechSpeed = ttsSettings.speechSpeed
                                )
                            }
                        )
                    }
                }
            } else {
                val male = TtsVoiceCatalog.findVoice(ttsSettings.maleVoiceId)
                val female = TtsVoiceCatalog.findVoice(ttsSettings.femaleVoiceId)
                val maleReady = male.backend != TtsBackendType.PIPER_ONNX || male.id in installedIds
                val femaleReady = female.backend != TtsBackendType.PIPER_ONNX || female.id in installedIds
                item(key = "tts_male_voice") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PlayerPanelRow(
                                title = "Giọng Nam",
                                subtitle = if (maleReady) male.displayName else "${male.displayName} (Chưa tải)",
                                titleEnd = "Chọn ›",
                                selected = false,
                                focusRequester = maleRowFocusRequester,
                                onClick = { activePicker = VoicePickerTarget.MALE }
                            )
                        }
                        TtsOverlayPreviewButton(
                            voiceId = male.id,
                            previewState = previewState,
                            tvFocusMode = tvFocusMode,
                            onClick = {
                                TtsVoicePreviewPlayer.togglePreview(
                                    context = context,
                                    voiceId = male.id,
                                    speechSpeed = ttsSettings.speechSpeed
                                )
                            }
                        )
                    }
                }
                item(key = "tts_female_voice") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PlayerPanelRow(
                                title = "Giọng Nữ",
                                subtitle = if (femaleReady) female.displayName else "${female.displayName} (Chưa tải)",
                                titleEnd = "Chọn ›",
                                selected = false,
                                focusRequester = femaleRowFocusRequester,
                                onClick = { activePicker = VoicePickerTarget.FEMALE }
                            )
                        }
                        TtsOverlayPreviewButton(
                            voiceId = female.id,
                            previewState = previewState,
                            tvFocusMode = tvFocusMode,
                            onClick = {
                                TtsVoicePreviewPlayer.togglePreview(
                                    context = context,
                                    voiceId = female.id,
                                    speechSpeed = ttsSettings.speechSpeed
                                )
                            }
                        )
                    }
                }
            }

            item(key = "tts_speed_stepper") {
                TtsValueStepperRow(
                    label = "Tốc độ đọc",
                    valueText = String.format(Locale.US, "%.2fx", ttsSettings.speechSpeed),
                    canDecrease = ttsSettings.speechSpeed > 0.72f,
                    canIncrease = ttsSettings.speechSpeed < 1.48f,
                    tvFocusMode = tvFocusMode,
                    onDecrease = {
                        val next = (((ttsSettings.speechSpeed - 0.05f) * 20).roundToInt() / 20f).coerceAtLeast(0.7f)
                        onEvent(PlayerEvent.OnSetTtsSpeechSpeed(next))
                    },
                    onIncrease = {
                        val next = (((ttsSettings.speechSpeed + 0.05f) * 20).roundToInt() / 20f).coerceAtMost(1.5f)
                        onEvent(PlayerEvent.OnSetTtsSpeechSpeed(next))
                    }
                )
            }

            item(key = "tts_voice_volume_stepper") {
                TtsValueStepperRow(
                    label = "Âm lượng giọng đọc",
                    valueText = "${ttsSettings.ttsVolumePercent}%",
                    canDecrease = ttsSettings.ttsVolumePercent > 0,
                    canIncrease = ttsSettings.ttsVolumePercent < 150,
                    tvFocusMode = tvFocusMode,
                    onDecrease = {
                        onEvent(PlayerEvent.OnSetTtsVolumePercent((ttsSettings.ttsVolumePercent - 10).coerceAtLeast(0)))
                    },
                    onIncrease = {
                        onEvent(PlayerEvent.OnSetTtsVolumePercent((ttsSettings.ttsVolumePercent + 10).coerceAtMost(150)))
                    }
                )
            }

            item(key = "tts_movie_volume_stepper") {
                TtsValueStepperRow(
                    label = "Âm lượng phim khi đọc",
                    valueText = "${ttsSettings.movieVolumePercent}%",
                    canDecrease = ttsSettings.movieVolumePercent > 0,
                    canIncrease = ttsSettings.movieVolumePercent < 100,
                    tvFocusMode = tvFocusMode,
                    onDecrease = {
                        onEvent(PlayerEvent.OnSetTtsMovieVolumePercent((ttsSettings.movieVolumePercent - 10).coerceAtLeast(0)))
                    },
                    onIncrease = {
                        onEvent(PlayerEvent.OnSetTtsMovieVolumePercent((ttsSettings.movieVolumePercent + 10).coerceAtMost(100)))
                    }
                )
            }

            item(key = "tts_hide_subtitles") {
                PlayerPanelRow(
                    title = "Ẩn phụ đề khi thuyết minh",
                    subtitle = "Giấu chữ hiển thị trên màn hình nhưng vẫn đọc bình thường",
                    titleEnd = if (ttsSettings.hideSubtitlesOnTts) "Bật" else "Tắt",
                    selected = ttsSettings.hideSubtitlesOnTts,
                    onClick = { onEvent(PlayerEvent.OnSetHideSubtitlesOnTts(!ttsSettings.hideSubtitlesOnTts)) }
                )
            }

            item(key = "tts_preserve_passthrough") {
                PlayerPanelRow(
                    title = "Giữ Passthrough âm thanh TV",
                    subtitle = if (ttsSettings.preserveTvPassthrough) {
                        "Giữ HDMI AC3/DTS/Atmos (không tự giảm tiếng phim & không tách Nam/Nữ)"
                    } else {
                        "Tắt Passthrough để giải mã PCM để giảm tiếng phim & tách giọng Nam/Nữ"
                    },
                    titleEnd = if (ttsSettings.preserveTvPassthrough) "Giữ HDMI" else "PCM Mix",
                    selected = ttsSettings.preserveTvPassthrough,
                    onClick = { onEvent(PlayerEvent.OnSetPreserveTvPassthrough(!ttsSettings.preserveTvPassthrough)) }
                )
            }
        }
    }
}

@Composable
private fun TtsValueStepperRow(
    label: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    tvFocusMode: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val decreaseFocusRequester = remember { FocusRequester() }
    val increaseFocusRequester = remember { FocusRequester() }
    var decreaseFocused by remember { mutableStateOf(false) }
    var increaseFocused by remember { mutableStateOf(false) }

    // Keep TV focus inside the stepper if a boundary becomes disabled while focused.
    LaunchedEffect(canDecrease, canIncrease, tvFocusMode) {
        if (!tvFocusMode) return@LaunchedEffect
        if (!canDecrease && decreaseFocused && canIncrease) {
            increaseFocusRequester.requestFocusAfterFrames(1)
        } else if (!canIncrease && increaseFocused && canDecrease) {
            decreaseFocusRequester.requestFocusAfterFrames(1)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioColors.Secondary
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDecrease,
                enabled = canDecrease,
                colors = IconButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                border = IconButtonDefaults.border(
                    focusedBorder = if (tvFocusMode) {
                        Border(border = BorderStroke(2.dp, Color.White), shape = CircleShape)
                    } else {
                        Border.None
                    }
                ),
                shape = IconButtonDefaults.shape(shape = CircleShape),
                modifier = Modifier
                    .size(34.dp)
                    .focusRequester(decreaseFocusRequester)
                    .nuvioV2Focus(decreaseFocused && tvFocusMode, CircleShape, stationary = true)
                    .onFocusChanged { decreaseFocused = it.isFocused }
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Giảm $label", modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onIncrease,
                enabled = canIncrease,
                colors = IconButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                border = IconButtonDefaults.border(
                    focusedBorder = if (tvFocusMode) {
                        Border(border = BorderStroke(2.dp, Color.White), shape = CircleShape)
                    } else {
                        Border.None
                    }
                ),
                shape = IconButtonDefaults.shape(shape = CircleShape),
                modifier = Modifier
                    .size(34.dp)
                    .focusRequester(increaseFocusRequester)
                    .nuvioV2Focus(increaseFocused && tvFocusMode, CircleShape, stationary = true)
                    .onFocusChanged { increaseFocused = it.isFocused }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Tăng $label", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TtsOverlayPreviewButton(
    voiceId: String,
    previewState: TtsVoicePreviewState,
    tvFocusMode: Boolean,
    onClick: () -> Unit
) {
    val isActive = previewState.activeVoiceId == voiceId
    val isLoading = isActive && previewState.isLoading
    val isPlaying = isActive && previewState.isPlaying
    val label = when {
        isLoading -> "Đang tải"
        isPlaying -> "Dừng"
        else -> "Nghe thử"
    }
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            containerColor = if (isPlaying) NuvioColors.Secondary.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        border = ButtonDefaults.border(
            focusedBorder = if (tvFocusMode) {
                Border(border = BorderStroke(2.dp, Color.White), shape = shape)
            } else {
                Border.None
            }
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 74.dp, minHeight = 36.dp)
            .nuvioV2Focus(isFocused && tvFocusMode, shape, stationary = true)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = label,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
