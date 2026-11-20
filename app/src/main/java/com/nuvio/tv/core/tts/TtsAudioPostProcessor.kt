package com.nuvio.tv.core.tts

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * TTS PCM post-processing (`Ll26.e` silence trim + `Lqo4.t` normalization, soft-clipping & WAV export).
 */
object TtsAudioPostProcessor {
    private const val SILENCE_THRESHOLD = 0.0052f
    private const val TARGET_PEAK = 0.85f
    private const val MAX_NORM_GAIN = 3.2f

    /**
     * Trims leading/trailing silence beyond `0.0052f` average amplitude in 10ms windows,
     * preserving 45ms padding and applying 12ms half-cosine fade in/out.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples

        val windowSize = max((sampleRate * 10) / 1000, 64)
        if (samples.size <= windowSize * 2) return samples

        var firstActiveIdx = -1
        var lastActiveIdx = -1

        var pos = 0
        while (pos < samples.size) {
            val end = min(pos + windowSize, samples.size)
            var absSum = 0.0f
            for (i in pos until end) {
                absSum += abs(samples[i])
            }
            val meanAbs = absSum / (end - pos).toFloat()
            if (meanAbs > SILENCE_THRESHOLD) {
                if (firstActiveIdx < 0) firstActiveIdx = pos
                lastActiveIdx = end
            }
            pos = end
        }

        if (firstActiveIdx < 0 || lastActiveIdx <= firstActiveIdx) {
            return samples
        }

        val padSamples = (sampleRate * 45) / 1000
        val start = max(0, firstActiveIdx - padSamples)
        val end = min(samples.size, lastActiveIdx + padSamples)
        val trimmed = samples.copyOfRange(start, end)

        val fadeSamples = min((sampleRate * 12) / 1000, trimmed.size / 3)
        if (fadeSamples > 1) {
            for (i in 0 until fadeSamples) {
                val fadeIn = 0.5f * (1.0f - cos(PI * i / fadeSamples).toFloat())
                trimmed[i] *= fadeIn
                val tailIdx = trimmed.lastIndex - i
                trimmed[tailIdx] *= fadeIn
            }
        }

        return trimmed
    }

    /**
     * Applies peak normalization (up to 3.2x -> 0.85 peak), volume boost above 100%,
     * hyperbolic soft-clipping when gain > 1.02x, and writes a 16-bit LE mono WAV file atomically.
     */
    fun writeNormalizedWav(
        samples: FloatArray,
        sampleRate: Int,
        ttsVolumePercent: Int,
        outFile: File
    ): Boolean {
        if (samples.isEmpty()) return false

        var peak = 0.0f
        for (sample in samples) {
            val a = abs(sample)
            if (a > peak) peak = a
        }

        val normGain = if (peak in 0.005f..TARGET_PEAK) {
            min(TARGET_PEAK / peak, MAX_NORM_GAIN)
        } else {
            1.0f
        }

        val extraOver100 = (ttsVolumePercent.coerceIn(0, 200) - 100).coerceAtLeast(0)
        val boostGain = 1.0f + (extraOver100 / 50.0f) * 1.35f
        val totalGain = normGain * boostGain
        val useSoftClip = totalGain > 1.02f

        val pcmDataSize = samples.size * 2
        val header = buildWavHeader(sampleRate = sampleRate, dataByteCount = pcmDataSize)
        val pcmBytes = ByteArray(pcmDataSize)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (s in samples) {
            val scaled = s * totalGain
            val limited = if (useSoftClip) {
                (0.98 * tanh(0.92 * scaled.toDouble())).toFloat()
            } else {
                scaled.coerceIn(-1.0f, 1.0f)
            }
            val pcm16 = (limited * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            bb.putShort(pcm16)
        }

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(header)
                fos.write(pcmBytes)
                fos.fd.sync()
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                return false
            }
            true
        } catch (_: Throwable) {
            tmpFile.delete()
            false
        }
    }

    private fun buildWavHeader(sampleRate: Int, dataByteCount: Int): ByteArray {
        val totalDataLen = dataByteCount + 36
        val byteRate = sampleRate * 2 // mono 16-bit
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1.toShort()) // PCM format
            putShort(1.toShort()) // mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2.toShort()) // block align
            putShort(16.toShort()) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataByteCount)
        }.array()
    }
}
