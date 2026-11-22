package com.nuvio.tv.core.proxy

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbCommunityKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Built-in local Stremio addon server for Fiml4K running strictly on-device (`127.0.0.1`)
 * using the user's own residential network (avoiding cloud datacenter IP blocks on `fiml4k.fun`)
 * and emitting `localProxy` `hls-rewrite` hints so `LocalProxyHttpServer` fetches and unwraps
 * disguised `.image` fMP4 segments straight from CDN with zero external proxy traffic.
 *
 * Users can freely install, disable, reorder, or remove this addon like any standard Stremio addon.
 */
class Fiml4kLocalAddonHandler(
    private val httpClientProvider: () -> OkHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE,
    private val tmdbBaseUrl: String = DEFAULT_TMDB_BASE
) {
    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    @Volatile private var cachedBaseId = 0
    @Volatile private var cachedAddonClient: OkHttpClient? = null
    private val clientLock = Any()

    private data class CacheEntry(
        val data: JsonElement,
        val expiresAtMs: Long
    )

    data class AddonHttpReply(
        val statusCode: Int,
        val bodyBytes: ByteArray,
        val cacheControl: String = "no-cache"
    )

    fun handleRequest(rawTarget: String, cleanPath: String): AddonHttpReply {
        val suffix = cleanPath.removePrefix(BASE_PATH)
        return when {
            suffix == "" || suffix == "/" -> {
                jsonReply(200, gson.toJson(mapOf("ok" to true, "addon" to ADDON_ID, "version" to ADDON_VERSION)))
            }
            suffix == "/manifest.json" -> {
                jsonReply(200, gson.toJson(buildManifest()), cacheControl = "public, max-age=300")
            }
            suffix.startsWith("/catalog/") -> handleCatalog(suffix, rawTarget)
            suffix.startsWith("/meta/") -> handleMeta(suffix)
            suffix.startsWith("/stream/") -> handleStream(suffix)
            else -> jsonReply(404, gson.toJson(mapOf("error" to "Unknown Fiml4K local addon endpoint")))
        }
    }

    private fun addonClient(): OkHttpClient {
        val base = httpClientProvider()
        val id = System.identityHashCode(base)
        cachedAddonClient?.takeIf { cachedBaseId == id }?.let { return it }
        synchronized(clientLock) {
            cachedAddonClient?.takeIf { cachedBaseId == id }?.let { return it }
            val built = base.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            cachedBaseId = id
            cachedAddonClient = built
            return built
        }
    }

    private fun buildManifest(): Map<String, Any> = mapOf(
        "id" to ADDON_ID,
        "version" to ADDON_VERSION,
        "name" to ADDON_NAME,
        "description" to ADDON_DESCRIPTION,
        "resources" to listOf(
            "catalog",
            mapOf("name" to "meta", "types" to listOf("movie", "series"), "idPrefixes" to listOf("fiml4k")),
            mapOf("name" to "stream", "types" to listOf("movie", "series"), "idPrefixes" to listOf("fiml4k", "tmdb", "imdb", "tt"))
        ),
        "types" to listOf("movie", "series"),
        "catalogs" to listOf(
            mapOf(
                "type" to "movie",
                "id" to "fiml4k_movies",
                "name" to "Fiml4K • Phim Lẻ",
                "extra" to listOf(
                    mapOf("name" to "search", "isRequired" to false),
                    mapOf("name" to "skip", "isRequired" to false)
                )
            ),
            mapOf(
                "type" to "series",
                "id" to "fiml4k_series",
                "name" to "Fiml4K • Phim Bộ",
                "extra" to listOf(
                    mapOf("name" to "search", "isRequired" to false),
                    mapOf("name" to "skip", "isRequired" to false)
                )
            )
        )
    )

    private fun handleCatalog(suffix: String, rawTarget: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        // Expected: ["catalog", type, catalogId, ...extras]
        val typeSeg = segments.getOrNull(1)?.lowercase() ?: "movie"
        val isSeries = typeSeg == "series" || typeSeg == "tv"
        val apiType = if (isSeries) "tv" else "movie"
        val stremioType = if (isSeries) "series" else "movie"

        var search = ""
        var skip = 0
        val query = rawTarget.substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                val k = decodeUri(if (eq >= 0) pair.substring(0, eq) else pair)
                val v = if (eq >= 0) decodeUri(pair.substring(eq + 1)) else ""
                if (k == "search") search = v
                if (k == "skip") skip = (v.toIntOrNull() ?: 0).coerceAtLeast(0)
            }
        }
        for (part in segments.drop(3)) {
            for (sub in part.split('&')) {
                when {
                    sub.startsWith("search=") -> search = decodeUri(sub.removePrefix("search="))
                    sub.startsWith("skip=") -> skip = (sub.removePrefix("skip=").toIntOrNull() ?: 0).coerceAtLeast(0)
                }
            }
        }
        search = search.trim()

        val metas = if (search.isNotEmpty()) {
            searchCatalog(search, stremioType, skip)
        } else {
            homeCatalog(apiType, stremioType, skip)
        }
        return jsonReply(200, gson.toJson(mapOf("metas" to metas)))
    }

    private fun homeCatalog(apiType: String, stremioType: String, skip: Int): List<Map<String, Any?>> {
        val startPage = (skip / API_PAGE_SIZE) + 1
        val offset = skip % API_PAGE_SIZE
        val items = if (offset == 0) {
            fetchHomePage(apiType, startPage)
        } else {
            val endPage = ((skip + API_PAGE_SIZE - 1) / API_PAGE_SIZE) + 1
            val combined = ArrayList<JsonObject>(API_PAGE_SIZE * 2)
            for (p in startPage..endPage) {
                combined.addAll(fetchHomePage(apiType, p))
            }
            if (offset < combined.size) {
                combined.subList(offset, minOf(combined.size, offset + API_PAGE_SIZE))
            } else {
                emptyList()
            }
        }
        return items.mapNotNull { itemToMeta(it, stremioType) }
    }

    private fun fetchHomePage(apiType: String, page: Int): List<JsonObject> {
        val cacheKey = "home:$apiType:$page"
        val cached = getCachedElement(cacheKey)?.takeIf { it.isJsonArray }?.asJsonArray
        if (cached != null) {
            return cached.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        }
        val url = "${apiBaseUrl.trimEnd('/')}/api/home?type=$apiType&page=$page"
        val data = fetchJsonObject(url) ?: return emptyList()
        val list = data.getAsJsonArray("list") ?: return emptyList()
        if (list.size() > 0) {
            putCachedElement(cacheKey, list, CATALOG_CACHE_TTL_MS)
        }
        return list.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    }

    private fun searchCatalog(query: String, stremioType: String, skip: Int): List<Map<String, Any?>> {
        val data = searchSuggest(query) ?: return emptyList()
        val filtered = data.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .filter { item ->
                val rawType = item.str("mediaType") ?: item.str("type")
                fiml4kTypeToStremio(rawType) == stremioType
            }
        if (skip >= filtered.size) return emptyList()
        return filtered.subList(skip, minOf(filtered.size, skip + API_PAGE_SIZE))
            .mapNotNull { itemToMeta(it, stremioType) }
    }

    private fun searchSuggest(query: String): JsonArray? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val data = fetchJsonObject("${apiBaseUrl.trimEnd('/')}/api/search/suggest?q=$encoded") ?: return null
        return data.getAsJsonArray("list")
    }

    private fun handleMeta(suffix: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        // Expected: ["meta", type, id]
        val typeSeg = segments.getOrNull(1)?.lowercase() ?: "movie"
        val isSeries = typeSeg == "series" || typeSeg == "tv"
        val stremioType = if (isSeries) "series" else "movie"
        val rawId = decodeUri(segments.lastOrNull().orEmpty())
        val slug = rawId.removePrefix("fiml4k:")
        if (slug.isBlank()) return jsonReply(200, gson.toJson(mapOf("meta" to null)))

        val titleData = fetchTitleData(slug)
        val movie = extractMovieObject(titleData)
        if (movie == null) {
            val fallback = mapOf(
                "id" to "fiml4k:$slug",
                "type" to stremioType,
                "name" to slug,
                "poster" to "",
                "background" to "",
                "description" to "",
                "releaseInfo" to "",
                "imdbRating" to "",
                "genres" to emptyList<String>()
            )
            return jsonReply(200, gson.toJson(mapOf("meta" to fallback)))
        }

        val metaMap = LinkedHashMap<String, Any?>()
        val actualSlug = movie.str("slug") ?: slug
        metaMap["id"] = "fiml4k:$actualSlug"
        metaMap["type"] = stremioType
        metaMap["name"] = preferVi(movie, "title").ifBlank { actualSlug }
        metaMap["poster"] = preferVi(movie, "poster")
        metaMap["background"] = movie.str("backdrop").orEmpty()
        metaMap["description"] = preferVi(movie, "overview")
        metaMap["releaseInfo"] = movie.numStr("year")
        metaMap["imdbRating"] = movie.numStr("score")
        metaMap["genres"] = extractGenres(movie)
        movie.str("imdbId")?.takeIf { it.isNotBlank() }?.let { metaMap["imdb_id"] = it }
        movie.longOrNull("tmdbId")?.let { metaMap["tmdb_id"] = it }

        if (isSeries && titleData != null) {
            val episodes = extractEpisodes(titleData)
            val videos = episodes.mapIndexedNotNull { idx, ep ->
                val season = ep.intOrNull("season") ?: ep.intOrNull("seasonNumber") ?: ep.intOrNull("season_number") ?: 1
                val episode = ep.intOrNull("episode") ?: ep.intOrNull("ep") ?: ep.intOrNull("episodeNumber") ?: ep.intOrNull("episode_number") ?: (idx + 1)
                if (episode <= 0) return@mapIndexedNotNull null
                val epTitle = preferVi(ep, "title").ifBlank { preferVi(ep, "name") }.ifBlank { "Tập $episode" }
                mapOf(
                    "id" to "fiml4k:$actualSlug:$season:$episode",
                    "title" to epTitle,
                    "season" to season,
                    "episode" to episode,
                    "overview" to preferVi(ep, "overview")
                )
            }
                .distinctBy { (it["season"] as Int) to (it["episode"] as Int) }
                .sortedWith(compareBy({ it["season"] as Int }, { it["episode"] as Int }))
            metaMap["videos"] = videos
        }
        return jsonReply(200, gson.toJson(mapOf("meta" to metaMap)))
    }

    private fun handleStream(suffix: String): AddonHttpReply {
        val clean = suffix.removeSuffix(".json")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        val typeSeg = segments.getOrNull(1)?.lowercase() ?: "movie"
        val isSeries = typeSeg == "series" || typeSeg == "tv"
        val stremioType = if (isSeries) "series" else "movie"
        val actualId = decodeUri(segments.lastOrNull().orEmpty())

        var parts = actualId.split(':')
        val prefix = parts.firstOrNull()?.lowercase().orEmpty()
        var resolvedTitleFromExternal: String? = null
        var resolvedYearFromExternal: String? = null
        var preloadedTitleData: JsonObject? = null

        when {
            prefix == "tmdb" -> {
                val tmdbId = parts.getOrNull(1)?.toLongOrNull() ?: return jsonReply(200, gson.toJson(mapOf("streams" to emptyList<Any>())))
                val resolved = resolveTmdbToFiml4k(tmdbId, stremioType) ?: return jsonReply(200, gson.toJson(mapOf("streams" to emptyList<Any>())))
                resolvedTitleFromExternal = resolved.title
                resolvedYearFromExternal = resolved.year
                preloadedTitleData = resolved.titleData
                parts = if (isSeries) {
                    listOf("fiml4k", resolved.slug, parts.getOrNull(2) ?: "1", parts.getOrNull(3) ?: "1")
                } else {
                    listOf("fiml4k", resolved.slug)
                }
            }
            prefix == "imdb" || Regex("""^tt\d+$""", RegexOption.IGNORE_CASE).matches(prefix) -> {
                val imdbId: String
                val seasonArg: String?
                val epArg: String?
                if (prefix == "imdb") {
                    imdbId = parts.getOrNull(1).orEmpty()
                    seasonArg = parts.getOrNull(2)
                    epArg = parts.getOrNull(3)
                } else {
                    imdbId = parts[0]
                    seasonArg = parts.getOrNull(1)
                    epArg = parts.getOrNull(2)
                }
                val resolved = resolveImdbToFiml4k(imdbId, stremioType) ?: return jsonReply(200, gson.toJson(mapOf("streams" to emptyList<Any>())))
                resolvedTitleFromExternal = resolved.title
                resolvedYearFromExternal = resolved.year
                preloadedTitleData = resolved.titleData
                parts = if (isSeries) {
                    listOf("fiml4k", resolved.slug, seasonArg ?: "1", epArg ?: "1")
                } else {
                    listOf("fiml4k", resolved.slug)
                }
            }
        }

        val slug = (parts.getOrNull(1) ?: parts.getOrNull(0) ?: "").trim()
        if (slug.isEmpty()) return jsonReply(200, gson.toJson(mapOf("streams" to emptyList<Any>())))
        val seasonNum = if (isSeries) (parts.getOrNull(2)?.toIntOrNull() ?: 1) else 1
        val episodeNum = if (isSeries) (parts.getOrNull(3)?.toIntOrNull() ?: 1) else 1
        val seasonStr = seasonNum.toString().padStart(2, '0')
        val episodeStr = episodeNum.toString().padStart(2, '0')

        val encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8.name())
        val base = apiBaseUrl.trimEnd('/')
        val watchUrls = listOf(
            "$base/api/watch/$encodedSlug?e=s${seasonStr}e${episodeStr}",
            "$base/api/watch/$encodedSlug?episode=$episodeNum",
            "$base/api/watch/$encodedSlug?ep=$episodeNum"
        )

        var watchData: JsonObject? = null
        for (wUrl in watchUrls) {
            val fetched = fetchJsonObject(wUrl)
            if (fetched != null && (extractSources(fetched).isNotEmpty() || extractEpisodes(fetched).isNotEmpty())) {
                watchData = fetched
                break
            }
            if (watchData == null && fetched != null) {
                watchData = fetched
            }
        }

        var titleData: JsonObject? = preloadedTitleData
        var sources = watchData?.let(::extractSourcesForEpisode).orEmpty()
        var currentEpisode = watchData?.let { findMatchingEpisode(it, seasonNum, episodeNum) }

        if (sources.isEmpty()) {
            val epsInWatch = watchData?.let(::extractEpisodes).orEmpty()
            val matchInWatch = findMatchingEpisode(epsInWatch, seasonNum, episodeNum)
            if (matchInWatch != null) {
                currentEpisode = matchInWatch
                sources = matchInWatch.arrayObjects("sources")
            }
        }

        if (sources.isEmpty()) {
            titleData = titleData ?: fetchTitleData(slug)
            if (titleData != null) {
                val eps = extractEpisodes(titleData)
                val match = findMatchingEpisode(eps, seasonNum, episodeNum)
                if (match != null) {
                    currentEpisode = match
                    sources = match.arrayObjects("sources")
                }
                if (sources.isEmpty()) {
                    sources = extractSources(titleData)
                }
            }
        }

        val movieObj = extractMovieObject(watchData) ?: extractMovieObject(titleData)
        val movieViTitle = movieObj?.let { preferVi(it, "title") }
            ?.takeIf { it.isNotBlank() }
            ?: resolvedTitleFromExternal
            ?: slug.replace('-', ' ')
        val yearStr = movieObj?.numStr("year")?.takeIf { it.isNotBlank() } ?: resolvedYearFromExternal.orEmpty()
        val maxRes = movieObj?.str("maxRes") ?: movieObj?.numStr("maxHeight").orEmpty()
        val viDub = movieObj?.bool("viDub") == true
        val viSubs = movieObj?.bool("viSubs") == true

        val downloadObj = currentEpisode?.obj("download")
            ?: currentEpisode?.arrayObjects("downloads")?.firstOrNull()
            ?: movieObj?.obj("download")
            ?: movieObj?.arrayObjects("downloads")?.firstOrNull()
        val rawFileName = downloadObj?.str("name").orEmpty()
        val rawSizeBytes = downloadObj?.longOrNull("size") ?: 0L

        val outStreams = ArrayList<Map<String, Any?>>()
        sources.forEachIndexed { idx, src ->
            val rawPath = src.str("url") ?: src.str("link") ?: src.str("file") ?: return@forEachIndexed
            if (!rawPath.contains(".m3u8", ignoreCase = true)) return@forEachIndexed
            val masterUrl = resolveUpstreamUrl(rawPath)
            val label = src.str("label") ?: src.str("name") ?: "Server ${idx + 1}"
            val isIndex = Regex("""(?:^|/)index\.m3u8(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(masterUrl)
            val variants = if (!isIndex) inspectMasterVariants(masterUrl) else emptyList()

            val targetVariants = if (variants.isNotEmpty()) {
                val filtered = variants.filter { it.quality == "4k" || it.quality == "1080" }
                filtered.ifEmpty { listOf(variants.first()) }
            } else {
                emptyList()
            }

            if (targetVariants.isNotEmpty()) {
                for (variant in targetVariants) {
                    val streamUrl = "$masterUrl#aio_quality=${variant.quality}"
                    val (nameBadge, fullTitle) = formatStreamDisplay(
                        movieTitle = movieViTitle,
                        year = yearStr,
                        season = if (isSeries) seasonNum else null,
                        episode = if (isSeries) episodeNum else null,
                        isSeries = isSeries,
                        filename = rawFileName,
                        sizeBytes = rawSizeBytes,
                        serverLabel = label,
                        maxRes = maxRes,
                        viDub = viDub,
                        viSubs = viSubs,
                        realWidth = variant.width,
                        realHeight = variant.height,
                        lockQuality = variant.quality
                    )
                    outStreams.add(
                        buildStreamEntry(
                            name = nameBadge,
                            title = fullTitle,
                            url = streamUrl,
                            slug = slug
                        )
                    )
                }
            } else {
                val (nameBadge, fullTitle) = formatStreamDisplay(
                    movieTitle = movieViTitle,
                    year = yearStr,
                    season = if (isSeries) seasonNum else null,
                    episode = if (isSeries) episodeNum else null,
                    isSeries = isSeries,
                    filename = rawFileName,
                    sizeBytes = rawSizeBytes,
                    serverLabel = label,
                    maxRes = maxRes,
                    viDub = viDub,
                    viSubs = viSubs,
                    realWidth = 0,
                    realHeight = 0,
                    lockQuality = null
                )
                outStreams.add(
                    buildStreamEntry(
                        name = nameBadge,
                        title = fullTitle,
                        url = masterUrl,
                        slug = slug
                    )
                )
            }
        }

        return jsonReply(200, gson.toJson(mapOf("streams" to outStreams)))
    }

    private fun buildStreamEntry(
        name: String,
        title: String,
        url: String,
        slug: String
    ): Map<String, Any> = mapOf(
        "name" to name,
        "title" to title,
        "url" to url,
        "type" to "hls",
        "behaviorHints" to mapOf(
            "notWebReady" to false,
            "bingeGroup" to "fiml4k-$slug",
            "localProxy" to mapOf(
                "enabled" to true,
                "mode" to "hls-rewrite",
                "autoUnwrapTs" to true,
                "headers" to mapOf(
                    "Referer" to "${apiBaseUrl.trimEnd('/')}/",
                    "Origin" to apiBaseUrl.trimEnd('/')
                )
            )
        )
    )

    private data class MasterVariant(
        val quality: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long
    )

    private fun inspectMasterVariants(masterUrl: String): List<MasterVariant> {
        val text = fetchText(masterUrl) ?: return emptyList()
        val variants = ArrayList<MasterVariant>()
        val resRegex = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        val bwRegex = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue
            val rm = resRegex.find(trimmed)
            val bm = bwRegex.find(trimmed)
            val w = rm?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val h = rm?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val bw = bm?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val maxDim = maxOf(w, h)
            val minDim = minOf(w, h)
            val qKey = when {
                maxDim >= 3840 || minDim >= 1500 -> "4k"
                maxDim >= 1920 || minDim >= 800 -> "1080"
                maxDim >= 1280 || minDim >= 700 -> "720"
                else -> "480"
            }
            if (variants.none { it.quality == qKey }) {
                variants.add(MasterVariant(qKey, w, h, bw))
            }
        }
        val rank = mapOf("4k" to 4, "1080" to 3, "720" to 2, "480" to 1)
        variants.sortByDescending { rank[it.quality] ?: 0 }
        return variants
    }

    private data class ResolvedFiml4k(
        val slug: String,
        val title: String?,
        val year: String?,
        val titleData: JsonObject
    )

    private fun resolveTmdbToFiml4k(tmdbId: Long, stremioType: String): ResolvedFiml4k? {
        val kind = if (stremioType == "series") "tv" else "movie"
        val tmdbData = fetchTmdbJson("${tmdbBaseUrl.trimEnd('/')}/$kind/$tmdbId?language=en-US") ?: return null
        val title = (if (stremioType == "series") tmdbData.str("name") else tmdbData.str("title"))
            ?.takeIf { it.isNotBlank() } ?: return null
        val year = (if (stremioType == "series") tmdbData.str("first_air_date") else tmdbData.str("release_date"))
            ?.take(4)
        val slug = slugifyFiml4k(title)
        if (slug.isNotEmpty()) {
            val titleData = fetchTitleData(slug)
            val movie = extractMovieObject(titleData)
            if (movie != null && titleData != null && matchesTmdbEntry(movie, tmdbId, stremioType)) {
                return ResolvedFiml4k(movie.str("slug") ?: slug, title, year, titleData)
            }
        }
        // Fallback: search by title when site slug appends year or differs slightly
        val candidates = searchSuggest(title)?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
        for (item in candidates.take(6)) {
            val candSlug = item.str("slug") ?: continue
            val candType = fiml4kTypeToStremio(item.str("mediaType") ?: item.str("type"))
            if (candType != stremioType) continue
            val candData = fetchTitleData(candSlug) ?: continue
            val movie = extractMovieObject(candData) ?: continue
            if (movie.longOrNull("tmdbId") == tmdbId) {
                return ResolvedFiml4k(movie.str("slug") ?: candSlug, title, year, candData)
            }
        }
        return null
    }

    private fun resolveImdbToFiml4k(imdbId: String, stremioType: String): ResolvedFiml4k? {
        val cleanImdb = imdbId.trim().removePrefix("imdb:")
        if (!Regex("""^tt\d+$""", RegexOption.IGNORE_CASE).matches(cleanImdb)) return null
        val encoded = URLEncoder.encode(cleanImdb, StandardCharsets.UTF_8.name())
        val findData = fetchTmdbJson("${tmdbBaseUrl.trimEnd('/')}/find/$encoded?external_source=imdb_id") ?: return null
        val results = findData.getAsJsonArray(if (stremioType == "series") "tv_results" else "movie_results") ?: return null
        val tmdbId = results.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.longOrNull("id") ?: return null
        val resolved = resolveTmdbToFiml4k(tmdbId, stremioType) ?: return null
        val movie = extractMovieObject(resolved.titleData)
        val siteImdb = movie?.str("imdbId")
        if (!siteImdb.isNullOrBlank() && !siteImdb.equals(cleanImdb, ignoreCase = true)) {
            return null
        }
        return resolved
    }

    private fun matchesTmdbEntry(movie: JsonObject, tmdbId: Long, stremioType: String): Boolean {
        val siteTmdb = movie.longOrNull("tmdbId")
        if (siteTmdb != null && siteTmdb != tmdbId) return false
        val rawMediaType = movie.str("mediaType")
        if (rawMediaType != null && fiml4kTypeToStremio(rawMediaType) != stremioType) return false
        return true
    }

    private fun fetchTitleData(slug: String): JsonObject? {
        val encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8.name())
        return fetchJsonObject("${apiBaseUrl.trimEnd('/')}/api/title/$encodedSlug")
    }

    private fun fetchTmdbJson(urlWithoutKey: String): JsonObject? {
        val sep = if ('?' in urlWithoutKey) '&' else '?'
        val primary = TmdbCommunityKeys.primaryKey()
        val fallback = TmdbCommunityKeys.fallbackKey()
        val req1 = Request.Builder().url("$urlWithoutKey${sep}api_key=$primary").get().build()
        addonClient().newCall(req1).execute().use { resp ->
            if (resp.isSuccessful) {
                return parseJsonObject(resp.body?.string())
            }
            if ((resp.code != 401 && resp.code != 429) || fallback.isBlank() || fallback == primary) {
                return null
            }
        }
        val req2 = Request.Builder().url("$urlWithoutKey${sep}api_key=$fallback").get().build()
        return addonClient().newCall(req2).execute().use { resp ->
            if (resp.isSuccessful) parseJsonObject(resp.body?.string()) else null
        }
    }

    private fun fetchJsonObject(url: String): JsonObject? {
        val text = fetchText(url) ?: return null
        return parseJsonObject(text)
    }

    private fun fetchText(url: String): String? {
        val origin = apiBaseUrl.trimEnd('/')
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Referer", "$origin/")
            .header("Origin", origin)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
            .header("Cookie", "locale=vi")
            .get()
            .build()
        return runCatching {
            addonClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun parseJsonObject(text: String?): JsonObject? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val elem = JsonParser.parseString(raw)
            elem.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.getOrNull() ?: runCatching {
            val first = raw.indexOf('{')
            val last = raw.lastIndexOf('}')
            if (first >= 0 && last > first) {
                JsonParser.parseString(raw.substring(first, last + 1))
                    .takeIf(JsonElement::isJsonObject)?.asJsonObject
            } else null
        }.getOrNull()
    }

    private fun itemToMeta(item: JsonObject, forcedType: String): Map<String, Any?>? {
        val slug = item.str("slug")?.takeIf { it.isNotBlank() } ?: return null
        val type = forcedType.ifBlank { fiml4kTypeToStremio(item.str("mediaType") ?: item.str("type")) }
        val meta = LinkedHashMap<String, Any?>()
        meta["id"] = "fiml4k:$slug"
        meta["type"] = type
        meta["name"] = preferVi(item, "title").ifBlank { slug }
        meta["poster"] = preferVi(item, "poster")
        meta["posterShape"] = "poster"
        meta["background"] = item.str("backdrop").orEmpty()
        meta["description"] = preferVi(item, "overview")
        meta["releaseInfo"] = item.numStr("year")
        meta["imdbRating"] = item.numStr("score")
        meta["genres"] = extractGenres(item)
        item.str("imdbId")?.takeIf { it.isNotBlank() }?.let { meta["imdb_id"] = it }
        item.longOrNull("tmdbId")?.let { meta["tmdb_id"] = it }
        return meta
    }

    private fun extractMovieObject(data: JsonObject?): JsonObject? {
        if (data == null) return null
        return data.obj("movie") ?: data.obj("result") ?: data.takeIf { it.has("slug") || it.has("title") }
    }

    private fun extractSourcesForEpisode(data: JsonObject): List<JsonObject> = extractSources(data)

    private fun extractSources(data: JsonObject): List<JsonObject> {
        val direct = data.arrayObjects("sources")
        if (direct.isNotEmpty()) return direct
        return data.obj("movie")?.arrayObjects("sources").orEmpty()
    }

    private fun extractEpisodes(data: JsonObject): List<JsonObject> {
        val direct = data.arrayObjects("episodes")
        if (direct.isNotEmpty()) return direct
        val inMovie = data.obj("movie")?.arrayObjects("episodes").orEmpty()
        if (inMovie.isNotEmpty()) return inMovie
        val found = ArrayList<JsonObject>()
        fun walk(el: JsonElement, depth: Int) {
            if (depth > 5) return
            when {
                el.isJsonArray -> el.asJsonArray.forEach { walk(it, depth + 1) }
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    val hasEp = obj.has("episode") || obj.has("ep") || obj.has("episodeNumber") || obj.has("episode_number")
                    val hasSeason = obj.has("season") || obj.has("seasonNumber") || obj.has("season_number")
                    if (hasEp || hasSeason) {
                        found.add(obj)
                    }
                    obj.entrySet().forEach { (k, v) ->
                        if (k != "related") walk(v, depth + 1)
                    }
                }
            }
        }
        walk(data, 0)
        return found
    }

    private fun findMatchingEpisode(data: JsonObject, season: Int, episode: Int): JsonObject? =
        findMatchingEpisode(extractEpisodes(data), season, episode)

    private fun findMatchingEpisode(episodes: List<JsonObject>, season: Int, episode: Int): JsonObject? =
        episodes.firstOrNull { ep ->
            val s = ep.intOrNull("season") ?: ep.intOrNull("seasonNumber") ?: ep.intOrNull("season_number") ?: 1
            val e = ep.intOrNull("episode") ?: ep.intOrNull("ep") ?: ep.intOrNull("episodeNumber") ?: ep.intOrNull("episode_number") ?: 0
            s == season && e == episode
        }

    private fun extractGenres(obj: JsonObject): List<String> {
        val genresEl = obj.get("genres") ?: return emptyList()
        val arr = when {
            genresEl.isJsonArray -> genresEl.asJsonArray
            genresEl.isJsonObject -> {
                val gObj = genresEl.asJsonObject
                gObj.getAsJsonArray("vi") ?: gObj.getAsJsonArray("en")
                    ?: gObj.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
            }
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(String::isNotBlank) }
    }

    private fun preferVi(obj: JsonObject, key: String): String {
        val v = obj.get(key) ?: return ""
        if (v.isJsonNull) return ""
        if (v.isJsonPrimitive) return v.asString
        if (v.isJsonObject) {
            val o = v.asJsonObject
            return o.str("vi")?.takeIf { it.isNotBlank() }
                ?: o.str("en")?.takeIf { it.isNotBlank() }
                ?: o.entrySet().firstOrNull { it.value.isJsonPrimitive }?.value?.asString.orEmpty()
        }
        return ""
    }

    private fun resolveUpstreamUrl(path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        if (path.startsWith("//")) return "https:$path"
        return runCatching {
            URI(apiBaseUrl.trimEnd('/') + "/").resolve(path).toString()
        }.getOrElse { "${apiBaseUrl.trimEnd('/')}/${path.trimStart('/')}" }
    }

    private fun formatStreamDisplay(
        movieTitle: String,
        year: String,
        season: Int?,
        episode: Int?,
        isSeries: Boolean,
        filename: String,
        sizeBytes: Long,
        serverLabel: String,
        maxRes: String,
        viDub: Boolean,
        viSubs: Boolean,
        realWidth: Int,
        realHeight: Int,
        lockQuality: String?
    ): Pair<String, String> {
        val cleanText = filename.replace(Regex("""fiml4k(\.fun)?|film4k(\.net)?""", RegexOption.IGNORE_CASE), " ")
        val (resBadge, resText) = when {
            lockQuality == "4k" || realHeight >= 1500 || realWidth >= 3840 ->
                "4K UHD" to if (realWidth > 0 && realHeight > 0) "4K UHD (${realWidth}x${realHeight})" else "4K UHD (2160p)"
            lockQuality == "1080" || realHeight >= 800 || realWidth >= 1920 ->
                "1080p FHD" to if (realWidth > 0 && realHeight > 0) "1080p Full HD (${realWidth}x${realHeight})" else "1080p Full HD"
            lockQuality == "720" || realHeight >= 650 || realWidth >= 1200 ->
                "720p HD" to if (realWidth > 0 && realHeight > 0) "720p HD (${realWidth}x${realHeight})" else "720p HD"
            Regex("""4k|2160p""", RegexOption.IGNORE_CASE).containsMatchIn(maxRes) ->
                "4K UHD" to "4K UHD (2160p)"
            else -> "1080p FHD" to "1080p Full HD"
        }

        val isDV = Regex("""\b(dv|dovi|dolby\s*vision)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleanText)
        val isHDR = Regex("""\b(hdr|hdr10|hdr10\+)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleanText)
        val hdrBadge = when {
            isDV && isHDR -> "DV • HDR"
            isDV -> "Dolby Vision"
            isHDR -> "HDR"
            else -> ""
        }

        val voiceParts = ArrayList<String>()
        if (viDub) voiceParts.add("🎙️ Thuyết minh")
        if (viSubs) voiceParts.add("💬 Phụ đề Vietsub")
        if (voiceParts.isEmpty()) voiceParts.add("🌐 Âm thanh gốc")

        val sizeStr = if (sizeBytes > 0L) {
            if (sizeBytes >= 1024L * 1024L * 1024L) {
                String.format(java.util.Locale.US, "%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            } else {
                String.format(java.util.Locale.US, "%.0f MB", sizeBytes / (1024.0 * 1024.0))
            }
        } else ""

        val yearTag = if (year.isNotBlank()) " • $year" else ""
        val lineTitle = if (isSeries && season != null && episode != null) {
            "🎬 $movieTitle • Mùa $season - Tập $episode (S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')})$yearTag"
        } else {
            "🎬 $movieTitle$yearTag"
        }

        val videoDetails = listOfNotNull(resText.ifBlank { resBadge }, hdrBadge.takeIf { it.isNotBlank() }).joinToString(" • ")
        val extraParts = listOfNotNull(
            sizeStr.takeIf { it.isNotBlank() }?.let { "💾 $it" },
            "⚡ ${serverLabel.ifBlank { "Fiml4K CDN" }} (Local Proxy)"
        ).joinToString(" • ")

        val nameBadge = if (hdrBadge.isNotBlank()) "$ADDON_NAME\n[$resBadge • $hdrBadge]" else "$ADDON_NAME\n[$resBadge]"
        val fullTitle = listOf(lineTitle, "📺 $videoDetails", voiceParts.joinToString(" • "), extraParts).joinToString("\n")
        return nameBadge to fullTitle
    }

    private fun fiml4kTypeToStremio(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }

    internal fun slugifyFiml4k(title: String): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("""[\u0300-\u036f]"""), "")
        return stripped
            .lowercase()
            .replace("đ", "d")
            .replace("['’]".toRegex(), "")
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun jsonReply(statusCode: Int, json: String, cacheControl: String = "no-cache"): AddonHttpReply =
        AddonHttpReply(
            statusCode = statusCode,
            bodyBytes = json.toByteArray(StandardCharsets.UTF_8),
            cacheControl = cacheControl
        )

    private fun getCachedElement(key: String): JsonElement? {
        val entry = memoryCache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            memoryCache.remove(key, entry)
            return null
        }
        return entry.data
    }

    private fun putCachedElement(key: String, data: JsonElement, ttlMs: Long) {
        if (memoryCache.size > 200) {
            memoryCache.clear()
        }
        memoryCache[key] = CacheEntry(data = data, expiresAtMs = System.currentTimeMillis() + ttlMs)
    }

    private fun decodeUri(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.numStr(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayObjects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()

    companion object {
        const val ADDON_ID = "community.fiml4k.local"
        const val ADDON_VERSION = "1.0.2"
        const val ADDON_NAME = "Nothing-P • Fiml4K"
        const val ADDON_DESCRIPTION = "Phim 4K & Phim Bộ Vietsub (chạy trực tiếp trên thiết bị qua Local Proxy)"
        const val DEFAULT_API_BASE = "https://fiml4k.fun"
        const val DEFAULT_TMDB_BASE = "https://api.themoviedb.org/3"
        const val LOCAL_ADDON_DEFAULT_PORT = 47123
        const val BASE_PATH = "/addon/fiml4k"
        const val CANONICAL_BASE_URL = "http://127.0.0.1:$LOCAL_ADDON_DEFAULT_PORT$BASE_PATH"
        const val MANIFEST_URL = "$CANONICAL_BASE_URL/manifest.json"

        private const val API_PAGE_SIZE = 24
        private const val CATALOG_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
