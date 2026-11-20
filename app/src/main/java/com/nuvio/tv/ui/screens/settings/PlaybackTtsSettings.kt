@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.tts.SpeakerGender
import com.nuvio.tv.core.tts.TtsBackendType
import com.nuvio.tv.core.tts.TtsModelManager
import com.nuvio.tv.core.tts.TtsPreferencesStore
import com.nuvio.tv.core.tts.TtsVoiceCatalog
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var activeDialogTarget by remember { mutableStateOf<PlaybackTtsVoiceTarget?>(null) }

    LaunchedEffect(Unit) {
        TtsModelManager.refreshInstalled(context)
    }

    SettingsSectionLabel(text = "Cài đặt thuyết minh")

    SettingsToggleRow(
        title = "Bật thuyết minh phụ đề",
        subtitle = "Tự động đọc phụ đề bằng Piper AI Offline, Google Dịch hoặc giọng hệ thống",
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
        SettingsActionRow(
            title = "Giọng đọc chính",
            subtitle = if (ready) primary.regionLabel else "${primary.regionLabel} • Chưa tải (chọn để tải)",
            value = primary.displayName,
            onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.PRIMARY }
        )
    } else {
        val male = TtsVoiceCatalog.findVoice(ttsSettings.maleVoiceId)
        val female = TtsVoiceCatalog.findVoice(ttsSettings.femaleVoiceId)
        SettingsActionRow(
            title = "Giọng Nam",
            subtitle = male.regionLabel,
            value = male.displayName,
            onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.MALE }
        )
        SettingsActionRow(
            title = "Giọng Nữ",
            subtitle = female.regionLabel,
            value = female.displayName,
            onClick = { activeDialogTarget = PlaybackTtsVoiceTarget.FEMALE }
        )
    }

    SliderSettingsItem(
        title = "Tốc độ đọc",
        value = (ttsSettings.speechSpeed * 100f).roundToInt(),
        valueText = String.format(Locale.US, "%.2fx", ttsSettings.speechSpeed),
        minValue = 70,
        maxValue = 150,
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
        subtitle = "Tải hoặc xóa các gói giọng đọc AI tiếng Việt (~63.5 MB / giọng)",
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

        NuvioDialog(
            onDismiss = { activeDialogTarget = null },
            title = dialogTitle,
            subtitle = "Chạm giọng chưa tải để tải model; chọn giọng đã tải để áp dụng",
            width = 620.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
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
                        SettingsActionRow(
                            title = voice.displayName,
                            subtitle = subtitleText,
                            value = valueBadge,
                            onClick = {
                                if (target == PlaybackTtsVoiceTarget.MANAGE_ONLY) {
                                    if (isDownloaded) {
                                        TtsModelManager.deleteVoice(context, voice.id)
                                    } else if (dl?.isDownloading != true) {
                                        scope.launch { TtsModelManager.downloadVoice(context, voice.id) }
                                    }
                                    return@SettingsActionRow
                                }

                                if (isDownloaded) {
                                    when (target) {
                                        PlaybackTtsVoiceTarget.PRIMARY -> prefs.setPrimaryVoiceId(voice.id)
                                        PlaybackTtsVoiceTarget.MALE -> prefs.setMaleVoiceId(voice.id)
                                        PlaybackTtsVoiceTarget.FEMALE -> prefs.setFemaleVoiceId(voice.id)
                                        PlaybackTtsVoiceTarget.MANAGE_ONLY -> Unit
                                    }
                                    activeDialogTarget = null
                                } else if (dl?.isDownloading != true) {
                                    scope.launch {
                                        val ok = TtsModelManager.downloadVoice(context, voice.id)
                                        if (ok) {
                                            when (target) {
                                                PlaybackTtsVoiceTarget.PRIMARY -> prefs.setPrimaryVoiceId(voice.id)
                                                PlaybackTtsVoiceTarget.MALE -> prefs.setMaleVoiceId(voice.id)
                                                PlaybackTtsVoiceTarget.FEMALE -> prefs.setFemaleVoiceId(voice.id)
                                                PlaybackTtsVoiceTarget.MANAGE_ONLY -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
