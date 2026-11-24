package com.nuvio.tv.ui.screens.player

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Pure policies for dynamic / AI-translating addon subtitles (e.g. 2-request flows where the
 * first request starts background translation and returns a short status SRT, while a later
 * reload of the same track returns the translated subtitle).
 */
internal object AddonSubtitleTranslationPolicy {
    private val translationTargetKeys = setOf("target", "targetlang", "target_lang", "to", "tl")
    private val translationSourceKeys = setOf("source", "sourcelang", "source_lang", "from", "sl")
    private val statusMarkers = listOf(
        "đang dịch phụ đề",
        "reload phụ đề",
        "bấm reload",
        "translating subtitle"
    )

    fun isTranslatingSubtitleUrl(url: String): Boolean {
        val parsed = parseHttpParts(url) ?: return false
        if (parsed.path.contains("/translate-sub", ignoreCase = true)) {
            return true
        }
        val target = translationTargetKeys.firstNotNullOfOrNull { key ->
            parsed.queryParams[key]?.takeIf { it.isNotBlank() }
        } ?: return false
        val source = translationSourceKeys.firstNotNullOfOrNull { key ->
            parsed.queryParams[key]?.takeIf { it.isNotBlank() }
        }
        return source == null || !source.equals(target, ignoreCase = true)
    }

    fun effectiveAddonSubtitleLanguage(declaredLang: String, url: String): String? {
        val parsed = parseHttpParts(url)
        val targetParam = parsed?.let { parts ->
            translationTargetKeys.firstNotNullOfOrNull { key ->
                parts.queryParams[key]?.takeIf { it.isNotBlank() }
            }
        }
        if (targetParam != null && isTranslatingSubtitleUrl(url)) {
            return PlayerSubtitleUtils.normalizeLanguageCode(targetParam).takeIf { it.isNotBlank() }
        }
        if (isTranslatingSubtitleUrl(url)) {
            return null
        }
        return PlayerSubtitleUtils.normalizeLanguageCode(declaredLang).takeIf { it.isNotBlank() }
    }

    fun isEligibleForAutoSelection(url: String): Boolean = !isTranslatingSubtitleUrl(url)

    fun isEligibleForAutoSelection(subtitle: com.nuvio.tv.domain.model.Subtitle): Boolean =
        isEligibleForAutoSelection(subtitle.url)

    fun matchesPreferredVisibilityLanguage(
        subtitle: com.nuvio.tv.domain.model.Subtitle,
        targetLanguage: String,
    ): Boolean {
        if (PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, targetLanguage)) return true
        val effectiveLang = effectiveAddonSubtitleLanguage(subtitle.lang, subtitle.url)
        return effectiveLang != null && PlayerSubtitleUtils.matchesLanguageCode(effectiveLang, targetLanguage)
    }

    fun hasNoStoreCacheControl(responseHeaders: Map<String, String>): Boolean {
        val cacheControl = responseHeaders.entries
            .firstOrNull { (key, _) -> key.equals("cache-control", ignoreCase = true) }
            ?.value
            ?.lowercase(Locale.US)
            ?: return false
        return cacheControl.contains("no-store") ||
            cacheControl.contains("no-cache") ||
            cacheControl.contains("max-age=0")
    }

    fun shouldBypassSubtitleCache(
        url: String,
        responseHeaders: Map<String, String> = emptyMap(),
    ): Boolean = isTranslatingSubtitleUrl(url) || hasNoStoreCacheControl(responseHeaders)

    fun isStatusPlaceholderSubtitle(
        url: String,
        cueCount: Int,
        rawBody: String,
        responseHeaders: Map<String, String> = emptyMap(),
    ): Boolean {
        if (cueCount !in 1..3) return false
        if (isTranslatingSubtitleUrl(url)) return true
        val lowerBody = rawBody.lowercase(Locale.ROOT)
        if (statusMarkers.any { marker -> lowerBody.contains(marker) }) return true
        return hasNoStoreCacheControl(responseHeaders) && cueCount == 1 && rawBody.contains("⏳")
    }

    private data class ParsedHttpParts(
        val path: String,
        val queryParams: Map<String, String>,
    )

    private fun parseHttpParts(url: String): ParsedHttpParts? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val path = uri?.rawPath ?: trimmed.substringBefore('?').substringBefore('#')
        val rawQuery = uri?.rawQuery ?: trimmed.substringAfter('?', "").substringBefore('#')
        val queryParams = buildMap {
            if (rawQuery.isNotEmpty()) {
                rawQuery.split('&').forEach { pair ->
                    if (pair.isBlank()) return@forEach
                    val eqIndex = pair.indexOf('=')
                    val rawKey = if (eqIndex >= 0) pair.substring(0, eqIndex) else pair
                    val rawValue = if (eqIndex >= 0) pair.substring(eqIndex + 1) else ""
                    val key = decodeQueryComponent(rawKey).lowercase(Locale.US)
                    val value = decodeQueryComponent(rawValue).trim()
                    if (key.isNotEmpty() && value.isNotEmpty() && key !in this) {
                        put(key, value)
                    }
                }
            }
        }
        return ParsedHttpParts(path = path, queryParams = queryParams)
    }

    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
