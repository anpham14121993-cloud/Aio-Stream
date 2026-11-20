package com.nuvio.tv.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class WsolaTimeStretcherTest {

    @Test
    fun `wsola compresses and expands audio duration accurately`() {
        val sr = 22050
        val seconds = 2.0
        val count = (sr * seconds).toInt()
        val input = FloatArray(count) { i ->
            (0.4 * sin(2.0 * PI * 220.0 * i / sr)).toFloat()
        }

        val faster = WsolaTimeStretcher.timeStretch(input, sr, 1.5f)
        val expectedFast = count / 1.5f
        assertTrue(
            "Expected compressed length ~${expectedFast}, got ${faster.size}",
            faster.size in (expectedFast * 0.85f).toInt()..(expectedFast * 1.15f).toInt()
        )
        assertTrue("Output should have non-zero energy", faster.any { kotlin.math.abs(it) > 0.1f })
        assertTrue("Output must not contain NaN", faster.none { it.isNaN() })

        val slower = WsolaTimeStretcher.timeStretch(input, sr, 0.8f)
        val expectedSlow = count / 0.8f
        assertTrue(
            "Expected expanded length ~${expectedSlow}, got ${slower.size}",
            slower.size in (expectedSlow * 0.85f).toInt()..(expectedSlow * 1.15f).toInt()
        )
    }

    @Test
    fun `trimSilence removes dead margins while retaining padding`() {
        val sr = 22050
        val leadingSilence = sr / 2 // 500 ms silence
        val speechSamples = sr / 2  // 500 ms tone
        val trailingSilence = sr / 2 // 500 ms silence
        val total = leadingSilence + speechSamples + trailingSilence
        val samples = FloatArray(total)
        for (i in 0 until speechSamples) {
            samples[leadingSilence + i] = (0.35 * sin(2.0 * PI * 300.0 * i / sr)).toFloat()
        }

        val trimmed = TtsAudioPostProcessor.trimSilence(samples, sr)
        // Expected: ~500 ms speech + ~90 ms pad = ~590 ms (~13000 samples), much shorter than 33075
        assertTrue("Trimmed length ${trimmed.size} should be substantially shorter than $total", trimmed.size < (total * 0.65))
        assertTrue("Trimmed audio should preserve active speech energy", trimmed.maxOf { kotlin.math.abs(it) } > 0.25f)
    }

    @Test
    fun `writeNormalizedWav writes valid RIFF WAVE 16bit mono header`() {
        val sr = 22050
        val samples = FloatArray(4000) { i ->
            (0.2 * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
        }
        val tmpFile = File.createTempFile("tts_wav_test", ".wav")
        try {
            val ok = TtsAudioPostProcessor.writeNormalizedWav(samples, sr, 120, tmpFile)
            assertTrue(ok)
            val bytes = tmpFile.readBytes()
            assertEquals(44 + samples.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1.toShort(), bb.getShort(20)) // PCM
            assertEquals(1.toShort(), bb.getShort(22)) // Mono
            assertEquals(sr, bb.getInt(24))
        } finally {
            tmpFile.delete()
        }
    }
}
