package com.nuvio.tv.core.tts

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Lossless Media3 [AudioProcessor] tap that forwards decoded PCM frames to [VoicePitchAnalyzer]
 * when dual-voice gender detection is active, passing all audio through unmodified.
 */
@UnstableApi
class TtsAudioTapProcessor(
    private val pitchAnalyzer: VoicePitchAnalyzer,
    private val currentPositionMsProvider: () -> Long
) : BaseAudioProcessor() {

    @Volatile
    var isTapActive: Boolean = false

    override fun isActive(): Boolean = super.isActive() && isTapActive

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        if (isTapActive) {
            runCatching {
                val tapSlice = inputBuffer.asReadOnlyBuffer()
                pitchAnalyzer.ingestPcm(
                    buffer = tapSlice,
                    sampleRate = inputAudioFormat.sampleRate,
                    channels = inputAudioFormat.channelCount,
                    encoding = inputAudioFormat.encoding,
                    mediaPositionMs = currentPositionMsProvider()
                )
            }
        }

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        // Keep timeline only on explicit seek/reset via coordinator
    }
}
