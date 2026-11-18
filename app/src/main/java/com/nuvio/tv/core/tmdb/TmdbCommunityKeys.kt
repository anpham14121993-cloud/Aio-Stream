package com.nuvio.tv.core.tmdb

import com.nuvio.tv.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Provides preloaded community TMDB API keys with automatic failover when rate-limited or unauthorized,
 * so metadata enrichment in Vietnamese works immediately without manual key setup.
 */
object TmdbCommunityKeys {
    const val COMMUNITY_PRIMARY_KEY = "b97222227e0440713868b5d5b71898b0"
    const val COMMUNITY_FALLBACK_KEY = "c8af09ffecd8ade9a74ba402c4d27485"

    fun primaryKey(): String =
        BuildConfig.TMDB_API_KEY.trim().ifBlank { COMMUNITY_PRIMARY_KEY }

    fun fallbackKey(): String =
        BuildConfig.TMDB_FALLBACK_API_KEY.trim().ifBlank { COMMUNITY_FALLBACK_KEY }
}

/**
 * Ensures every TMDB HTTP call carries a valid community/configured key and transparently
 * retries once with the community fallback key on HTTP 401 or 429.
 */
class TmdbCommunityKeyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        val primary = TmdbCommunityKeys.primaryKey()
        val fallback = TmdbCommunityKeys.fallbackKey()

        val currentKey = url.queryParameter("api_key").orEmpty().trim()
        val firstRequest = if (currentKey.isEmpty()) {
            val updatedUrl = url.newBuilder()
                .setQueryParameter("api_key", primary)
                .build()
            original.newBuilder().url(updatedUrl).build()
        } else {
            original
        }

        val response = chain.proceed(firstRequest)
        if ((response.code == 401 || response.code == 429) && fallback.isNotBlank()) {
            val usedKey = firstRequest.url.queryParameter("api_key").orEmpty().trim()
            if (usedKey != fallback) {
                response.close()
                val retryUrl = firstRequest.url.newBuilder()
                    .setQueryParameter("api_key", fallback)
                    .build()
                return chain.proceed(firstRequest.newBuilder().url(retryUrl).build())
            }
        }
        return response
    }
}
