package com.nuvio.tv.core.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class WavPcmHeader(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataLength: Int
)

/**
 * Direct [AudioTrack] PCM16 WAV player for TTS playback (`TtsAudioPlayerQueue` and `TtsVoicePreviewPlayer`).
 *
 * Plays uncompressed 16-bit PCM WAV files directly through AudioFlinger (`USAGE_MEDIA` +
 * `CONTENT_TYPE_SPEECH`) without OEM NuPlayer/MediaPlayer 22050 Hz mono WAV quirks, and
 * automatically resamples to 48 kHz stereo if a TV HAL rejects the source format.
 */
internal object TtsWavPcmHelper {
    private const val TARGET_FALLBACK_RATE = 48_000

    fun parseWavHeader(bytes: ByteArray): WavPcmHeader? {
        if (bytes.size < 44) return null
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
        ) return null

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var hasFmt = false

        while (offset + 8 <= bytes.size) {
            val id0 = bytes[offset].toInt().toChar()
            val id1 = bytes[offset + 1].toInt().toChar()
            val id2 = bytes[offset + 2].toInt().toChar()
            val id3 = bytes[offset + 3].toInt().toChar()
            val chunkSizeLong = bb.getInt(offset + 4).toLong() and 0xFFFF_FFFFL
            val chunkStart = offset + 8
            if (chunkStart > bytes.size) break
            val available = (bytes.size - chunkStart).toLong()
            val boundedSize = min(chunkSizeLong, available).toInt().coerceAtLeast(0)

            if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ') {
                if (boundedSize < 16) return null
                audioFormat = bb.getShort(chunkStart).toInt() and 0xFFFF
                channels = bb.getShort(chunkStart + 2).toInt() and 0xFFFF
                sampleRate = bb.getInt(chunkStart + 4)
                bitsPerSample = bb.getShort(chunkStart + 14).toInt() and 0xFFFF
                hasFmt = true
            } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                if (!hasFmt) return null
                if (audioFormat != 1 || bitsPerSample != 16) return null
                if (channels != 1 && channels != 2) return null
                if (sampleRate !in 8000..192_000) return null
                val frameAlign = channels * 2
                val alignedLen = (boundedSize / frameAlign) * frameAlign
                if (alignedLen <= 0) return null
                return WavPcmHeader(
                    sampleRate = sampleRate,
                    channelCount = channels,
                    bitsPerSample = bitsPerSample,
                    dataOffset = chunkStart,
                    dataLength = alignedLen
                )
            }

            // RIFF chunks are word-aligned (odd sizes are followed by 1 pad byte).
            val paddedAdvance = chunkSizeLong + (chunkSizeLong and 1L)
            val next = chunkStart.toLong() + paddedAdvance
            if (next <= offset || next > bytes.size.toLong()) break
            offset = next.toInt()
        }
        return null
    }

    fun resamplePcm16To48kStereo(
        wavBytes: ByteArray,
        header: WavPcmHeader
    ): ByteArray {
        val srcFrameSize = header.channelCount * 2
        val srcFrames = header.dataLength / srcFrameSize
        if (srcFrames <= 0) return ByteArray(0)

        val dstFrames = ((srcFrames.toLong() * TARGET_FALLBACK_RATE) / header.sampleRate.toLong())
            .toInt()
            .coerceAtLeast(1)
        val out = ByteArray(dstFrames * 4)
        val inBb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until dstFrames) {
            val srcPos = (i.toDouble() * header.sampleRate.toDouble()) / TARGET_FALLBACK_RATE.toDouble()
            val idx0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val idx1 = min(idx0 + 1, srcFrames - 1)
            val frac = (srcPos - idx0).toFloat()

            val off0 = header.dataOffset + idx0 * srcFrameSize
            val off1 = header.dataOffset + idx1 * srcFrameSize
            val l0 = inBb.getShort(off0).toFloat()
            val l1 = inBb.getShort(off1).toFloat()
            val left = (l0 + (l1 - l0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()

            val right = if (header.channelCount == 2) {
                val r0 = inBb.getShort(off0 + 2).toFloat()
                val r1 = inBb.getShort(off1 + 2).toFloat()
                (r0 + (r1 - r0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            } else {
                left
            }

            outBb.putShort(left)
            outBb.putShort(right)
        }
        return out
    }
}

/**
 * Non-blocking [Handler]-driven [AudioTrack] playback controller for one PCM16 WAV clip.
 * All methods must be invoked on the owner [Handler]'s Looper thread.
 */
internal class ActiveWavAudioTrack(
    private val handler: Handler,
    private val audioAttributes: AudioAttributes,
    file: File,
    private val effectiveSpeed: Float,
    volumePercent: Int,
    private val onCompleted: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val track: AudioTrack
    private val pcmData: ByteArray
    private val totalFrames: Int
    private val sampleRate: Int
    private var enhancer: LoudnessEnhancer? = null

    private var writtenBytes = 0
    private var drainStartMs = -1L
    private var paused = false
    private var stoppedOrReleased = false

    private val pumpRunnable = object : Runnable {
        override fun run() {
            pumpOnHandlerThread()
        }
    }

    init {
        val rawBytes = file.readBytes()
        val header = TtsWavPcmHelper.parseWavHeader(rawBytes)
            ?: throw IllegalArgumentException("Not an uncompressed 16-bit PCM WAV")

        val nativeMask = if (header.channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val nativeMinBuf = AudioTrack.getMinBufferSize(
            header.sampleRate,
            nativeMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        var chosenRate = header.sampleRate
        var chosenChannels = header.channelCount
        var chosenMask = nativeMask
        var chosenPcm = rawBytes.copyOfRange(header.dataOffset, header.dataOffset + header.dataLength)

        var builtTrack = tryBuildTrack(
            rate = chosenRate,
            mask = chosenMask,
            minBuf = nativeMinBuf,
            frameSize = chosenChannels * 2
        )

        if (builtTrack == null) {
            chosenRate = 48_000
            chosenChannels = 2
            chosenMask = AudioFormat.CHANNEL_OUT_STEREO
            chosenPcm = TtsWavPcmHelper.resamplePcm16To48kStereo(rawBytes, header)
            val fallbackMinBuf = AudioTrack.getMinBufferSize(
                chosenRate,
                chosenMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            builtTrack = tryBuildTrack(
                rate = chosenRate,
                mask = chosenMask,
                minBuf = fallbackMinBuf,
                frameSize = 4
            )
        }

        track = builtTrack ?: throw IllegalStateException("AudioTrack init failed (${header.sampleRate}Hz ${header.channelCount}ch)")
        pcmData = chosenPcm
        sampleRate = chosenRate
        totalFrames = pcmData.size / (chosenChannels * 2)

        updateVolumePercent(volumePercent)
    }

    private fun tryBuildTrack(
        rate: Int,
        mask: Int,
        minBuf: Int,
        frameSize: Int
    ): AudioTrack? {
        if (minBuf <= 0) return null
        val targetBuf = max(minBuf * 2, (rate / 4) * frameSize)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(mask)
            .build()
        return try {
            val candidate = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(targetBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                null
            } else {
                candidate
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun start() {
        if (stoppedOrReleased) return
        paused = false
        track.play()
        if (abs(effectiveSpeed - 1.0f) > 0.02f) {
            runCatching {
                track.playbackParams = PlaybackParams()
                    .setSpeed(effectiveSpeed.coerceIn(0.6f, 2.0f))
                    .setPitch(1.0f)
            }
        }
        pumpOnHandlerThread()
    }

    fun setPaused(pausePlayback: Boolean) {
        if (stoppedOrReleased) return
        if (paused == pausePlayback) return
        paused = pausePlayback
        handler.removeCallbacks(pumpRunnable)
        if (pausePlayback) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            drainStartMs = -1L
        } else {
            runCatching { track.play() }
            pumpOnHandlerThread()
        }
    }

    fun updateVolumePercent(percent: Int) {
        if (stoppedOrReleased) return
        val ratio = (percent / 100.0f).coerceIn(0.0f, 1.5f)
        val vol = ratio.coerceAtMost(1.0f)
        runCatching { track.setVolume(vol) }

        if (ratio > 1.01f) {
            val gainMb = ((ratio - 1.0f) * 2800.0f).roundToInt().coerceIn(0, 1400)
            runCatching {
                val active = enhancer ?: LoudnessEnhancer(track.audioSessionId).also { enhancer = it }
                active.setTargetGain(gainMb)
                active.enabled = true
            }
        } else {
            runCatching { enhancer?.enabled = false }
        }
    }

    fun release() {
        if (stoppedOrReleased) return
        stoppedOrReleased = true
        handler.removeCallbacks(pumpRunnable)
        runCatching { enhancer?.release() }
        enhancer = null
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.pause()
                track.flush()
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    private fun pumpOnHandlerThread() {
        if (stoppedOrReleased || paused) return
        try {
            if (writtenBytes < pcmData.size) {
                val chunk = min(16_384, pcmData.size - writtenBytes)
                val wrote = track.write(pcmData, writtenBytes, chunk, AudioTrack.WRITE_NON_BLOCKING)
                if (wrote < 0) {
                    val err = "AudioTrack write error=$wrote"
                    release()
                    onError(err)
                    return
                }
                if (wrote > 0) {
                    writtenBytes += wrote
                }
                if (writtenBytes >= pcmData.size) {
                    runCatching { track.stop() }
                    drainStartMs = android.os.SystemClock.elapsedRealtime()
                }
                handler.postDelayed(pumpRunnable, if (wrote > 0 && writtenBytes < pcmData.size) 8L else 20L)
                return
            }

            val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            val now = android.os.SystemClock.elapsedRealtime()
            if (drainStartMs < 0L) drainStartMs = now
            val remainingFrames = (totalFrames - head).coerceAtLeast(0L)
            val totalClipMs = ((totalFrames * 1000.0) / (sampleRate * effectiveSpeed.coerceIn(0.6f, 2.0f))).toLong()
            val tailBudgetMs = totalClipMs + 450L

            if (remainingFrames <= 48L || (now - drainStartMs) >= tailBudgetMs) {
                release()
                onCompleted()
            } else {
                handler.postDelayed(pumpRunnable, 20L)
            }
        } catch (t: Throwable) {
            release()
            onError(t.message ?: t.javaClass.simpleName)
        }
    }
}
