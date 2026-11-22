package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Supported internal local proxy modes declared by addons via `behaviorHints.localProxy`.
 */
@Immutable
enum class LocalProxyMode {
    NONE,
    HTTP_HEADERS,
    HLS_REWRITE,
    DASH_REWRITE,
    HF_XOR_256,
    PREFIX_SKIP,
    MPEG_TS_UNWRAP
}

/**
 * Addon-supplied instructions describing how [com.nuvio.tv.core.proxy.LocalStreamProxy]
 * should handle this stream locally on-device without any external proxy server.
 */
@Immutable
data class LocalProxyHints(
    val enabled: Boolean? = null,
    val mode: String? = null,
    val xorKey: String? = null,
    val autoKey: Boolean? = null,
    val headerSize: Int? = null,
    val raw: Boolean? = null,
    val metadataUrl: String? = null,
    val skipBytes: Long? = null,
    val contentType: String? = null,
    val forceRewrite: Boolean? = null,
    val autoUnwrapTs: Boolean? = null,
    val headers: Map<String, String>? = null
) {
    fun isExplicitlyDisabled(): Boolean {
        if (enabled == false) return true
        return when (mode?.trim()?.lowercase()) {
            "none", "off", "false", "direct", "disabled" -> true
            else -> false
        }
    }

    fun resolvedMode(): LocalProxyMode? {
        if (isExplicitlyDisabled()) return LocalProxyMode.NONE
        val normalized = mode?.trim()?.lowercase()?.replace('_', '-')
        if (normalized != null) {
            return when (normalized) {
                "none", "off", "direct", "disabled" -> LocalProxyMode.NONE
                "http", "http-headers", "headers", "relay" -> LocalProxyMode.HTTP_HEADERS
                "hls", "hls-rewrite", "m3u8" -> LocalProxyMode.HLS_REWRITE
                "dash", "dash-rewrite", "mpd" -> LocalProxyMode.DASH_REWRITE
                "hf-xor-256", "hf-xor", "hfxor", "xor-256", "xor256", "p4k-hf", "hf" -> LocalProxyMode.HF_XOR_256
                "prefix-skip", "skip-prefix", "skip-bytes" -> LocalProxyMode.PREFIX_SKIP
                "mpegts-unwrap", "mpeg-ts-unwrap", "ts-unwrap", "png-ts" -> LocalProxyMode.MPEG_TS_UNWRAP
                else -> null
            }
        }
        return when {
            !xorKey.isNullOrBlank() || autoKey == true || headerSize != null || raw != null -> LocalProxyMode.HF_XOR_256
            (skipBytes ?: 0L) > 0L -> LocalProxyMode.PREFIX_SKIP
            autoUnwrapTs == true -> LocalProxyMode.HLS_REWRITE
            enabled == true -> LocalProxyMode.HTTP_HEADERS
            else -> null
        }
    }

    fun decodedXorKey(): ByteArray? = decodeKeyMaterial(xorKey)

    companion object {
        internal fun decodeKeyMaterial(raw: String?): ByteArray? {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (cleaned.length % 2 == 0 && cleaned.all { it.isHexDigit() }) {
                return ByteArray(cleaned.length / 2) { index ->
                    val hi = Character.digit(cleaned[index * 2], 16)
                    val lo = Character.digit(cleaned[index * 2 + 1], 16)
                    ((hi shl 4) or lo).toByte()
                }
            }
            return decodeBase64Flexible(cleaned)
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        internal fun decodeBase64Flexible(input: String): ByteArray? {
            val normalized = input
                .replace('-', '+')
                .replace('_', '/')
                .filterNot { it.isWhitespace() }
            if (normalized.isEmpty()) return null
            val padded = when (normalized.length % 4) {
                0 -> normalized
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> return null
            }
            val out = ByteArray((padded.length / 4) * 3)
            var outLen = 0
            var i = 0
            while (i < padded.length) {
                val c0 = base64Value(padded[i])
                val c1 = base64Value(padded[i + 1])
                val p2 = padded[i + 2]
                val p3 = padded[i + 3]
                if (c0 < 0 || c1 < 0) return null
                val c2 = if (p2 == '=') 0 else base64Value(p2).also { if (it < 0) return null }
                val c3 = if (p3 == '=') 0 else base64Value(p3).also { if (it < 0) return null }
                val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
                out[outLen++] = ((triple ushr 16) and 0xFF).toByte()
                if (p2 != '=') out[outLen++] = ((triple ushr 8) and 0xFF).toByte()
                if (p3 != '=') out[outLen++] = (triple and 0xFF).toByte()
                i += 4
            }
            return out.copyOf(outLen)
        }

        private fun base64Value(c: Char): Int = when (c) {
            in 'A'..'Z' -> c.code - 'A'.code
            in 'a'..'z' -> c.code - 'a'.code + 26
            in '0'..'9' -> c.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }
    }
}

@JsonClass(generateAdapter = true)
data class LocalProxyHintsDto(
    @Json(name = "enabled") val enabled: Boolean? = null,
    @Json(name = "mode") val mode: String? = null,
    @Json(name = "xorKey") val xorKey: String? = null,
    @Json(name = "autoKey") val autoKey: Boolean? = null,
    @Json(name = "headerSize") val headerSize: Int? = null,
    @Json(name = "raw") val raw: Boolean? = null,
    @Json(name = "metadataUrl") val metadataUrl: String? = null,
    @Json(name = "skipBytes") val skipBytes: Long? = null,
    @Json(name = "contentType") val contentType: String? = null,
    @Json(name = "forceRewrite") val forceRewrite: Boolean? = null,
    @Json(name = "autoUnwrapTs") val autoUnwrapTs: Boolean? = null,
    @Json(name = "headers") val headers: Map<String, String>? = null
) {
    fun toDomain(): LocalProxyHints = LocalProxyHints(
        enabled = enabled,
        mode = mode,
        xorKey = xorKey,
        autoKey = autoKey,
        headerSize = headerSize,
        raw = raw,
        metadataUrl = metadataUrl,
        skipBytes = skipBytes,
        contentType = contentType,
        forceRewrite = forceRewrite,
        autoUnwrapTs = autoUnwrapTs,
        headers = headers?.takeIf { it.isNotEmpty() }
    )
}
