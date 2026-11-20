package com.nuvio.tv.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseTextNormalizerTest {

    @Test
    fun `parses speaker gender delay and emotion tags while stripping markup`() {
        val parsed = VietnameseTextNormalizer.parseAndCleanCue(
            "[F][D:250][E:happy] <i>Xin chào</i> (cười) ♪"
        )
        assertEquals(SpeakerGender.FEMALE, parsed.tagGender)
        assertEquals(250L, parsed.delayMs)
        assertEquals("happy", parsed.emotion)
        assertEquals("Xin chào", parsed.speakableText)
    }

    @Test
    fun `parses male tag and ASS override tags`() {
        val parsed = VietnameseTextNormalizer.parseAndCleanCue(
            """[M] {\pos(500,900)}- Chúng ta đi thôi!"""
        )
        assertEquals(SpeakerGender.MALE, parsed.tagGender)
        assertEquals("Chúng ta đi thôi!", parsed.speakableText)
    }

    @Test
    fun `returns empty speakableText for stage directions or music only`() {
        val stageOnly = VietnameseTextNormalizer.parseAndCleanCue("[tiếng súng nổ]")
        assertEquals("", stageOnly.speakableText)

        val musicOnly = VietnameseTextNormalizer.parseAndCleanCue("♪ ♫ ♪")
        assertEquals("", musicOnly.speakableText)
    }

    @Test
    fun `converts Vietnamese numbers up to trieu ty`() {
        assertEquals("không", VietnameseTextNormalizer.numberToVietnameseWords(0L))
        assertEquals("một trăm lẻ năm", VietnameseTextNormalizer.numberToVietnameseWords(105L))
        assertEquals("hai mươi mốt", VietnameseTextNormalizer.numberToVietnameseWords(21L))
        assertEquals("mười lăm", VietnameseTextNormalizer.numberToVietnameseWords(15L))
        assertEquals(
            "một triệu hai trăm năm mươi nghìn",
            VietnameseTextNormalizer.numberToVietnameseWords(1_250_000L)
        )
        assertEquals(
            "hai tỷ năm trăm triệu",
            VietnameseTextNormalizer.numberToVietnameseWords(2_500_000_000L)
        )
        assertEquals("âm bốn mươi hai", VietnameseTextNormalizer.numberToVietnameseWords(-42L))
    }

    @Test
    fun `normalizes dates times currencies percentages and loanwords`() {
        val normalized = VietnameseTextNormalizer.normalizeForSpeech(
            "Hẹn 14:30 ngày 20/11/2026 tại TP.HCM, giảm 25%, giá $10, OK?"
        )
        assertTrue(normalized.contains("mười bốn giờ ba mươi phút"))
        assertTrue(normalized.contains("ngày hai mươi tháng mười một năm hai nghìn không trăm hai mươi sáu"))
        assertTrue(normalized.contains("thành phố Hồ Chí Minh"))
        assertTrue(normalized.contains("hai mươi lăm phần trăm"))
        assertTrue(normalized.contains("mười đô la"))
        assertTrue(normalized.contains("ô kê?"))
    }
}
