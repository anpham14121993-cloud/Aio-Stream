package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.core.proxy.GitHubAddonRepoEntry
import com.nuvio.tv.core.proxy.LocalJsAddonHandler

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installedAddons: List<Addon> = emptyList(),
    val healthByUrl: Map<String, AddonHealthLevel> = emptyMap(),
    val error: String? = null,
    val transientMessage: String? = null,
    val transientMessageIsError: Boolean = false,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null,
    // GitHub Online Local JS Addons Store
    val isGitHubStoreOpen: Boolean = false,
    val githubRepoUrl: String = LocalJsAddonHandler.DEFAULT_GITHUB_REPO_URL,
    val isLoadingGitHubRepo: Boolean = false,
    val githubRepoName: String? = null,
    val githubRepoDescription: String? = null,
    val githubEntries: List<GitHubAddonRepoEntry> = emptyList(),
    val githubError: String? = null,
    val installingGitHubAddonId: String? = null,
    val installedLocalJsIds: Set<String> = emptySet(),
    val installedLocalJsVersions: Map<String, String> = emptyMap()
)

data class PendingChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val catalogsReordered: Boolean = false,
    val disabledCatalogNames: List<String> = emptyList(),
    val enabledCatalogNames: List<String> = emptyList(),
    val addedNames: Map<String, String> = emptyMap(),
    val removedNames: Map<String, String> = emptyMap(),
    val collectionsChanged: Boolean = false,
    val proposedCollectionsJson: String? = null,
    val proposedCollectionCount: Int = 0,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    val isApplying: Boolean = false,
    val trackingAddonNames: List<String>? = null,
    val trackingConsentUrls: List<String> = emptyList(),
    val awaitingTrackingConsent: Boolean = false
)
