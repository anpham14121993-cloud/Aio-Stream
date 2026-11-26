package com.nuvio.tv.core.tts

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TtsSettingsState(
    val enabled: Boolean = false,
    val dualVoiceEnabled: Boolean = false,
    val primaryVoiceId: String = TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID,
    val maleVoiceId: String = TtsVoiceCatalog.DEFAULT_MALE_VOICE_ID,
    val femaleVoiceId: String = TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID,
    val speechSpeed: Float = 1.0f,
    val ttsVolumePercent: Int = 100,
    val movieVolumePercent: Int = 70,
    val hideSubtitlesOnTts: Boolean = false,
    val preserveTvPassthrough: Boolean = false
)

/**
 * SharedPreferences store for subtitle dubbing (TTS) preferences.
 * Isolated from media-protocol preferences using dedicated `tts_voice_prefs`.
 */
class TtsPreferencesStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<TtsSettingsState> = _state.asStateFlow()

    // Keep strong reference so Android SharedPreferences does not GC the listener
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = readState()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    val current: TtsSettingsState
        get() = _state.value

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setDualVoiceEnabled(enabled: Boolean) = update { it.copy(dualVoiceEnabled = enabled) }

    fun setPrimaryVoiceId(voiceId: String) = update { it.copy(primaryVoiceId = voiceId) }

    fun setMaleVoiceId(voiceId: String) = update { it.copy(maleVoiceId = voiceId) }

    fun setFemaleVoiceId(voiceId: String) = update { it.copy(femaleVoiceId = voiceId) }

    fun setSpeechSpeed(speed: Float) = update {
        it.copy(speechSpeed = speed.coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED))
    }

    fun setTtsVolumePercent(percent: Int) = update {
        it.copy(ttsVolumePercent = percent.coerceIn(0, 150))
    }

    fun setMovieVolumePercent(percent: Int) = update {
        it.copy(movieVolumePercent = percent.coerceIn(0, 100))
    }

    fun setHideSubtitlesOnTts(hide: Boolean) = update {
        it.copy(hideSubtitlesOnTts = hide)
    }

    fun setPreserveTvPassthrough(preserve: Boolean) = update {
        it.copy(preserveTvPassthrough = preserve)
    }

    private inline fun update(transform: (TtsSettingsState) -> TtsSettingsState) {
        var nextState = _state.value
        _state.update { current ->
            transform(current).also { nextState = it }
        }
        writeState(nextState)
    }

    private fun readState(): TtsSettingsState = TtsSettingsState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        dualVoiceEnabled = prefs.getBoolean(KEY_DUAL_VOICE, false),
        primaryVoiceId = sanitizeVoiceId(
            prefs.getString(KEY_PRIMARY_VOICE, TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID),
            TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID
        ),
        maleVoiceId = sanitizeVoiceId(
            prefs.getString(KEY_MALE_VOICE, TtsVoiceCatalog.DEFAULT_MALE_VOICE_ID),
            TtsVoiceCatalog.DEFAULT_MALE_VOICE_ID
        ),
        femaleVoiceId = sanitizeVoiceId(
            prefs.getString(KEY_FEMALE_VOICE, TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID),
            TtsVoiceCatalog.DEFAULT_FEMALE_VOICE_ID
        ),
        speechSpeed = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED),
        ttsVolumePercent = prefs.getInt(KEY_TTS_VOLUME, 100).coerceIn(0, 150),
        movieVolumePercent = prefs.getInt(KEY_MOVIE_VOLUME, 70).coerceIn(0, 100),
        hideSubtitlesOnTts = prefs.getBoolean(KEY_HIDE_SUBS, false),
        preserveTvPassthrough = prefs.getBoolean(KEY_PRESERVE_PASSTHROUGH, false)
    )

    private fun sanitizeVoiceId(raw: String?, fallback: String): String {
        val candidate = raw?.takeIf { it.isNotBlank() } ?: return fallback
        if (candidate == LEGACY_SYSTEM_VOICE_ID) return TtsVoiceCatalog.GOOGLE_VOICE_ID
        return candidate
    }

    private fun writeState(state: TtsSettingsState) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putBoolean(KEY_DUAL_VOICE, state.dualVoiceEnabled)
            .putString(KEY_PRIMARY_VOICE, state.primaryVoiceId)
            .putString(KEY_MALE_VOICE, state.maleVoiceId)
            .putString(KEY_FEMALE_VOICE, state.femaleVoiceId)
            .putFloat(KEY_SPEED, state.speechSpeed)
            .putInt(KEY_TTS_VOLUME, state.ttsVolumePercent)
            .putInt(KEY_MOVIE_VOLUME, state.movieVolumePercent)
            .putBoolean(KEY_HIDE_SUBS, state.hideSubtitlesOnTts)
            .putBoolean(KEY_PRESERVE_PASSTHROUGH, state.preserveTvPassthrough)
            .apply()
    }

    companion object {
        const val MIN_SPEECH_SPEED = 0.7f
        const val MAX_SPEECH_SPEED = 2.0f
        private const val LEGACY_SYSTEM_VOICE_ID = "system_tts"
        private const val PREFS_NAME = "tts_voice_prefs"
        private const val KEY_ENABLED = "tts_enabled"
        private const val KEY_DUAL_VOICE = "tts_dual_voice"
        private const val KEY_PRIMARY_VOICE = "tts_primary_voice"
        private const val KEY_MALE_VOICE = "tts_male_voice"
        private const val KEY_FEMALE_VOICE = "tts_female_voice"
        private const val KEY_SPEED = "tts_speech_speed"
        private const val KEY_TTS_VOLUME = "tts_volume_percent"
        private const val KEY_MOVIE_VOLUME = "tts_movie_volume_percent"
        private const val KEY_HIDE_SUBS = "tts_hide_subtitles"
        private const val KEY_PRESERVE_PASSTHROUGH = "tts_preserve_tv_passthrough"
    }
}
