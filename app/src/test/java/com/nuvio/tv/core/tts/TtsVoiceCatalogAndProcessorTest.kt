package com.nuvio.tv.core.tts

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsVoiceCatalogAndProcessorTest {

    @Test
    fun allPiperVoicesHaveBundledSamplePaths() {
        assertEquals(17, TtsVoiceCatalog.piperVoices.size)
        for (voice in TtsVoiceCatalog.piperVoices) {
            val assetPath = TtsVoiceCatalog.samplePreviewAssetPath(voice.id)
            assertTrue(assetPath.startsWith("tts_samples/"))
            assertTrue(assetPath.endsWith(".m4a"))
            assertTrue(TtsVoiceCatalog.onnxDownloadUrl(voice.id).endsWith("${voice.id}.onnx"))
        }
    }

    @Test
    fun remoteSampleMirrorsResolveWithoutCrash() {
        assertNotNull(TtsVoiceCatalog.samplePreviewRemoteUrl("ngoc_huyen_new"))
        assertNotNull(TtsVoiceCatalog.samplePreviewRemoteUrl("ban_mai"))
        assertNotNull(TtsVoiceCatalog.samplePreviewRemoteUrl("ngoc_ngan"))
        assertNull(TtsVoiceCatalog.samplePreviewRemoteUrl("google_online"))
    }

    @Test
    fun audioTapProcessorIsInactiveUntilDualVoiceTapEnabled() {
        val processor = TtsAudioTapProcessor(VoicePitchAnalyzer()) { 0L }
        assertFalse(processor.isTapActive)
        assertFalse(processor.isActive())
        processor.isTapActive = true
        // Without onConfigure having set a valid AudioFormat, BaseAudioProcessor.isActive() remains false.
        assertFalse(processor.isActive())
    }

    @Test
    fun assDialogueParserExtractsMkvBlockAndExternalScriptCues() {
        val rawBlock = "1,0,Default,,0,0,0,,{\\pos(100,200)\\i1}Xin chào{\\i0}\\Nbạn khỏe không?"
        assertEquals("Xin chào bạn khỏe không?", AssDialogueParser.parseMatroskaDialogueText(rawBlock))

        val script = """
            [Script Info]
            Title: Test
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.00,0:00:08.50,Default,,0,0,0,,Dòng một\NDòng hai
            Dialogue: 0,0:00:05.05,0:00:09.00,Default,,0,0,0,,{\b1}Ghi chú{\b0}
            Dialogue: 0,0:00:10.00,0:00:12.25,Default,,0,0,0,,Câu tiếp theo
        """.trimIndent()
        val parsed = AssDialogueParser.parseAssScript(script)
        assertEquals(2, parsed.size)
        assertEquals(5000L, parsed[0].startTimeMs)
        assertEquals(9000L, parsed[0].endTimeMs)
        assertEquals("Dòng một Dòng hai\nGhi chú", parsed[0].text)
        assertEquals(10000L, parsed[1].startTimeMs)
        assertEquals(12250L, parsed[1].endTimeMs)
        assertEquals("Câu tiếp theo", parsed[1].text)
    }

    @Test
    fun mergeSimultaneousCuesCombinesOverlappingLines() {
        val cues = listOf(
            SubtitleSyncCue(1000L, 2500L, "Câu 1"),
            SubtitleSyncCue(1050L, 3000L, "Câu 2"),
            SubtitleSyncCue(5000L, 6000L, "Câu 3")
        )
        val merged = AssDialogueParser.mergeSimultaneousCues(cues)
        assertEquals(2, merged.size)
        assertEquals("Câu 1\nCâu 2", merged[0].text)
        assertEquals(3000L, merged[0].endTimeMs)
    }

    @Test
    fun androidIcuSafeRegexesAndNormalizationWork() {
        // Reflectively inspect all Regex patterns on TTS singletons so an accidental unescaped '}'
        // (valid on OpenJDK JVM, fatal PatternSyntaxException on Android ICU4C) fails CI immediately.
        val singletons = listOf<Any>(AssDialogueParser, VietnameseTextNormalizer, VietnameseG2P)
        val regexes = mutableListOf<Regex>()
        for (target in singletons) {
            for (field in target::class.java.declaredFields) {
                field.isAccessible = true
                when (val v = field.get(target)) {
                    is Regex -> regexes += v
                    is List<*> -> v.forEach { item ->
                        if (item is Pair<*, *> && item.first is Regex) {
                            regexes += item.first as Regex
                        }
                    }
                }
            }
        }
        assertFalse(regexes.isEmpty())
        for (rx in regexes) {
            val pat = rx.pattern
            for (i in pat.indices) {
                val c = pat[i]
                if (c == '}' && (i == 0 || pat[i - 1] != '\\')) {
                    val openIdx = pat.lastIndexOf('{', i - 1)
                    val prev = if (openIdx >= 0) pat.substring(openIdx + 1, i) else ""
                    val isQuantifier = prev.isNotEmpty() && prev.all { it.isDigit() || it == ',' }
                    val isUnicodeProp = openIdx >= 2 && pat[openIdx - 2] == '\\' && (pat[openIdx - 1] == 'p' || pat[openIdx - 1] == 'P')
                    assertFalse("Unescaped '}' breaks Android ICU regex in: $pat", !isQuantifier && !isUnicodeProp)
                }
            }
        }

        val normalizer = VietnameseTextNormalizer.parseAndCleanCue(
            "[F][D:100][E:happy] {\\pos(10,20)}Xin chào (cười) [nhạc] {note}"
        )
        assertEquals(SpeakerGender.FEMALE, normalizer.tagGender)
        assertEquals(100L, normalizer.delayMs)
        assertEquals("Xin chào", normalizer.speakableText)
    }

    @Test
    fun piperChunkingAndHardwareBudgetProtectLowRam32BitTv() {
        val budget32MultiCore = PiperOnnxEngine.computeBudget(
            is64Bit = false,
            totalMemMb = 2048L,
            isLowRamDevice = false,
            cpuCores = 4
        )
        assertTrue(budget32MultiCore.isConstrainedHardware)
        assertEquals(1, budget32MultiCore.maxCachedSessions)
        assertEquals(2, budget32MultiCore.intraOpThreads)
        assertTrue(budget32MultiCore.enableMemoryArena)

        val budgetVeryLowRam = PiperOnnxEngine.computeBudget(
            is64Bit = false,
            totalMemMb = 1024L,
            isLowRamDevice = true,
            cpuCores = 4
        )
        assertTrue(budgetVeryLowRam.isConstrainedHardware)
        assertEquals(1, budgetVeryLowRam.maxCachedSessions)
        assertEquals(1, budgetVeryLowRam.intraOpThreads)
        assertFalse(budgetVeryLowRam.enableMemoryArena)

        val budget64 = PiperOnnxEngine.computeBudget(
            is64Bit = true,
            totalMemMb = 6144L,
            isLowRamDevice = false,
            cpuCores = 8
        )
        assertFalse(budget64.isConstrainedHardware)
        assertEquals(2, budget64.maxCachedSessions)
        assertEquals(3, budget64.intraOpThreads)
        assertTrue(budget64.enableMemoryArena)

        val longCue = "Đây là một đoạn phụ đề rất dài để kiểm tra việc chia nhỏ câu thoại trước khi chạy mô hình Piper ONNX. " +
            "Khi chia câu tại dấu câu hoặc khoảng trắng thì thiết bị TV cấu hình thấp không bị tràn bộ nhớ."
        val chunks = PiperOnnxEngine.splitIntoSpeakableChunks(longCue, maxChars = 90)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 90 })
    }

    @Test
    fun wavPcmHeaderParserAndResamplerHandle22050HzMonoAndExtraChunks() {
        val sampleCount = 441
        val dataSize = sampleCount * 2
        val junkPayload = ByteArray(10) { 0x55 }
        val totalBytes = 12 + 8 + 16 + 8 + junkPayload.size + 8 + dataSize
        val wav = ByteArray(totalBytes)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(totalBytes - 8)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(1) // mono
        bb.putInt(22050)
        bb.putInt(22050 * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("JUNK".toByteArray(Charsets.US_ASCII))
        bb.putInt(junkPayload.size)
        bb.put(junkPayload)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        for (i in 0 until sampleCount) {
            bb.putShort(((i % 100) * 150).toShort())
        }

        val parsed = TtsWavPcmHelper.parseWavHeader(wav)
        assertNotNull(parsed)
        assertEquals(22050, parsed!!.sampleRate)
        assertEquals(1, parsed.channelCount)
        assertEquals(16, parsed.bitsPerSample)
        assertEquals(dataSize, parsed.dataLength)

        val stereo48k = TtsWavPcmHelper.resamplePcm16To48kStereo(wav, parsed)
        val expectedFrames = ((sampleCount.toLong() * 48000L) / 22050L).toInt()
        assertEquals(expectedFrames * 4, stereo48k.size)
        assertNull(TtsWavPcmHelper.parseWavHeader("ID3\u0003\u0000".toByteArray()))
    }

    @Test
    fun rapidDialogueDeltaSlotAndCueScanPreventSkipsAndDrift() {
        // Incremental / rolling live caption diffing prevents reading the first line twice.
        assertEquals(
            "Tôi ra siêu thị.",
            TtsSubtitleCoordinator.extractDeltaLiveText(
                previousRawText = "Anh đi đâu vậy?",
                newRawText = "Anh đi đâu vậy?\nTôi ra siêu thị.",
                withinWindow = true
            )
        )
        assertEquals(
            "Mua ít đồ.",
            TtsSubtitleCoordinator.extractDeltaLiveText(
                previousRawText = "Anh đi đâu vậy?\nTôi ra siêu thị.",
                newRawText = "Tôi ra siêu thị.\nMua ít đồ.",
                withinWindow = true
            )
        )
        assertEquals(
            "",
            TtsSubtitleCoordinator.extractDeltaLiveText(
                previousRawText = "Anh đi đâu vậy?",
                newRawText = "Anh đi đâu vậy?",
                withinWindow = true
            )
        )

        val rapidA = SubtitleSyncCue(10_000L, 11_400L, "Câu 1")
        val rapidB = SubtitleSyncCue(11_400L, 12_600L, "Câu 2")
        val isolated = SubtitleSyncCue(15_000L, 16_200L, "Câu 3")
        assertEquals(1400L, TtsSubtitleCoordinator.computeTimelineSlotWindowMs(rapidA, rapidB.startTimeMs))
        assertEquals(1950L, TtsSubtitleCoordinator.computeTimelineSlotWindowMs(isolated, 25_000L))

        val cues = listOf(rapidA, rapidB, isolated)
        val triggered = mutableSetOf<String>()
        // Overlapping / pre-cached lead triggers rapidA 150ms ahead when cache is ready
        val firstDue = TtsSubtitleCoordinator.findDueTimelineIndices(
            cues = cues,
            posMs = 9_850L,
            leadMs = 180L,
            lateWindowMs = 2400L,
            triggeredKeys = triggered,
            hasPrefetchedClip = { it == TtsSubtitleCoordinator.cueKeyFor(rapidA, 0) }
        )
        assertEquals(listOf(0), firstDue)
        triggered += TtsSubtitleCoordinator.cueKeyFor(rapidA, 0)

        // When tick arrives right at rapidB while queue wasn't triggered earlier, chronological scan catches rapidB
        val secondDue = TtsSubtitleCoordinator.findDueTimelineIndices(
            cues = cues,
            posMs = 11_420L,
            leadMs = 180L,
            lateWindowMs = 2400L,
            triggeredKeys = triggered,
            hasPrefetchedClip = { true }
        )
        assertEquals(listOf(1), secondDue)
    }
}
