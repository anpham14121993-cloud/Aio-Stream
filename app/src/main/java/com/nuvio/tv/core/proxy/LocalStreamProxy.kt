package com.nuvio.tv.core.proxy

import com.nuvio.tv.domain.model.LocalProxyHints
import com.nuvio.tv.domain.model.LocalProxyMode
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class ProxyInputContext(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val hints: LocalProxyHints? = null,
    val forExternalPlayer: Boolean = false
)

data class ProxyDecision(
    val mode: LocalProxyMode,
    val targetUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val hints: LocalProxyHints? = null
)

fun interface LocalProxyRule {
    fun evaluate(context: ProxyInputContext): ProxyDecision?
}

data class ProxyPreparationResult(
    val playUrl: String,
    val playHeaders: Map<String, String>,
    val originalUrl: String,
    val originalHeaders: Map<String, String>,
    val proxied: Boolean,
    val mode: LocalProxyMode = LocalProxyMode.NONE
)

/**
 * Thread-safe bounded cache remembering `LocalProxyHints` from `Stream` metadata so downstream
 * navigation routes that carry only `(url, headers, mimeType)` still know how to proxy/decode.
 */
object LocalProxyHintsStore {
    private const val MAX_ENTRIES = 512
    private val byUrl = Collections.synchronizedMap(
        object : LinkedHashMap<String, LocalProxyHints>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LocalProxyHints>?): Boolean =
                size > MAX_ENTRIES
        }
    )

    fun remember(url: String?, hints: LocalProxyHints?) {
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (hints == null) return
        byUrl[cleanUrl] = hints
    }

    fun get(url: String?): LocalProxyHints? {
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byUrl[cleanUrl]
    }

    fun clear() {
        byUrl.clear()
    }
}

/**
 * Extensible rule engine determining whether a given stream should be served locally through
 * [LocalStreamProxy] or passed directly untouched.
 *
 * Non-proxied addons and direct CDN/Debrid/Torrent/YouTube streams pass through with zero changes.
 */
class LocalProxyRuleRegistry {
    private val customRules = CopyOnWriteArrayList<LocalProxyRule>()

    fun registerRule(rule: LocalProxyRule) {
        customRules.addIfAbsent(rule)
    }

    fun unregisterRule(rule: LocalProxyRule) {
        customRules.remove(rule)
    }

    fun clearCustomRules() {
        customRules.clear()
    }

    fun evaluate(
        context: ProxyInputContext,
        settings: SettingsSnapshot
    ): ProxyDecision? {
        if (!settings.localProxyEnabled) return null
        val url = context.url.trim()
        if (url.isEmpty() || !isRemoteHttpUrl(url)) return null

        val effectiveHints = context.hints ?: LocalProxyHintsStore.get(url)
        if (effectiveHints?.isExplicitlyDisabled() == true) {
            return null
        }

        // 1. Registered custom rules from future addons or plugins
        for (rule in customRules) {
            val match = rule.evaluate(context.copy(hints = effectiveHints))
            if (match != null) {
                return if (match.mode == LocalProxyMode.NONE) null else match
            }
        }

        // 2. Explicit addon declaration via `behaviorHints.localProxy`
        val explicitMode = effectiveHints?.resolvedMode()
        if (explicitMode != null) {
            if (explicitMode == LocalProxyMode.NONE) return null
            val targetUrl = if (explicitMode == LocalProxyMode.HF_XOR_256) {
                unwrapHfWrapperUrl(url) ?: url
            } else {
                url
            }
            return ProxyDecision(
                mode = explicitMode,
                targetUrl = targetUrl,
                headers = HttpHeaderSanitizer.merge(context.headers, effectiveHints.headers ?: emptyMap()),
                hints = effectiveHints
            )
        }

        // 3. HuggingFace proxy-wrapper unwrapping (`/hf/datasets/...` or `/hf/buckets/...` emitted
        // by legacy worker/server endpoints) -> route directly on-device to huggingface.co.
        val unwrappedHf = unwrapHfWrapperUrl(url)
        if (unwrappedHf != null) {
            return ProxyDecision(
                mode = LocalProxyMode.HF_XOR_256,
                targetUrl = unwrappedHf,
                headers = context.headers,
                hints = LocalProxyHints(
                    enabled = true,
                    mode = "hf-xor-256",
                    autoKey = true,
                    contentType = context.mimeType
                )
            )
        }

        // 4. Optional header-preserving proxy for HLS / DASH / External players when enabled
        if (!settings.localProxyHeadersEnabled) return null

        val hasCustomHeaders = hasRelayRelevantHeaders(context.headers)
        if (!hasCustomHeaders) return null

        val isHls = isHlsUrlOrMime(url, context.mimeType)
        if (isHls) {
            return ProxyDecision(
                mode = LocalProxyMode.HLS_REWRITE,
                targetUrl = url,
                headers = context.headers,
                hints = effectiveHints
            )
        }

        val isDash = isDashUrlOrMime(url, context.mimeType)
        if (isDash) {
            return ProxyDecision(
                mode = LocalProxyMode.DASH_REWRITE,
                targetUrl = url,
                headers = context.headers,
                hints = effectiveHints
            )
        }

        if (context.forExternalPlayer) {
            return ProxyDecision(
                mode = LocalProxyMode.HTTP_HEADERS,
                targetUrl = url,
                headers = context.headers,
                hints = effectiveHints
            )
        }

        // Direct streams without explicit `localProxy` hints, without HLS/DASH manifests, and
        // for internal players pass straight through untouched.
        return null
    }

    data class SettingsSnapshot(
        val localProxyEnabled: Boolean = true,
        val localProxyHeadersEnabled: Boolean = true
    )

    companion object {
        private val hfPathRegex = Regex("""^/hf/((?:datasets|buckets|spaces)/.+)$""")
        private val hfDoubleExtRegex = Regex("""(\.[a-z0-9]+)\.mkv$""", RegexOption.IGNORE_CASE)

        /**
         * If an addon returns a wrapper URL such as `https://worker.example/hf/datasets/owner/repo/resolve/main/file.mp4`,
         * converts it directly back to `https://huggingface.co/datasets/owner/repo/resolve/main/file.mp4`
         * so Aio-Stream fetches straight from HuggingFace without touching any external proxy server.
         */
        fun unwrapHfWrapperUrl(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host == "huggingface.co" || host.endsWith(".huggingface.co")) {
                return null
            }
            val rawPath = uri.rawPath ?: return null
            val match = hfPathRegex.matchEntire(rawPath) ?: return null
            var hfPath = "/${match.groupValues[1]}"
            if (hfDoubleExtRegex.containsMatchIn(hfPath)) {
                hfPath = hfDoubleExtRegex.replace(hfPath, "$1")
            }
            val filteredQuery = filterProxyWrapperQuery(uri.rawQuery)
            return URI(
                "https",
                "huggingface.co",
                hfPath,
                filteredQuery,
                null
            ).toASCIIString()
        }

        private fun filterProxyWrapperQuery(rawQuery: String?): String? {
            if (rawQuery.isNullOrBlank()) return null
            val proxyOnlyParams = setOf("token", "sig", "exp", "meta", "raw", "key", "xorkey", "headersize", "autokey")
            val kept = rawQuery.split('&').filter { pair ->
                val name = pair.substringBefore('=').lowercase()
                name.isNotEmpty() && name !in proxyOnlyParams
            }
            return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
        }

        fun isRemoteHttpUrl(url: String): Boolean {
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                return false
            }
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
            return host != "127.0.0.1" && host != "localhost" && host != "::1" && host != "[::1]"
        }

        fun isHlsUrlOrMime(url: String, mimeType: String?): Boolean {
            val lowerMime = mimeType?.lowercase().orEmpty()
            if (lowerMime.contains("mpegurl") || lowerMime.contains("hls")) return true
            val clean = url.substringBefore('?').substringBefore('#').lowercase()
            return clean.endsWith(".m3u8") || clean.contains("/playlist.m3u8")
        }

        fun isDashUrlOrMime(url: String, mimeType: String?): Boolean {
            val lowerMime = mimeType?.lowercase().orEmpty()
            if (lowerMime.contains("dash+xml")) return true
            val clean = url.substringBefore('?').substringBefore('#').lowercase()
            return clean.endsWith(".mpd")
        }

        fun hasRelayRelevantHeaders(headers: Map<String, String>): Boolean {
            if (headers.isEmpty()) return false
            return headers.keys.any { key ->
                val lower = key.lowercase()
                lower == "referer" ||
                    lower == "origin" ||
                    lower == "cookie" ||
                    lower == "authorization" ||
                    lower == "user-agent" ||
                    lower.startsWith("x-")
            }
        }
    }
}

/**
 * Main entry point for Aio-Stream's on-device Local Stream Proxy (`127.0.0.1:<port>`).
 */
class LocalStreamProxy(
    httpClientProvider: () -> OkHttpClient = { defaultHttpClient }
) {
    val server: LocalProxyHttpServer = LocalProxyHttpServer(httpClientProvider)
    val rules: LocalProxyRuleRegistry = LocalProxyRuleRegistry()

    @Volatile
    private var settings = LocalProxyRuleRegistry.SettingsSnapshot()

    fun updateSettings(localProxyEnabled: Boolean, localProxyHeadersEnabled: Boolean) {
        settings = LocalProxyRuleRegistry.SettingsSnapshot(
            localProxyEnabled = localProxyEnabled,
            localProxyHeadersEnabled = localProxyHeadersEnabled
        )
    }

    fun currentSettings(): LocalProxyRuleRegistry.SettingsSnapshot = settings

    fun portOrNull(): Int? = server.port.takeIf { it > 0 }

    fun ensureStarted(preferredPort: Int = 0): Int = server.start(preferredPort)

    fun stop() {
        server.stop()
    }

    fun rememberHints(url: String?, hints: LocalProxyHints?) {
        LocalProxyHintsStore.remember(url, hints)
    }

    fun prepareStreamForPlayback(
        url: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        hints: LocalProxyHints? = null,
        forExternalPlayer: Boolean = false
    ): ProxyPreparationResult {
        val originalUrl = originalUrlOf(url) ?: url
        val sanitizedOriginalHeaders = HttpHeaderSanitizer.sanitizeMap(headers)
        if (hints != null) {
            LocalProxyHintsStore.remember(originalUrl, hints)
        }

        val effectiveHints = hints ?: LocalProxyHintsStore.get(originalUrl)
        val decision = rules.evaluate(
            context = ProxyInputContext(
                url = originalUrl,
                headers = sanitizedOriginalHeaders,
                mimeType = mimeType,
                hints = effectiveHints,
                forExternalPlayer = forExternalPlayer
            ),
            settings = settings
        )

        if (decision == null || decision.mode == LocalProxyMode.NONE) {
            return ProxyPreparationResult(
                playUrl = originalUrl,
                playHeaders = sanitizedOriginalHeaders,
                originalUrl = originalUrl,
                originalHeaders = sanitizedOriginalHeaders,
                proxied = false,
                mode = LocalProxyMode.NONE
            )
        }

        val session = server.registerSession(
            originalUrl = originalUrl,
            targetUrl = decision.targetUrl.ifBlank { originalUrl },
            mode = decision.mode,
            headers = decision.headers,
            hints = decision.hints ?: effectiveHints
        )
        val entryRoute = when (decision.mode) {
            LocalProxyMode.HLS_REWRITE -> "hls"
            LocalProxyMode.DASH_REWRITE -> "dash"
            else -> "media"
        }
        val playUrl = server.buildSessionEntryUrl(session, entryRoute)
        return ProxyPreparationResult(
            playUrl = playUrl,
            playHeaders = emptyMap(),
            originalUrl = originalUrl,
            originalHeaders = sanitizedOriginalHeaders,
            proxied = true,
            mode = decision.mode
        )
    }

    fun prepareSubtitleForPlayback(
        url: String,
        headers: Map<String, String>? = null,
        forExternalPlayer: Boolean = false
    ): Pair<String, Map<String, String>> {
        if (!settings.localProxyEnabled || (!settings.localProxyHeadersEnabled && !forExternalPlayer)) {
            return url to (headers ?: emptyMap())
        }
        val effectiveHeaders = HttpHeaderSanitizer.sanitizeMap(headers)
        if (effectiveHeaders.isEmpty() || !LocalProxyRuleRegistry.isRemoteHttpUrl(url)) {
            return url to effectiveHeaders
        }
        val session = server.registerSession(
            originalUrl = url,
            targetUrl = url,
            mode = LocalProxyMode.HTTP_HEADERS,
            headers = effectiveHeaders,
            hints = null
        )
        return server.buildSessionEntryUrl(session, "sub") to emptyMap()
    }

    fun isProxyUrl(url: String?): Boolean {
        val candidate = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return extractSessionId(candidate) != null && server.getSession(extractSessionId(candidate)!!) != null ||
            isLoopbackProxySyntax(candidate)
    }

    fun originalUrlOf(url: String?): String? {
        val candidate = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sid = extractSessionId(candidate) ?: return null
        return server.getSession(sid)?.originalUrl
    }

    fun originalHeadersOf(url: String?): Map<String, String>? {
        val candidate = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sid = extractSessionId(candidate) ?: return null
        return server.getSession(sid)?.headers
    }

    private fun extractSessionId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "127.0.0.1" && host != "localhost") return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/p/")) return null
        val rest = path.removePrefix("/p/")
        return rest.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun isLoopbackProxySyntax(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        return (host == "127.0.0.1" || host == "localhost") && path.startsWith("/p/")
    }

    companion object {
        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        @Volatile
        private var configuredHttpClientProvider: () -> OkHttpClient = { defaultHttpClient }

        fun configureHttpClientProvider(provider: () -> OkHttpClient) {
            configuredHttpClientProvider = provider
        }

        val shared: LocalStreamProxy by lazy {
            LocalStreamProxy(httpClientProvider = { configuredHttpClientProvider() })
        }
    }
}
