package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbCommunityKeys
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListRatingsDiskCache
import com.nuvio.tv.data.mdblist.MdbListRatingsLoader
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private data class MediaRef(val provider: String, val id: String, val mediaType: String)

internal class PublicCommunityRatingsSource(
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val okHttpClient: OkHttpClient
) {
    private data class CachedEntry(val ratings: MDBListRatings, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CachedEntry>()

    suspend fun getRatings(
        imdbId: String?,
        tmdbId: Int?,
        mediaType: String
    ): MDBListRatings? = withContext(Dispatchers.IO) {
        val key = "${imdbId ?: "-"}:${tmdbId ?: "-"}:$mediaType"
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expiresAtMs > now) return@withContext it.ratings }

        val cinemetaType = if (mediaType == "show") "series" else "movie"
        val resolvedTmdbId = tmdbId ?: imdbId?.let { runCatching { tmdbService.imdbToTmdb(it, cinemetaType) }.getOrNull() }

        val ratings = coroutineScope {
            val imdbScoreDeferred = async {
                imdbId?.let { fetchCinemetaImdbScore(cinemetaType, it) }
            }
            val tmdbScoreDeferred = async {
                resolvedTmdbId?.let { fetchTmdbScore(mediaType, it) }
            }
            val imdbScore = imdbScoreDeferred.await()
            val tmdbScore = tmdbScoreDeferred.await()
            if (imdbScore == null && tmdbScore == null) null
            else MDBListRatings(imdb = imdbScore, tmdb = tmdbScore)
        } ?: return@withContext null

        cache[key] = CachedEntry(ratings, now + 30L * 60L * 1000L)
        ratings
    }

    private fun fetchCinemetaImdbScore(cinemetaType: String, imdbId: String): Double? {
        return runCatching {
            val req = Request.Builder()
                .url("https://v3-cinemeta.strem.io/meta/$cinemetaType/$imdbId.json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                val ratingStr = JSONObject(body).optJSONObject("meta")?.optString("imdbRating")
                ratingStr?.toDoubleOrNull()?.takeIf { it > 0.0 }
            }
        }.getOrNull()
    }

    private suspend fun fetchTmdbScore(mediaType: String, tmdbId: Int): Double? {
        return runCatching {
            val apiKey = TmdbCommunityKeys.primaryKey()
            val resp = if (mediaType == "show") {
                tmdbApi.getTvDetails(tmdbId, apiKey, "vi-VN")
            } else {
                tmdbApi.getMovieDetails(tmdbId, apiKey, "vi-VN")
            }
            val vote = resp.body()?.voteAverage ?: return null
            if (vote > 0.0) kotlin.math.round(vote * 10.0) else null
        }.getOrNull()
    }
}

@Singleton
class MDBListRepository internal constructor(
    private val api: MdbListRatingsClient,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService,
    private val ratingsLoader: MdbListRatingsLoader,
    private val publicSource: PublicCommunityRatingsSource? = null
) {
    @Inject constructor(
        api: MdbListRatingsClient,
        settingsDataStore: MDBListSettingsDataStore,
        tmdbService: TmdbService,
        tmdbApi: TmdbApi,
        @Named("validated") okHttpClient: OkHttpClient,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ) : this(
        api = api,
        settingsDataStore = settingsDataStore,
        tmdbService = tmdbService,
        ratingsLoader = MdbListRatingsLoader(api, MdbListRatingsDiskCache(context)),
        publicSource = PublicCommunityRatingsSource(tmdbService, tmdbApi, okHttpClient)
    )

    fun isAvailable(settings: MDBListSettings): Boolean =
        settings.enabled && (api.credential(settings.apiKey) != null || publicSource != null)

    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val credential = api.credential(settings.apiKey)

        val mediaType = normalizeMediaType(itemType)
        val ref = resolveMediaRef(
            meta = Meta(
                id = itemId,
                type = when (mediaType) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        val mdb = if (credential != null) {
            ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.imdb
        } else null
        if (mdb != null) return mdb

        return publicSource?.let { src ->
            val imdb = if (ref.provider == "imdb") ref.id else null
            val tmdb = if (ref.provider == "tmdb") ref.id.toIntOrNull() else null
            src.getRatings(imdb, tmdb, mediaType)?.imdb
        }
    }

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        if (!settings.hasEnabledProviders()) return null

        val credential = api.credential(settings.apiKey)
        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val ref = resolveMediaRef(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val allRatings = if (credential != null) {
            ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)
        } else {
            val imdb = extractImdbId(meta.id) ?: extractImdbId(fallbackItemId) ?: extractImdbId(meta.imdbId)
            val tmdb = (extractPrefixedId(meta.id, "tmdb") ?: extractPrefixedId(fallbackItemId, "tmdb"))?.toIntOrNull()
            publicSource?.getRatings(imdb, tmdb, mediaType)
        } ?: if (publicSource != null) {
            // Network/rate-limit fallback when MDBList API call itself fails
            val imdb = extractImdbId(meta.id) ?: extractImdbId(fallbackItemId) ?: extractImdbId(meta.imdbId)
            val tmdb = (extractPrefixedId(meta.id, "tmdb") ?: extractPrefixedId(fallbackItemId, "tmdb"))?.toIntOrNull()
            publicSource.getRatings(imdb, tmdb, mediaType)
        } else null

        val ratings = allRatings?.let { r ->
            MDBListRatings(
                trakt = r.trakt.takeIf { settings.showTrakt },
                imdb = r.imdb.takeIf { settings.showImdb },
                tmdb = r.tmdb.takeIf { settings.showTmdb },
                letterboxd = r.letterboxd.takeIf { settings.showLetterboxd },
                tomatoes = r.tomatoes.takeIf { settings.showTomatoes },
                audience = r.audience.takeIf { settings.showAudience },
                metacritic = r.metacritic.takeIf { settings.showMetacritic },
                mal = r.mal.takeIf { settings.showMal },
                tomatoesCertified = settings.showTomatoes && r.tomatoesCertified,
                audienceCertified = settings.showAudience && r.audienceCertified
            )
        }?.takeUnless { it.isEmpty() } ?: return null

        return MDBListRatingsResult(ratings, hasImdbRating = ratings.imdb != null)
    }

    private fun MDBListSettings.hasEnabledProviders(): Boolean =
        showTrakt || showImdb || showTmdb || showLetterboxd || showTomatoes || showAudience || showMetacritic || showMal

    private suspend fun resolveMediaRef(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): MediaRef? {
        extractImdbId(meta.id)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(fallbackItemId)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(meta.imdbId)?.let { return MediaRef("imdb", it, mediaType) }

        val tmdbId = extractPrefixedId(meta.id, "tmdb")
            ?: extractPrefixedId(fallbackItemId, "tmdb")

        if (tmdbId != null) {
            return MediaRef("tmdb", tmdbId, mediaType)
        }

        val malId = extractPrefixedId(meta.id, "mal")
            ?: extractPrefixedId(fallbackItemId, "mal")
        if (malId != null) return MediaRef("mal", malId, "any")

        val tvdbId = extractPrefixedId(meta.id, "tvdb")
            ?: extractPrefixedId(fallbackItemId, "tvdb")
        if (tvdbId != null) return MediaRef("tvdb", tvdbId, mediaType)

        return null
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractPrefixedId(rawId: String?, prefix: String): String? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("$prefix:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}
