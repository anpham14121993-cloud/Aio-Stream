package com.nuvio.tv.core.proxy

/**
 * Parsed HTTP `Range: bytes=...` request for progressive media delivery and byte transforms.
 */
data class RangeSpec(
    val start: Long,
    val endInclusive: Long?
) {
    fun contentLengthOrNull(): Long? = endInclusive?.let { (it - start + 1L).coerceAtLeast(0L) }

    fun clampToTotal(totalLength: Long?): RangeSpec? {
        if (totalLength == null) return this
        if (totalLength <= 0L || start >= totalLength) return null
        val clampedEnd = endInclusive?.coerceAtMost(totalLength - 1L) ?: (totalLength - 1L)
        if (clampedEnd < start) return null
        return RangeSpec(start = start, endInclusive = clampedEnd)
    }
}

sealed interface RangeParseResult {
    data object NoRange : RangeParseResult
    data class SingleRange(val spec: RangeSpec) : RangeParseResult
    data class Unsatisfiable(val totalLength: Long?) : RangeParseResult
}

object RangeHeaderParser {
    fun parse(rangeHeader: String?, totalLength: Long?): RangeParseResult {
        val raw = rangeHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return RangeParseResult.NoRange
        if (!raw.startsWith("bytes=", ignoreCase = true)) {
            return RangeParseResult.NoRange
        }
        val specPart = raw.substringAfter('=')
            .substringBefore(',')
            .trim()
        if (specPart.isEmpty() || '-' !in specPart) {
            return RangeParseResult.Unsatisfiable(totalLength)
        }
        val dashIdx = specPart.indexOf('-')
        val startStr = specPart.substring(0, dashIdx).trim()
        val endStr = specPart.substring(dashIdx + 1).trim()

        if (startStr.isEmpty()) {
            val suffix = endStr.toLongOrNull() ?: return RangeParseResult.Unsatisfiable(totalLength)
            if (suffix <= 0L) return RangeParseResult.Unsatisfiable(totalLength)
            if (totalLength == null) {
                return RangeParseResult.Unsatisfiable(null)
            }
            if (totalLength <= 0L) return RangeParseResult.Unsatisfiable(totalLength)
            val start = (totalLength - suffix).coerceAtLeast(0L)
            return RangeParseResult.SingleRange(RangeSpec(start = start, endInclusive = totalLength - 1L))
        }

        val start = startStr.toLongOrNull() ?: return RangeParseResult.Unsatisfiable(totalLength)
        if (start < 0L) return RangeParseResult.Unsatisfiable(totalLength)
        if (totalLength != null && start >= totalLength) {
            return RangeParseResult.Unsatisfiable(totalLength)
        }

        val endInclusive = if (endStr.isEmpty()) {
            totalLength?.let { it - 1L }
        } else {
            val parsedEnd = endStr.toLongOrNull() ?: return RangeParseResult.Unsatisfiable(totalLength)
            if (parsedEnd < start) return RangeParseResult.Unsatisfiable(totalLength)
            totalLength?.let { parsedEnd.coerceAtMost(it - 1L) } ?: parsedEnd
        }

        return RangeParseResult.SingleRange(RangeSpec(start = start, endInclusive = endInclusive))
    }

    fun parseContentRangeTotal(contentRange: String?): Long? {
        val value = contentRange?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slashIndex = value.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex == value.lastIndex) return null
        val totalPart = value.substring(slashIndex + 1).trim()
        if (totalPart == "*") return null
        return totalPart.toLongOrNull()?.takeIf { it >= 0L }
    }
}

object HttpHeaderSanitizer {
    private val hopByHopHeaders = setOf(
        "host",
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailers",
        "transfer-encoding",
        "upgrade",
        "content-length",
        "accept-encoding"
    )

    fun sanitizeMap(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        val dedup = LinkedHashMap<String, Pair<String, String>>()
        for ((rawKey, rawValue) in headers) {
            val key = cleanToken(rawKey)
            val value = cleanValue(rawValue)
            if (key.isEmpty()) continue
            val lower = key.lowercase()
            if (lower in hopByHopHeaders) continue
            dedup[lower] = key to value
        }
        return dedup.values.associate { it.first to it.second }
    }

    fun merge(base: Map<String, String>, overlay: Map<String, String>): Map<String, String> {
        if (base.isEmpty()) return sanitizeMap(overlay)
        if (overlay.isEmpty()) return sanitizeMap(base)
        val merged = LinkedHashMap<String, Pair<String, String>>()
        for ((k, v) in sanitizeMap(base)) {
            merged[k.lowercase()] = k to v
        }
        for ((k, v) in sanitizeMap(overlay)) {
            merged[k.lowercase()] = k to v
        }
        return merged.values.associate { it.first to it.second }
    }

    fun findHeaderIgnoreCase(headers: Map<String, String>, name: String): String? {
        headers[name]?.let { return it }
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    fun hasHeaderIgnoreCase(headers: Map<String, String>, name: String): Boolean =
        findHeaderIgnoreCase(headers, name) != null

    private fun cleanToken(input: String): String =
        input.replace("\r", "").replace("\n", "").trim()

    private fun cleanValue(input: String): String =
        input.replace("\r", "").replace("\n", "").trim()
}
