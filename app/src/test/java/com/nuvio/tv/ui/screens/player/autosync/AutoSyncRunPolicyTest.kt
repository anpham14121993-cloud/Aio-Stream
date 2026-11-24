package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.screens.player.autoSyncFailureMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncRunPolicyTest {

    @Test
    fun `restored subtitle in translation placeholder does not switch to other English tracks`() {
        assertFalse(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.TRANSLATION_IN_PROGRESS
            )
        )
    }

    @Test
    fun `restored normal subtitle that clearly does not fit still searches alternatives`() {
        assertTrue(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_FIT
            )
        )
        assertTrue(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.INCONCLUSIVE
            )
        )
    }

    @Test
    fun `translation in progress does not show a mismatch error toast`() {
        assertNull(autoSyncFailureMessage(AutoSyncAnalysisOutcome.TRANSLATION_IN_PROGRESS))
    }

    @Test
    fun `dynamic translating URL always uses SELECTED_ONLY and never allows AutoSync swap`() {
        val translatingUrl =
            "https://sub.example.vercel.app/translate-sub/g/tok_123?provider=os&source=en&target=vi&v=1.3.2"
        val normalEnglishUrl = "https://subs.example.com/english-1.srt"

        assertEquals(
            AutoSyncCandidateScope.SELECTED_ONLY,
            candidateScopeForAddonSelection(
                isUserExplicitSelection = false,
                subtitleUrl = translatingUrl,
                isManualReload = false,
            )
        )
        assertEquals(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH,
            candidateScopeForAddonSelection(
                isUserExplicitSelection = false,
                subtitleUrl = normalEnglishUrl,
                isManualReload = false,
            )
        )
        assertFalse(canAutoSyncSwitchSelectedAddonSubtitle(translatingUrl, normalEnglishUrl))
        assertFalse(canAutoSyncSwitchSelectedAddonSubtitle(normalEnglishUrl, translatingUrl))
        assertTrue(canAutoSyncSwitchSelectedAddonSubtitle(normalEnglishUrl, "https://subs.example.com/english-2.srt"))
    }

    @Test
    fun `subtitle download timeouts stay well above NothingP-SubAI 15s in-flight wait`() {
        assertTrue(PlayerPlaybackNetworking.SUBTITLE_HTTP_READ_TIMEOUT_SECONDS >= 60L)
        assertTrue(PlayerPlaybackNetworking.SUBTITLE_HTTP_CONNECT_TIMEOUT_SECONDS >= 60L)
        assertTrue(AutomaticSubtitleSync.SUBTITLE_DOWNLOAD_TIMEOUT_MS >= 60_000L)
    }

    @Test
    fun `mpv snapshot preserves active addon selection while reload download is in flight`() {
        assertTrue(
            shouldKeepSelectedAddonOnMpvSnapshot(
                isMpvAddonSelectionInFlight = true,
                hasPendingAddonSubtitle = true,
            )
        )
        assertFalse(
            shouldKeepSelectedAddonOnMpvSnapshot(
                isMpvAddonSelectionInFlight = false,
                hasPendingAddonSubtitle = true,
            )
        )
    }
}
