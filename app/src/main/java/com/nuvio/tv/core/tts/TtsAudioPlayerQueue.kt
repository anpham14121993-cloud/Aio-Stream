package com.nuvio.tv.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
 */
class TtsAudioPlayerQueue(
    context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private val queue = ConcurrentLinkedQueue<TtsPlaybackItem>()
    private val playerLock = Any()

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
        if (!item.audioFile.exists() || item.audioFile.length() <= 64L) return
        synchronized(playerLock) {
            queue.add(item)
            if (activePlayer == null && !pausedByMovie) {
                playNextLocked()
            }
        }
    }

    fun updateVolumePercent(percent: Int) {
        ttsVolumePercent = percent.coerceIn(0, 150)
        synchronized(playerLock) {
            val mp = activePlayer ?: return
            applyVolumeToPlayerLocked(mp, ttsVolumePercent)
        }
    }

    fun setMoviePaused(paused: Boolean) {
        synchronized(playerLock) {
            pausedByMovie = paused
            val mp = activePlayer
            if (paused) {
                if (mp != null) {
                    runCatching {
                        if (mp.isPlaying) mp.pause()
                    }
                }
                notifySpeakingLocked(false)
            } else {
                if (mp != null) {
                    runCatching {
                        mp.start()
                        notifySpeakingLocked(true)
                    }.onFailure {
                        releaseActiveLocked()
                        playNextLocked()
                    }
                } else {
                    playNextLocked()
                }
            }
        }
    }

    fun clearAndStop() {
        synchronized(playerLock) {
            queue.clear()
            releaseActiveLocked()
            notifySpeakingLocked(false)
        }
    }

    fun release() {
        clearAndStop()
    }

    private fun playNextLocked() {
        if (pausedByMovie) return
        while (true) {
            val next = queue.poll()
            if (next == null) {
                notifySpeakingLocked(false)
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
                applyVolumeToPlayerLocked(mp, ttsVolumePercent)
                runCatching {
                    val params = mp.playbackParams ?: PlaybackParams()
                    params.speed = effectiveSpeed
                    mp.playbackParams = params
                }
                mp.setOnCompletionListener { finished ->
                    synchronized(playerLock) {
                        if (activePlayer === finished) {
                            releaseActiveLocked()
                            playNextLocked()
                        } else {
                            runCatching { finished.release() }
                        }
                    }
                }
                mp.setOnErrorListener { errored, _, _ ->
                    synchronized(playerLock) {
                        if (activePlayer === errored) {
                            releaseActiveLocked()
                            playNextLocked()
                        } else {
                            runCatching { errored.release() }
                        }
                    }
                    true
                }
                activePlayer = mp
                mp.start()
                notifySpeakingLocked(true)
                return
            } catch (_: Throwable) {
                runCatching { mp.release() }
                activePlayer = null
            }
        }
    }

    private fun applyVolumeToPlayerLocked(mp: MediaPlayer, percent: Int) {
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

    private fun releaseActiveLocked() {
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

    private fun notifySpeakingLocked(speaking: Boolean) {
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            onSpeakingStateChanged(speaking)
        }
    }
}
