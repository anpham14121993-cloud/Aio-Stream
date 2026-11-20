package com.nuvio.tv.core.tts

import android.content.Context
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates VAX-parity subtitle dubbing (`Lz26` + `Ls26` + `Lxa4`):
 * - Timeline mode (55 ms tick, 5000 ms lookahead prefetch, F0 gender-flip re-prefetch, WSOLA window fit)
 * - Live-cue fallback (for embedded ExoPlayer / MPV tracks without advance cue lists)
 * - Asymmetric movie audio ducking (180 ms duck down, 900 ms smooth restore)
 */
class TtsSubtitleCoordinator(
    context: Context,
    val preferences: TtsPreferencesStore,
    private val positionProviderMs: () -> Long,
    private val onDuckVolumeChanged: (Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val ttsErrorHandler = CoroutineExceptionHandler { _, _ ->
        // Non-fatal TTS synthesis/network errors must never tear down video playback.
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + ttsErrorHandler)

    val pitchAnalyzer = VoicePitchAnalyzer()
    val audioTapProcessor = TtsAudioTapProcessor(pitchAnalyzer, positionProviderMs)

    private val piperEngine = PiperOnnxEngine(appContext)
    private val googleEngine = GoogleTranslateTtsEngine(appContext)
    private val systemEngine = SystemTtsEngine(appContext)

    private val wavCacheDir: File by lazy {
        File(appContext.cacheDir, "tts_cache").apply { mkdirs() }
    }

    private data class PrefetchedClip(
        val key: String,
        val cueStartMs: Long,
        val cueEndMs: Long,
        val file: File,
        val voiceId: String,
        val resolvedGender: SpeakerGender,
        val usedPitchHint: Boolean
    )

    private val prefetchedClips = ConcurrentHashMap<String, PrefetchedClip>()
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var timelineCues: List<SubtitleSyncCue> = emptyList()

    @Volatile
    private var lastTriggeredCueKey: String? = null

    @Volatile
    private var lastLiveCueText: String? = null

    @Volatile
    private var lastLiveTriggerAtMs: Long = 0L

    @Volatile
    private var isPlayerPlaying = false

    @Volatile
    private var isSpeaking = false

    private var lookaheadJob: Job? = null
    private var duckAnimJob: Job? = null
    private var currentDuckFactor = 1.0f

    private val playerQueue = TtsAudioPlayerQueue(appContext) { speaking ->
        isSpeaking = speaking
        scheduleDuckTransition()
    }

    init {
        TtsModelManager.refreshInstalled(appContext)
    }

    fun onSettingsUpdated(settings: TtsSettingsState) {
        audioTapProcessor.isTapActive = settings.enabled && settings.dualVoiceEnabled && !settings.preserveTvPassthrough
        playerQueue.updateVolumePercent(settings.ttsVolumePercent)

        if (!settings.enabled) {
            stopLookahead()
            playerQueue.clearAndStop()
            cancelAllPrefetches()
            pitchAnalyzer.reset()
        } else {
            ensureLookaheadRunningIfNeeded()
        }
        scheduleDuckTransition()
    }

    fun updateTimelineCues(cues: List<SubtitleSyncCue>) {
        timelineCues = cues
        lastTriggeredCueKey = null
        cancelAllPrefetches()
        ensureLookaheadRunningIfNeeded()
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        isPlayerPlaying = isPlaying
        playerQueue.setMoviePaused(!isPlaying)
        if (!isPlaying) {
            isSpeaking = false
        }
        scheduleDuckTransition()
    }

    fun onSeek(newPosMs: Long) {
        playerQueue.clearAndStop()
        lastTriggeredCueKey = null
        lastLiveCueText = null
        cancelAllPrefetches()
        pitchAnalyzer.reset()

        // Mark any cue that already started well before seek target as skipped
        val cues = timelineCues
        if (cues.isNotEmpty()) {
            val idx = findActiveCueIndex(cues, newPosMs - 600L)
            if (idx >= 0) {
                lastTriggeredCueKey = cueKey(cues[idx], idx)
            }
        }
    }

    /**
     * Live fallback entry point for embedded ExoPlayer / MPV subtitle text when sidecar cues are absent.
     */
    fun onLiveCueText(rawText: String, positionMs: Long) {
        val settings = preferences.current
        if (!settings.enabled || !isPlayerPlaying) return
        if (timelineCues.isNotEmpty()) return // Timeline lookahead handles sidecar cues directly

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(rawText)
        if (parsed.speakableText.isBlank()) return

        val now = System.currentTimeMillis()
        if (parsed.speakableText == lastLiveCueText && (now - lastLiveTriggerAtMs) < LIVE_DEDUP_MS) {
            return
        }
        lastLiveCueText = parsed.speakableText
        lastLiveTriggerAtMs = now

        val cueKey = "live_${positionMs}_${sha256Short(parsed.speakableText)}"
        val targetGender = resolveGenderForSpan(settings, parsed.tagGender, positionMs - 800L, positionMs + 800L)
        val voiceId = resolveVoiceId(settings, targetGender)

        scope.launch {
            val file = synthesizeClip(
                cueKey = cueKey,
                text = parsed.speakableText,
                voiceId = voiceId,
                gender = targetGender,
                speechSpeed = settings.speechSpeed,
                ttsVolumePercent = settings.ttsVolumePercent,
                targetWindowMs = null
            ) ?: return@launch

            if (preferences.current.enabled && isPlayerPlaying) {
                playerQueue.enqueue(
                    TtsPlaybackItem(
                        cueKey = cueKey,
                        audioFile = file,
                        baseSpeed = settings.speechSpeed
                    )
                )
            }
        }
    }

    private fun ensureLookaheadRunningIfNeeded() {
        val settings = preferences.current
        if (!settings.enabled || timelineCues.isEmpty()) {
            stopLookahead()
            return
        }
        if (lookaheadJob?.isActive == true) return

        lookaheadJob = scope.launch {
            while (isActive && preferences.current.enabled && timelineCues.isNotEmpty()) {
                if (isPlayerPlaying) {
                    val posMs = positionProviderMs()
                    processTimelineTick(posMs, preferences.current)
                }
                delay(LOOKAHEAD_TICK_MS)
            }
        }
    }

    private fun stopLookahead() {
        lookaheadJob?.cancel()
        lookaheadJob = null
    }

    private fun processTimelineTick(posMs: Long, settings: TtsSettingsState) {
        val cues = timelineCues
        if (cues.isEmpty()) return

        if (settings.dualVoiceEnabled && !settings.preserveTvPassthrough) {
            pitchAnalyzer.refreshEstimation()
        }

        // 1. Trigger due cue
        val currentIdx = findActiveCueIndex(cues, posMs)
        if (currentIdx >= 0) {
            val cue = cues[currentIdx]
            val key = cueKey(cue, currentIdx)
            if (key != lastTriggeredCueKey && posMs <= cue.startTimeMs + MAX_LATE_TRIGGER_MS) {
                lastTriggeredCueKey = key
                triggerTimelineCue(cue, key, settings)
            }
        }

        // 2. Prefetch upcoming cues within lookahead window & check F0 flip re-prefetch (`Lxa4`)
        val horizonMs = posMs + PREFETCH_LOOKAHEAD_MS
        for (i in max(0, currentIdx) until cues.size) {
            val cue = cues[i]
            if (cue.startTimeMs > horizonMs) break
            if (cue.endTimeMs < posMs) continue

            val key = cueKey(cue, i)
            val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
            if (parsed.speakableText.isBlank()) continue

            val desiredGender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
            val desiredVoiceId = resolveVoiceId(settings, desiredGender)

            val existing = prefetchedClips[key]
            if (existing != null) {
                // If dual-voice pitch estimate flipped between prefetch and playback, re-synthesize!
                if (settings.dualVoiceEnabled &&
                    parsed.tagGender == SpeakerGender.UNSPECIFIED &&
                    desiredGender != SpeakerGender.UNSPECIFIED &&
                    existing.voiceId != desiredVoiceId &&
                    posMs < cue.startTimeMs - 150L
                ) {
                    prefetchedClips.remove(key)
                    launchPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, settings)
                }
                continue
            }

            if (!inFlightJobs.containsKey(key)) {
                launchPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, settings)
            }
        }
    }

    private fun triggerTimelineCue(
        cue: SubtitleSyncCue,
        key: String,
        settings: TtsSettingsState
    ) {
        val ready = prefetchedClips.remove(key)
        if (ready != null && ready.file.exists()) {
            playerQueue.enqueue(
                TtsPlaybackItem(
                    cueKey = key,
                    audioFile = ready.file,
                    baseSpeed = settings.speechSpeed
                )
            )
            return
        }

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
        if (parsed.speakableText.isBlank()) return
        val gender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
        val voiceId = resolveVoiceId(settings, gender)

        scope.launch {
            val windowMs = (cue.endTimeMs - cue.startTimeMs).takeIf { it > 350L }
            val file = synthesizeClip(
                cueKey = key,
                text = parsed.speakableText,
                voiceId = voiceId,
                gender = gender,
                speechSpeed = settings.speechSpeed,
                ttsVolumePercent = settings.ttsVolumePercent,
                targetWindowMs = windowMs
            ) ?: return@launch

            if (preferences.current.enabled && isPlayerPlaying) {
                val nowPos = positionProviderMs()
                if (nowPos <= cue.endTimeMs + 300L) {
                    playerQueue.enqueue(
                        TtsPlaybackItem(
                            cueKey = key,
                            audioFile = file,
                            baseSpeed = settings.speechSpeed
                        )
                    )
                }
            }
        }
    }

    private fun launchPrefetch(
        cue: SubtitleSyncCue,
        key: String,
        text: String,
        voiceId: String,
        gender: SpeakerGender,
        settings: TtsSettingsState
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val windowMs = (cue.endTimeMs - cue.startTimeMs).takeIf { it > 350L }
                val file = synthesizeClip(
                    cueKey = key,
                    text = text,
                    voiceId = voiceId,
                    gender = gender,
                    speechSpeed = settings.speechSpeed,
                    ttsVolumePercent = settings.ttsVolumePercent,
                    targetWindowMs = windowMs
                )
                if (file != null) {
                    prefetchedClips[key] = PrefetchedClip(
                        key = key,
                        cueStartMs = cue.startTimeMs,
                        cueEndMs = cue.endTimeMs,
                        file = file,
                        voiceId = voiceId,
                        resolvedGender = gender,
                        usedPitchHint = settings.dualVoiceEnabled
                    )
                }
            } finally {
                val self = this.coroutineContext[Job]
                if (self != null) {
                    inFlightJobs.remove(key, self)
                } else {
                    inFlightJobs.remove(key)
                }
            }
        }
        inFlightJobs[key] = job
        job.start()
    }

    private suspend fun synthesizeClip(
        cueKey: String,
        text: String,
        voiceId: String,
        gender: SpeakerGender,
        speechSpeed: Float,
        ttsVolumePercent: Int,
        targetWindowMs: Long?
    ): File? {
        val voice = TtsVoiceCatalog.findVoice(voiceId)
        val effectiveBackend = when {
            voice.backend == TtsBackendType.PIPER_ONNX && TtsVoiceCatalog.isVoiceReady(appContext, voice.id) ->
                TtsBackendType.PIPER_ONNX
            voice.backend == TtsBackendType.SYSTEM_TTS -> TtsBackendType.SYSTEM_TTS
            voice.backend == TtsBackendType.GOOGLE_TRANSLATE -> TtsBackendType.GOOGLE_TRANSLATE
            // Selected Piper model not downloaded yet -> seamless fallback to System TTS / Google
            else -> TtsBackendType.GOOGLE_TRANSLATE
        }

        val hash = sha256Short("${voice.id}|$effectiveBackend|$gender|$speechSpeed|$targetWindowMs|$text")
        return when (effectiveBackend) {
            TtsBackendType.PIPER_ONNX -> {
                val outWav = File(wavCacheDir, "piper_$hash.wav")
                if (outWav.exists() && outWav.length() > 256L) return outWav

                val synth = piperEngine.synthesize(voice.id, text, speechSpeed) ?: return null
                val trimmed = TtsAudioPostProcessor.trimSilence(synth.samples, synth.sampleRate)

                val stretched = if (targetWindowMs != null && targetWindowMs >= 450L) {
                    val audioDurationMs = (trimmed.size * 1000.0) / synth.sampleRate.toDouble()
                    val availableMs = targetWindowMs.toDouble() + 180.0
                    if (audioDurationMs > availableMs) {
                        val fitRatio = (audioDurationMs / availableMs).toFloat().coerceIn(0.8f, 1.65f)
                        WsolaTimeStretcher.timeStretch(trimmed, synth.sampleRate, fitRatio)
                    } else {
                        trimmed
                    }
                } else {
                    trimmed
                }

                val written = TtsAudioPostProcessor.writeNormalizedWav(
                    samples = stretched,
                    sampleRate = synth.sampleRate,
                    ttsVolumePercent = ttsVolumePercent,
                    outFile = outWav
                )
                if (written) outWav else null
            }
            TtsBackendType.GOOGLE_TRANSLATE -> {
                googleEngine.synthesizeToMp3(text, hash)
            }
            TtsBackendType.SYSTEM_TTS -> {
                systemEngine.synthesizeToWav(text, gender, speechSpeed, hash)
            }
        }
    }

    private fun resolveGenderForSpan(
        settings: TtsSettingsState,
        tagGender: SpeakerGender,
        startMs: Long,
        endMs: Long
    ): SpeakerGender {
        if (!settings.dualVoiceEnabled) return SpeakerGender.UNSPECIFIED
        if (tagGender != SpeakerGender.UNSPECIFIED) return tagGender
        return pitchAnalyzer.estimateGenderForSpan(startMs, endMs)
    }

    private fun resolveVoiceId(settings: TtsSettingsState, gender: SpeakerGender): String {
        if (!settings.dualVoiceEnabled) return settings.primaryVoiceId
        return when (gender) {
            SpeakerGender.MALE -> settings.maleVoiceId
            SpeakerGender.FEMALE -> settings.femaleVoiceId
            SpeakerGender.UNSPECIFIED -> settings.primaryVoiceId
        }
    }

    private fun scheduleDuckTransition() {
        val settings = preferences.current
        val targetDuck = if (settings.enabled && !settings.preserveTvPassthrough && isSpeaking) {
            (settings.movieVolumePercent / 100.0f).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        duckAnimJob?.cancel()
        val startDuck = currentDuckFactor
        if (kotlin.math.abs(startDuck - targetDuck) < 0.01f) {
            currentDuckFactor = targetDuck
            scope.launch(Dispatchers.Main) { onDuckVolumeChanged(targetDuck) }
            return
        }

        val durationMs = if (targetDuck < startDuck) DUCK_DOWN_MS else DUCK_RESTORE_MS
        duckAnimJob = scope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTime).coerceAtMost(durationMs)
                val t = elapsed.toFloat() / durationMs.toFloat()
                val smooth = t * t * (3.0f - 2.0f * t)
                val value = startDuck + (targetDuck - startDuck) * smooth
                currentDuckFactor = value
                withContext(Dispatchers.Main) {
                    onDuckVolumeChanged(value)
                }
                if (elapsed >= durationMs) break
                delay(24L)
            }
        }
    }

    private fun findActiveCueIndex(cues: List<SubtitleSyncCue>, posMs: Long): Int {
        var lo = 0
        var hi = cues.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cue = cues[mid]
            if (cue.startTimeMs <= posMs) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (candidate >= 0 && posMs <= cues[candidate].endTimeMs) candidate else -1
    }

    private fun cueKey(cue: SubtitleSyncCue, index: Int): String =
        "${index}_${cue.startTimeMs}_${cue.endTimeMs}_${cue.text.hashCode()}"

    private fun cancelAllPrefetches() {
        inFlightJobs.values.forEach { it.cancel() }
        inFlightJobs.clear()
        prefetchedClips.clear()
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun release() {
        stopLookahead()
        duckAnimJob?.cancel()
        cancelAllPrefetches()
        playerQueue.release()
        piperEngine.release()
        systemEngine.release()
        runCatching { onDuckVolumeChanged(1.0f) }
        scope.cancel()
    }

    companion object {
        private const val LOOKAHEAD_TICK_MS = 55L
        private const val PREFETCH_LOOKAHEAD_MS = 5000L
        private const val MAX_LATE_TRIGGER_MS = 900L
        private const val LIVE_DEDUP_MS = 350L
        private const val DUCK_DOWN_MS = 180L
        private const val DUCK_RESTORE_MS = 900L
    }
}
