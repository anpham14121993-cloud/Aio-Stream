package com.nuvio.tv.core.tts

import android.content.Context
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates VAX-parity subtitle dubbing (`Lz26` + `Ls26` + `Lxa4`):
 * - Timeline mode (55 ms tick, 5000 ms lookahead prefetch, F0 gender-flip re-prefetch, WSOLA window fit)
 * - Live-cue fallback (for embedded ExoPlayer / MPV tracks without advance cue lists)
 * - Asymmetric movie audio ducking (180 ms duck down, 900 ms smooth restore)
 * - Shared single-flight synthesis (`inFlightPrefetches`) + multi-backend fallback (`Piper -> Google -> System`).
 */
class TtsSubtitleCoordinator(
    context: Context,
    val preferences: TtsPreferencesStore,
    private val positionProviderMs: () -> Long,
    private val onDuckVolumeChanged: (Float) -> Unit,
    private val subtitleOffsetMsProvider: () -> Long = { 0L }
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

    private val _runtimeStatus = MutableStateFlow<String?>(null)
    val runtimeStatus: StateFlow<String?> = _runtimeStatus.asStateFlow()

    private fun publishRuntimeStatus(msg: String?) {
        _runtimeStatus.value = msg
        TtsDiagnosticsState.statusMessage.value = msg
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
    private val inFlightPrefetches = ConcurrentHashMap<String, Deferred<File?>>()
    private val timelineLock = Any()
    private val lookaheadLock = Any()
    private val streamingTimelineBuffer = ArrayList<SubtitleSyncCue>(256)
    private var streamingTimelineDirty = false
    private val released = AtomicBoolean(false)

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
            publishRuntimeStatus(null)
        } else {
            ensureLookaheadRunningIfNeeded()
        }
        scheduleDuckTransition()
    }

    fun updateTimelineCues(cues: List<SubtitleSyncCue>) {
        if (released.get()) return
        val merged = AssDialogueParser.mergeSimultaneousCues(cues)
        synchronized(timelineLock) {
            streamingTimelineBuffer.clear()
            streamingTimelineDirty = false
            timelineCues = merged
        }
        lastTriggeredCueKey = null
        cancelAllPrefetches()
        ensureLookaheadRunningIfNeeded()
    }

    /**
     * Incrementally ingests ASS/SSA cues extracted on-the-fly from MKV containers when `useLibass = true`.
     * Bounded and O(1) amortized under [timelineLock]; snapshot sorting/copying happens lazily on read.
     */
    fun appendStreamingTimelineCue(cue: SubtitleSyncCue) {
        if (released.get()) return
        val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
        if (parsed.speakableText.isBlank()) return

        val shouldStartLookahead = synchronized(timelineLock) {
            val lastIdx = streamingTimelineBuffer.lastIndex
            if (lastIdx >= 0) {
                val last = streamingTimelineBuffer[lastIdx]
                if (kotlin.math.abs(last.startTimeMs - cue.startTimeMs) <= 120L) {
                    if (last.text == cue.text) return
                    streamingTimelineBuffer[lastIdx] = last.copy(
                        endTimeMs = maxOf(last.endTimeMs, cue.endTimeMs),
                        text = "${last.text}\n${cue.text}"
                    )
                    streamingTimelineDirty = true
                    return@synchronized streamingTimelineBuffer.size == 1
                }
            }
            if (streamingTimelineBuffer.size >= MAX_STREAMING_TIMELINE_CUES) {
                return
            }
            if (lastIdx < 0 || cue.startTimeMs >= streamingTimelineBuffer[lastIdx].startTimeMs) {
                streamingTimelineBuffer.add(cue)
            } else {
                val insertAt = findInsertionIndex(streamingTimelineBuffer, cue.startTimeMs)
                streamingTimelineBuffer.add(insertAt, cue)
            }
            streamingTimelineDirty = true
            streamingTimelineBuffer.size == 1 || lookaheadJob?.isActive != true
        }
        if (shouldStartLookahead && preferences.current.enabled) {
            refreshStreamingTimelineSnapshot()
            ensureLookaheadRunningIfNeeded()
        }
    }

    fun clearStreamingTimelineCues() {
        val hadCues = synchronized(timelineLock) {
            val nonEmpty = streamingTimelineBuffer.isNotEmpty() || timelineCues.isNotEmpty()
            streamingTimelineBuffer.clear()
            streamingTimelineDirty = false
            timelineCues = emptyList()
            nonEmpty
        }
        if (hadCues) {
            lastTriggeredCueKey = null
            cancelAllPrefetches()
            stopLookahead()
        }
    }

    private fun refreshStreamingTimelineSnapshot(): List<SubtitleSyncCue> {
        synchronized(timelineLock) {
            if (streamingTimelineDirty) {
                timelineCues = if (streamingTimelineBuffer.isEmpty()) {
                    emptyList()
                } else {
                    ArrayList(streamingTimelineBuffer)
                }
                streamingTimelineDirty = false
            }
            return timelineCues
        }
    }

    private fun findInsertionIndex(list: List<SubtitleSyncCue>, startTimeMs: Long): Int {
        var lo = 0
        var hi = list.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startTimeMs <= startTimeMs) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return lo
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        val changed = isPlayerPlaying != isPlaying
        isPlayerPlaying = isPlaying
        playerQueue.setMoviePaused(!isPlaying)
        if (!isPlaying) {
            isSpeaking = false
        }
        if (changed) {
            scheduleDuckTransition()
        }
    }

    fun onSeek(newPosMs: Long) {
        playerQueue.clearAndStop()
        lastTriggeredCueKey = null
        lastLiveCueText = null
        cancelAllPrefetches()
        pitchAnalyzer.reset()

        val effectivePosMs = (newPosMs + subtitleOffsetMsProvider()).coerceAtLeast(0L)
        val cues = refreshStreamingTimelineSnapshot()
        if (cues.isNotEmpty()) {
            val idx = findActiveCueIndex(cues, effectivePosMs - 600L)
            if (idx >= 0) {
                lastTriggeredCueKey = cueKey(cues[idx], idx)
            }
        }
    }

    /**
     * Live cue entry point for embedded ExoPlayer / MPV subtitle text (or immediate trigger when toggling TTS ON).
     */
    fun onLiveCueText(rawText: String, positionMs: Long, force: Boolean = false) {
        val settings = preferences.current
        if (!settings.enabled) return
        if (!isPlayerPlaying && !force) return
        if (timelineCues.isNotEmpty() && !force) return

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(rawText)
        if (parsed.speakableText.isBlank()) return

        val now = System.currentTimeMillis()
        if (!force && parsed.speakableText == lastLiveCueText && (now - lastLiveTriggerAtMs) < LIVE_DEDUP_MS) {
            return
        }
        lastLiveCueText = parsed.speakableText
        lastLiveTriggerAtMs = now

        val cueKey = "live_${positionMs}_${sha256Short(parsed.speakableText)}"
        val targetGender = resolveGenderForSpan(settings, parsed.tagGender, positionMs - 800L, positionMs + 800L)
        val voiceId = resolveVoiceId(settings, targetGender)

        scope.launch {
            val file = synthesizeClipWithFallback(
                cueKey = cueKey,
                text = parsed.speakableText,
                voiceId = voiceId,
                gender = targetGender,
                speechSpeed = settings.speechSpeed,
                ttsVolumePercent = settings.ttsVolumePercent,
                targetWindowMs = null
            ) ?: return@launch

            if (preferences.current.enabled && (isPlayerPlaying || force)) {
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
        val cues = refreshStreamingTimelineSnapshot()
        if (released.get() || !settings.enabled || cues.isEmpty()) {
            stopLookahead()
            return
        }
        synchronized(lookaheadLock) {
            if (released.get() || lookaheadJob?.isActive == true) return
            lookaheadJob = scope.launch {
                while (isActive && !released.get() && preferences.current.enabled) {
                    val currentCues = refreshStreamingTimelineSnapshot()
                    if (currentCues.isEmpty()) break
                    if (isPlayerPlaying) {
                        val posMs = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                        processTimelineTick(posMs, preferences.current)
                    }
                    delay(LOOKAHEAD_TICK_MS)
                }
            }
        }
    }

    private fun stopLookahead() {
        synchronized(lookaheadLock) {
            lookaheadJob?.cancel()
            lookaheadJob = null
        }
    }

    private fun processTimelineTick(posMs: Long, settings: TtsSettingsState) {
        val cues = refreshStreamingTimelineSnapshot()
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
                if (settings.dualVoiceEnabled &&
                    parsed.tagGender == SpeakerGender.UNSPECIFIED &&
                    desiredGender != SpeakerGender.UNSPECIFIED &&
                    existing.voiceId != desiredVoiceId &&
                    posMs < cue.startTimeMs - 150L
                ) {
                    prefetchedClips.remove(key)
                    getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, settings)
                }
                continue
            }

            // Skip starting duplicate prefetch if triggerTimelineCue is already synthesizing or done for currentIdx
            if (!inFlightPrefetches.containsKey(key) && key != lastTriggeredCueKey) {
                getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, settings)
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

        // Reuse any already-in-flight prefetch Deferred instead of launching a conflicting second writer
        val deferred = getOrStartPrefetch(cue, key, parsed.speakableText, voiceId, gender, settings)

        scope.launch {
            val file = deferred.await() ?: return@launch
            prefetchedClips.remove(key)

            if (preferences.current.enabled && isPlayerPlaying) {
                val nowPos = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                if (nowPos <= cue.endTimeMs + 1500L) {
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

    private fun getOrStartPrefetch(
        cue: SubtitleSyncCue,
        key: String,
        text: String,
        voiceId: String,
        gender: SpeakerGender,
        settings: TtsSettingsState
    ): Deferred<File?> {
        inFlightPrefetches[key]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return existing
        }

        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                val windowMs = (cue.endTimeMs - cue.startTimeMs).takeIf { it > 350L }
                val file = synthesizeClipWithFallback(
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
                file
            } finally {
                val self = this.coroutineContext[Job] as? Deferred<*>
                if (self != null) {
                    inFlightPrefetches.remove(key, self)
                } else {
                    inFlightPrefetches.remove(key)
                }
            }
        }
        val raced = inFlightPrefetches.putIfAbsent(key, deferred)
        if (raced != null) {
            deferred.cancel()
            return raced
        }
        deferred.start()
        return deferred
    }

    private suspend fun synthesizeClipWithFallback(
        cueKey: String,
        text: String,
        voiceId: String,
        gender: SpeakerGender,
        speechSpeed: Float,
        ttsVolumePercent: Int,
        targetWindowMs: Long?
    ): File? {
        val voice = TtsVoiceCatalog.findVoice(voiceId)
        val primaryBackend = when {
            voice.backend == TtsBackendType.PIPER_ONNX && TtsVoiceCatalog.isVoiceReady(appContext, voice.id) ->
                TtsBackendType.PIPER_ONNX
            voice.backend == TtsBackendType.SYSTEM_TTS -> TtsBackendType.SYSTEM_TTS
            voice.backend == TtsBackendType.GOOGLE_TRANSLATE -> TtsBackendType.GOOGLE_TRANSLATE
            else -> TtsBackendType.GOOGLE_TRANSLATE
        }

        val attempts: List<TtsBackendType> = when (primaryBackend) {
            TtsBackendType.PIPER_ONNX -> listOf(
                TtsBackendType.PIPER_ONNX,
                TtsBackendType.GOOGLE_TRANSLATE,
                TtsBackendType.SYSTEM_TTS
            )
            TtsBackendType.GOOGLE_TRANSLATE -> listOf(
                TtsBackendType.GOOGLE_TRANSLATE,
                TtsBackendType.SYSTEM_TTS
            )
            TtsBackendType.SYSTEM_TTS -> listOf(
                TtsBackendType.SYSTEM_TTS,
                TtsBackendType.GOOGLE_TRANSLATE
            )
        }

        for (backend in attempts) {
            val result = synthesizeWithBackend(
                backend = backend,
                voice = voice,
                text = text,
                gender = gender,
                speechSpeed = speechSpeed,
                ttsVolumePercent = ttsVolumePercent,
                targetWindowMs = targetWindowMs
            )
            if (result != null && result.exists() && result.length() > 64L) {
                publishRuntimeStatus(
                    when {
                        backend == TtsBackendType.PIPER_ONNX -> "Đang đọc offline • ${voice.displayName}"
                        backend == TtsBackendType.GOOGLE_TRANSLATE && voice.backend == TtsBackendType.PIPER_ONNX ->
                            "Đang đọc bằng Google Dịch (${voice.displayName} chưa tải)"
                        backend == TtsBackendType.GOOGLE_TRANSLATE -> "Đang đọc • Google Dịch (Online)"
                        else -> "Đang đọc • Giọng hệ thống (Android TTS)"
                    }
                )
                return result
            }
        }

        publishRuntimeStatus("Không tạo được giọng đọc (kiểm tra mạng hoặc cài giọng hệ thống tiếng Việt / tải model Piper)")
        return null
    }

    private suspend fun synthesizeWithBackend(
        backend: TtsBackendType,
        voice: TtsVoiceOption,
        text: String,
        gender: SpeakerGender,
        speechSpeed: Float,
        ttsVolumePercent: Int,
        targetWindowMs: Long?
    ): File? {
        val hash = sha256Short("${voice.id}|$backend|$gender|$speechSpeed|$ttsVolumePercent|$targetWindowMs|$text")
        return when (backend) {
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
                googleEngine.synthesizeToMp3(text, "g_$hash")
            }
            TtsBackendType.SYSTEM_TTS -> {
                systemEngine.synthesizeToWav(text, gender, speechSpeed, "s_$hash")
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
        if (released.get()) return
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
        inFlightPrefetches.values.forEach { it.cancel() }
        inFlightPrefetches.clear()
        prefetchedClips.clear()
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        stopLookahead()
        duckAnimJob?.cancel()
        cancelAllPrefetches()
        playerQueue.release()
        piperEngine.release()
        systemEngine.release()
        publishRuntimeStatus(null)
        runCatching { onDuckVolumeChanged(1.0f) }
        scope.cancel()
    }

    companion object {
        private const val LOOKAHEAD_TICK_MS = 55L
        private const val PREFETCH_LOOKAHEAD_MS = 5000L
        private const val MAX_LATE_TRIGGER_MS = 2200L
        private const val LIVE_DEDUP_MS = 350L
        private const val DUCK_DOWN_MS = 180L
        private const val DUCK_RESTORE_MS = 900L
        private const val MAX_STREAMING_TIMELINE_CUES = 12_000
    }
}

object TtsDiagnosticsState {
    val statusMessage = MutableStateFlow<String?>(null)
}
