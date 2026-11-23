package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.autoSyncFailureMessage
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
}
