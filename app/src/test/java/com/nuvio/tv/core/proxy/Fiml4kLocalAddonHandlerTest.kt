package com.nuvio.tv.core.proxy

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class Fiml4kLocalAddonHandlerTest {

    private lateinit var fiml4kServer: MockWebServer
    private lateinit var tmdbServer: MockWebServer
    private lateinit var handler: Fiml4kLocalAddonHandler

    @Before
    fun setUp() {
        fiml4kServer = MockWebServer()
        tmdbServer = MockWebServer()
        fiml4kServer.start()
        tmdbServer.start()
        val client = OkHttpClient.Builder().build()
        handler = Fiml4kLocalAddonHandler(
            httpClientProvider = { client },
            apiBaseUrl = fiml4kServer.url("/").toString().trimEnd('/'),
            tmdbBaseUrl = tmdbServer.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        fiml4kServer.shutdown()
        tmdbServer.shutdown()
    }

    @Test
    fun servesManifestAndHomeAndSearchCatalogs() {
        fiml4kServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/home?type=movie&page=1" -> {
                        assertEquals("locale=vi", request.getHeader("Cookie"))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "list": [
                                {
                                  "slug": "pacific-rim",
                                  "mediaType": "movie",
                                  "titleVi": "Siêu Đại Chiến",
                                  "title": "Pacific Rim",
                                  "poster": "/posters/pacific.jpg",
                                  "year": 2013,
                                  "score": 7.0,
                                  "imdbId": "tt1663662",
                                  "tmdbId": 68726
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/api/search/suggest?q=") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "list": [
                                { "slug": "kinnporsche-movie", "mediaType": "movie", "title": "Other" },
                                { "slug": "kinnporsche-the-series", "mediaType": "tv", "titleVi": "Thiếu Gia Xã Hội Đen", "year": 2022 }
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val manifestReply = handler.handleRequest("/addon/fiml4k/manifest.json", "/addon/fiml4k/manifest.json")
        assertEquals(200, manifestReply.statusCode)
        val manifest = JsonParser.parseString(String(manifestReply.bodyBytes, StandardCharsets.UTF_8)).asJsonObject
        assertEquals(Fiml4kLocalAddonHandler.ADDON_ID, manifest.get("id").asString)
        assertEquals(2, manifest.getAsJsonArray("catalogs").size())

        val catalogReply = handler.handleRequest(
            "/addon/fiml4k/catalog/movie/fiml4k_movies.json",
            "/addon/fiml4k/catalog/movie/fiml4k_movies.json"
        )
        assertEquals(200, catalogReply.statusCode)
        val metas = JsonParser.parseString(String(catalogReply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonArray("metas")
        assertEquals(1, metas.size())
        val item = metas[0].asJsonObject
        assertEquals("fiml4k:pacific-rim", item.get("id").asString)
        assertEquals("Siêu Đại Chiến", item.get("name").asString)
        assertEquals("${fiml4kServer.url("/posters/pacific.jpg")}", item.get("poster").asString)

        val searchReply = handler.handleRequest(
            "/addon/fiml4k/catalog/series/fiml4k_series/search=kinnporsche.json",
            "/addon/fiml4k/catalog/series/fiml4k_series/search=kinnporsche.json"
        )
        assertEquals(200, searchReply.statusCode)
        val seriesMetas = JsonParser.parseString(String(searchReply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonArray("metas")
        assertEquals(1, seriesMetas.size())
        assertEquals("fiml4k:kinnporsche-the-series", seriesMetas[0].asJsonObject.get("id").asString)
    }

    @Test
    fun buildsDeduplicatedSortedSeriesMetaVideos() {
        fiml4kServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/title/kinnporsche") {
                    return MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "movie": { "slug": "kinnporsche", "mediaType": "tv", "titleVi": "Thiếu Gia" },
                          "episodes": [
                            { "season": 1, "episode": 2, "title": "Ep 2" },
                            { "season": 1, "episode": 1, "titleVi": "Tập Mở Đầu" },
                            { "season": 1, "episode": 2, "title": "Ep 2 Duplicate" }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val reply = handler.handleRequest(
            "/addon/fiml4k/meta/series/fiml4k%3Akinnporsche.json",
            "/addon/fiml4k/meta/series/fiml4k%3Akinnporsche.json"
        )
        assertEquals(200, reply.statusCode)
        val videos = JsonParser.parseString(String(reply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonObject("meta").getAsJsonArray("videos")
        assertEquals(2, videos.size())
        assertEquals("fiml4k:kinnporsche:1:1", videos[0].asJsonObject.get("id").asString)
        assertEquals("Tập Mở Đầu", videos[0].asJsonObject.get("title").asString)
        assertEquals("fiml4k:kinnporsche:1:2", videos[1].asJsonObject.get("id").asString)
    }

    @Test
    fun resolvesStreamsForFiml4kImdbViaTmdbSearchFallbackAndSeriesTitleEpisodes() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160
            2160/s0.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080/s0.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720/s0.m3u8
        """.trimIndent()

        fiml4kServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/watch/pacific-rim?e=s01e01" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "movie": { "slug": "pacific-rim", "titleVi": "Siêu Đại Chiến", "year": 2013, "viSubs": true, "maxRes": "4K" },
                          "sources": [{ "label": "SV1", "url": "/api/hls/pacific-rim/master.m3u8" }]
                        }
                        """.trimIndent()
                    )
                    path == "/api/hls/pacific-rim/master.m3u8" -> MockResponse().setResponseCode(200).setBody(masterPlaylist)
                    path == "/api/title/interstellar" -> MockResponse().setResponseCode(404)
                    path.startsWith("/api/search/suggest?q=Interstellar") -> MockResponse().setResponseCode(200).setBody(
                        """{"list":[{"slug":"interstellar-2014","mediaType":"movie","title":"Interstellar"}]}"""
                    )
                    path == "/api/title/interstellar-2014" -> MockResponse().setResponseCode(200).setBody(
                        """{"movie":{"slug":"interstellar-2014","mediaType":"movie","tmdbId":157336,"imdbId":"tt0816692","title":"Interstellar","year":2014}}"""
                    )
                    path == "/api/watch/interstellar-2014?e=s01e01" -> MockResponse().setResponseCode(200).setBody(
                        """{"sources":[{"label":"SV-VIP","url":"/api/hls/interstellar-2014/index.m3u8"}]}"""
                    )
                    path.startsWith("/api/watch/kinnporsche") -> MockResponse().setResponseCode(200).setBody(
                        """{"sources":[]}"""
                    )
                    path == "/api/title/kinnporsche" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "movie": { "slug": "kinnporsche", "mediaType": "tv", "titleVi": "Thiếu Gia", "year": 2022 },
                          "episodes": [
                            { "season": 1, "episode": 2, "sources": [{ "label": "Ep2", "url": "/api/hls/kinnporsche-s01e02/master.m3u8" }] }
                          ]
                        }
                        """.trimIndent()
                    )
                    path == "/api/hls/kinnporsche-s01e02/master.m3u8" -> MockResponse().setResponseCode(200).setBody(masterPlaylist)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/find/tt0816692") -> MockResponse().setResponseCode(200).setBody(
                        """{"movie_results":[{"id":157336}]}"""
                    )
                    path.startsWith("/movie/157336") -> MockResponse().setResponseCode(200).setBody(
                        """{"id":157336,"title":"Interstellar","release_date":"2014-11-05"}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Case A: fiml4k slug with 4K + 1080p quality split & localProxy hints
        val movieReply = handler.handleRequest(
            "/addon/fiml4k/stream/movie/fiml4k:pacific-rim.json",
            "/addon/fiml4k/stream/movie/fiml4k:pacific-rim.json"
        )
        assertEquals(200, movieReply.statusCode)
        val movieStreams = JsonParser.parseString(String(movieReply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonArray("streams")
        assertEquals(2, movieStreams.size())
        val urls = movieStreams.map { it.asJsonObject.get("url").asString }
        assertTrue(urls[0].endsWith("#aio_quality=4k"))
        assertTrue(urls[1].endsWith("#aio_quality=1080"))
        val hints = movieStreams[0].asJsonObject
            .getAsJsonObject("behaviorHints")
            .getAsJsonObject("localProxy")
        assertTrue(hints.get("enabled").asBoolean)
        assertEquals("hls-rewrite", hints.get("mode").asString)
        assertTrue(hints.get("autoUnwrapTs").asBoolean)
        assertNotNull(hints.getAsJsonObject("headers").get("Referer"))

        // Case B: IMDb -> TMDB -> suggest search fallback matching tmdbId=157336
        val imdbReply = handler.handleRequest(
            "/addon/fiml4k/stream/movie/tt0816692.json",
            "/addon/fiml4k/stream/movie/tt0816692.json"
        )
        assertEquals(200, imdbReply.statusCode)
        val imdbStreams = JsonParser.parseString(String(imdbReply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonArray("streams")
        assertEquals(1, imdbStreams.size())
        assertTrue(imdbStreams[0].asJsonObject.get("url").asString.endsWith("/api/hls/interstellar-2014/index.m3u8"))

        // Case C: Series fallback to titleData.episodes when watchData.sources is empty
        val seriesReply = handler.handleRequest(
            "/addon/fiml4k/stream/series/fiml4k:kinnporsche:1:2.json",
            "/addon/fiml4k/stream/series/fiml4k:kinnporsche:1:2.json"
        )
        assertEquals(200, seriesReply.statusCode)
        val seriesStreams = JsonParser.parseString(String(seriesReply.bodyBytes, StandardCharsets.UTF_8))
            .asJsonObject.getAsJsonArray("streams")
        assertEquals(2, seriesStreams.size())
        assertTrue(seriesStreams[0].asJsonObject.get("title").asString.contains("S01E02"))
    }
}
