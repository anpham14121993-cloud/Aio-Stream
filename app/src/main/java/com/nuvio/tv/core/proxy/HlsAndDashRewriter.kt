package com.nuvio.tv.core.proxy

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Thrown when a manifest advertises DRM (`SAMPLE-AES*`, Widevine, FairPlay, PlayReady, `ContentProtection`).
 * Aio-Stream's local proxy enforces a strict non-DRM policy and refuses to unwrap/modify DRM streams.
 */
class DrmProtectedStreamException(message: String) : IllegalStateException(message)

enum class ProxyAssetKind {
    HLS_PLAYLIST,
    HLS_SEGMENT,
    HLS_KEY,
    DASH_ASSET,
    MEDIA,
    SUBTITLE
}

/**
 * Generic URL builder passed into manifest rewriters so the rewriter remains pure and unit-testable.
 */
fun interface ProxyAssetUrlBuilder {
    fun buildUrl(
        kind: ProxyAssetKind,
        targetUrl: String,
        refererUrl: String?
    ): String
}

/**
 * Rewrites non-DRM HLS Master & Media `.m3u8` playlists and DASH `.mpd` manifests
 * so all sub-requests flow through Aio-Stream's local loopback proxy with original
 * request headers and playlist `Referer` preserved.
 */
object HlsAndDashRewriter {
    private val hlsDrmMethods = listOf(
        "SAMPLE-AES-CTR",
        "SAMPLE-AES-CENC",
        "SAMPLE-AES",
        "CENC",
        "CBCS"
    )

    private val drmKeyFormats = listOf(
        "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", // Widevine
        "9a04f079-9840-4286-ab92-e65be0885f95", // PlayReady
        "com.apple.streamingkeydelivery"         // FairPlay
    )

    private val uriAttrRegex = Regex("""URI="([^"]+)"""")
    private val baseUrlRegex = Regex("""(<BaseURL(?:\s[^>]*)?>)(.*?)(</BaseURL>)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val mpdOpenTagRegex = Regex("""(<MPD\b[^>]*>)""", setOf(RegexOption.IGNORE_CASE))
    private val dashDrmRegex = Regex("""<(?:\w+:)?(?:ContentProtection|pssh)\b""", setOf(RegexOption.IGNORE_CASE))

    fun containsHlsDrm(playlistText: String): Boolean {
        val lines = playlistText.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                !line.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true)
            ) {
                continue
            }
            val upper = line.uppercase()
            if (hlsDrmMethods.any { method ->
                    upper.contains("METHOD=$method") || upper.contains("METHOD=\"$method\"")
                }
            ) {
                return true
            }
            val lower = line.lowercase()
            if (drmKeyFormats.any { fmt -> lower.contains(fmt) }) {
                return true
            }
        }
        return false
    }

    fun containsDashDrm(mpdText: String): Boolean = dashDrmRegex.containsMatchIn(mpdText)

    fun rewriteHlsPlaylist(
        playlistText: String,
        playlistUrl: String,
        urlBuilder: ProxyAssetUrlBuilder
    ): String {
        if (containsHlsDrm(playlistText)) {
            throw DrmProtectedStreamException("DRM-protected HLS playlist cannot be rewritten by local proxy")
        }

        val endsWithNewline = playlistText.endsWith("\n")
        val lines = playlistText.split("\n")
        val out = ArrayList<String>(lines.size)
        var nextUriIsVariantPlaylist = false

        for (rawLine in lines) {
            val line = rawLine.removeSuffix("\r")
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() -> {
                    out.add(line)
                }

                trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    nextUriIsVariantPlaylist = true
                    out.add(line)
                }

                trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) -> {
                    out.add(rewriteUriAttribute(line, playlistUrl, ProxyAssetKind.HLS_PLAYLIST, urlBuilder))
                }

                trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-PART", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-PRELOAD-HINT", ignoreCase = true) -> {
                    out.add(rewriteUriAttribute(line, playlistUrl, ProxyAssetKind.HLS_SEGMENT, urlBuilder))
                }

                trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true) -> {
                    // METHOD=NONE has no URI; public AES-128 key gets rewritten with playlist Referer.
                    out.add(rewriteUriAttribute(line, playlistUrl, ProxyAssetKind.HLS_KEY, urlBuilder))
                }

                trimmed.startsWith("#") -> {
                    out.add(line)
                }

                else -> {
                    val resolved = resolveUrl(playlistUrl, trimmed)
                    val kind = if (nextUriIsVariantPlaylist) {
                        ProxyAssetKind.HLS_PLAYLIST
                    } else {
                        ProxyAssetKind.HLS_SEGMENT
                    }
                    nextUriIsVariantPlaylist = false
                    out.add(urlBuilder.buildUrl(kind, resolved, playlistUrl))
                }
            }
        }

        val joined = out.joinToString("\n")
        return if (endsWithNewline && !joined.endsWith("\n")) "$joined\n" else joined
    }

    /**
     * Rewrites `<BaseURL>` elements in non-DRM DASH MPD documents. If no `<BaseURL>` exists,
     * inserts one derived from the MPD's parent directory so relative `SegmentTemplate` URLs
     * route through the local proxy with session headers intact.
     */
    fun rewriteDashManifest(
        mpdText: String,
        mpdUrl: String,
        baseRelayPrefixBuilder: (resolvedBaseUrl: String) -> String
    ): String {
        if (containsDashDrm(mpdText)) {
            throw DrmProtectedStreamException("DRM-protected DASH manifest cannot be rewritten by local proxy")
        }

        if (baseUrlRegex.containsMatchIn(mpdText)) {
            return baseUrlRegex.replace(mpdText) { match ->
                val openTag = match.groupValues[1]
                val inner = match.groupValues[2].trim()
                val closeTag = match.groupValues[3]
                if (inner.isEmpty()) {
                    match.value
                } else {
                    val resolved = resolveUrl(mpdUrl, inner)
                    val proxiedBase = baseRelayPrefixBuilder(resolved)
                    "$openTag$proxiedBase$closeTag"
                }
            }
        }

        val parentBaseUrl = parentDirectoryUrl(mpdUrl)
        val proxiedRootBase = baseRelayPrefixBuilder(parentBaseUrl)
        return mpdOpenTagRegex.replace(mpdText) { match ->
            "${match.groupValues[1]}\n  <BaseURL>$proxiedRootBase</BaseURL>"
        }
    }

    private fun rewriteUriAttribute(
        line: String,
        playlistUrl: String,
        kind: ProxyAssetKind,
        urlBuilder: ProxyAssetUrlBuilder
    ): String {
        return uriAttrRegex.replace(line) { match ->
            val rawRef = match.groupValues[1]
            val resolved = resolveUrl(playlistUrl, rawRef)
            val proxied = urlBuilder.buildUrl(kind, resolved, playlistUrl)
            "URI=\"$proxied\""
        }
    }

    fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.startsWith("http://", ignoreCase = true) ||
            ref.startsWith("https://", ignoreCase = true) ||
            ref.startsWith("data:", ignoreCase = true)
        ) {
            return ref
        }
        return runCatching {
            URI(baseUrl).resolve(ref).toString()
        }.getOrElse { ref }
    }

    fun parentDirectoryUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val path = uri.rawPath.orEmpty()
            val dirPath = when {
                path.endsWith("/") -> path
                '/' in path -> path.substringBeforeLast('/') + "/"
                else -> "/"
            }
            URI(uri.scheme, uri.rawAuthority, dirPath, null, null).toString()
        }.getOrElse { url.substringBefore('?').substringBeforeLast('/') + "/" }
    }

    fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    fun encodeBase64Url(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            if (has1) sb.append(alphabet[(triple ushr 6) and 0x3F])
            if (has2) sb.append(alphabet[triple and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun decodeBase64Url(encoded: String): String? {
        val bytes = com.nuvio.tv.domain.model.LocalProxyHints.decodeBase64Flexible(encoded) ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }
}
