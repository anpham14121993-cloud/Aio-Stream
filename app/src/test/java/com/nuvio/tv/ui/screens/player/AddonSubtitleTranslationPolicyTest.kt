package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSubtitleTranslationPolicyTest {

    @Test
    fun `detects NothingP-SubAI translate-sub URL as translating`() {
        val url = "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&fileId=99&source=en&target=vi&v=1.3.2"
        assertTrue(AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(url))
        assertEquals("vi", AddonSubtitleTranslationPolicy.effectiveAddonSubtitleLanguage("eng", url))
    }

    @Test
    fun `treats generic target translation query as translating`() {
        val url = "https://addon.example.com/sub?id=42&source=en&target=vi"
        assertTrue(AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(url))
        assertEquals("vi", AddonSubtitleTranslationPolicy.effectiveAddonSubtitleLanguage("eng", url))
    }

    @Test
    fun `static subtitle URL is not treated as translating`() {
        val url = "https://addon.example.com/proxy-os?fileId=99&lang=eng"
        assertFalse(AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(url))
        assertEquals("en", AddonSubtitleTranslationPolicy.effectiveAddonSubtitleLanguage("eng", url))
    }

    @Test
    fun `translate-sub URL without explicit target returns null effective language`() {
        val url = "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&fileId=99"
        assertTrue(AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(url))
        assertNull(AddonSubtitleTranslationPolicy.effectiveAddonSubtitleLanguage("eng", url))
    }

    @Test
    fun `detects 1-cue translation status SRT as placeholder`() {
        val url = "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&source=en&target=vi"
        val statusBody = """
            1
            00:00:00,000 --> 01:00:00,000
            🟡 Gemini AI đang dịch phụ đề...
            🔄 Khi dịch xong, bấm Reload phụ đề để nhận bản Việt.
        """.trimIndent()
        assertTrue(
            AddonSubtitleTranslationPolicy.isStatusPlaceholderSubtitle(
                url = url,
                cueCount = 1,
                rawBody = statusBody,
                responseHeaders = mapOf("cache-control" to "no-store, no-cache, must-revalidate"),
            )
        )
    }

    @Test
    fun `full translated subtitle with many cues is not treated as placeholder`() {
        val url = "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&source=en&target=vi"
        assertFalse(
            AddonSubtitleTranslationPolicy.isStatusPlaceholderSubtitle(
                url = url,
                cueCount = 650,
                rawBody = "1\n00:00:01,000 --> 00:00:03,000\nXin chào.",
                responseHeaders = mapOf("cache-control" to "no-store"),
            )
        )
    }

    @Test
    fun `no-store response header forces subtitle cache bypass`() {
        assertTrue(
            AddonSubtitleTranslationPolicy.hasNoStoreCacheControl(
                mapOf("Cache-Control" to "no-store, no-cache, must-revalidate")
            )
        )
        assertFalse(
            AddonSubtitleTranslationPolicy.hasNoStoreCacheControl(
                mapOf("Cache-Control" to "public, max-age=3600")
            )
        )
    }

    @Test
    fun `translating subtitle is excluded from auto-selection but remains visible for target language`() {
        val aiTrack = Subtitle(
            id = "ai-eng-vi",
            url = "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&source=en&target=vi&v=1.3.2",
            lang = "eng",
            addonName = "NothingP-SubAI",
            addonLogo = null,
        )
        val staticEnglishTrack = Subtitle(
            id = "os-eng",
            url = "https://sub.example.vercel.app/sub/os/123.srt",
            lang = "eng",
            addonName = "OpenSubtitles",
            addonLogo = null,
        )

        assertFalse(AddonSubtitleTranslationPolicy.isEligibleForAutoSelection(aiTrack))
        assertTrue(AddonSubtitleTranslationPolicy.isEligibleForAutoSelection(staticEnglishTrack))
        assertTrue(AddonSubtitleTranslationPolicy.matchesPreferredVisibilityLanguage(aiTrack, "vi"))
        assertTrue(AddonSubtitleTranslationPolicy.matchesPreferredVisibilityLanguage(aiTrack, "en"))
        assertFalse(AddonSubtitleTranslationPolicy.matchesPreferredVisibilityLanguage(staticEnglishTrack, "vi"))
    }
}
