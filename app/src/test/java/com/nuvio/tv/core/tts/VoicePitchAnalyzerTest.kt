package com.nuvio.tv.core.tts

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class VoicePitchAnalyzerTest {

    @Test
    fun `classifies 115 Hz male voice and 220 Hz female voice cleanly`() {
        val analyzer = VoicePitchAnalyzer()
        val malePcm = buildStereoHarmonicPcm16(
            sampleRate = 48_000,
            durationSec = 1.1,
            f0Hz = 115.0,
            bgmFreqHz = 55.0
        )
        analyzer.ingestPcm(
            buffer = malePcm,
            sampleRate = 48_000,
            channels = 2,
            encoding = C.ENCODING_PCM_16BIT,
            mediaPositionMs = 1_000L
        )
        assertEquals(SpeakerGender.MALE, analyzer.estimateGenderForSpan(1_000L, 2_100L))

        // After hysteresis expires (> 2500ms gap), female voice should flip to FEMALE
        val femalePcm = buildStereoHarmonicPcm16(
            sampleRate = 48_000,
            durationSec = 1.1,
            f0Hz = 225.0,
            bgmFreqHz = 55.0
        )
        analyzer.ingestPcm(
            buffer = femalePcm,
            sampleRate = 48_000,
            channels = 2,
            encoding = C.ENCODING_PCM_16BIT,
            mediaPositionMs = 6_000L
        )
        assertEquals(SpeakerGender.FEMALE, analyzer.estimateGenderForSpan(6_000L, 7_100L))
    }

    private fun buildStereoHarmonicPcm16(
        sampleRate: Int,
        durationSec: Double,
        f0Hz: Double,
        bgmFreqHz: Double
    ): ByteBuffer {
        val frames = (sampleRate * durationSec).toInt()
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate.toDouble()
            // Speech harmonics (center/mid) + out-of-phase background music (side)
            val voice = 0.45 * sin(2.0 * PI * f0Hz * t) +
                0.20 * sin(2.0 * PI * (f0Hz * 2.0) * t) +
                0.10 * sin(2.0 * PI * (f0Hz * 3.0) * t)
            val sideBgm = 0.12 * sin(2.0 * PI * bgmFreqHz * t)
            val left = (voice + sideBgm).coerceIn(-0.99, 0.99)
            val right = (voice - sideBgm).coerceIn(-0.99, 0.99)
            buf.putShort((left * 32767.0).toInt().toShort())
            buf.putShort((right * 32767.0).toInt().toShort())
        }
        buf.flip()
        return buf
    }
}
