package com.nuvio.tv.core.tts

import android.content.Context
import android.os.SystemClock
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates VAX-parity subtitle dubbing (`Lz26` + `Ls26` + `Lxa4`):
 * - Timeline mode (55 ms tick + immediate event-driven refill, Piper 60 s lookahead or Google Online rolling sliding-window prefetch)
 * - Live-cue fallback (for embedded ExoPlayer / MPV tracks without advance cue lists, with rolling Google pre-warming)
 * - Asymmetric movie audio ducking (180 ms duck down, 900 ms smooth restore)
 * - Shared single-flight synthesis (`inFlightPrefetches`) + multi-backend fallback (`Piper -> Google`).
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

    private val wavCacheDir: File by lazy {
        File(appContext.cacheDir, "tts_cache").apply { mkdirs() }
    }

    private val _runtimeStatus = MutableStateFlow<String?>(null)
    val runtimeStatus: StateFlow<String?> = _runtimeStatus.asStateFlow()

    private fun publishRuntimeStatus(msg: String?) {
        _runtimeStatus.value = msg
        TtsDiagnosticsState.statusMessage.value = msg
    }

    private data class SynthesizedClipResult(
        val file: File,
        val backend: TtsBackendType
    )

    private data class PrefetchedClip(
        val key: String,
        val cueStartMs: Long,
        val cueEndMs: Long,
        val file: File,
        val backend: TtsBackendType,
        val voiceId: String,
        val resolvedGender: SpeakerGender,
        val usedPitchHint: Boolean
    )

    private data class PrefetchFailureState(
        val failures: Int,
        val retryAfterElapsedMs: Long
    )

    private data class PendingLiveCue(
        val cueKey: String,
        val text: String,
        val positionMs: Long,
        val gender: SpeakerGender,
        val voiceId: String,
        val speechSpeed: Float,
        val ttsVolumePercent: Int,
        val force: Boolean,
        val googlePrefetchDeferred: Deferred<File?>? = null
    )

    private val prefetchedClips = ConcurrentHashMap<String, PrefetchedClip>()
    private val inFlightPrefetches = ConcurrentHashMap<String, Deferred<SynthesizedClipResult?>>()
    private val prefetchFailures = ConcurrentHashMap<String, PrefetchFailureState>()
    private val refillSignal = Channel<Unit>(Channel.CONFLATED)
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

    private val urgentSynthWaiters = java.util.concurrent.atomic.AtomicInteger(0)
    private val playedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val skippedLateCount = java.util.concurrent.atomic.AtomicInteger(0)

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
        },
        onItemSkipped = { _ ->
            skippedLateCount.incrementAndGet()
        },
        onItemPlayed = {
            playedCount.incrementAndGet()
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
            requestRollingRefill()
        }
        scheduleDuckTransition()
    }

    private fun isVoicePiperReady(voiceId: String): Boolean {
        val voice = TtsVoiceCatalog.findVoice(voiceId)
        return voice.backend == TtsBackendType.PIPER_ONNX &&
            TtsVoiceCatalog.isVoiceReady(appContext, voice.id)
    }

    private fun isGoogleOnlineActive(settings: TtsSettingsState): Boolean {
        if (!settings.dualVoiceEnabled) {
            return !isVoicePiperReady(settings.primaryVoiceId)
        }
        return !isVoicePiperReady(settings.primaryVoiceId) &&
            !isVoicePiperReady(settings.maleVoiceId) &&
            !isVoicePiperReady(settings.femaleVoiceId)
    }

    private fun synthesisSignatureFor(settings: TtsSettingsState): String {
        val candidateVoiceId = resolveVoiceId(settings, SpeakerGender.UNSPECIFIED)
        val primaryReady = TtsVoiceCatalog.isVoiceReady(appContext, candidateVoiceId)
        val maleReady = TtsVoiceCatalog.isVoiceReady(appContext, settings.maleVoiceId)
        val femaleReady = TtsVoiceCatalog.isVoiceReady(appContext, settings.femaleVoiceId)
        // Google Online MP3 files are speed-independent (speed is applied dynamically at playback),
        // so changing reading speed does not invalidate or wipe the rolling prefetch buffer.
        val speedSig = if (isGoogleOnlineActive(settings)) "mp3" else settings.speechSpeed.toString()
        return "${settings.dualVoiceEnabled}|${settings.primaryVoiceId}:$primaryReady|" +
            "${settings.maleVoiceId}:$maleReady|${settings.femaleVoiceId}:$femaleReady|$speedSig"
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
        requestRollingRefill()
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
        if (preferences.current.enabled) {
            if (shouldStartLookahead) {
                refreshStreamingTimelineSnapshot()
                ensureLookaheadRunningIfNeeded()
            }
            requestRollingRefill()
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
            if (isPlaying) requestRollingRefill()
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
        requestRollingRefill()
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

        val googlePrefetch = if (isGoogleOnlineActive(settings)) {
            scope.async { googleEngine.synthesizeToMp3(parsed.speakableText) }
        } else {
            null
        }

        val item = PendingLiveCue(
            cueKey = cueKey,
            text = parsed.speakableText,
            positionMs = positionMs,
            gender = targetGender,
            voiceId = voiceId,
            speechSpeed = settings.speechSpeed,
            ttsVolumePercent = settings.ttsVolumePercent,
            force = force,
            googlePrefetchDeferred = googlePrefetch
        )

        synchronized(liveQueueLock) {
            if (livePendingQueue.lastOrNull()?.text == item.text) {
                googlePrefetch?.cancel()
                return
            }
            while (livePendingQueue.size >= MAX_PENDING_LIVE_CUES) {
                livePendingQueue.removeFirst().googlePrefetchDeferred?.cancel()
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
                    if (!currentSettings.enabled) {
                        nextItem.googlePrefetchDeferred?.cancel()
                        continue
                    }
                    if (!isPlayerPlaying && !nextItem.force) {
                        nextItem.googlePrefetchDeferred?.cancel()
                        continue
                    }

                    val prewarmedGoogle = nextItem.googlePrefetchDeferred?.let { deferred ->
                        runCatching { deferred.await() }.getOrNull()
                    }
                    val result = if (prewarmedGoogle != null && prewarmedGoogle.exists() && prewarmedGoogle.length() > 64L) {
                        publishGoogleStatus(TtsVoiceCatalog.findVoice(nextItem.voiceId), piperFailedReason = null)
                        SynthesizedClipResult(prewarmedGoogle, TtsBackendType.GOOGLE_TRANSLATE)
                    } else {
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

                        synthesizeClipWithFallback(
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
                        )
                    } ?: continue

                    val latestSettings = preferences.current
                    if (latestSettings.enabled && (isPlayerPlaying || nextItem.force)) {
                        playerQueue.enqueue(
                            TtsPlaybackItem(
                                cueKey = nextItem.cueKey,
                                audioFile = result.file,
                                baseSpeed = playbackBaseSpeedFor(result.backend, latestSettings.speechSpeed),
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
            while (livePendingQueue.isNotEmpty()) {
                livePendingQueue.removeFirst().googlePrefetchDeferred?.cancel()
            }
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

    private fun requestRollingRefill() {
        if (!released.get()) {
            refillSignal.trySend(Unit)
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
                    withTimeoutOrNull(LOOKAHEAD_TICK_MS) {
                        refillSignal.receive()
                    }
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
        if (prefetchFailures.size > MAX_TRIGGERED_KEYS) {
            prefetchFailures.clear()
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

        // 2. Rolling prefetch ("tải trước cuốn chiếu"):
        //    - Google Online uses a sliding window of upcoming cues with bounded concurrent HTTP downloads
        //      and does NOT pause behind urgentSynthWaiters so consecutive cues stay warm with zero gaps.
        //    - Piper ONNX yields while urgent dialogue awaits the single-session native ONNX mutex.
        val googleRolling = isGoogleOnlineActive(settings)
        if (!googleRolling && urgentSynthWaiters.get() > 0) {
            return
        }

        val activePrefetchCap = if (googleRolling) GOOGLE_MAX_CONCURRENT_PREFETCHES else MAX_ACTIVE_PREFETCHES
        val maxWindowCues = if (googleRolling) GOOGLE_ROLLING_WINDOW_CUES else MAX_PREFETCH_CACHE_CLIPS
        val lookaheadMs = if (googleRolling) GOOGLE_ROLLING_LOOKAHEAD_MS else PREFETCH_LOOKAHEAD_MS

        // Dual-voice pitch correction: if an already-prefetched clip's gender flipped before playback, invalidate & re-fetch it.
        if (settings.dualVoiceEnabled) {
            val horizonMs = posMs + lookaheadMs
            val firstScanIdx = max(0, findActiveCueIndex(cues, posMs - 200L).let {
                if (it >= 0) it else findInsertionPointByStart(cues, posMs)
            })
            for (i in firstScanIdx until cues.size) {
                if (!googleRolling && urgentSynthWaiters.get() > 0) return
                if (inFlightPrefetches.size >= activePrefetchCap) break
                val cue = cues[i]
                if (cue.startTimeMs > horizonMs) break
                if (cue.endTimeMs < posMs) continue
                val key = cueKey(cue, i)
                val existing = prefetchedClips[key] ?: continue
                val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
                if (parsed.speakableText.isBlank()) continue
                val desiredGender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
                val desiredVoiceId = resolveVoiceId(settings, desiredGender)
                if (parsed.tagGender == SpeakerGender.UNSPECIFIED &&
                    desiredGender != SpeakerGender.UNSPECIFIED &&
                    existing.voiceId != desiredVoiceId &&
                    posMs < cue.startTimeMs - 150L
                ) {
                    prefetchedClips.remove(key)
                    val nextCueStartMs = cues.getOrNull(i + 1)?.startTimeMs
                    getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, nextCueStartMs, settings)
                }
            }
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val triggeredSnapshot = synchronized(triggeredLock) { triggeredCueKeys.toSet() }
        val toLaunch = selectRollingPrefetchIndices(
            cues = cues,
            posMs = posMs,
            nowElapsedMs = nowElapsedMs,
            lookaheadMs = lookaheadMs,
            maxWindowCues = maxWindowCues,
            maxConcurrentPrefetches = activePrefetchCap,
            currentInFlightCount = inFlightPrefetches.size,
            isSpeakable = { cue -> VietnameseTextNormalizer.parseAndCleanCue(cue.text).speakableText.isNotBlank() },
            isReady = { key -> prefetchedClips.containsKey(key) },
            isInFlight = { key -> inFlightPrefetches.containsKey(key) },
            isTriggered = { key -> key in triggeredSnapshot },
            retryAfterElapsedMs = { key -> prefetchFailures[key]?.retryAfterElapsedMs }
        )

        for (idx in toLaunch) {
            if (!googleRolling && urgentSynthWaiters.get() > 0) break
            val cue = cues[idx]
            val key = cueKey(cue, idx)
            val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
            if (parsed.speakableText.isBlank()) continue
            val desiredGender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
            val desiredVoiceId = resolveVoiceId(settings, desiredGender)
            val nextCueStartMs = cues.getOrNull(idx + 1)?.startTimeMs
            getOrStartPrefetch(cue, key, parsed.speakableText, desiredVoiceId, desiredGender, nextCueStartMs, settings)
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
                    baseSpeed = playbackBaseSpeedFor(ready.backend, settings.speechSpeed),
                    cueStartMs = cue.startTimeMs,
                    cueEndMs = cue.endTimeMs
                )
            )
            // Slide rolling prefetch window forward immediately after consuming this clip.
            requestRollingRefill()
            return
        }

        val parsed = VietnameseTextNormalizer.parseAndCleanCue(cue.text)
        if (parsed.speakableText.isBlank()) return
        val gender = resolveGenderForSpan(settings, parsed.tagGender, cue.startTimeMs, cue.endTimeMs)
        val voiceId = resolveVoiceId(settings, gender)

        val trackUrgentForPiper = !isGoogleOnlineActive(settings)
        if (trackUrgentForPiper) {
            urgentSynthWaiters.incrementAndGet()
        }
        val deferred = getOrStartPrefetch(cue, key, parsed.speakableText, voiceId, gender, nextCueStartMs, settings)

        scope.launch {
            val result = try {
                deferred.await()
            } finally {
                if (trackUrgentForPiper) {
                    urgentSynthWaiters.decrementAndGet()
                }
            } ?: return@launch
            prefetchedClips.remove(key)
            requestRollingRefill()

            val latestSettings = preferences.current
            if (latestSettings.enabled && isPlayerPlaying) {
                val nowPos = (positionProviderMs() + subtitleOffsetMsProvider()).coerceAtLeast(0L)
                val queueDepth = playerQueue.queueDepth()
                // Never discard a completed utterance if the audio queue is empty or only playing 1 item.
                val withinWindow = nowPos <= cue.endTimeMs + PLAY_LATE_TOLERANCE_MS || queueDepth <= 1
                if (withinWindow && queueDepth < MAX_PLAYER_QUEUE_DEPTH) {
                    playerQueue.enqueue(
                        TtsPlaybackItem(
                            cueKey = key,
                            audioFile = result.file,
                            baseSpeed = playbackBaseSpeedFor(result.backend, latestSettings.speechSpeed),
                            cueStartMs = cue.startTimeMs,
                            cueEndMs = cue.endTimeMs
                        )
                    )
                } else {
                    skippedLateCount.incrementAndGet()
                    publishRuntimeStatus(
                        "Xử lý chậm hơn phụ đề (${(nowPos - cue.endTimeMs).coerceAtLeast(0L)}ms • Đọc:${playedCount.get()} Bỏ:${skippedLateCount.get()})"
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
        nextCueStartMs: Long?,
        settings: TtsSettingsState
    ): Deferred<SynthesizedClipResult?> {
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
                val result = synthesizeClipWithFallback(
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
                if (result != null) {
                    prefetchFailures.remove(key)
                    prefetchedClips[key] = PrefetchedClip(
                        key = key,
                        cueStartMs = cue.startTimeMs,
                        cueEndMs = cue.endTimeMs,
                        file = result.file,
                        backend = result.backend,
                        voiceId = voiceId,
                        resolvedGender = gender,
                        usedPitchHint = settings.dualVoiceEnabled
                    )
                } else if (!shouldAbortPrefetch()) {
                    recordPrefetchFailure(key)
                }
                result
            } finally {
                val self = this.coroutineContext[Job] as? Deferred<*>
                if (self != null) {
                    inFlightPrefetches.remove(key, self)
                } else {
                    inFlightPrefetches.remove(key)
                }
                requestRollingRefill()
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

    private fun recordPrefetchFailure(key: String) {
        val prev = prefetchFailures[key]?.failures ?: 0
        val nextFailures = prev + 1
        val retryAfter = if (nextFailures >= GOOGLE_MAX_PREFETCH_FAILURES) {
            Long.MAX_VALUE
        } else {
            val backoff = min(
                GOOGLE_PREFETCH_BACKOFF_INITIAL_MS shl (nextFailures - 1),
                GOOGLE_PREFETCH_BACKOFF_MAX_MS
            )
            SystemClock.elapsedRealtime() + backoff
        }
        prefetchFailures[key] = PrefetchFailureState(
            failures = nextFailures,
            retryAfterElapsedMs = retryAfter
        )
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
    ): SynthesizedClipResult? {
        if (shouldAbort()) return null
        val voice = TtsVoiceCatalog.findVoice(voiceId)
        val piperReady = voice.backend == TtsBackendType.PIPER_ONNX && TtsVoiceCatalog.isVoiceReady(appContext, voice.id)
        val primaryBackend = if (piperReady) TtsBackendType.PIPER_ONNX else TtsBackendType.GOOGLE_TRANSLATE

        val attempts: List<TtsBackendType> = when (primaryBackend) {
            TtsBackendType.PIPER_ONNX -> listOf(
                TtsBackendType.PIPER_ONNX,
                TtsBackendType.GOOGLE_TRANSLATE
            )
            TtsBackendType.GOOGLE_TRANSLATE -> listOf(
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
                speechSpeed = speechSpeed,
                ttsVolumePercent = ttsVolumePercent,
                targetWindowMs = targetWindowMs,
                nativeSpeedBoost = nativeSpeedBoost,
                shouldAbort = shouldAbort
            )
            if (result != null && result.exists() && result.length() > 64L) {
                when (backend) {
                    TtsBackendType.PIPER_ONNX -> {
                        val tlCount = timelineCues.size
                        val modeTag = if (tlCount > 0) "TL:$tlCount" else "Live"
                        val statsSuffix = if (emaPiperSynthMs > 0L || playedCount.get() > 0 || skippedLateCount.get() > 0) {
                            " • $modeTag • ${emaPiperSynthMs}ms • Đọc:${playedCount.get()} Bỏ:${skippedLateCount.get()}"
                        } else {
                            ""
                        }
                        publishRuntimeStatus("Đang đọc offline • ${voice.displayName}$statsSuffix")
                    }
                    TtsBackendType.GOOGLE_TRANSLATE -> {
                        publishGoogleStatus(voice, if (piperReady) piperFailureReason else null)
                    }
                }
                return SynthesizedClipResult(result, backend)
            }
            if (backend == TtsBackendType.PIPER_ONNX && piperReady && !shouldAbort()) {
                piperFailureReason = piperEngine.lastErrorMessage()
            }
        }

        if (!shouldAbort()) {
            publishRuntimeStatus(
                piperFailureReason
                    ?: "Không tạo được giọng đọc (kiểm tra mạng hoặc tải model Piper)"
            )
        }
        return null
    }

    private fun publishGoogleStatus(voice: TtsVoiceOption, piperFailedReason: String?) {
        val bufferedCount = prefetchedClips.size + inFlightPrefetches.size
        val statusMsg = when {
            piperFailedReason != null ->
                "Piper lỗi ($piperFailedReason) • Dùng Google Dịch"
            voice.backend == TtsBackendType.PIPER_ONNX ->
                "Đang đọc bằng Google Dịch (${voice.displayName} chưa tải)"
            bufferedCount > 0 ->
                "Đang đọc • Google Dịch (Online) • Đệm:$bufferedCount • Đọc:${playedCount.get()}"
            else ->
                "Đang đọc • Google Dịch (Online)"
        }
        publishRuntimeStatus(statusMsg)
    }

    private suspend fun synthesizeWithBackend(
        backend: TtsBackendType,
        voice: TtsVoiceOption,
        text: String,
        speechSpeed: Float,
        ttsVolumePercent: Int,
        targetWindowMs: Long?,
        nativeSpeedBoost: Float,
        shouldAbort: () -> Boolean
    ): File? {
        return try {
            when (backend) {
                TtsBackendType.PIPER_ONNX -> {
                    val piperSpeechSpeed = (speechSpeed * nativeSpeedBoost).coerceIn(0.6f, 2.0f)
                    val hash = sha256Short("${voice.id}|$backend|$piperSpeechSpeed|$ttsVolumePercent|$targetWindowMs|$text")
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
                    // Cache purely by normalized Vietnamese text so speed/volume adjustments hit disk cache immediately.
                    googleEngine.synthesizeToMp3(text)
                }
            }
        } catch (ce: CancellationException) {
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

    private fun findActiveCueIndex(cues: List<SubtitleSyncCue>, posMs: Long): Int =
        findActiveCueIndexIn(cues, posMs)

    private fun cueKey(cue: SubtitleSyncCue, index: Int): String =
        cueKeyFor(cue, index)

    private fun cancelAllPrefetches() {
        inFlightPrefetches.values.forEach { it.cancel() }
        inFlightPrefetches.clear()
        prefetchedClips.clear()
        prefetchFailures.clear()
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
        refillSignal.close()
        duckAnimJob?.cancel()
        cancelAllPrefetches()
        clearTriggeredKeys()
        playerQueue.release()
        piperEngine.release()
        publishRuntimeStatus(null)
        runCatching { onDuckVolumeChanged(1.0f) }
        scope.cancel()
    }

    companion object {
        private const val LOOKAHEAD_TICK_MS = 55L
        private const val PREFETCH_LOOKAHEAD_MS = 60_000L
        private const val MAX_ACTIVE_PREFETCHES = 1
        private const val MAX_PREFETCH_CACHE_CLIPS = 45
        private const val GOOGLE_ROLLING_LOOKAHEAD_MS = 45_000L
        private const val GOOGLE_ROLLING_WINDOW_CUES = 12
        private const val GOOGLE_MAX_CONCURRENT_PREFETCHES = 3
        private const val GOOGLE_PREFETCH_BACKOFF_INITIAL_MS = 1_500L
        private const val GOOGLE_PREFETCH_BACKOFF_MAX_MS = 6_000L
        private const val GOOGLE_MAX_PREFETCH_FAILURES = 3
        private const val TRIGGER_LEAD_MS = 240L
        private const val MAX_LATE_TRIGGER_MS = 7_000L
        private const val PLAY_LATE_TOLERANCE_MS = 8_000L
        private const val MAX_PLAYER_QUEUE_DEPTH = 6
        private const val MAX_PENDING_LIVE_CUES = 6
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

        /**
         * Piper ONNX already synthesizes WAV files at the user's [speechSpeed], so playback queue
         * starts at 1.0x (only applying catch-up speedup when behind). Google Online MP3 files are 1.0x,
         * so playback queue scales them directly to [speechSpeed].
         */
        internal fun playbackBaseSpeedFor(backend: TtsBackendType, speechSpeed: Float): Float =
            when (backend) {
                TtsBackendType.PIPER_ONNX -> 1.0f
                TtsBackendType.GOOGLE_TRANSLATE -> speechSpeed.coerceIn(0.7f, 2.0f)
            }

        /**
         * Pure sliding-window ("cuốn chiếu") planner: picks the earliest untriggered cues within
         * [maxWindowCues] / [lookaheadMs] ahead of [posMs] up to [maxConcurrentPrefetches], skipping
         * already-ready, in-flight, and temporarily backed-off cues so one transient failure never
         * stalls subsequent cues.
         */
        internal fun selectRollingPrefetchIndices(
            cues: List<SubtitleSyncCue>,
            posMs: Long,
            nowElapsedMs: Long,
            lookaheadMs: Long,
            maxWindowCues: Int,
            maxConcurrentPrefetches: Int,
            currentInFlightCount: Int,
            isSpeakable: (SubtitleSyncCue) -> Boolean,
            isReady: (String) -> Boolean,
            isInFlight: (String) -> Boolean,
            isTriggered: (String) -> Boolean,
            retryAfterElapsedMs: (String) -> Long?
        ): List<Int> {
            if (cues.isEmpty() || maxWindowCues <= 0 || currentInFlightCount >= maxConcurrentPrefetches) {
                return emptyList()
            }
            val horizonMs = posMs + lookaheadMs
            val firstScanIdx = max(0, findActiveCueIndexIn(cues, posMs - 200L).let {
                if (it >= 0) it else findInsertionPointByStartIn(cues, posMs)
            })
            val slotsAvailable = maxConcurrentPrefetches - currentInFlightCount
            val selected = ArrayList<Int>(slotsAvailable)
            var windowCount = 0

            for (i in firstScanIdx until cues.size) {
                val cue = cues[i]
                if (cue.startTimeMs > horizonMs) break
                if (cue.endTimeMs < posMs) continue
                if (!isSpeakable(cue)) continue

                val key = cueKeyFor(cue, i)
                if (isTriggered(key)) continue

                windowCount++
                if (windowCount > maxWindowCues) break

                if (isReady(key) || isInFlight(key)) continue
                val retryAt = retryAfterElapsedMs(key)
                if (retryAt != null && nowElapsedMs < retryAt) continue

                selected.add(i)
                if (selected.size >= slotsAvailable) break
            }
            return selected
        }

        private fun findActiveCueIndexIn(cues: List<SubtitleSyncCue>, posMs: Long): Int {
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

        private fun findInsertionPointByStartIn(cues: List<SubtitleSyncCue>, posMs: Long): Int {
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

        internal fun computeNativeSpeedBoost(
            slotMs: Long?,
            textLength: Int,
            lagMs: Long,
            leadRemainingMs: Long,
            emaSynthMs: Long,
            backlogDepth: Int
        ): Float {
            return when {
                lagMs > 1200L || backlogDepth >= 2 -> 1.24f
                backlogDepth >= 1 || lagMs > 400L -> 1.15f
                emaSynthMs > 1100L -> 1.12f
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
