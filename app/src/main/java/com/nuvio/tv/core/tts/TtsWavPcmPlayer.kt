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

    /**
     * Appends a short silent PCM16 zero tail after real speech frames so vendor Android TV Audio
     * HALs flush every spoken syllable cleanly while [ActiveWavAudioTrack] monitors drain without
     * calling `AudioTrack.stop()` prematurely.
     */
    internal fun appendSilenceTail(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channelCount: Int,
        tailMs: Long = 64L
    ): ByteArray {
        val frameSize = channelCount.coerceIn(1, 2) * 2
        val safeRate = sampleRate.coerceAtLeast(8_000)
        val tailFrames = ((safeRate * tailMs.coerceIn(0L, 250L)) / 1_000L).toInt()
        if (tailFrames <= 0) return pcmBytes
        return pcmBytes.copyOf(pcmBytes.size + tailFrames * frameSize)
    }

    /**
     * Decodes a PCM16 WAV payload, applies pure-Kotlin WSOLA speed scaling when `speedRatio != 1.0f`
     * (bypassing flaky OEM TV `AudioTrack.playbackParams` speed), and resamples to 48 kHz stereo PCM16.
     */
    internal fun decodeWavTo48kStereoPcm16(
        wavBytes: ByteArray,
        speedRatio: Float = 1.0f
    ): ByteArray? {
        val header = parseWavHeader(wavBytes) ?: return null
        val srcFrames = header.dataLength / (header.channelCount * 2)
        if (srcFrames <= 0) return null

        if (abs(speedRatio - 1.0f) <= 0.03f) {
            return resamplePcm16To48kStereo(wavBytes, header)
        }

        val inBb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val monoFloat = FloatArray(srcFrames)
        if (header.channelCount == 1) {
            for (i in 0 until srcFrames) {
                monoFloat[i] = inBb.getShort(header.dataOffset + i * 2) / 32768.0f
            }
        } else {
            for (i in 0 until srcFrames) {
                val off = header.dataOffset + i * 4
                val l = inBb.getShort(off).toFloat()
                val r = inBb.getShort(off + 2).toFloat()
                monoFloat[i] = ((l + r) * 0.5f) / 32768.0f
            }
        }

        val stretched = WsolaTimeStretcher.timeStretch(monoFloat, header.sampleRate, speedRatio)
        if (stretched.isEmpty()) return ByteArray(0)

        val dstFrames = ((stretched.size.toLong() * TARGET_FALLBACK_RATE) / header.sampleRate.toLong())
            .toInt()
            .coerceAtLeast(1)
        val out = ByteArray(dstFrames * 4)
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dstFrames) {
            val srcPos = (i.toDouble() * header.sampleRate.toDouble()) / TARGET_FALLBACK_RATE.toDouble()
            val idx0 = srcPos.toInt().coerceIn(0, stretched.lastIndex)
            val idx1 = min(idx0 + 1, stretched.lastIndex)
            val frac = (srcPos - idx0).toFloat()
            val sampleFloat = stretched[idx0] + (stretched[idx1] - stretched[idx0]) * frac
            val pcm16 = (sampleFloat * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            outBb.putShort(pcm16)
            outBb.putShort(pcm16)
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
    private val audioFrames: Int
    private val totalFrames: Int
    private val sampleRate: Int
    private val bufferSizeBytes: Int
    private var activeSpeed: Float = 1.0f
    private var totalClipMs: Long
    private var enhancer: LoudnessEnhancer? = null

    private var writtenBytes = 0
    private var drainStartMs = -1L
    private var drainBudgetMs = -1L
    private var activePlayedMs = 0L
    private var activeResumeUptimeMs = -1L
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

        var builtResult = tryBuildTrack(
            rate = chosenRate,
            mask = chosenMask,
            minBuf = nativeMinBuf,
            frameSize = chosenChannels * 2
        )

        if (builtResult == null) {
            chosenRate = 48_000
            chosenChannels = 2
            chosenMask = AudioFormat.CHANNEL_OUT_STEREO
            chosenPcm = TtsWavPcmHelper.resamplePcm16To48kStereo(rawBytes, header)
            val fallbackMinBuf = AudioTrack.getMinBufferSize(
                chosenRate,
                chosenMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            builtResult = tryBuildTrack(
                rate = chosenRate,
                mask = chosenMask,
                minBuf = fallbackMinBuf,
                frameSize = 4
            )
        }

        val pair = builtResult ?: throw IllegalStateException("AudioTrack init failed (${header.sampleRate}Hz ${header.channelCount}ch)")
        track = pair.first
        bufferSizeBytes = pair.second
        val paddedPcm = TtsWavPcmHelper.appendSilenceTail(
            pcmBytes = chosenPcm,
            sampleRate = chosenRate,
            channelCount = chosenChannels,
            tailMs = 96L
        )
        pcmData = paddedPcm
        sampleRate = chosenRate
        audioFrames = chosenPcm.size / (chosenChannels * 2)
        totalFrames = paddedPcm.size / (chosenChannels * 2)
        totalClipMs = ((audioFrames * 1000.0) / sampleRate).toLong()

        updateVolumePercent(volumePercent)
    }

    private fun tryBuildTrack(
        rate: Int,
        mask: Int,
        minBuf: Int,
        frameSize: Int
    ): Pair<AudioTrack, Int>? {
        if (minBuf <= 0) return null
        val targetBuf = max(minBuf, (rate / 8) * frameSize)
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
                candidate to targetBuf
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun start() {
        if (stoppedOrReleased) return
        paused = false
        // Prefill up to the hardware buffer before play() to eliminate initial startup silence/underrun.
        val initialChunk = min(bufferSizeBytes, pcmData.size)
        if (initialChunk > 0) {
            val prewrote = track.write(pcmData, 0, initialChunk, AudioTrack.WRITE_NON_BLOCKING)
            if (prewrote > 0) {
                writtenBytes += prewrote
            }
        }
        activeResumeUptimeMs = android.os.SystemClock.elapsedRealtime()
        track.play()
        val targetSpeed = effectiveSpeed.coerceIn(0.6f, 1.55f)
        activeSpeed = if (abs(targetSpeed - 1.0f) > 0.02f) {
            val applied = runCatching {
                track.playbackParams = PlaybackParams()
                    .setSpeed(targetSpeed)
                    .setPitch(1.0f)
                track.playbackParams.speed
            }.getOrNull()
            if (applied != null && abs(applied - targetSpeed) < 0.25f) applied else 1.0f
        } else {
            1.0f
        }
        totalClipMs = ((audioFrames * 1000.0) / (sampleRate * activeSpeed)).toLong()
        if (writtenBytes >= pcmData.size) {
            markWrittenComplete()
        }
        pumpOnHandlerThread()
    }

    fun setPaused(pausePlayback: Boolean) {
        if (stoppedOrReleased) return
        if (paused == pausePlayback) return
        paused = pausePlayback
        handler.removeCallbacks(pumpRunnable)
        if (pausePlayback) {
            if (activeResumeUptimeMs >= 0L) {
                activePlayedMs += (android.os.SystemClock.elapsedRealtime() - activeResumeUptimeMs).coerceAtLeast(0L)
                activeResumeUptimeMs = -1L
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            drainStartMs = -1L
        } else {
            activeResumeUptimeMs = android.os.SystemClock.elapsedRealtime()
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

    private fun markWrittenComplete() {
        // Never call track.stop() immediately after play(): vendor Android TV Audio HALs cancel
        // or mute short MODE_STREAM buffers in STATE_STOPPING before MixerThread drains them.
        // Keep PLAYSTATE_PLAYING while trailing silence drains and stop() only in release().
        val headNow = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val remainingFrames = (totalFrames - headNow).coerceAtLeast(0L)
        drainBudgetMs = ((remainingFrames * 1000.0) / (sampleRate * activeSpeed)).toLong() + 480L
        drainStartMs = android.os.SystemClock.elapsedRealtime()
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
                    markWrittenComplete()
                }
                handler.postDelayed(pumpRunnable, if (wrote > 0 && writtenBytes < pcmData.size) 8L else 20L)
                return
            }

            val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            val now = android.os.SystemClock.elapsedRealtime()
            if (drainStartMs < 0L) {
                val remNow = (totalFrames - head).coerceAtLeast(0L)
                drainBudgetMs = ((remNow * 1000.0) / (sampleRate * activeSpeed)).toLong() + 480L
                drainStartMs = now
            }
            val remainingAudioFrames = (audioFrames - head).coerceAtLeast(0L)
            val totalActiveMs = activePlayedMs + if (activeResumeUptimeMs >= 0L) {
                (now - activeResumeUptimeMs).coerceAtLeast(0L)
            } else {
                0L
            }

            if (remainingAudioFrames <= 0L ||
                (now - drainStartMs) >= drainBudgetMs ||
                totalActiveMs >= totalClipMs + 520L
            ) {
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

/**
 * Persistent single-stream 48 kHz stereo PCM16 [AudioTrack] writer for consecutive movie TTS playback.
 *
 * Unlike [ActiveWavAudioTrack], which creates and destroys an `AudioTrack` on every sentence (used
 * for one-shot voice previews), this stream keeps one 48 kHz stereo AudioFlinger sink open for the
 * duration of playback so Android TV Audio HALs never drop alternating sentences or trigger HDMI
 * re-lock mutes between consecutive dialogue lines.
 */
internal class PersistentTtsAudioStream(
    private val audioAttributes: AudioAttributes,
    initialVolumePercent: Int,
    private val hasQueuedNext: () -> Boolean
) {
    private data class QueuedPcmClip(
        val pcm48kStereo: ByteArray,
        val generation: Long,
        val onCompleted: () -> Unit,
        val onError: (String) -> Unit
    )

    private val lock = Object()
    private val trackLock = Object()
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var volumePercent: Int = initialVolumePercent.coerceIn(0, 150)

    @Volatile
    private var paused = false

    @Volatile
    private var released = false

    private var pendingClip: QueuedPcmClip? = null
    private var track: AudioTrack? = null
    private var enhancer: LoudnessEnhancer? = null

    private val writerThread = Thread({ writerLoop() }, "TtsPcmStreamWriter").apply {
        isDaemon = true
        start()
    }

    fun submitWav(
        file: File,
        speedRatio: Float,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (released || !file.exists() || file.length() < 44L) return false
        val wavBytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        val pcm48k = TtsWavPcmHelper.decodeWavTo48kStereoPcm16(wavBytes, speedRatio) ?: return false
        if (pcm48k.isEmpty()) return false

        // Pre-verify AudioTrack can initialize on this device before committing away from MediaPlayer fallback.
        if (ensureTrack() == null) return false

        synchronized(lock) {
            if (released) return false
            pendingClip = QueuedPcmClip(
                pcm48kStereo = pcm48k,
                generation = generation.get(),
                onCompleted = onCompleted,
                onError = onError
            )
            lock.notifyAll()
        }
        return true
    }

    fun updateVolumePercent(percent: Int) {
        volumePercent = percent.coerceIn(0, 150)
        synchronized(trackLock) {
            track?.let { applyVolumeToTrack(it, volumePercent) }
        }
    }

    fun setPaused(pausePlayback: Boolean) {
        synchronized(lock) {
            if (released || paused == pausePlayback) return
            paused = pausePlayback
            lock.notifyAll()
        }
        synchronized(trackLock) {
            val activeTrack = track ?: return
            runCatching {
                if (pausePlayback) {
                    if (activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        activeTrack.pause()
                    }
                } else {
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        activeTrack.play()
                    }
                }
            }
        }
    }

    fun flushAndCancel() {
        generation.incrementAndGet()
        synchronized(lock) {
            pendingClip = null
            lock.notifyAll()
        }
        synchronized(trackLock) {
            val activeTrack = track ?: return
            runCatching {
                if (activeTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    activeTrack.pause()
                    activeTrack.flush()
                }
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        synchronized(lock) {
            if (released) return
            released = true
            pendingClip = null
            lock.notifyAll()
        }
        releaseTrackResources()
        runCatching { writerThread.join(250L) }
    }

    private fun ensureTrack(): AudioTrack? {
        synchronized(trackLock) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let { return it }
            releaseTrackLocked()
            val minBuf = AudioTrack.getMinBufferSize(
                TARGET_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            val targetBuf = max(minBuf, (TARGET_RATE / 5) * 4)
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(TARGET_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val built = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(targetBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: return null
            track = built
            applyVolumeToTrack(built, volumePercent)
            return built
        }
    }

    private fun releaseTrackResources() {
        synchronized(trackLock) {
            releaseTrackLocked()
        }
    }

    private fun releaseTrackLocked() {
        runCatching { enhancer?.release() }
        enhancer = null
        val old = track
        track = null
        if (old != null) {
            runCatching {
                if (old.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    old.pause()
                    old.flush()
                    old.stop()
                }
            }
            runCatching { old.release() }
        }
    }

    private fun applyVolumeToTrack(target: AudioTrack, percent: Int) {
        val ratio = (percent / 100.0f).coerceIn(0.0f, 1.5f)
        runCatching { target.setVolume(ratio.coerceAtMost(1.0f)) }
        if (ratio > 1.01f) {
            val gainMb = ((ratio - 1.0f) * 2800.0f).roundToInt().coerceIn(0, 1400)
            runCatching {
                val active = enhancer ?: LoudnessEnhancer(target.audioSessionId).also { enhancer = it }
                active.setTargetGain(gainMb)
                active.enabled = true
            }
        } else {
            runCatching { enhancer?.enabled = false }
        }
    }

    private fun writerLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val silenceChunk = ByteArray(WRITE_CHUNK_BYTES)
        while (true) {
            val clip: QueuedPcmClip? = synchronized(lock) {
                while (!released && (paused || pendingClip == null)) {
                    lock.wait(500L)
                }
                if (released) null else pendingClip?.also { pendingClip = null }
            }
            if (clip == null) break
            if (clip.generation != generation.get()) continue

            playClipOnTrack(clip, silenceChunk)
        }
    }

    private fun playClipOnTrack(clip: QueuedPcmClip, silenceChunk: ByteArray) {
        var activeTrack: AudioTrack = ensureTrack() ?: run {
            clip.onError("AudioTrack 48k stereo init failed")
            return
        }
        runCatching {
            if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                activeTrack.play()
            }
        }.onFailure {
            clip.onError(it.message ?: it.javaClass.simpleName)
            return
        }

        val data = clip.pcm48kStereo
        var offset = 0
        var retriedDead = false
        while (offset < data.size) {
            if (released || clip.generation != generation.get()) return
            if (paused) {
                synchronized(lock) {
                    while (!released && paused && clip.generation == generation.get()) {
                        lock.wait(250L)
                    }
                }
                if (released || clip.generation != generation.get()) return
                activeTrack = ensureTrack() ?: return
                runCatching {
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        activeTrack.play()
                    }
                }
            }

            val len = min(WRITE_CHUNK_BYTES, data.size - offset)
            val wrote = try {
                activeTrack.write(data, offset, len, AudioTrack.WRITE_BLOCKING)
            } catch (t: Throwable) {
                clip.onError(t.message ?: t.javaClass.simpleName)
                return
            }
            if (wrote < 0) {
                if (!retriedDead && !released && clip.generation == generation.get()) {
                    retriedDead = true
                    releaseTrackResources()
                    val rebuilt = ensureTrack()
                    if (rebuilt != null) {
                        activeTrack = rebuilt
                        runCatching { activeTrack.play() }
                        continue
                    }
                }
                clip.onError("AudioTrack write error=$wrote")
                return
            }
            if (wrote == 0) {
                Thread.sleep(8L)
            } else {
                offset += wrote
            }
        }

        if (released || clip.generation != generation.get()) return

        // Write a crisp 48ms silence separator before waking the next clip.
        writeSilenceBytes(activeTrack, silenceChunk, INTER_CLIP_SILENCE_BYTES, clip.generation, interruptOnPending = false)

        // Dispatch completion on owner queue BEFORE writing the longer HDMI drain tail if idle.
        clip.onCompleted()

        if (released || clip.generation != generation.get()) return
        val moreSoon = synchronized(lock) {
            if (pendingClip == null && !hasQueuedNext() && !released && !paused) {
                lock.wait(30L)
            }
            pendingClip != null || hasQueuedNext()
        }
        if (!moreSoon && !released && !paused && clip.generation == generation.get()) {
            writeSilenceBytes(activeTrack, silenceChunk, IDLE_TAIL_SILENCE_BYTES, clip.generation, interruptOnPending = true)
        }
    }

    private fun writeSilenceBytes(
        activeTrack: AudioTrack,
        zeros: ByteArray,
        totalBytes: Int,
        clipGen: Long,
        interruptOnPending: Boolean
    ) {
        var remaining = totalBytes
        while (remaining > 0 && !released && !paused && clipGen == generation.get()) {
            if (interruptOnPending) {
                val hasNext = synchronized(lock) { pendingClip != null } || hasQueuedNext()
                if (hasNext) break
            }
            val n = min(zeros.size, remaining)
            val w = runCatching { activeTrack.write(zeros, 0, n, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1)
            if (w <= 0) break
            remaining -= w
        }
    }

    private companion object {
        const val TARGET_RATE = 48_000
        const val WRITE_CHUNK_BYTES = 4_096
        const val INTER_CLIP_SILENCE_BYTES = 9_216 // ~48ms @ 48kHz stereo
        const val IDLE_TAIL_SILENCE_BYTES = 46_080 // ~240ms @ 48kHz stereo
    }
}
