package com.nuvio.tv.core.proxy

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * Resolves user-supplied GitHub repository / raw URLs and parses `repo.json` addon catalogs.
 */
object GitHubAddonRepositoryParser {

    private val SAFE_ID_CHARS = Regex("""[^a-zA-Z0-9._-]""")
    private val SHORTHAND_REPO = Regex("""^([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)$""")
    private val GITHUB_ROOT_REPO = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/?#]+?)(?:\.git)?/?$""",
        RegexOption.IGNORE_CASE
    )
    private val GITHUB_TREE_REPO = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/tree/([^/]+)(?:/(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val GITHUB_BLOB_FILE = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun safeAddonId(rawId: String): String =
        rawId.trim()
            .replace(SAFE_ID_CHARS, "-")
            .trim('-', '.')
            .ifBlank { "local-js-addon" }

    fun candidateRepoUrls(rawInput: String): List<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        SHORTHAND_REPO.matchEntire(trimmed)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/repo.json"
            )
        }

        GITHUB_BLOB_FILE.matchEntire(trimmed)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3]
            val filePath = m.groupValues[4]
            return listOf("https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath")
        }

        GITHUB_TREE_REPO.matchEntire(trimmed)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3]
            val subpath = m.groupValues[4].trim('/')
            val base = if (subpath.isEmpty()) {
                "https://raw.githubusercontent.com/$owner/$repo/$branch"
            } else {
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$subpath"
            }
            return listOf("$base/repo.json")
        }

        GITHUB_ROOT_REPO.matchEntire(trimmed)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/repo.json"
            )
        }

        val noQuery = trimmed.substringBefore('?').substringBefore('#').lowercase()
        return when {
            noQuery.endsWith(".js") || noQuery.endsWith(".json") -> listOf(trimmed)
            trimmed.endsWith("/") -> listOf("${trimmed}repo.json", trimmed.trimEnd('/'))
            else -> listOf(trimmed, "$trimmed/repo.json")
        }
    }

    fun resolveScriptUrl(repoUrl: String, entryPath: String): String {
        val trimmed = entryPath.trim()
        GITHUB_BLOB_FILE.matchEntire(trimmed)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
        }
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching { URI(repoUrl).resolve(trimmed).toString() }
            .getOrElse { trimmed }
    }

    fun isStandaloneScriptResponse(resolvedUrl: String, body: String): Boolean {
        val noQuery = resolvedUrl.substringBefore('?').substringBefore('#').lowercase()
        if (noQuery.endsWith(".js")) return true
        val trimmed = body.trimStart()
        return !trimmed.startsWith("{") && !trimmed.startsWith("[")
    }

    fun parseRepoJson(
        body: String,
        resolvedRepoUrl: String,
        originalRepoUrl: String
    ): GitHubAddonCatalogResult? {
        val root = runCatching {
            JsonParser.parseString(body.trim()).takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.getOrNull() ?: return null

        val rawList = root.getAsJsonArray("addons")
            ?: root.getAsJsonArray("plugins")
            ?: return null

        val repoName = root.str("name")?.ifBlank { null } ?: "Kho Addon GitHub"
        val repoDescription = root.str("description")?.takeIf { it.isNotBlank() }

        val entries = rawList.mapNotNull { elem ->
            val obj = elem.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val entryPath = obj.str("entry")
                ?: obj.str("scriptUrl")
                ?: obj.str("url")
                ?: return@mapNotNull null
            val rawId = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val safeId = safeAddonId(rawId)
            val name = obj.str("name")?.ifBlank { safeId } ?: safeId
            val version = obj.str("version")?.ifBlank { "1.0.0" } ?: "1.0.0"
            val desc = obj.str("description").orEmpty()
            val types = obj.getAsJsonArray("types")
                ?.mapNotNull { t -> t.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(String::isNotBlank) }
                ?.ifEmpty { listOf("movie", "series") }
                ?: listOf("movie", "series")
            val icon = obj.str("icon")?.takeIf { it.isNotBlank() }
                ?.let { resolveScriptUrl(resolvedRepoUrl, it) }

            GitHubAddonRepoEntry(
                id = safeId,
                name = name,
                version = version,
                description = desc,
                types = types,
                scriptUrl = resolveScriptUrl(resolvedRepoUrl, entryPath),
                repoUrl = originalRepoUrl.trim().ifBlank { resolvedRepoUrl },
                icon = icon
            )
        }

        return GitHubAddonCatalogResult(
            repoName = repoName,
            description = repoDescription,
            repoUrl = resolvedRepoUrl,
            entries = entries
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim()
}
