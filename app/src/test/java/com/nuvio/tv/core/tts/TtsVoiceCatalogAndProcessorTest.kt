package com.nuvio.tv.core.tts

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
