package com.nuvio.tv.core.tts

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class PitchSpanMeasurement(
    val startMs: Long,
    val endMs: Long,
    val gender: SpeakerGender,
    val f0Hz: Float
)

/**
 * Real-time speaker gender detector from decoded movie PCM (`Lan6` + `Lxl2.b` + `Ld54` parity).
 * Applies stereo Mid/Side BGM suppression, 8-tap Hann-windowed sinc resampling to 16 kHz,
 * YIN F0 estimation (88..320 Hz), 512-pt Radix-2 FFT spectral centroid disambiguation (108..163 Hz),
 * 2500 ms hysteresis, and a 128-entry timeline buffer for lookahead cues.
 */
class VoicePitchAnalyzer {
    private val lock = Any()

    // 3.0 seconds circular buffer at 16 kHz
    private val ringSize = 48_000
    private val ringSamples = FloatArray(ringSize)
    private var ringWritePos = 0
    private var totalWritten16k = 0L
    private var latestMediaPosMs = 0L

    private val timeline = ArrayDeque<PitchSpanMeasurement>(MAX_TIMELINE_ENTRIES + 4)

    @Volatile
    var currentGender: SpeakerGender = SpeakerGender.UNSPECIFIED
        private set

    private var lastGenderSwitchMs = -HYSTERESIS_MS
    private var lastAnalyzeAtTotal16k = 0L

    // Scratch buffers reused during estimation to avoid GC churn
    private val scratchAnalysis = FloatArray(ANALYSIS_WINDOW_16K)
    private val diffBuf = FloatArray(LAG_MAX + 1)
    private val cmndfBuf = FloatArray(LAG_MAX + 1)
    private val fftRe = DoubleArray(FFT_SIZE)
    private val fftIm = DoubleArray(FFT_SIZE)
    private val hann512 = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    fun reset() {
        synchronized(lock) {
            ringSamples.fill(0f)
            ringWritePos = 0
            totalWritten16k = 0L
            latestMediaPosMs = 0L
            lastAnalyzeAtTotal16k = 0L
            timeline.clear()
            currentGender = SpeakerGender.UNSPECIFIED
            lastGenderSwitchMs = -HYSTERESIS_MS
        }
    }

    /**
     * Ingests a decoded PCM buffer on the audio thread.
     * Kept lock-brief and allocation-free on hot paths to prevent ExoPlayer underruns.
     */
    fun ingestPcm(
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        encoding: Int,
        mediaPositionMs: Long
    ) {
        if (sampleRate <= 0 || channels <= 0) return
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return

        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frameCount = buffer.remaining() / bytesPerFrame
        if (frameCount <= 0) return

        val work = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(frameCount)

        if (encoding == C.ENCODING_PCM_16BIT) {
            when (channels) {
                1 -> {
                    for (i in 0 until frameCount) {
                        val s = work.short / 32768.0f
                        mono[i] = (s * INPUT_PREGAIN).coerceIn(-1f, 1f)
                    }
                }
                2 -> {
                    for (i in 0 until frameCount) {
                        val l = work.short / 32768.0f
                        val r = work.short / 32768.0f
                        mono[i] = midSideSpeechExtract(l, r)
                    }
                }
                else -> {
                    for (i in 0 until frameCount) {
                        val l = work.short / 32768.0f
                        val r = work.short / 32768.0f
                        val c = work.short / 32768.0f
                        for (ch in 3 until channels) work.short
                        val mix = if (abs(c) > 1e-4f) 0.35f * (l + r) + 0.65f * c else midSideSpeechExtract(l, r)
                        mono[i] = (mix * INPUT_PREGAIN).coerceIn(-1f, 1f)
                    }
                }
            }
        } else {
            when (channels) {
                1 -> {
                    for (i in 0 until frameCount) {
                        mono[i] = (work.float * INPUT_PREGAIN).coerceIn(-1f, 1f)
                    }
                }
                2 -> {
                    for (i in 0 until frameCount) {
                        val l = work.float
                        val r = work.float
                        mono[i] = midSideSpeechExtract(l, r)
                    }
                }
                else -> {
                    for (i in 0 until frameCount) {
                        val l = work.float
                        val r = work.float
                        val c = work.float
                        for (ch in 3 until channels) work.float
                        val mix = if (abs(c) > 1e-4f) 0.35f * (l + r) + 0.65f * c else midSideSpeechExtract(l, r)
                        mono[i] = (mix * INPUT_PREGAIN).coerceIn(-1f, 1f)
                    }
                }
            }
        }

        val resampled = downsampleTo16k(mono, sampleRate)
        if (resampled.isEmpty()) return

        val chunkDurationMs = (frameCount * 1000L) / sampleRate
        synchronized(lock) {
            latestMediaPosMs = mediaPositionMs + chunkDurationMs
            for (sample in resampled) {
                ringSamples[ringWritePos] = sample
                ringWritePos = (ringWritePos + 1) % ringSize
                totalWritten16k++
            }
        }
    }

    /**
     * Evaluates recent PCM history (throttled ~120ms) and updates [currentGender] & [timeline].
     */
    fun refreshEstimation(): SpeakerGender {
        val snapshotLen: Int
        val endPosMs: Long
        synchronized(lock) {
            if (totalWritten16k - lastAnalyzeAtTotal16k < MIN_ANALYZE_HOP_16K) {
                return currentGender
            }
            val avail = min(totalWritten16k, ANALYSIS_WINDOW_16K.toLong()).toInt()
            if (avail < FRAME_SIZE * 2) return currentGender

            snapshotLen = avail
            endPosMs = latestMediaPosMs
            lastAnalyzeAtTotal16k = totalWritten16k

            val startIdx = (ringWritePos - avail + ringSize) % ringSize
            val firstPart = min(avail, ringSize - startIdx)
            System.arraycopy(ringSamples, startIdx, scratchAnalysis, 0, firstPart)
            if (firstPart < avail) {
                System.arraycopy(ringSamples, 0, scratchAnalysis, firstPart, avail - firstPart)
            }
        }

        val detected = detectGenderFromWindow(scratchAnalysis, snapshotLen) ?: return currentGender
        val (gender, medianF0) = detected

        synchronized(lock) {
            val windowDurationMs = (snapshotLen * 1000L) / TARGET_SAMPLE_RATE
            val startMs = max(0L, endPosMs - windowDurationMs)
            val prev = currentGender

            if (prev == SpeakerGender.UNSPECIFIED ||
                prev == gender ||
                (endPosMs - lastGenderSwitchMs) >= HYSTERESIS_MS
            ) {
                if (prev != gender) {
                    lastGenderSwitchMs = endPosMs
                }
                currentGender = gender
            }

            if (timeline.size >= MAX_TIMELINE_ENTRIES) {
                timeline.removeFirst()
            }
            timeline.addLast(
                PitchSpanMeasurement(
                    startMs = startMs,
                    endMs = endPosMs,
                    gender = gender,
                    f0Hz = medianF0
                )
            )
            return currentGender
        }
    }

    fun estimateGenderForSpan(startMs: Long, endMs: Long): SpeakerGender {
        refreshEstimation()
        synchronized(lock) {
            if (timeline.isEmpty()) return currentGender
            var maleScore = 0L
            var femaleScore = 0L
            for (entry in timeline) {
                val overlapStart = max(startMs - 400L, entry.startMs)
                val overlapEnd = min(endMs + 200L, entry.endMs)
                val overlap = overlapEnd - overlapStart
                if (overlap > 0L) {
                    when (entry.gender) {
                        SpeakerGender.MALE -> maleScore += overlap
                        SpeakerGender.FEMALE -> femaleScore += overlap
                        SpeakerGender.UNSPECIFIED -> {}
                    }
                }
            }
            return when {
                maleScore > femaleScore && maleScore >= 120L -> SpeakerGender.MALE
                femaleScore > maleScore && femaleScore >= 120L -> SpeakerGender.FEMALE
                else -> currentGender
            }
        }
    }

    private fun midSideSpeechExtract(left: Float, right: Float): Float {
        val mid = 0.5f * (left + right)
        val side = 0.5f * abs(left - right)
        val absMid = abs(mid)
        val attenuated = if (side > absMid && absMid + side > 1e-5f) {
            val factor = (absMid / (absMid + 0.55f * side)).coerceIn(0.45f, 1.0f)
            mid * factor
        } else {
            mid
        }
        return (attenuated * INPUT_PREGAIN).coerceIn(-1f, 1f)
    }

    private fun downsampleTo16k(input: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == TARGET_SAMPLE_RATE) return input
        if (srcRate == 32_000) {
            val outLen = input.size / 2
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val idx = i * 2
                out[i] = 0.5f * (input[idx] + input[idx + 1])
            }
            return out
        }
        if (srcRate == 48_000) {
            val outLen = input.size / 3
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val idx = i * 3
                out[i] = 0.25f * input[idx] + 0.5f * input[idx + 1] + 0.25f * input[idx + 2]
            }
            return out
        }
        // General 8-tap Hann-windowed sinc resampler (`Lxl2.b` parity)
        val ratio = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val outLen = (input.size / ratio).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val cutoff = min(1.0, TARGET_SAMPLE_RATE.toDouble() / srcRate.toDouble())
        val tapsHalf = 4

        for (i in 0 until outLen) {
            val center = i * ratio
            val centerInt = center.toInt()
            var acc = 0.0
            var wSum = 0.0
            for (k in (centerInt - tapsHalf + 1)..(centerInt + tapsHalf)) {
                if (k in input.indices) {
                    val x = k - center
                    val sinc = if (abs(x) < 1e-6) {
                        1.0
                    } else {
                        val px = PI * x * cutoff
                        sin(px) / px
                    }
                    val hann = 0.5 * (1.0 + cos(PI * x / tapsHalf))
                    val w = sinc * hann
                    acc += input[k] * w
                    wSum += w
                }
            }
            out[i] = if (wSum > 1e-6) (acc / wSum).toFloat() else input[centerInt.coerceIn(input.indices)]
        }
        return out
    }

    private fun detectGenderFromWindow(window: FloatArray, len: Int): Pair<SpeakerGender, Float>? {
        val f0Samples = ArrayList<Float>(16)
        val frameOffsets = ArrayList<Int>(16)

        var frameStart = 0
        var frameCount = 0
        while (frameStart + FRAME_SIZE <= len && frameCount < MAX_FRAMES_PER_WINDOW) {
            val f0 = estimateFrameYinF0(window, frameStart)
            if (f0 != null) {
                f0Samples.add(f0)
                frameOffsets.add(frameStart)
            }
            frameStart += FRAME_HOP
            frameCount++
        }

        if (f0Samples.size < 3) return null

        val sorted = f0Samples.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        val iqr = max(12f, q3 - q1)
        val filteredF0 = sorted.filter { it >= q1 - 1.5f * iqr && it <= q3 + 1.5f * iqr }
        val usable = if (filteredF0.isNotEmpty()) filteredF0 else sorted
        val medianF0 = usable[usable.size / 2]

        val gender = when {
            medianF0 >= 166.0f -> SpeakerGender.FEMALE
            medianF0 <= 160.0f && medianF0 >= 108.0f -> {
                // Ambiguous male/female overlap (108..163 Hz) -> check FFT spectral centroid
                disambiguateWithSpectralCentroid(window, frameOffsets, medianF0)
            }
            medianF0 < 108.0f -> SpeakerGender.MALE
            medianF0 > 160.0f -> {
                disambiguateWithSpectralCentroid(window, frameOffsets, medianF0)
            }
            else -> if (medianF0 >= 164.0f) SpeakerGender.FEMALE else SpeakerGender.MALE
        }

        return gender to medianF0
    }

    private fun estimateFrameYinF0(samples: FloatArray, offset: Int): Float? {
        // Compute frame RMS energy to reject silence/noise frames
        var energy = 0.0
        for (i in 0 until FRAME_SIZE) {
            val s = samples[offset + i].toDouble()
            energy += s * s
        }
        if (sqrt(energy / FRAME_SIZE) < 0.012) return null

        // Difference function
        diffBuf[0] = 0f
        for (tau in 1..LAG_MAX) {
            var sum = 0.0
            for (j in 0 until (FRAME_SIZE - LAG_MAX)) {
                val d = samples[offset + j] - samples[offset + j + tau]
                sum += d * d
            }
            diffBuf[tau] = sum.toFloat()
        }

        // Cumulative mean normalized difference function (CMNDF)
        cmndfBuf[0] = 1f
        var runningSum = 0.0f
        var minCmn = 1.0f
        for (tau in 1..LAG_MAX) {
            runningSum += diffBuf[tau]
            val valCmn = if (runningSum > 1e-8f) diffBuf[tau] * tau / runningSum else 1f
            cmndfBuf[tau] = valCmn
            if (tau >= LAG_MIN && valCmn < minCmn) {
                minCmn = valCmn
            }
        }

        var chosenTau = -1
        for (tau in LAG_MIN..LAG_MAX) {
            val threshold = if (tau <= 96) min(0.23f, minCmn + 0.1f) else 0.16f
            if (cmndfBuf[tau] < threshold) {
                var t = tau
                while (t + 1 <= LAG_MAX && cmndfBuf[t + 1] < cmndfBuf[t]) {
                    t++
                }
                chosenTau = t
                break
            }
        }
        if (chosenTau < 0) return null

        // Parabolic interpolation around chosenTau
        val betterTau = if (chosenTau in (LAG_MIN + 1) until LAG_MAX) {
            val s0 = cmndfBuf[chosenTau - 1]
            val s1 = cmndfBuf[chosenTau]
            val s2 = cmndfBuf[chosenTau + 1]
            val denom = 2.0f * (2.0f * s1 - s2 - s0)
            if (abs(denom) > 1e-6f) chosenTau + (s2 - s0) / denom else chosenTau.toFloat()
        } else {
            chosenTau.toFloat()
        }

        val f0 = TARGET_SAMPLE_RATE / betterTau
        return if (f0 in 85f..330f) f0 else null
    }

    private fun disambiguateWithSpectralCentroid(
        window: FloatArray,
        voicedOffsets: List<Int>,
        medianF0: Float
    ): SpeakerGender {
        if (voicedOffsets.isEmpty()) {
            return if (medianF0 >= 164f) SpeakerGender.FEMALE else SpeakerGender.MALE
        }
        val offset = voicedOffsets[voicedOffsets.size / 2]
        if (offset + FFT_SIZE > window.size) {
            return if (medianF0 >= 164f) SpeakerGender.FEMALE else SpeakerGender.MALE
        }

        for (i in 0 until FFT_SIZE) {
            fftRe[i] = (window[offset + i] * hann512[i]).toDouble()
            fftIm[i] = 0.0
        }
        fft512(fftRe, fftIm)

        var weightedFreq = 0.0
        var totalMag = 0.0
        var lowEnergy = 0.0
        var highEnergy = 0.0
        val binHz = TARGET_SAMPLE_RATE.toDouble() / FFT_SIZE.toDouble()

        for (bin in 1 until FFT_SIZE / 2) {
            val freq = bin * binHz
            if (freq < 200.0 || freq > 4500.0) continue
            val mag = sqrt(fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin])
            weightedFreq += freq * mag
            totalMag += mag
            if (freq < 1000.0) lowEnergy += mag else highEnergy += mag
        }

        if (totalMag <= 1e-6) {
            return if (medianF0 >= 164f) SpeakerGender.FEMALE else SpeakerGender.MALE
        }

        val centroid = weightedFreq / totalMag
        val hiLowRatio = if (lowEnergy > 1e-6) highEnergy / lowEnergy else 0.0

        return when {
            centroid >= 1650.0 && hiLowRatio >= 0.62 -> SpeakerGender.FEMALE
            centroid >= 1520.0 && hiLowRatio >= 0.52 && medianF0 >= 145f -> SpeakerGender.FEMALE
            medianF0 >= 164f -> SpeakerGender.FEMALE
            else -> SpeakerGender.MALE
        }
    }

    private fun fft512(re: DoubleArray, im: DoubleArray) {
        val n = FFT_SIZE
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angleStep = -2.0 * PI / len
            for (start in 0 until n step len) {
                for (k in 0 until half) {
                    val angle = k * angleStep
                    val wr = cos(angle)
                    val wi = sin(angle)
                    val idx1 = start + k
                    val idx2 = idx1 + half
                    val tr = wr * re[idx2] - wi * im[idx2]
                    val ti = wr * im[idx2] + wi * re[idx2]
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                }
            }
            len = len shl 1
        }
    }

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val FRAME_SIZE = 512
        private const val FRAME_HOP = 256
        private const val FFT_SIZE = 512
        private const val LAG_MIN = 50   // 320 Hz
        private const val LAG_MAX = 181  // 88 Hz
        private const val ANALYSIS_WINDOW_16K = 16_000 // 1.0 s window
        private const val MIN_ANALYZE_HOP_16K = 1_920  // 120 ms throttle
        private const val MAX_FRAMES_PER_WINDOW = 64
        private const val MAX_TIMELINE_ENTRIES = 128
        private const val HYSTERESIS_MS = 2500L
        private const val INPUT_PREGAIN = 1.35f
    }
}
