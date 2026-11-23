package com.nuvio.tv.core.proxy

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class GitHubLocalJsAddonTest {

    private lateinit var tempDir: File
    private lateinit var githubMock: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var store: LocalJsAddonStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("aio_github_addon_test").toFile()
        githubMock = MockWebServer()
        githubMock.start()
        client = OkHttpClient.Builder().build()
        store = LocalJsAddonStore(tempDir)
    }

    @After
    fun tearDown() {
        githubMock.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun resolvesGitHubShorthandTreeBlobAndRawUrls() {
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/anpham14121993-cloud/NothingP-Addons/main/repo.json",
                "https://raw.githubusercontent.com/anpham14121993-cloud/NothingP-Addons/master/repo.json"
            ),
            GitHubAddonRepositoryParser.candidateRepoUrls("https://github.com/anpham14121993-cloud/NothingP-Addons")
        )
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/main/repo.json",
                "https://raw.githubusercontent.com/owner/repo/master/repo.json"
            ),
            GitHubAddonRepositoryParser.candidateRepoUrls("owner/repo")
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/owner/repo/dev/sub/repo.json"),
            GitHubAddonRepositoryParser.candidateRepoUrls("https://github.com/owner/repo/tree/dev/sub")
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/owner/repo/main/addons/fiml4k.js"),
            GitHubAddonRepositoryParser.candidateRepoUrls("https://github.com/owner/repo/blob/main/addons/fiml4k.js")
        )
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/main/addons/fiml4k.js",
            GitHubAddonRepositoryParser.resolveScriptUrl(
                "https://raw.githubusercontent.com/owner/repo/main/repo.json",
                "addons/fiml4k.js"
            )
        )
        assertTrue(GitHubAddonRepositoryParser.isNewerVersion("1.0.4", "1.0.3"))
        assertTrue(GitHubAddonRepositoryParser.isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(GitHubAddonRepositoryParser.isNewerVersion("v2.0.0", "1.9.9"))
        assertFalse(GitHubAddonRepositoryParser.isNewerVersion("1.0.3", "1.0.3"))
        assertFalse(GitHubAddonRepositoryParser.isNewerVersion("1.0.2", "1.0.3"))
    }

    @Test
    fun autoUpdatesInstalledAddonsOnlyWhenRemoteVersionIsNewer() = runBlocking {
        val repoVersion = AtomicInteger(3)
        val scriptVersion = AtomicInteger(3)
        val scriptFetches = AtomicInteger(0)

        githubMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/repo.json" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "id": "nothingp-github-addons",
                              "name": "Nothing-P Repo",
                              "addons": [
                                {
                                  "id": "community.fiml4k.local",
                                  "name": "Nothing-P • Fiml4K",
                                  "version": "1.0.${repoVersion.get()}",
                                  "description": "Phim 4K Vietsub",
                                  "types": ["movie", "series"],
                                  "entry": "addons/fiml4k.js"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    "/addons/fiml4k.js" -> {
                        scriptFetches.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("// version=${scriptVersion.get()}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val runner = JsAddonScriptRunner { scriptCode, action, _, _ ->
            val v = Regex("""// version=(\d+)""").find(scriptCode)?.groupValues?.get(1) ?: "3"
            when (action) {
                "manifest" -> """{"id":"community.fiml4k.local","name":"Nothing-P • Fiml4K","version":"1.0.$v","resources":["catalog","meta","stream"],"types":["movie","series"]}"""
                else -> "{}"
            }
        }

        val handler = LocalJsAddonHandler(
            store = store,
            httpClientProvider = { client },
            scriptRunner = runner
        )
        val catalog = handler.fetchRepoCatalog(githubMock.url("/repo.json").toString()).getOrThrow()
        handler.downloadAndInstallAddon(catalog.entries[0]).getOrThrow()
        assertEquals(1, scriptFetches.get())

        // Same version on repo -> no extra script download on autoUpdate.
        assertTrue(handler.autoUpdateInstalledAddons().isEmpty())
        assertEquals(1, scriptFetches.get())

        // Lower version on repo -> never downgrades.
        repoVersion.set(2)
        assertTrue(handler.autoUpdateInstalledAddons().isEmpty())
        assertEquals(1, scriptFetches.get())

        // Bumped version on repo (1.0.3 -> 1.0.4) -> automatically downloads & upgrades stored version.
        repoVersion.set(4)
        scriptVersion.set(4)
        val updated = handler.autoUpdateInstalledAddons()
        assertEquals(1, updated.size)
        assertEquals("1.0.4", updated[0].version)
        assertEquals("1.0.4", store.get("community.fiml4k.local")?.meta?.version)
        assertEquals(2, scriptFetches.get())
    }

    @Test
    fun storesReloadsUpdatesAndServesGitHubJsAddonViaLoopbackServer() = runBlocking {
        val scriptVersion = AtomicInteger(1)
        githubMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/repo.json" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "id": "nothingp-github-addons",
                              "name": "Nothing-P Repo",
                              "description": "Online addons",
                              "addons": [
                                {
                                  "id": "community.fiml4k.local",
                                  "name": "Nothing-P • Fiml4K",
                                  "version": "1.0.3",
                                  "description": "Phim 4K Vietsub",
                                  "types": ["movie", "series"],
                                  "entry": "addons/fiml4k.js"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    "/addons/fiml4k.js" -> {
                        val v = scriptVersion.get()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("// version=$v\nmodule.exports = {};")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val runner = JsAddonScriptRunner { scriptCode, action, requestJson, _ ->
            val vMatch = Regex("""// version=(\d+)""").find(scriptCode)?.groupValues?.get(1) ?: "1"
            val ver = "1.0.$vMatch"
            val req = JsonParser.parseString(requestJson).asJsonObject
            when (action) {
                "manifest" -> """{"id":"community.fiml4k.local","name":"Nothing-P • Fiml4K","version":"$ver","resources":["catalog","meta","stream"],"types":["movie","series"]}"""
                "catalog" -> {
                    val search = req.getAsJsonObject("extra")?.get("search")?.asString.orEmpty()
                    """{"metas":[{"id":"fiml4k:pacific-rim","type":"movie","name":"Search:$search"}]}"""
                }
                "meta" -> {
                    val itemId = req.get("id").asString
                    """{"meta":{"id":"$itemId","type":"series","videos":[{"id":"$itemId:1:1","season":1,"episode":1}]}}"""
                }
                "stream" -> {
                    val hasTmdbKey = req.getAsJsonObject("env")?.get("tmdbPrimaryKey")?.asString?.isNotBlank() == true
                    val itemId = req.get("id").asString
                    assertTrue(hasTmdbKey)
                    """{"streams":[{"name":"Nothing-P • Fiml4K\n[4K UHD]","title":"$itemId","url":"https://fiml4k.fun/master.m3u8#aio_quality=4k"}]}"""
                }
                else -> "{}"
            }
        }

        val handler = LocalJsAddonHandler(
            store = store,
            httpClientProvider = { client },
            scriptRunner = runner
        )

        // Ensure nothing is pre-installed before the user clicks install.
        assertTrue(handler.listInstalled().isEmpty())

        val catalogRes = handler.fetchRepoCatalog(githubMock.url("/repo.json").toString())
        assertTrue(catalogRes.isSuccess)
        val entries = catalogRes.getOrThrow().entries
        assertEquals(1, entries.size)
        assertEquals("community.fiml4k.local", entries[0].id)

        val installRes = handler.downloadAndInstallAddon(entries[0])
        assertTrue(installRes.isSuccess)
        val installed = installRes.getOrThrow()
        assertEquals("1.0.1", installed.version)

        // Verify persistence across a new store instance.
        val reloadedStore = LocalJsAddonStore(tempDir)
        assertNotNull(reloadedStore.get("community.fiml4k.local"))

        val proxyServer = LocalProxyHttpServer({ client }, 0, handler)
        proxyServer.start()
        try {
            val base = "http://127.0.0.1:${proxyServer.port}${LocalJsAddonHandler.BASE_PATH}/community.fiml4k.local"

            client.newCall(Request.Builder().url("$base/manifest.json").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("\"version\":\"1.0.1\""))
            }

            client.newCall(Request.Builder().url("$base/catalog/movie/fiml4k_movies/search=Pacific%20Rim.json").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("Search:Pacific Rim"))
            }

            client.newCall(Request.Builder().url("$base/meta/series/fiml4k%3Akinn.json").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("fiml4k:kinn:1:1"))
            }

            client.newCall(Request.Builder().url("$base/stream/movie/tt0816692.json").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val json = resp.body!!.string()
                assertTrue(json.contains("tt0816692"))
                assertTrue(json.contains("#aio_quality=4k"))
            }

            // Simulate user editing addons/fiml4k.js on GitHub and clicking Update in app.
            scriptVersion.set(2)
            val updateRes = handler.updateInstalledAddon("community.fiml4k.local")
            assertTrue(updateRes.isSuccess)
            assertEquals("1.0.2", updateRes.getOrThrow().version)

            client.newCall(Request.Builder().url("$base/manifest.json").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("\"version\":\"1.0.2\""))
            }

            assertTrue(handler.removeInstalledAddon("http://127.0.0.1:47123/js-addon/community.fiml4k.local"))
            assertFalse(handler.listInstalled().any { it.id == "community.fiml4k.local" })

            client.newCall(Request.Builder().url("$base/manifest.json").get().build()).execute().use { resp ->
                assertEquals(404, resp.code)
            }
        } finally {
            proxyServer.stop()
        }
    }
}
