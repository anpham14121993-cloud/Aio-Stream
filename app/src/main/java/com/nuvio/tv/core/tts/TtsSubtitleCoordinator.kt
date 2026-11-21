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
import kotlinx.coroutines.flow.drop
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

    private data class PendingLiveCue(
        val cueKey: String,
        val text: String,
        val positionMs: Long,
        val gender: SpeakerGender,
        val voiceId: String,
        val speechSpeed: Float,
        val ttsVolumePercent: Int,
        val force: Boolean
    )

    private val prefetchedClips = ConcurrentHashMap<String, PrefetchedClip>()
    private val inFlightPrefetches = ConcurrentHashMap<String, Deferred<File?>>()
    private val timelineLock = Any()
    private val lookaheadLock = Any()
    private val triggeredLock = Any()
    private val triggeredCueKeys = LinkedHashSet<String>(128)
    private val liveQueueLock = Any()
    private val livePendingQueue = ArrayDeque<PendingLiveCue>()
    private val streamingTimelineBuffer = ArrayList<SubtitleSyncCue>(256)
    private var streamingTimelineDirty = false
    private val released = AtomicBoolean(false)

    @Volatile
    private var timelineCues: List<SubtitleSyncCue> = emptyList()

    @Volatile
    private var lastTimelinePosMs: Long = -1L

    @Volatile
    private var lastLiveRawText: String? = null

    @Volatile
    private var lastLiveCueText: String? = null

    @Volatile
    private var lastLiveTriggerAtMs: Long = 0L

    @Volatile
    private var lastSynthesisSignature: String? = null

    @Volatile
    private var lastPrewarmedSignature: String? = null

    @Volatile
    private var isPlayerPlaying = false

    @Volatile
    private var isSpeaking = false

    private var lookaheadJob: Job? = null
    private var liveWorkerJob: Job? = null
    private var warmupJob: Job? = null
    private var duckAnimJob: Job? = null
    private var currentDuckFactor = 1.0f

    @Volatile
    private var emaPiperSynthMs: Long = 0L

    private val playerQueue = TtsAudioPlayerQueue(
        context = appContext,
        onSpeakingStateChanged = { speaking ->
            isSpeaking = speaking
            scheduleDuckTransition()
        },
        onPlaybackError = { err ->
            publishRuntimeStatus("Lỗi phát âm thanh TTS ($err)")
        },
        currentPositionMsProvider = {
            (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
        }
    )

    init {
        TtsModelManager.refreshInstalled(appContext)
        scope.launch {
            TtsModelManager.installedIds.drop(1).collect {
                if (!released.get() && preferences.current.enabled) {
                    onSettingsUpdated(preferences.current)
                }
            }
        }
    }

    fun onSettingsUpdated(settings: TtsSettingsState) {
        audioTapProcessor.isTapActive = settings.enabled && settings.dualVoiceEnabled && !settings.preserveTvPassthrough
        playerQueue.updateVolumePercent(settings.ttsVolumePercent)

        if (!settings.enabled) {
            warmupJob?.cancel()
            warmupJob = null
            lastPrewarmedSignature = null
            clearLiveQueue()
            stopLookahead()
            playerQueue.clearAndStop()
            cancelAllPrefetches()
            clearTriggeredKeys()
            pitchAnalyzer.reset()
            publishRuntimeStatus(null)
        } else {
            val sig = synthesisSignatureFor(settings)
            val prevSig = lastSynthesisSignature
            lastSynthesisSignature = sig
            if (prevSig != null && prevSig != sig) {
                cancelAllPrefetches()
                clearTriggeredKeys()
                lastLiveCueText = null
                lastLiveRawText = null
            }
            schedulePiperWarmup(settings, sig)
            ensureLookaheadRunningIfNeeded()
        }
        scheduleDuckTransition()
    }

    private fun synthesisSignatureFor(settings: TtsSettingsState): String {
        val candidateVoiceId = resolveVoiceId(settings, SpeakerGender.UNSPECIFIED)
        val primaryReady = TtsVoiceCatalog.isVoiceReady(appContext, candidateVoiceId)
        val maleReady = TtsVoiceCatalog.isVoiceReady(appContext, settings.maleVoiceId)
        val femaleReady = TtsVoiceCatalog.isVoiceReady(appContext, settings.femaleVoiceId)
        return "${settings.dualVoiceEnabled}|${settings.primaryVoiceId}:$primaryReady|" +
            "${settings.maleVoiceId}:$maleReady|${settings.femaleVoiceId}:$femaleReady|${settings.speechSpeed}"
    }

    private fun schedulePiperWarmup(settings: TtsSettingsState, signature: String) {
        if (released.get() || !settings.enabled || lastPrewarmedSignature == signature) return
        val candidateVoiceId = resolveVoiceId(settings, SpeakerGender.UNSPECIFIED)
        val candidateVoice = TtsVoiceCatalog.findVoice(candidateVoiceId)
        if (candidateVoice.backend != TtsBackendType.PIPER_ONNX ||
            !TtsVoiceCatalog.isVoiceReady(appContext, candidateVoice.id)
        ) {
            lastPrewarmedSignature = signature
            return
        }

        lastPrewarmedSignature = signature
        warmupJob?.cancel()
        warmupJob = scope.launch {
            if (_runtimeStatus.value.isNullOrBlank() ||
                _runtimeStatus.value?.startsWith("Đang chờ") == true ||
                _runtimeStatus.value?.startsWith("Sẵn sàng") == true
            ) {
                publishRuntimeStatus("Đang nạp giọng offline • ${candidateVoice.displayName}...")
            }
            val loaded = piperEngine.warmUp(candidateVoice.id)
            if (released.get() || !preferences.current.enabled) return@launch
            if (!loaded) {
                val err = piperEngine.lastErrorMessage()
                publishRuntimeStatus(err ?: "Không nạp được giọng Piper ${candidateVoice.displayName}")
            } else if (_runtimeStatus.value?.startsWith("Đang nạp giọng offline") == true) {
                publishRuntimeStatus("Sẵn sàng đọc offline • ${candidateVoice.displayName}")
            }
        }
    }

    fun updateTimelineCues(cues: List<SubtitleSyncCue>) {
        if (released.get()) return
        val merged = AssDialogueParser.mergeSimultaneousCues(cues)
        synchronized(timelineLock) {
            streamingTimelineBuffer.clear()
            streamingTimelineDirty = false
            timelineCues = merged
        }
        clearTriggeredKeys()
        lastTimelinePosMs = -1L
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
            clearTriggeredKeys()
            lastTimelinePosMs = -1L
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
        clearTriggeredKeys()
        clearLiveQueue()
        lastLiveRawText = null
        lastLiveCueText = null
        cancelAllPrefetches()
        pitchAnalyzer.reset()

        val effectivePosMs = (newPosMs + subtitleOffsetMsProvider()).coerceAtLeast(0L)
        lastTimelinePosMs = effectivePosMs
        val cues = refreshStreamingTimelineSnapshot()
        if (cues.isNotEmpty()) {
            val idx = findActiveCueIndex(cues, effectivePosMs - 600L)
            if (idx >= 0) {
                markCueTriggered(cueKey(cues[idx], idx))
            }
        }
    }

    /**
     * Live cue entry point for embedded ExoPlayer / MPV subtitle text (or immediate trigger when toggling TTS ON).
     * Diffs incremental multi-line updates and preserves rapid back-to-back dialogue via a bounded FIFO worker
     * instead of dropping sentences mid-synthesis.
     */
    fun onLiveCueText(rawText: String, positionMs: Long, force: Boolean = false) {
        val settings = preferences.current
        if (!settings.enabled) return
        if (!isPlayerPlaying && !force) return
        if (timelineCues.isNotEmpty() && !force) return

        val now = System.currentTimeMillis()
        val prevRaw = lastLiveRawText
        val deltaText = if (force) {
            rawText
        } else {
            extractDeltaLiveText(
                previousRawText = prevRaw,
                newRawText = rawText,
                withinWindow = prevRaw != null && (now - lastLiveTriggerAtMs) <= LIVE_DELTA_WINDOW_MS
            )
        }
        lastLiveRawText = rawText
        if (deltaText.isBlank()) return

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(deltaText)
        if (parsed.speakableText.isBlank()) return

        if (!force && parsed.speakableText == lastLiveCueText && (now - lastLiveTriggerAtMs) < LIVE_DEDUP_MS) {
            return
        }
        lastLiveCueText = parsed.speakableText
        lastLiveTriggerAtMs = now

        val cueKey = "live_${positionMs}_${sha256Short(parsed.speakableText)}"
        val targetGender = resolveGenderForSpan(settings, parsed.tagGender, positionMs - 800L, positionMs + 800L)
        val voiceId = resolveVoiceId(settings, targetGender)

        val item = PendingLiveCue(
            cueKey = cueKey,
            text = parsed.speakableText,
            positionMs = positionMs,
            gender = targetGender,
            voiceId = voiceId,
            speechSpeed = settings.speechSpeed,
            ttsVolumePercent = settings.ttsVolumePercent,
            force = force
        )

        synchronized(liveQueueLock) {
            if (livePendingQueue.lastOrNull()?.text == item.text) return
            while (livePendingQueue.size >= MAX_PENDING_LIVE_CUES) {
                livePendingQueue.removeFirst()
            }
            livePendingQueue.addLast(item)
        }
        ensureLiveWorkerRunning()
    }

    private fun ensureLiveWorkerRunning() {
        synchronized(liveQueueLock) {
            if (released.get() || liveWorkerJob?.isActive == true) return
            liveWorkerJob = scope.launch {
                while (isActive && !released.get()) {
                    val nextItem = synchronized(liveQueueLock) {
                        if (livePendingQueue.isEmpty()) null else livePendingQueue.removeFirst()
                    } ?: break

                    val currentSettings = preferences.current
                    if (!currentSettings.enabled) continue
                    if (!isPlayerPlaying && !nextItem.force) continue

                    val livePendingRemaining = synchronized(liveQueueLock) { livePendingQueue.size }
                    val backlogDepth = livePendingRemaining + playerQueue.queueDepth()
                    val nowPosMs = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                    val lagMs = (nowPosMs - nextItem.positionMs).coerceAtLeast(0L)
                    val nativeBoost = computeNativeSpeedBoost(
                        slotMs = null,
                        textLength = nextItem.text.length,
                        lagMs = lagMs,
                        leadRemainingMs = -lagMs,
                        emaSynthMs = emaPiperSynthMs,
                        backlogDepth = backlogDepth
                    )

                    val file = synthesizeClipWithFallback(
                        cueKey = nextItem.cueKey,
                        text = nextItem.text,
                        voiceId = nextItem.voiceId,
                        gender = nextItem.gender,
                        speechSpeed = nextItem.speechSpeed,
                        ttsVolumePercent = nextItem.ttsVolumePercent,
                        targetWindowMs = null,
                        nativeSpeedBoost = nativeBoost,
                        shouldAbort = {
                            released.get() ||
                                !preferences.current.enabled ||
                                (!isPlayerPlaying && !nextItem.force)
                        }
                    ) ?: continue

                    if (preferences.current.enabled && (isPlayerPlaying || nextItem.force)) {
                        playerQueue.enqueue(
                            TtsPlaybackItem(
                                cueKey = nextItem.cueKey,
                                audioFile = file,
                                baseSpeed = preferences.current.speechSpeed,
                                cueStartMs = nextItem.positionMs,
                                cueEndMs = nextItem.positionMs + 2200L
                            )
                        )
                    }
                }
            }
        }
    }

    private fun clearLiveQueue() {
        synchronized(liveQueueLock) {
            livePendingQueue.clear()
            liveWorkerJob?.cancel()
            liveWorkerJob = null
        }
    }

    private fun markCueTriggered(key: String) {
        synchronized(triggeredLock) {
            if (triggeredCueKeys.size >= MAX_TRIGGERED_KEYS) {
                val it = triggeredCueKeys.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            triggeredCueKeys.add(key)
        }
    }

    private fun isCueTriggered(key: String): Boolean = synchronized(triggeredLock) {
        key in triggeredCueKeys
    }

    private fun clearTriggeredKeys() {
        synchronized(triggeredLock) {
            triggeredCueKeys.clear()
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
                    val posMs = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                    processTimelineTick(posMs, preferences.current, triggerPlayback = isPlayerPlaying)
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

    private fun processTimelineTick(
        posMs: Long,
        settings: TtsSettingsState,
        triggerPlayback: Boolean
    ) {
        val cues = refreshStreamingTimelineSnapshot()
        if (cues.isEmpty()) return

        if (lastTimelinePosMs >= 0L && posMs + 1500L < lastTimelinePosMs) {
            clearTriggeredKeys()
        }
        lastTimelinePosMs = posMs

        if (settings.dualVoiceEnabled && !settings.preserveTvPassthrough) {
            pitchAnalyzer.refreshEstimation()
        }

        // Prune expired prefetched clips so stale synthesis never accumulates.
        val iterator = prefetchedClips.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.cueEndMs + PLAY_LATE_TOLERANCE_MS < posMs) {
                iterator.remove()
            }
        }

        // 1. Trigger all due chronological cues ONLY when actively playing.
        if (triggerPlayback) {
            val triggeredSnapshot = synchronized(triggeredLock) { triggeredCueKeys.toSet() }
            val dueIndices = findDueTimelineIndices(
                cues = cues,
                posMs = posMs,
                leadMs = if (playerQueue.queueDepth() == 0) TRIGGER_LEAD_MS else 90L,
                lateWindowMs = MAX_LATE_TRIGGER_MS,
                triggeredKeys = triggeredSnapshot,
                hasPrefetchedClip = { key -> prefetchedClips.containsKey(key) },
                maxResults = 3
            )
            for (idx in dueIndices) {
                val cue = cues[idx]
                val key = cueKey(cue, idx)
                markCueTriggered(key)
                val nextCueStartMs = cues.getOrNull(idx + 1)?.startTimeMs
                triggerTimelineCue(cue, key, nextCueStartMs, settings)
            }
        }

        // 2. Prefetch upcoming cues (28s lookahead, capped so Piper mutex is never starved behind far-future cues).
        val activePrefetchCap = if (triggerPlayback) MAX_ACTIVE_PREFETCHES else 1
        val horizonMs = posMs + PREFETCH_LOOKAHEAD_MS
        val firstScanIdx = max(0, findActiveCueIndex(cues, posMs - 200L).let {
            if (it >= 0) it else findInsertionPointByStart(cues, posMs)
        })
        for (i in firstScanIdx until cues.size) {
            if (inFlightPrefetches.size >= activePrefetchCap) break
            if (prefetchedClips.size + inFlightPrefetches.size >= MAX_PREFETCH_CACHE_CLIPS) break
            val cue = cues[i]
            if (cue.startTimeMs > horizonMs) break
            if (cue.endTimeMs < posMs) continue

            val key = cueKey(cue, i)
            val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
            if (parsed.speakableText.isBlank()) continue

            val desiredGender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
            val desiredVoiceId = resolveVoiceId(settings, desiredGender)
            val nextCueStartMs = cues.getOrNull(i + 1)?.startTimeMs

            val existing = prefetchedClips[key]
            if (existing != null) {
                if (settings.dualVoiceEnabled &&
                    parsed.tagGender == SpeakerGender.UNSPECIFIED &&
                    desiredGender != SpeakerGender.UNSPECIFIED &&
                    existing.voiceId != desiredVoiceId &&
                    posMs < cue.startTimeMs - 150L
                ) {
                    prefetchedClips.remove(key)
                    getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, nextCueStartMs, settings)
                }
                continue
            }

            if (!inFlightPrefetches.containsKey(key) && !isCueTriggered(key)) {
                getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, nextCueStartMs, settings)
            }
        }
    }

    private fun triggerTimelineCue(
        cue: SubtitleSyncCue,
        key: String,
        nextCueStartMs: Long?,
        settings: TtsSettingsState
    ) {
        val ready = prefetchedClips.remove(key)
        if (ready != null && ready.file.exists()) {
            playerQueue.enqueue(
                TtsPlaybackItem(
                    cueKey = key,
                    audioFile = ready.file,
                    baseSpeed = settings.speechSpeed,
                    cueStartMs = cue.startTimeMs,
                    cueEndMs = cue.endTimeMs
                )
            )
            return
        }

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
        if (parsed.speakableText.isBlank()) return
        val gender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
        val voiceId = resolveVoiceId(settings, gender)

        // Reuse any already-in-flight prefetch Deferred instead of launching a conflicting second writer
        val deferred = getOrStartPrefetch(cue, key, parsed.speakableText, voiceId, gender, nextCueStartMs, settings)

        scope.launch {
            val file = deferred.await() ?: return@launch
            prefetchedClips.remove(key)

            val latestSettings = preferences.current
            if (latestSettings.enabled && isPlayerPlaying) {
                val nowPos = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                if (nowPos <= cue.endTimeMs + PLAY_LATE_TOLERANCE_MS &&
                    playerQueue.queueDepth() < MAX_PLAYER_QUEUE_DEPTH
                ) {
                    playerQueue.enqueue(
                        TtsPlaybackItem(
                            cueKey = key,
                            audioFile = file,
                            baseSpeed = latestSettings.speechSpeed,
                            cueStartMs = cue.startTimeMs,
                            cueEndMs = cue.endTimeMs
                        )
                    )
                } else if (_runtimeStatus.value == null || _runtimeStatus.value?.startsWith("Đang đọc offline") == true) {
                    publishRuntimeStatus("Piper xử lý chậm hơn phụ đề (${(nowPos - cue.endTimeMs).coerceAtLeast(0L)}ms)")
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
        nextCueStartMs: Long?,
        settings: TtsSettingsState
    ): Deferred<File?> {
        inFlightPrefetches[key]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return existing
        }

        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                val shouldAbortPrefetch = {
                    val nowPos = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                    released.get() ||
                        !preferences.current.enabled ||
                        nowPos > cue.endTimeMs + PLAY_LATE_TOLERANCE_MS
                }
                if (shouldAbortPrefetch()) return@async null

                val nowPos = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                val slotMs = computeTimelineSlotWindowMs(cue, nextCueStartMs)
                val nativeBoost = computeNativeSpeedBoost(
                    slotMs = slotMs,
                    textLength = text.length,
                    lagMs = (nowPos - cue.startTimeMs).coerceAtLeast(0L),
                    leadRemainingMs = cue.startTimeMs - nowPos,
                    emaSynthMs = emaPiperSynthMs,
                    backlogDepth = playerQueue.queueDepth()
                )
                val file = synthesizeClipWithFallback(
                    cueKey = key,
                    text = text,
                    voiceId = voiceId,
                    gender = gender,
                    speechSpeed = settings.speechSpeed,
                    ttsVolumePercent = settings.ttsVolumePercent,
                    targetWindowMs = slotMs,
                    nativeSpeedBoost = nativeBoost,
                    shouldAbort = shouldAbortPrefetch
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
        targetWindowMs: Long?,
        nativeSpeedBoost: Float = 1.0f,
        shouldAbort: () -> Boolean = { false }
    ): File? {
        if (shouldAbort()) return null
        val voice = TtsVoiceCatalog.findVoice(voiceId)
        val piperReady = voice.backend == TtsBackendType.PIPER_ONNX && TtsVoiceCatalog.isVoiceReady(appContext, voice.id)
        val primaryBackend = when {
            piperReady -> TtsBackendType.PIPER_ONNX
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

        var piperFailureReason: String? = null
        for (backend in attempts) {
            if (shouldAbort()) return null
            val result = synthesizeWithBackend(
                backend = backend,
                voice = voice,
                text = text,
                gender = gender,
                speechSpeed = speechSpeed,
                ttsVolumePercent = ttsVolumePercent,
                targetWindowMs = targetWindowMs,
                nativeSpeedBoost = nativeSpeedBoost,
                shouldAbort = shouldAbort
            )
            if (result != null && result.exists() && result.length() > 64L) {
                publishRuntimeStatus(
                    when {
                        backend == TtsBackendType.PIPER_ONNX -> "Đang đọc offline • ${voice.displayName}"
                        backend == TtsBackendType.GOOGLE_TRANSLATE && piperReady ->
                            "Piper lỗi (${piperFailureReason ?: "chưa đọc được"}) • Dùng Google Dịch"
                        backend == TtsBackendType.GOOGLE_TRANSLATE && voice.backend == TtsBackendType.PIPER_ONNX ->
                            "Đang đọc bằng Google Dịch (${voice.displayName} chưa tải)"
                        backend == TtsBackendType.GOOGLE_TRANSLATE -> "Đang đọc • Google Dịch (Online)"
                        piperReady && piperFailureReason != null ->
                            "Piper lỗi ($piperFailureReason) • Dùng giọng hệ thống"
                        else -> "Đang đọc • Giọng hệ thống (Android TTS)"
                    }
                )
                return result
            }
            if (backend == TtsBackendType.PIPER_ONNX && piperReady && !shouldAbort()) {
                piperFailureReason = piperEngine.lastErrorMessage()
            }
        }

        if (!shouldAbort()) {
            publishRuntimeStatus(
                piperFailureReason
                    ?: "Không tạo được giọng đọc (kiểm tra mạng hoặc cài giọng hệ thống tiếng Việt / tải model Piper)"
            )
        }
        return null
    }

    private suspend fun synthesizeWithBackend(
        backend: TtsBackendType,
        voice: TtsVoiceOption,
        text: String,
        gender: SpeakerGender,
        speechSpeed: Float,
        ttsVolumePercent: Int,
        targetWindowMs: Long?,
        nativeSpeedBoost: Float,
        shouldAbort: () -> Boolean
    ): File? {
        val piperSpeechSpeed = (speechSpeed * nativeSpeedBoost).coerceIn(0.6f, 2.0f)
        val hash = sha256Short("${voice.id}|$backend|$gender|$piperSpeechSpeed|$ttsVolumePercent|$targetWindowMs|$text")
        return try {
            when (backend) {
                TtsBackendType.PIPER_ONNX -> {
                    val outWav = File(wavCacheDir, "piper_$hash.wav")
                    if (outWav.exists() && outWav.length() > 256L) return outWav

                    val synthStartedNs = System.nanoTime()
                    val synth = piperEngine.synthesize(
                        voiceId = voice.id,
                        normalizedText = text,
                        speechSpeed = piperSpeechSpeed,
                        shouldAbort = shouldAbort
                    ) ?: return null
                    val synthElapsedMs = ((System.nanoTime() - synthStartedNs) / 1_000_000L).coerceAtLeast(1L)
                    emaPiperSynthMs = if (emaPiperSynthMs <= 0L) {
                        synthElapsedMs
                    } else {
                        (emaPiperSynthMs * 3L + synthElapsedMs) / 4L
                    }
                    if (shouldAbort()) return null
                    val trimmed = TtsAudioPostProcessor.trimSilence(synth.samples, synth.sampleRate)

                    val stretched = if (targetWindowMs != null && targetWindowMs >= 420L) {
                        val audioDurationMs = (trimmed.size * 1000.0) / synth.sampleRate.toDouble()
                        val availableMs = targetWindowMs.toDouble() + 80.0
                        if (audioDurationMs > availableMs) {
                            val fitRatio = (audioDurationMs / availableMs).toFloat().coerceIn(0.8f, 1.85f)
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
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
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

    private fun findInsertionPointByStart(cues: List<SubtitleSyncCue>, posMs: Long): Int {
        var lo = 0
        var hi = cues.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startTimeMs < posMs) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return lo
    }

    private fun cueKey(cue: SubtitleSyncCue, index: Int): String =
        cueKeyFor(cue, index)

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
        warmupJob?.cancel()
        warmupJob = null
        clearLiveQueue()
        stopLookahead()
        duckAnimJob?.cancel()
        cancelAllPrefetches()
        clearTriggeredKeys()
        playerQueue.release()
        piperEngine.release()
        systemEngine.release()
        publishRuntimeStatus(null)
        runCatching { onDuckVolumeChanged(1.0f) }
        scope.cancel()
    }

    companion object {
        private const val LOOKAHEAD_TICK_MS = 55L
        private const val PREFETCH_LOOKAHEAD_MS = 28_000L
        private const val MAX_ACTIVE_PREFETCHES = 2
        private const val MAX_PREFETCH_CACHE_CLIPS = 18
        private const val TRIGGER_LEAD_MS = 240L
        private const val MAX_LATE_TRIGGER_MS = 3_200L
        private const val PLAY_LATE_TOLERANCE_MS = 4_500L
        private const val MAX_PLAYER_QUEUE_DEPTH = 4
        private const val MAX_PENDING_LIVE_CUES = 4
        private const val MAX_TRIGGERED_KEYS = 512
        private const val LIVE_DEDUP_MS = 350L
        private const val LIVE_DELTA_WINDOW_MS = 8000L
        private const val DUCK_DOWN_MS = 180L
        private const val DUCK_RESTORE_MS = 900L
        private const val MAX_STREAMING_TIMELINE_CUES = 12_000

        /**
         * Key deliberately omits the list index so out-of-order streaming cue insertions never
         * invalidate already-prefetched clips or duplicate already-triggered dialogue lines.
         */
        internal fun cueKeyFor(cue: SubtitleSyncCue, index: Int): String =
            "${cue.startTimeMs}_${cue.text.hashCode()}"

        internal fun computeNativeSpeedBoost(
            slotMs: Long?,
            textLength: Int,
            lagMs: Long,
            leadRemainingMs: Long,
            emaSynthMs: Long,
            backlogDepth: Int
        ): Float {
            return when {
                lagMs > 1200L || backlogDepth >= 2 -> 1.22f
                backlogDepth >= 1 || lagMs > 400L -> 1.12f
                emaSynthMs > 1400L && leadRemainingMs in -300L..(emaSynthMs + 500L) -> 1.15f
                slotMs != null && slotMs < 2100L && textLength > 34 -> 1.12f
                else -> 1.0f
            }
        }

        internal fun extractDeltaLiveText(
            previousRawText: String?,
            newRawText: String,
            withinWindow: Boolean
        ): String {
            if (!withinWindow || previousRawText.isNullOrBlank()) return newRawText
            val prevLines = previousRawText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val currLines = newRawText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (prevLines.isEmpty() || currLines.isEmpty()) return newRawText
            if (prevLines == currLines) return ""

            val maxOverlap = min(prevLines.size, currLines.size)
            for (k in maxOverlap downTo 1) {
                if (prevLines.subList(prevLines.size - k, prevLines.size) == currLines.subList(0, k)) {
                    val delta = currLines.subList(k, currLines.size)
                    return delta.joinToString("\n")
                }
            }
            return newRawText
        }

        internal fun computeTimelineSlotWindowMs(
            cue: SubtitleSyncCue,
            nextCueStartMs: Long?
        ): Long? {
            val cueDur = (cue.endTimeMs - cue.startTimeMs).takeIf { it > 350L } ?: return null
            if (nextCueStartMs == null) return cueDur
            val gapToNext = (nextCueStartMs - cue.startTimeMs).coerceAtLeast(350L)
            return if (gapToNext <= cueDur + 250L) {
                min(cueDur, gapToNext)
            } else {
                // Idle pause after this cue: allow slightly more breathing room without rushing speech.
                val extra = min(750L, (gapToNext - cueDur) / 2L)
                cueDur + extra
            }
        }

        internal fun findDueTimelineIndices(
            cues: List<SubtitleSyncCue>,
            posMs: Long,
            leadMs: Long,
            lateWindowMs: Long,
            triggeredKeys: Set<String>,
            hasPrefetchedClip: (String) -> Boolean,
            maxResults: Int = 2
        ): List<Int> {
            if (cues.isEmpty()) return emptyList()
            var lo = 0
            var hi = cues.lastIndex
            var firstCandidate = cues.size
            val earliestStartBound = posMs - 12_000L
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (cues[mid].startTimeMs >= earliestStartBound) {
                    firstCandidate = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            if (firstCandidate >= cues.size) return emptyList()

            val result = ArrayList<Int>(maxResults)
            for (i in firstCandidate until cues.size) {
                val cue = cues[i]
                if (cue.startTimeMs > posMs + leadMs) break
                val key = cueKeyFor(cue, i)
                if (key in triggeredKeys) continue
                val effectiveLead = if (leadMs > 0L && hasPrefetchedClip(key)) leadMs else 0L
                val isDue = cue.startTimeMs <= posMs + effectiveLead &&
                    posMs <= cue.startTimeMs + lateWindowMs &&
                    posMs <= cue.endTimeMs + lateWindowMs
                if (isDue) {
                    result.add(i)
                    if (result.size >= maxResults) break
                }
            }
            return result
        }
    }
}

object TtsDiagnosticsState {
    val statusMessage = MutableStateFlow<String?>(null)
}
