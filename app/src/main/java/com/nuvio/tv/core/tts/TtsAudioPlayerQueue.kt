package com.nuvio.tv.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class TtsPlaybackItem(
    val cueKey: String,
    val audioFile: File,
    val baseSpeed: Float = 1.0f,
    val cueStartMs: Long = -1L,
    val cueEndMs: Long = -1L
)

/**
 * FIFO [MediaPlayer] / [AudioTrack] queue with timestamp- & backlog-aware catch-up speed scaling,
 * [LoudnessEnhancer] gain boost above 100%, and speaking-state callbacks for movie ducking.
 *
 * All playback work executes on a dedicated [HandlerThread] with an active [android.os.Looper]
 * so Android completion/error callbacks fire reliably without blocking Main or coroutine pools.
 */
class TtsAudioPlayerQueue(
    context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onPlaybackError: (String) -> Unit = {},
    private val currentPositionMsProvider: () -> Long = { -1L },
    private val onItemSkipped: ((Long) -> Unit)? = null,
    private val onItemPlayed: (() -> Unit)? = null
) {
    private val queue = ConcurrentLinkedQueue<TtsPlaybackItem>()
    private val released = AtomicBoolean(false)

    private val handlerThread = HandlerThread("TtsAudioPlayer", Process.THREAD_PRIORITY_AUDIO).apply {
        start()
    }
    private val playerHandler = Handler(handlerThread.looper)

    // Accessed strictly from handlerThread (or after quit on release)
    private var activePlayer: MediaPlayer? = null
    private var activeWavToken: Any? = null
    private var activeEnhancer: LoudnessEnhancer? = null
    private var pausedByMovie = false

    @Volatile
    private var ttsVolumePercent: Int = 100

    @Volatile
    private var isSpeaking = false

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val persistentWavStream = PersistentTtsAudioStream(
        audioAttributes = speechAttributes,
        initialVolumePercent = ttsVolumePercent,
        hasQueuedNext = { !queue.isEmpty() }
    )

    fun enqueue(item: TtsPlaybackItem) {
        if (released.get() || !item.audioFile.exists() || item.audioFile.length() <= 64L) return
        queue.add(item)
        postOnPlayerThread {
            if (!released.get() && activePlayer == null && activeWavToken == null && !pausedByMovie) {
                playNextOnPlayerThread()
            }
        }
    }

    fun pendingCount(): Int = queue.size

    fun queueDepth(): Int = queue.size + if (activePlayer != null || activeWavToken != null) 1 else 0

    fun updateVolumePercent(percent: Int) {
        ttsVolumePercent = percent.coerceIn(0, 150)
        persistentWavStream.updateVolumePercent(ttsVolumePercent)
        postOnPlayerThread {
            val mp = activePlayer ?: return@postOnPlayerThread
            applyVolumeOnPlayerThread(mp, ttsVolumePercent)
        }
    }

    fun setMoviePaused(paused: Boolean) {
        persistentWavStream.setPaused(paused)
        postOnPlayerThread {
            if (released.get()) return@postOnPlayerThread
            pausedByMovie = paused
            if (activeWavToken != null) {
                notifySpeaking(!paused)
                return@postOnPlayerThread
            }
            val mp = activePlayer
            if (paused) {
                if (mp != null) {
                    runCatching {
                        if (mp.isPlaying) mp.pause()
                    }
                }
                notifySpeaking(false)
            } else {
                if (mp != null) {
                    runCatching {
                        mp.start()
                        notifySpeaking(true)
                    }.onFailure {
                        releaseActiveOnPlayerThread()
                        playNextOnPlayerThread()
                    }
                } else {
                    playNextOnPlayerThread()
                }
            }
        }
    }

    fun clearAndStop() {
        queue.clear()
        persistentWavStream.flushAndCancel()
        postOnPlayerThread {
            releaseActiveOnPlayerThread()
            notifySpeaking(false)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        queue.clear()
        persistentWavStream.release()
        val latch = CountDownLatch(1)
        val posted = runCatching {
            playerHandler.post {
                try {
                    releaseActiveOnPlayerThread()
                    notifySpeaking(false)
                } finally {
                    latch.countDown()
                }
            }
        }.getOrDefault(false)
        if (posted) {
            runCatching { latch.await(250, TimeUnit.MILLISECONDS) }
        } else {
            runCatching {
                releaseActiveOnPlayerThread()
                notifySpeaking(false)
            }
        }
        runCatching { handlerThread.quitSafely() }
    }

    private fun postOnPlayerThread(block: () -> Unit) {
        if (released.get()) return
        runCatching {
            if (Thread.currentThread() === handlerThread) {
                block()
            } else {
                playerHandler.post(block)
            }
        }
    }

    private fun playNextOnPlayerThread() {
        if (pausedByMovie || released.get()) return
        while (!released.get()) {
            val next = queue.poll()
            if (next == null) {
                notifySpeaking(false)
                return
            }
            if (!next.audioFile.exists() || next.audioFile.length() <= 64L) {
                continue
            }

            val nowPosMs = currentPositionMsProvider()
            val latenessMs = if (next.cueStartMs >= 0L && nowPosMs >= 0L) {
                (nowPosMs - next.cueStartMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val pendingCount = queue.size
            // Only discard a stale item when severely behind (>7.5s) AND at least 2 newer sentences are
            // ready in queue; never skip the sole active/next sentence so dialogue never alternates silent.
            if (latenessMs > 7_500L && pendingCount >= 2) {
                onItemSkipped?.invoke(latenessMs)
                continue
            }

            val backlogSpeed = when {
                pendingCount >= 3 -> max(next.baseSpeed * 1.30f, 1.35f)
                pendingCount == 2 -> max(next.baseSpeed * 1.20f, 1.24f)
                pendingCount == 1 -> max(next.baseSpeed * 1.12f, 1.15f)
                else -> next.baseSpeed
            }
            val latenessSpeed = when {
                latenessMs > 2800L -> max(next.baseSpeed * 1.30f, 1.36f)
                latenessMs > 1500L -> max(next.baseSpeed * 1.20f, 1.24f)
                latenessMs > 600L -> max(next.baseSpeed * 1.10f, 1.14f)
                else -> next.baseSpeed
            }
            val effectiveSpeed = max(backlogSpeed, latenessSpeed).coerceIn(0.6f, 1.42f)

            // Prefer persistent 48 kHz stereo AudioTrack for PCM WAV (Piper / System TTS) to bypass
            // Android TV OEM HAL track-reopen mutes and 22050 Hz mono NuPlayer quirks; fall back to
            // MediaPlayer on non-PCM formats (.mp3 Google Translate) or if AudioTrack init fails.
            if (next.audioFile.name.endsWith(".wav", ignoreCase = true)) {
                val wavToken = Any()
                activeWavToken = wavToken
                val accepted = persistentWavStream.submitWav(
                    file = next.audioFile,
                    speedRatio = effectiveSpeed,
                    onCompleted = {
                        postOnPlayerThread {
                            if (activeWavToken === wavToken) {
                                activeWavToken = null
                                onItemPlayed?.invoke()
                                playNextOnPlayerThread()
                            }
                        }
                    },
                    onError = { err ->
                        postOnPlayerThread {
                            if (activeWavToken === wavToken) {
                                activeWavToken = null
                                runCatching { onPlaybackError("AudioTrack WAV: $err") }
                                playNextOnPlayerThread()
                            }
                        }
                    }
                )
                if (accepted) {
                    notifySpeaking(true)
                    return
                }
                if (activeWavToken === wavToken) {
                    activeWavToken = null
                }
            }

            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(speechAttributes)
                mp.setDataSource(next.audioFile.absolutePath)
                mp.prepare()
                applyVolumeOnPlayerThread(mp, ttsVolumePercent)
                mp.setOnCompletionListener { finished ->
                    postOnPlayerThread {
                        if (activePlayer === finished) {
                            releaseActiveOnPlayerThread()
                            playNextOnPlayerThread()
                        } else {
                            runCatching { finished.release() }
                        }
                    }
                }
                mp.setOnErrorListener { errored, what, extra ->
                    postOnPlayerThread {
                        if (activePlayer === errored) {
                            runCatching { onPlaybackError("MediaPlayer($what/$extra)") }
                            releaseActiveOnPlayerThread()
                            playNextOnPlayerThread()
                        } else {
                            runCatching { errored.release() }
                        }
                    }
                    true
                }
                activePlayer = mp
                mp.start()
                // Only apply PlaybackParams AFTER start() and only when speed actually differs from 1.0x:
                // some Android TV OEM MediaPlayers return pitch=0f/speed=0f when queried in Prepared
                // state or mute short clips if PlaybackParams is set before start().
                if (abs(effectiveSpeed - 1.0f) > 0.02f) {
                    runCatching {
                        mp.playbackParams = PlaybackParams()
                            .setSpeed(effectiveSpeed)
                            .setPitch(1.0f)
                    }
                }
                notifySpeaking(true)
                return
            } catch (t: Throwable) {
                runCatching { onPlaybackError("MediaPlayer: ${t.javaClass.simpleName}: ${t.message}") }
                runCatching { mp.release() }
                if (activePlayer === mp) activePlayer = null
            }
        }
    }

    private fun applyVolumeOnPlayerThread(mp: MediaPlayer, percent: Int) {
        val ratio = (percent / 100.0f).coerceIn(0.0f, 1.5f)
        val mediaVolume = ratio.coerceAtMost(1.0f)
        runCatching { mp.setVolume(mediaVolume, mediaVolume) }

        if (ratio > 1.01f) {
            val gainMb = ((ratio - 1.0f) * 2800.0f).roundToInt().coerceIn(0, 1400)
            runCatching {
                val enhancer = activeEnhancer ?: LoudnessEnhancer(mp.audioSessionId).also {
                    activeEnhancer = it
                }
                enhancer.setTargetGain(gainMb)
                enhancer.enabled = true
            }
        } else {
            runCatching {
                activeEnhancer?.enabled = false
            }
        }
    }

    private fun releaseActiveOnPlayerThread() {
        activeWavToken = null
        runCatching { activeEnhancer?.release() }
        activeEnhancer = null
        activePlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
        activePlayer = null
    }

    private fun notifySpeaking(speaking: Boolean) {
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            runCatching { onSpeakingStateChanged(speaking) }
        }
    }
}
