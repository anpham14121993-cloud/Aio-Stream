package com.nuvio.tv.core.tts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin WSOLA (Waveform Similarity Overlap-Add) time-stretcher (`Lz26.f` parity).
 * Preserves vocal formant pitch while adapting speech duration to fit subtitle windows.
 */
object WsolaTimeStretcher {
    fun timeStretch(
        samples: FloatArray,
        sampleRate: Int,
        speedRatio: Float
    ): FloatArray {
        if (samples.isEmpty() || abs(speedRatio - 1.0f) < 0.015f) {
            return samples
        }

        val speed = speedRatio.coerceIn(0.65f, 2.0f)
        val winSize = max((sampleRate * 24) / 1000, 128).let { if (it % 2 == 0) it else it + 1 }
        val halfWin = winSize / 2
        val searchRadius = max((sampleRate * 8) / 1000, 32)

        if (samples.size < winSize + halfWin) {
            return samples
        }

        val expectedOutLen = ((samples.size - winSize) / speed).roundToInt() + winSize
        val out = FloatArray(max(expectedOutLen + winSize, winSize * 2))

        // Copy initial half-window cleanly
        System.arraycopy(samples, 0, out, 0, min(halfWin, samples.size))

        var outPos = 0
        var inExpected = 0.0
        val anaHop = halfWin * speed.toDouble()
        val corrStride = if (sampleRate >= 44100) 4 else 2

        while (true) {
            val naturalIdx = (inExpected + anaHop).roundToInt()
            if (naturalIdx + halfWin > samples.size) break

            val tailStart = outPos + halfWin
            if (tailStart + winSize >= out.size) break

            // Search around naturalIdx for best waveform alignment with previous overlap tail
            val prevOverlapStart = naturalIdx - (anaHop.roundToInt()) + halfWin
            val searchStart = max(0, naturalIdx - searchRadius)
            val searchEnd = min(samples.size - winSize, naturalIdx + searchRadius)

            var bestIdx = naturalIdx.coerceIn(0, samples.size - winSize)
            if (prevOverlapStart in 0..(samples.size - halfWin) && searchStart <= searchEnd) {
                var bestCorr = -1.0
                for (cand in searchStart..searchEnd) {
                    val corr = normalizedCorrelation(
                        bufA = out,
                        offA = tailStart,
                        bufB = samples,
                        offB = cand,
                        len = halfWin,
                        stride = corrStride
                    )
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestIdx = cand
                    }
                }
            }

            // Linear overlap-add crossfade on first half, direct copy on second half
            for (i in 0 until halfWin) {
                val fadeIn = i.toFloat() / halfWin.toFloat()
                val fadeOut = 1.0f - fadeIn
                val existing = out[tailStart + i]
                val incoming = samples[bestIdx + i]
                out[tailStart + i] = existing * fadeOut + incoming * fadeIn
            }
            System.arraycopy(samples, bestIdx + halfWin, out, tailStart + halfWin, halfWin)

            outPos += halfWin
            inExpected += anaHop
        }

        val validLength = (outPos + winSize).coerceIn(1, out.size)
        return out.copyOf(validLength)
    }

    private fun normalizedCorrelation(
        bufA: FloatArray,
        offA: Int,
        bufB: FloatArray,
        offB: Int,
        len: Int,
        stride: Int
    ): Double {
        var dot = 0.0
        var energyA = 0.0
        var energyB = 0.0
        var i = 0
        while (i < len) {
            val a = bufA[offA + i].toDouble()
            val b = bufB[offB + i].toDouble()
            dot += a * b
            energyA += a * a
            energyB += b * b
            i += stride
        }
        val denom = sqrt(energyA * energyB)
        return if (denom > 1e-9) dot / denom else 0.0
    }
}
