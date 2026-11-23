package com.nuvio.tv.core.proxy

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbCommunityKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Manages downloading, persisting, updating, and locally executing JavaScript Stremio addons
 * hosted on GitHub or raw HTTP URLs via `http://127.0.0.1:<port>/js-addon/<addonId>/...`.
 */
class LocalJsAddonHandler(
    private val store: LocalJsAddonStore,
    private val httpClientProvider: () -> OkHttpClient,
    private val scriptRunner: JsAddonScriptRunner
) {
    private val gson = Gson()
    @Volatile private var cachedBaseId = 0
    @Volatile private var cachedOutboundClient: OkHttpClient? = null
    private val clientLock = Any()

    private fun outboundClient(): OkHttpClient {
        val base = httpClientProvider()
        val id = System.identityHashCode(base)
        cachedOutboundClient?.takeIf { cachedBaseId == id }?.let { return it }
        synchronized(clientLock) {
            cachedOutboundClient?.takeIf { cachedBaseId == id }?.let { return it }
            val built = base.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            cachedBaseId = id
            cachedOutboundClient = built
            return built
        }
    }

    fun listInstalled(): List<InstalledLocalJsAddon> = store.listInstalled()

    fun getInstalled(addonId: String): InstalledLocalJsAddon? = store.get(addonId)?.meta

    fun removeInstalledAddon(addonIdOrBaseUrl: String): Boolean {
        val id = extractAddonIdFromBaseUrl(addonIdOrBaseUrl)
            ?: GitHubAddonRepositoryParser.safeAddonId(addonIdOrBaseUrl)
        return store.remove(id)
    }

    suspend fun fetchRepoCatalog(repoInput: String): Result<GitHubAddonCatalogResult> = withContext(Dispatchers.IO) {
        val candidates = GitHubAddonRepositoryParser.candidateRepoUrls(repoInput)
        if (candidates.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("URL kho GitHub không hợp lệ"))
        }

        var lastError: String? = null
        for (candidateUrl in candidates) {
            val fetchResult = fetchFreshText(candidateUrl)
            if (fetchResult.isFailure) {
                lastError = fetchResult.exceptionOrNull()?.message
                continue
            }
            val (resolvedUrl, body) = fetchResult.getOrThrow()
            if (GitHubAddonRepositoryParser.isStandaloneScriptResponse(resolvedUrl, body)) {
                val probed = runCatching {
                    probeScriptManifest(
                        scriptCode = body,
                        scriptUrl = resolvedUrl,
                        repoUrl = repoInput.trim()
                    )
                }
                if (probed.isSuccess) {
                    val info = probed.getOrThrow()
                    return@withContext Result.success(
                        GitHubAddonCatalogResult(
                            repoName = info.name,
                            description = info.description.ifBlank { null },
                            repoUrl = resolvedUrl,
                            entries = listOf(
                                GitHubAddonRepoEntry(
                                    id = info.id,
                                    name = info.name,
                                    version = info.version,
                                    description = info.description,
                                    types = info.types,
                                    scriptUrl = resolvedUrl,
                                    repoUrl = repoInput.trim()
                                )
                            )
                        )
                    )
                }
                lastError = probed.exceptionOrNull()?.message
                continue
            }

            val parsed = GitHubAddonRepositoryParser.parseRepoJson(
                body = body,
                resolvedRepoUrl = resolvedUrl,
                originalRepoUrl = repoInput
            )
            if (parsed != null) {
                return@withContext Result.success(parsed)
            }
            lastError = "Không đọc được danh sách addons từ JSON"
        }

        Result.failure(IllegalStateException(lastError ?: "Không thể kết nối kho GitHub"))
    }

    suspend fun downloadAndInstallAddon(entry: GitHubAddonRepoEntry): Result<InstalledLocalJsAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val scriptResult = fetchFreshText(entry.scriptUrl).getOrThrow()
            val scriptCode = scriptResult.second
            val manifestReply = evaluateScriptManifest(scriptCode, fallbackId = entry.id)
            val manifestObj = JsonParser.parseString(manifestReply).asJsonObject
            val actualId = GitHubAddonRepositoryParser.safeAddonId(
                manifestObj.str("id") ?: entry.id
            )
            manifestObj.addProperty("id", actualId)
            val finalName = manifestObj.str("name") ?: entry.name
            val finalVersion = manifestObj.str("version") ?: entry.version
            val finalDesc = manifestObj.str("description") ?: entry.description

            val installed = InstalledLocalJsAddon(
                id = actualId,
                name = finalName,
                version = finalVersion,
                description = finalDesc,
                scriptUrl = scriptResult.first,
                repoUrl = entry.repoUrl,
                manifestJson = gson.toJson(manifestObj),
                updatedAtMs = System.currentTimeMillis()
            )
            store.save(installed, scriptCode)
            installed
        }
    }

    suspend fun updateInstalledAddon(addonIdOrBaseUrl: String): Result<InstalledLocalJsAddon> {
        val id = extractAddonIdFromBaseUrl(addonIdOrBaseUrl) ?: addonIdOrBaseUrl
        val existing = store.get(id)
            ?: return Result.failure(IllegalStateException("Không tìm thấy trình cắm cục bộ: $id"))
        val meta = existing.meta
        return downloadAndInstallAddon(
            GitHubAddonRepoEntry(
                id = meta.id,
                name = meta.name,
                version = meta.version,
                description = meta.description,
                types = listOf("movie", "series"),
                scriptUrl = meta.scriptUrl,
                repoUrl = meta.repoUrl
            )
        )
    }

    /**
     * Checks already-fetched GitHub catalog [entries] against installed local JS addons and
     * automatically downloads & replaces any addon whose remote version is newer.
     */
    suspend fun autoUpdateMatchingEntries(
        entries: List<GitHubAddonRepoEntry>
    ): List<InstalledLocalJsAddon> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        val installedById = store.listInstalled().associateBy { it.id }
        if (installedById.isEmpty()) return@withContext emptyList()

        val updated = ArrayList<InstalledLocalJsAddon>()
        for (entry in entries) {
            val current = installedById[entry.id] ?: continue
            if (GitHubAddonRepositoryParser.isNewerVersion(entry.version, current.version)) {
                downloadAndInstallAddon(entry).getOrNull()?.let { updated.add(it) }
            }
        }
        updated
    }

    /**
     * Queries each installed local JS addon's source GitHub repository (or standalone `.js` URL)
     * and automatically downloads & installs newer versions in the background.
     */
    suspend fun autoUpdateInstalledAddons(): List<InstalledLocalJsAddon> = withContext(Dispatchers.IO) {
        val installed = store.listInstalled()
        if (installed.isEmpty()) return@withContext emptyList()

        val updated = ArrayList<InstalledLocalJsAddon>()
        val groupedByRepo = installed.groupBy { it.repoUrl.trim().ifBlank { it.scriptUrl } }
        for ((repoSource, group) in groupedByRepo) {
            if (repoSource.isBlank()) continue
            val catalog = fetchRepoCatalog(repoSource).getOrNull()
            if (catalog != null && catalog.entries.isNotEmpty()) {
                val entryById = catalog.entries.associateBy { it.id }
                for (local in group) {
                    val remoteEntry = entryById[local.id] ?: continue
                    if (GitHubAddonRepositoryParser.isNewerVersion(remoteEntry.version, local.version)) {
                        downloadAndInstallAddon(remoteEntry).getOrNull()?.let { updated.add(it) }
                    }
                }
            } else {
                for (local in group) {
                    if (local.scriptUrl.isBlank()) continue
                    val probeResult = fetchFreshText(local.scriptUrl).getOrNull() ?: continue
                    val manifestObj = runCatching {
                        val raw = evaluateScriptManifest(probeResult.second, fallbackId = local.id)
                        JsonParser.parseString(raw).asJsonObject
                    }.getOrNull() ?: continue
                    val remoteVer = manifestObj.str("version").orEmpty()
                    if (GitHubAddonRepositoryParser.isNewerVersion(remoteVer, local.version)) {
                        val entry = GitHubAddonRepoEntry(
                            id = local.id,
                            name = manifestObj.str("name") ?: local.name,
                            version = remoteVer,
                            description = manifestObj.str("description") ?: local.description,
                            types = listOf("movie", "series"),
                            scriptUrl = local.scriptUrl,
                            repoUrl = local.repoUrl
                        )
                        downloadAndInstallAddon(entry).getOrNull()?.let { updated.add(it) }
                    }
                }
            }
        }
        updated
    }

    fun handleRequest(addonId: String, rawTarget: String, cleanPath: String): AddonHttpReply {
        val stored = store.get(addonId)
            ?: return jsonReply(
                404,
                gson.toJson(mapOf("error" to "Local JS addon '$addonId' is not installed"))
            )

        val prefix = "$BASE_PATH/${stored.meta.id}"
        val rawPrefix = "$BASE_PATH/$addonId"
        val suffix = when {
            cleanPath.startsWith(prefix) -> cleanPath.removePrefix(prefix)
            cleanPath.startsWith(rawPrefix) -> cleanPath.removePrefix(rawPrefix)
            else -> ""
        }

        return when {
            suffix.isEmpty() || suffix == "/" -> {
                jsonReply(
                    200,
                    gson.toJson(
                        mapOf(
                            "ok" to true,
                            "id" to stored.meta.id,
                            "name" to stored.meta.name,
                            "version" to stored.meta.version
                        )
                    )
                )
            }
            suffix == "/manifest.json" -> {
                jsonReply(200, stored.meta.manifestJson, cacheControl = "no-cache")
            }
            suffix.startsWith("/catalog/") -> handleCatalog(stored, suffix, rawTarget)
            suffix.startsWith("/meta/") -> handleMeta(stored, suffix)
            suffix.startsWith("/stream/") -> handleStream(stored, suffix)
            else -> jsonReply(404, gson.toJson(mapOf("error" to "Unknown endpoint: $suffix")))
        }
    }

    private fun handleCatalog(stored: StoredJsAddon, suffix: String, rawTarget: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        val type = decodeUri(segments.getOrNull(1).orEmpty()).ifBlank { "movie" }
        val catalogId = decodeUri(segments.getOrNull(2).orEmpty())
        val extra = parseExtraArgs(segments.drop(3), rawTarget)

        val payload = mapOf(
            "action" to "catalog",
            "type" to type,
            "id" to catalogId,
            "catalogId" to catalogId,
            "extra" to extra,
            "env" to buildJsEnv(stored.meta)
        )
        return executeAndReply(stored, "catalog", payload, "{\"metas\":[]}")
    }

    private fun handleMeta(stored: StoredJsAddon, suffix: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        val type = decodeUri(segments.getOrNull(1).orEmpty()).ifBlank { "movie" }
        val itemId = decodeUri(segments.drop(2).joinToString("/"))

        val payload = mapOf(
            "action" to "meta",
            "type" to type,
            "id" to itemId,
            "env" to buildJsEnv(stored.meta)
        )
        return executeAndReply(stored, "meta", payload, "{\"meta\":null}")
    }

    private fun handleStream(stored: StoredJsAddon, suffix: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        val type = decodeUri(segments.getOrNull(1).orEmpty()).ifBlank { "movie" }
        val itemId = decodeUri(segments.drop(2).joinToString("/"))

        val payload = mapOf(
            "action" to "stream",
            "type" to type,
            "id" to itemId,
            "env" to buildJsEnv(stored.meta)
        )
        return executeAndReply(stored, "stream", payload, "{\"streams\":[]}")
    }

    private fun executeAndReply(
        stored: StoredJsAddon,
        action: String,
        requestPayload: Map<String, Any?>,
        emptyFallbackJson: String
    ): AddonHttpReply {
        val bodyJson = runCatching {
            val rawOut = scriptRunner.evaluateRequest(
                scriptCode = stored.scriptCode,
                action = action,
                requestJson = gson.toJson(requestPayload),
                httpClient = outboundClient()
            )
            val parsed = JsonParser.parseString(rawOut)
            if (parsed.isJsonObject) rawOut else emptyFallbackJson
        }.getOrDefault(emptyFallbackJson)
        return jsonReply(200, bodyJson)
    }

    private fun evaluateScriptManifest(scriptCode: String, fallbackId: String): String {
        val reqJson = gson.toJson(
            mapOf(
                "action" to "manifest",
                "env" to mapOf(
                    "addonId" to fallbackId,
                    "tmdbPrimaryKey" to TmdbCommunityKeys.primaryKey(),
                    "tmdbFallbackKey" to TmdbCommunityKeys.fallbackKey()
                )
            )
        )
        val raw = scriptRunner.evaluateRequest(
            scriptCode = scriptCode,
            action = "manifest",
            requestJson = reqJson,
            httpClient = outboundClient()
        )
        val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: error("Script không trả về manifest JSON hợp lệ")
        if (!parsed.has("id") && !parsed.has("name")) {
            error("Manifest thiếu id hoặc name")
        }
        return raw
    }

    private data class ProbedManifestInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val types: List<String>
    )

    private fun probeScriptManifest(
        scriptCode: String,
        scriptUrl: String,
        repoUrl: String
    ): ProbedManifestInfo {
        val fallbackId = GitHubAddonRepositoryParser.safeAddonId(
            scriptUrl.substringAfterLast('/').substringBefore('.').ifBlank { "custom-js-addon" }
        )
        val manifestRaw = evaluateScriptManifest(scriptCode, fallbackId = fallbackId)
        val obj = JsonParser.parseString(manifestRaw).asJsonObject
        val id = GitHubAddonRepositoryParser.safeAddonId(obj.str("id") ?: fallbackId)
        val name = obj.str("name") ?: id
        val version = obj.str("version") ?: "1.0.0"
        val desc = obj.str("description").orEmpty()
        val types = obj.getAsJsonArray("types")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(String::isNotBlank) }
            ?.ifEmpty { listOf("movie", "series") }
            ?: listOf("movie", "series")
        return ProbedManifestInfo(id, name, version, desc, types)
    }

    private fun buildJsEnv(meta: InstalledLocalJsAddon): Map<String, String> = mapOf(
        "addonId" to meta.id,
        "addonVersion" to meta.version,
        "scriptUrl" to meta.scriptUrl,
        "tmdbPrimaryKey" to TmdbCommunityKeys.primaryKey(),
        "tmdbFallbackKey" to TmdbCommunityKeys.fallbackKey()
    )

    private fun fetchFreshText(url: String): Result<Pair<String, String>> = runCatching {
        val wireUrl = withGitHubCacheBuster(url)
        val req = Request.Builder()
            .url(wireUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        outboundClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code} từ ${stripCacheBuster(resp.request.url.toString())}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                error("Phản hồi rỗng từ ${stripCacheBuster(resp.request.url.toString())}")
            }
            stripCacheBuster(resp.request.url.toString()) to body
        }
    }

    private fun withGitHubCacheBuster(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase().orEmpty()
        if (host != "raw.githubusercontent.com") return url
        val sep = if (uri.rawQuery.isNullOrBlank()) "?" else "&"
        return "$url${sep}_ts=${System.currentTimeMillis()}"
    }

    private fun stripCacheBuster(url: String): String {
        if (!url.contains("_ts=")) return url
        return url
            .replace(Regex("""([?&])_ts=\d+&?""")) { m ->
                if (m.groupValues[1] == "?" && m.value.endsWith("&")) "?" else if (m.value.endsWith("&")) "&" else ""
            }
            .trimEnd('?', '&')
    }

    private fun parseExtraArgs(extraSegments: List<String>, rawTarget: String): Map<String, String> {
        val extra = LinkedHashMap<String, String>()
        val query = rawTarget.substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val k = decodeUri(if (eq >= 0) pair.substring(0, eq) else pair)
                val v = if (eq >= 0) decodeUri(pair.substring(eq + 1)) else ""
                if (k.isNotBlank()) extra[k] = v
            }
        }
        for (seg in extraSegments) {
            for (pair in seg.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = decodeUri(pair.substring(0, eq))
                    val v = decodeUri(pair.substring(eq + 1))
                    if (k.isNotBlank()) extra[k] = v
                }
            }
        }
        return extra
    }

    private fun decodeUri(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun jsonReply(statusCode: Int, json: String, cacheControl: String = "no-cache"): AddonHttpReply =
        AddonHttpReply(
            statusCode = statusCode,
            bodyBytes = json.toByteArray(StandardCharsets.UTF_8),
            cacheControl = cacheControl
        )

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim()

    companion object {
        const val LOCAL_ADDON_DEFAULT_PORT = 47123
        const val BASE_PATH = "/js-addon"
        const val DEFAULT_GITHUB_REPO_URL = "https://github.com/anpham14121993-cloud/NothingP-Addons"

        fun addonBaseUrl(addonId: String, port: Int = LOCAL_ADDON_DEFAULT_PORT): String =
            "http://127.0.0.1:$port$BASE_PATH/${GitHubAddonRepositoryParser.safeAddonId(addonId)}"

        fun extractAddonIdFromBaseUrl(urlOrPath: String): String? {
            val clean = urlOrPath.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            val path = runCatching {
                if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                    URI(clean).path.orEmpty()
                } else {
                    clean
                }
            }.getOrDefault(clean).trimEnd('/')
            if (!path.startsWith("$BASE_PATH/")) return null
            val rest = path.removePrefix("$BASE_PATH/")
            return rest.substringBefore('/').takeIf { it.isNotBlank() }
        }
    }
}
