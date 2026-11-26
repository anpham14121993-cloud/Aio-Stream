@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.tts.SpeakerGender
import com.nuvio.tv.core.tts.TtsBackendType
import com.nuvio.tv.core.tts.TtsModelManager
import com.nuvio.tv.core.tts.TtsPreferencesStore
import com.nuvio.tv.core.tts.TtsVoiceCatalog
import com.nuvio.tv.core.tts.TtsVoicePreviewPlayer
import com.nuvio.tv.core.tts.TtsVoicePreviewState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.tvhybrid.Button
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.util.Locale
import kotlin.math.roundToInt

private enum class PlaybackTtsVoiceTarget {
    PRIMARY,
    MALE,
    FEMALE,
    MANAGE_ONLY
}

@Composable
internal fun PlaybackTtsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { TtsPreferencesStore(context) }
    val ttsSettings by prefs.state.collectAsState()
    val installedIds by TtsModelManager.installedIds.collectAsState()
    val downloadStates by TtsModelManager.downloadStates.collectAsState()
    val previewState by TtsVoicePreviewPlayer.state.collectAsState()
    var activeDialogTarget by remember { mutableStateOf<PlaybackTtsVoiceTarget?>(null) }

    LaunchedEffect(Unit) {
        TtsModelManager.refreshInstalled(context)
    }

    DisposableEffect(activeDialogTarget) {
        onDispose { TtsVoicePreviewPlayer.stop() }
    }

    DisposableEffect(Unit) {
        onDispose { TtsVoicePreviewPlayer.stop() }
    }

    SettingsSectionLabel(text = "Cài đặt thuyết minh")

    SettingsToggleRow(
        title = "Bật thuyết minh phụ đề",
        subtitle = "Tự động đọc phụ đề bằng Piper AI Offline hoặc Google Dịch Online",
        checked = ttsSettings.enabled,
        onToggle = { prefs.setEnabled(!ttsSettings.enabled) }
    )

    SettingsToggleRow(
        title = "Lồng tiếng 2 giọng (Nam / Nữ)",
        subtitle = "Tự phân tích cao độ giọng gốc trong phim & thẻ [M]/[F]",
        checked = ttsSettings.dualVoiceEnabled,
        onToggle = { prefs.setDualVoiceEnabled(!ttsSettings.dualVoiceEnabled) }
    )

    if (!ttsSettings.dualVoiceEnabled) {
        val primary = TtsVoiceCatalog.findVoice(ttsSettings.primaryVoiceId)
        val ready = primary.backend != TtsBackendType.PIPER_ONNX || primary.id in installedIds
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsActionRow(
                    title = "Giọng đọc chính",
                    subtitle = if (ready) primary.regionLabel else "${primary.regionLabel} • Chưa tải (chọn để tải)",
                    value = primary.displayName,
                    onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.PRIMARY }
                )
            }
            SettingsTtsPreviewButton(
                voiceId = primary.id,
                previewState = previewState,
                onClick = {
                    TtsVoicePreviewPlayer.togglePreview(
                        context = context,
                        voiceId = primary.id,
                        speechSpeed = ttsSettings.speechSpeed
                    )
                }
            )
        }
    } else {
        val male = TtsVoiceCatalog.findVoice(ttsSettings.maleVoiceId)
        val female = TtsVoiceCatalog.findVoice(ttsSettings.femaleVoiceId)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsActionRow(
                    title = "Giọng Nam",
                    subtitle = male.regionLabel,
                    value = male.displayName,
                    onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.MALE }
                )
            }
            SettingsTtsPreviewButton(
                voiceId = male.id,
                previewState = previewState,
                onClick = {
                    TtsVoicePreviewPlayer.togglePreview(
                        context = context,
                        voiceId = male.id,
                        speechSpeed = ttsSettings.speechSpeed
                    )
                }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsActionRow(
                    title = "Giọng Nữ",
                    subtitle = female.regionLabel,
                    value = female.displayName,
                    onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.FEMALE }
                )
            }
            SettingsTtsPreviewButton(
                voiceId = female.id,
                previewState = previewState,
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

    SliderSettingsItem(
        title = "Tốc độ đọc",
        value = (ttsSettings.speechSpeed * 100f).roundToInt(),
        valueText = String.format(Locale.US, "%.2fx", ttsSettings.speechSpeed),
        minValue = 70,
        maxValue = 200,
        step = 5,
        onValueChange = { percent -> prefs.setSpeechSpeed(percent / 100f) }
    )

    SliderSettingsItem(
        title = "Âm lượng giọng đọc",
        subtitle = "Trên 100% sẽ khuếch đại thêm bằng LoudnessEnhancer",
        value = ttsSettings.ttsVolumePercent,
        valueText = "${ttsSettings.ttsVolumePercent}%",
        minValue = 0,
        maxValue = 150,
        step = 10,
        onValueChange = { percent -> prefs.setTtsVolumePercent(percent) }
    )

    SliderSettingsItem(
        title = "Âm lượng phim khi đọc",
        subtitle = "Tự giảm êm tiếng phim khi đọc và hồi phục khi ngắt câu",
        value = ttsSettings.movieVolumePercent,
        valueText = "${ttsSettings.movieVolumePercent}%",
        minValue = 0,
        maxValue = 100,
        step = 10,
        onValueChange = { percent -> prefs.setMovieVolumePercent(percent) }
    )

    SettingsSectionLabel(text = "Hiển thị & Âm thanh TV")

    SettingsToggleRow(
        title = "Ẩn phụ đề hiển thị khi thuyết minh",
        subtitle = "Giấu chữ phụ đề trên màn hình nhưng vẫn chạy ngầm để thuyết minh",
        checked = ttsSettings.hideSubtitlesOnTts,
        onToggle = { prefs.setHideSubtitlesOnTts(!ttsSettings.hideSubtitlesOnTts) }
    )

    SettingsToggleRow(
        title = "Giữ Passthrough âm thanh TV",
        subtitle = if (ttsSettings.preserveTvPassthrough) {
            "Giữ nguyên HDMI AC3/DTS/Atmos ra AVR/Soundbar (không giảm tiếng phim & không tách Nam/Nữ)"
        } else {
            "Tắt Passthrough để giải mã PCM: vừa giảm tiếng phim vừa tách giọng Nam/Nữ chính xác"
        },
        checked = ttsSettings.preserveTvPassthrough,
        onToggle = { prefs.setPreserveTvPassthrough(!ttsSettings.preserveTvPassthrough) }
    )

    SettingsActionRow(
        title = "Quản lý giọng đọc Offline (Piper ONNX)",
        subtitle = "Nghe thử, tải hoặc xóa các gói giọng đọc AI tiếng Việt (~63.5 MB / giọng)",
        value = "Đã tải ${installedIds.size}/${TtsVoiceCatalog.piperVoices.size}",
        onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.MANAGE_ONLY }
    )

    activeDialogTarget?.let { target ->
        val dialogTitle = when (target) {
            PlaybackTtsVoiceTarget.PRIMARY -> "Chọn giọng đọc chính"
            PlaybackTtsVoiceTarget.MALE -> "Chọn giọng Nam"
            PlaybackTtsVoiceTarget.FEMALE -> "Chọn giọng Nữ"
            PlaybackTtsVoiceTarget.MANAGE_ONLY -> "Quản lý giọng Offline (Piper ONNX)"
        }
        val selectedVoiceId = when (target) {
            PlaybackTtsVoiceTarget.PRIMARY -> ttsSettings.primaryVoiceId
            PlaybackTtsVoiceTarget.MALE -> ttsSettings.maleVoiceId
            PlaybackTtsVoiceTarget.FEMALE -> ttsSettings.femaleVoiceId
            PlaybackTtsVoiceTarget.MANAGE_ONLY -> null
        }
        val voicesList = remember(target) {
            when (target) {
                PlaybackTtsVoiceTarget.MALE -> TtsVoiceCatalog.voices.sortedByDescending {
                    it.gender == SpeakerGender.MALE
                }
                PlaybackTtsVoiceTarget.FEMALE -> TtsVoiceCatalog.voices.sortedByDescending {
                    it.gender == SpeakerGender.FEMALE
                }
                PlaybackTtsVoiceTarget.PRIMARY -> TtsVoiceCatalog.voices
                PlaybackTtsVoiceTarget.MANAGE_ONLY -> TtsVoiceCatalog.piperVoices
            }
        }
        val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()
        val selectedIndex = remember(voicesList, selectedVoiceId) {
            voicesList.indexOfFirst { it.id == selectedVoiceId }.coerceAtLeast(0)
        }
        val dialogListState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val dialogFocusRequester = remember(target) { FocusRequester() }

        LaunchedEffect(target, tvFocusMode) {
            if (!tvFocusMode) return@LaunchedEffect
            dialogListState.scrollToItem(selectedIndex)
            dialogFocusRequester.requestFocusAfterFrames()
        }

        NuvioDialog(
            onDismiss = { activeDialogTarget = null },
            title = dialogTitle,
            subtitle = "Bấm 'Nghe thử' để nghe giọng mẫu trước khi tải • Chạm vào dòng giọng để tải hoặc chọn",
            width = 680.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                previewState.errorMessage?.let { errMsg ->
                    SettingsNote(text = errMsg, tone = SettingsNoteTone.Warning)
                }
                LazyColumn(
                    state = dialogListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(items = voicesList, key = { it.id }) { voice ->
                        val isPiper = voice.backend == TtsBackendType.PIPER_ONNX
                        val isDownloaded = !isPiper || voice.id in installedIds
                        val dl = downloadStates[voice.id]
                        val isInitialFocus = voicesList.getOrNull(selectedIndex)?.id == voice.id
                        val subtitleText = dl?.errorMessage ?: when {
                            dl?.isDownloading == true -> "Đang tải về ${dl.progressPercent}%..."
                            !isPiper -> voice.regionLabel
                            isDownloaded -> "${voice.regionLabel} • Đã tải (${voice.sizeMb} MB)"
                            else -> "${voice.regionLabel} • Chưa tải (${voice.sizeMb} MB)"
                        }
                        val valueBadge = when {
                            dl?.isDownloading == true -> "${dl.progressPercent}%"
                            selectedVoiceId == voice.id -> "Đang dùng"
                            target == PlaybackTtsVoiceTarget.MANAGE_ONLY && isDownloaded -> "Xóa"
                            !isDownloaded -> "Tải về"
                            else -> "Chọn"
                        }
                        val applyTargetVoice: () -> Unit = {
                            when (target) {
                                PlaybackTtsVoiceTarget.PRIMARY -> prefs.setPrimaryVoiceId(voice.id)
                                PlaybackTtsVoiceTarget.MALE -> prefs.setMaleVoiceId(voice.id)
                                PlaybackTtsVoiceTarget.FEMALE -> prefs.setFemaleVoiceId(voice.id)
                                PlaybackTtsVoiceTarget.MANAGE_ONLY -> Unit
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SettingsActionRow(
                                    title = voice.displayName,
                                    subtitle = subtitleText,
                                    value = valueBadge,
                                    jumpAnchor = false,
                                    modifier = if (isInitialFocus) Modifier.focusRequester(dialogFocusRequester) else Modifier,
                                    onClick = {
                                        if (target == PlaybackTtsVoiceTarget.MANAGE_ONLY) {
                                            if (isDownloaded) {
                                                TtsModelManager.deleteVoice(context, voice.id)
                                            } else if (dl?.isDownloading != true) {
                                                TtsModelManager.startDownload(context, voice.id)
                                            }
                                            return@SettingsActionRow
                                        }

                                        if (isDownloaded) {
                                            applyTargetVoice()
                                            activeDialogTarget = null
                                        } else if (dl?.isDownloading != true) {
                                            TtsModelManager.startDownload(context, voice.id) { ok ->
                                                if (ok) applyTargetVoice()
                                            }
                                        }
                                    }
                                )
                            }
                            SettingsTtsPreviewButton(
                                voiceId = voice.id,
                                previewState = previewState,
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
                }
            }
        }
    }
}

@Composable
private fun SettingsTtsPreviewButton(
    voiceId: String,
    previewState: TtsVoicePreviewState,
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
    val tvFocusMode = com.nuvio.tv.ui.monolith.isTvFocusMode()
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            containerColor = if (isPlaying) NuvioTheme.colors.Secondary.copy(alpha = 0.28f) else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
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
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 86.dp, minHeight = 42.dp)
            .nuvioV2Focus(isFocused && tvFocusMode, shape, stationary = true)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = label,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
