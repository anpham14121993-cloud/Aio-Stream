package com.nuvio.tv.core.proxy

import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent disk-backed store for downloaded online JS Stremio addons (`*.js` + `*.meta.json`).
 */
class LocalJsAddonStore(
    private val storageDir: File
) {
    private val gson = Gson()
    private val lock = Any()
    private val addons = ConcurrentHashMap<String, StoredJsAddon>()

    init {
        loadFromDisk()
    }

    fun listInstalled(): List<InstalledLocalJsAddon> =
        addons.values
            .map { it.meta }
            .sortedBy { it.name.lowercase() }

    fun get(addonId: String): StoredJsAddon? {
        val safeId = GitHubAddonRepositoryParser.safeAddonId(addonId)
        return addons[safeId]
    }

    fun save(meta: InstalledLocalJsAddon, scriptCode: String) {
        val safeId = GitHubAddonRepositoryParser.safeAddonId(meta.id)
        val normalizedMeta = meta.copy(id = safeId)
        synchronized(lock) {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            val scriptFile = File(storageDir, "$safeId$SCRIPT_EXT")
            val metaFile = File(storageDir, "$safeId$META_EXT")
            scriptFile.writeText(scriptCode, StandardCharsets.UTF_8)
            metaFile.writeText(gson.toJson(normalizedMeta), StandardCharsets.UTF_8)
            addons[safeId] = StoredJsAddon(normalizedMeta, scriptCode)
        }
    }

    fun remove(addonId: String): Boolean {
        val safeId = GitHubAddonRepositoryParser.safeAddonId(addonId)
        synchronized(lock) {
            val removed = addons.remove(safeId) != null
            val scriptFile = File(storageDir, "$safeId$SCRIPT_EXT")
            val metaFile = File(storageDir, "$safeId$META_EXT")
            if (scriptFile.exists()) scriptFile.delete()
            if (metaFile.exists()) metaFile.delete()
            return removed
        }
    }

    fun clear() {
        synchronized(lock) {
            addons.clear()
            storageDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(SCRIPT_EXT) || file.name.endsWith(META_EXT)) {
                    file.delete()
                }
            }
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            addons.clear()
            if (!storageDir.isDirectory) return
            val metaFiles = storageDir.listFiles { _, name -> name.endsWith(META_EXT) } ?: return
            for (metaFile in metaFiles) {
                runCatching {
                    val safeId = metaFile.name.removeSuffix(META_EXT)
                    val scriptFile = File(storageDir, "$safeId$SCRIPT_EXT")
                    if (!scriptFile.isFile) return@runCatching
                    val meta = gson.fromJson(
                        metaFile.readText(StandardCharsets.UTF_8),
                        InstalledLocalJsAddon::class.java
                    ) ?: return@runCatching
                    val script = scriptFile.readText(StandardCharsets.UTF_8)
                    val key = GitHubAddonRepositoryParser.safeAddonId(meta.id.ifBlank { safeId })
                    addons[key] = StoredJsAddon(meta.copy(id = key), script)
                }
            }
        }
    }

    companion object {
        private const val SCRIPT_EXT = ".js"
        private const val META_EXT = ".meta.json"
    }
}
