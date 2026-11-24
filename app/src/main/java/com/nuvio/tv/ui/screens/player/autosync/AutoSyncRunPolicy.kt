package com.nuvio.tv.ui.screens.player.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,

    /**
     * A selection restored from an earlier episode: check it first and keep it if it fits;
     * only when it does not, search the other same-language subtitles like a startup run.
     */
    SELECTED_THEN_SEARCH,
}

/** Whether a failed check of the selected subtitle should be followed by a full search. */
internal fun AutoSyncCandidateScope.searchAfterSelectedFailed(
    outcome: AutoSyncAnalysisOutcome?,
): Boolean =
    this == AutoSyncCandidateScope.SELECTED_THEN_SEARCH &&
        // Nothing in the video to compare with, or a temporary translation status placeholder.
        outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
        outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE &&
        outcome != AutoSyncAnalysisOutcome.TRANSLATION_IN_PROGRESS

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null

/**
 * Manual user selections, reloads, and dynamic AI-translating tracks must always run AutoSync
 * only against the selected track so AutoSync can never swap them out for another subtitle.
 */
internal fun candidateScopeForAddonSelection(
    isUserExplicitSelection: Boolean,
    subtitleUrl: String,
    isManualReload: Boolean = false,
): AutoSyncCandidateScope =
    if (
        isUserExplicitSelection ||
        isManualReload ||
        com.nuvio.tv.ui.screens.player.AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(subtitleUrl)
    ) {
        AutoSyncCandidateScope.SELECTED_ONLY
    } else {
        AutoSyncCandidateScope.SELECTED_THEN_SEARCH
    }

/** Prevents AutoSync from replacing or selecting dynamic AI-translating tracks as alternatives. */
internal fun canAutoSyncSwitchSelectedAddonSubtitle(
    selectedUrl: String,
    chosenUrl: String,
): Boolean {
    if (selectedUrl == chosenUrl) return false
    return !com.nuvio.tv.ui.screens.player.AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(selectedUrl) &&
        !com.nuvio.tv.ui.screens.player.AddonSubtitleTranslationPolicy.isTranslatingSubtitleUrl(chosenUrl)
}

/** Keeps the UI pinned to the user's pending/active addon subtitle while MPV finishes sub-add. */
internal fun shouldKeepSelectedAddonOnMpvSnapshot(
    isMpvAddonSelectionInFlight: Boolean,
    hasPendingAddonSubtitle: Boolean,
): Boolean = isMpvAddonSelectionInFlight && hasPendingAddonSubtitle

