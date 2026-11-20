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
import kotlin.math.max
import kotlin.math.roundToInt

data class TtsPlaybackItem(
    val cueKey: String,
    val audioFile: File,
    val baseSpeed: Float = 1.0f
)

/**
 * FIFO [MediaPlayer] queue (`Lk26` + `Li26` parity) with backlog catch-up speed scaling,
 * [LoudnessEnhancer] gain boost above 100%, and speaking-state callbacks for movie ducking.
 *
 * All [MediaPlayer] work executes on a dedicated [HandlerThread] with an active [android.os.Looper]
 * so Android completion/error callbacks fire reliably without blocking Main or coroutine pools.
 */
class TtsAudioPlayerQueue(
    context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private val queue = ConcurrentLinkedQueue<TtsPlaybackItem>()
    private val released = AtomicBoolean(false)

    private val handlerThread = HandlerThread("TtsAudioPlayer", Process.THREAD_PRIORITY_AUDIO).apply {
        start()
    }
    private val playerHandler = Handler(handlerThread.looper)

    // Accessed strictly from handlerThread (or after quit on release)
    private var activePlayer: MediaPlayer? = null
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

    fun enqueue(item: TtsPlaybackItem) {
        if (released.get() || !item.audioFile.exists() || item.audioFile.length() <= 64L) return
        queue.add(item)
        postOnPlayerThread {
            if (!released.get() && activePlayer == null && !pausedByMovie) {
                playNextOnPlayerThread()
            }
        }
    }

    fun updateVolumePercent(percent: Int) {
        ttsVolumePercent = percent.coerceIn(0, 150)
        postOnPlayerThread {
            val mp = activePlayer ?: return@postOnPlayerThread
            applyVolumeOnPlayerThread(mp, ttsVolumePercent)
        }
    }

    fun setMoviePaused(paused: Boolean) {
        postOnPlayerThread {
            if (released.get()) return@postOnPlayerThread
            pausedByMovie = paused
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
        postOnPlayerThread {
            releaseActiveOnPlayerThread()
            notifySpeaking(false)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        queue.clear()
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

            val pendingCount = queue.size
            val effectiveSpeed = when {
                pendingCount >= 2 -> max(next.baseSpeed * (1.0f + (pendingCount - 1) * 0.1f), 1.3f).coerceAtMost(1.6f)
                pendingCount == 1 -> (next.baseSpeed * 1.08f).coerceAtMost(1.5f)
                else -> next.baseSpeed.coerceIn(0.6f, 2.0f)
            }

            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(speechAttributes)
                mp.setDataSource(next.audioFile.absolutePath)
                mp.prepare()
                applyVolumeOnPlayerThread(mp, ttsVolumePercent)
                runCatching {
                    val params = mp.playbackParams ?: PlaybackParams()
                    params.speed = effectiveSpeed
                    mp.playbackParams = params
                }
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
                mp.setOnErrorListener { errored, _, _ ->
                    postOnPlayerThread {
                        if (activePlayer === errored) {
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
                notifySpeaking(true)
                return
            } catch (_: Throwable) {
                runCatching { mp.release() }
                activePlayer = null
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
