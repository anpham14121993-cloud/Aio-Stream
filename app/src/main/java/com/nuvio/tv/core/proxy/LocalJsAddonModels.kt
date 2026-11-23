package com.nuvio.tv.core.proxy

import okhttp3.OkHttpClient

/**
 * HTTP reply produced by a local JavaScript Stremio addon.
 */
data class AddonHttpReply(
    val statusCode: Int,
    val bodyBytes: ByteArray,
    val cacheControl: String = "no-cache"
)

/**
 * Metadata for one downloadable addon entry discovered in a GitHub repository `repo.json`
 * or direct `.js` addon URL.
 */
data class GitHubAddonRepoEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val types: List<String>,
    val scriptUrl: String,
    val repoUrl: String,
    val icon: String? = null
)

/**
 * Result of loading an online GitHub repository index or standalone JS addon URL.
 */
data class GitHubAddonCatalogResult(
    val repoName: String,
    val description: String?,
    val repoUrl: String,
    val entries: List<GitHubAddonRepoEntry>
)

/**
 * Persisted metadata for an online JS Stremio addon stored locally on device.
 */
data class InstalledLocalJsAddon(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val scriptUrl: String,
    val repoUrl: String,
    val manifestJson: String,
    val updatedAtMs: Long
)

/**
 * Full on-device bundle (metadata + JavaScript source) for an installed local JS addon.
 */
data class StoredJsAddon(
    val meta: InstalledLocalJsAddon,
    val scriptCode: String
)

/**
 * Abstraction executing a JavaScript Stremio addon action (`manifest`, `catalog`, `meta`, `stream`)
 * using the host's outbound HTTP client so Android uses QuickJS while JVM unit tests can inject
 * deterministic script executors.
 */
fun interface JsAddonScriptRunner {
    fun evaluateRequest(
        scriptCode: String,
        action: String,
        requestJson: String,
        httpClient: OkHttpClient
    ): String
}
