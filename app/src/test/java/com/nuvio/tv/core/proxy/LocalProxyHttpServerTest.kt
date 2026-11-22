package com.nuvio.tv.core.proxy

import com.nuvio.tv.domain.model.LocalProxyHints
import com.nuvio.tv.domain.model.LocalProxyMode
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LocalProxyHttpServerTest {

    private lateinit var upstream: MockWebServer
    private lateinit var server: LocalProxyHttpServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.start()
        client = OkHttpClient.Builder().build()
        server = LocalProxyHttpServer { client }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        upstream.shutdown()
    }

    @Test
    fun hfXor256StreamsFullAndArbitraryRangesAfter302And401Refresh() {
        val plain = ByteArray(2048) { idx ->
            when (idx) {
                4 -> 'f'.code.toByte()
                5 -> 't'.code.toByte()
                6 -> 'y'.code.toByte()
                7 -> 'p'.code.toByte()
                else -> ((idx * 19 + 3) and 0xFF).toByte()
            }
        }
        val encryptedBody = plain.copyOf()
        HfXor256Transform().transformInPlace(encryptedBody, 0, encryptedBody.size, 0L)
        val fullObject = ByteArray(64) { 0x7A } + encryptedBody

        var refreshCount = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/expired") -> MockResponse().setResponseCode(401)
                    path.startsWith("/meta.json") -> {
                        refreshCount++
                        val freshUrl = upstream.url("/redirect-hf").toString()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"url":"$freshUrl","headers":{"Authorization":"Bearer fresh_token"}}""")
                    }
                    path.startsWith("/redirect-hf") -> {
                        MockResponse()
                            .setResponseCode(302)
                            .addHeader("Location", upstream.url("/cdn/object?X-Amz-Signature=sig123").toString())
                    }
                    path.startsWith("/cdn/object") -> {
                        // Cross-origin style presigned target should still serve content cleanly.
                        serveRangedBytes(request, fullObject, "application/octet-stream")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val session = server.registerSession(
            originalUrl = upstream.url("/expired").toString(),
            targetUrl = upstream.url("/expired").toString(),
            mode = LocalProxyMode.HF_XOR_256,
            headers = mapOf("Authorization" to "Bearer stale_token"),
            hints = LocalProxyHints(
                enabled = true,
                mode = "hf-xor-256",
                autoKey = true,
                metadataUrl = upstream.url("/meta.json").toString()
            )
        )
        val proxyUrl = server.buildSessionEntryUrl(session, "media")

        // HEAD check reports decoded Content-Length (2048) with 0 body
        client.newCall(Request.Builder().url(proxyUrl).head().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("2048", resp.header("Content-Length"))
            assertEquals("bytes", resp.header("Accept-Ranges"))
            assertEquals(0, resp.body?.bytes()?.size ?: 0)
        }
        assertEquals(1, refreshCount)

        // Full GET decrypts exact payload
        client.newCall(Request.Builder().url(proxyUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertArrayEquals(plain, resp.body!!.bytes())
        }

        // Mid-stream Range GET across 256-byte XOR cycle boundary
        client.newCall(
            Request.Builder().url(proxyUrl).addHeader("Range", "bytes=300-699").get().build()
        ).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("bytes 300-699/2048", resp.header("Content-Range"))
            assertEquals("400", resp.header("Content-Length"))
            assertArrayEquals(plain.copyOfRange(300, 700), resp.body!!.bytes())
        }

        // Out-of-bounds Range returns 416
        client.newCall(
            Request.Builder().url(proxyUrl).addHeader("Range", "bytes=5000-5100").get().build()
        ).execute().use { resp ->
            assertEquals(416, resp.code)
            assertEquals("bytes */2048", resp.header("Content-Range"))
        }
    }

    @Test
    fun hfXor256MultiLaneStreams9MbMkvCachesCdnRedirectAndAutoDetectsKey() {
        val totalDecoded = 9 * 1024 * 1024 // > 6MB threshold triggers 4-lane pipeline (512KB + 2MB + 3MB + 3MB + tail)
        val customKey = ByteArray(256) { idx -> ((idx * 29 + 5) and 0xFF).toByte() }
        // Sparse zero-rich payload like MKV padding, plus real EBML signature at byte 0
        val plain = ByteArray(totalDecoded) { idx ->
            when {
                idx == 0 -> 0x1A
                idx == 1 -> 0x45
                idx == 2 -> 0xDF.toByte()
                idx == 3 -> 0xA3.toByte()
                idx % 97 == 0 -> ((idx * 7) and 0xFF).toByte()
                else -> 0
            }
        }
        val encrypted = plain.copyOf()
        HfXor256Transform(headerSize = 64, xorKey = customKey).transformInPlace(encrypted, 0, encrypted.size, 0L)
        val fullRaw = ByteArray(64) { 0x50 } + encrypted

        var redirectHits = 0
        var cdnRangeHits = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/entry") -> {
                        redirectHits++
                        MockResponse()
                            .setResponseCode(302)
                            .addHeader("Location", upstream.url("/fast-cdn/movie.pth").toString())
                    }
                    path.startsWith("/fast-cdn/movie.pth") -> {
                        cdnRangeHits++
                        serveRangedBytes(request, fullRaw, "application/octet-stream")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val session = server.registerSession(
            originalUrl = upstream.url("/entry").toString(),
            targetUrl = upstream.url("/entry").toString(),
            mode = LocalProxyMode.HF_XOR_256,
            headers = emptyMap(),
            hints = LocalProxyHints(enabled = true, mode = "hf-xor-256", autoKey = true)
        )
        // Single-stream profile ("smedia") enables adaptive multi-lane throughput pipeline
        val proxyUrl = server.buildSessionEntryUrl(session, "smedia")

        // Open-ended streaming GET exercises adaptive multi-lane pipeline & Matroska Content-Type inference
        client.newCall(Request.Builder().url(proxyUrl).addHeader("Range", "bytes=0-").get().build()).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("video/x-matroska", resp.header("Content-Type"))
            assertEquals(totalDecoded.toString(), resp.header("Content-Length"))
            val actual = resp.body!!.bytes()
            assertArrayEquals(plain, actual)
        }
        // 302 redirect only happened once during initial probe; all chunks hit cached CDN URL directly.
        assertEquals(1, redirectHits)
        assertTrue("Expected multi-chunk adaptive fetches, got $cdnRangeHits", cdnRangeHits >= 4)

        // Open-ended mid-file seek still works cleanly via adaptive pipeline without triggering another 302
        val seekStart = 4 * 1024 * 1024 + 123
        client.newCall(
            Request.Builder().url(proxyUrl).addHeader("Range", "bytes=$seekStart-").get().build()
        ).execute().use { resp ->
            assertEquals(206, resp.code)
            assertArrayEquals(plain.copyOfRange(seekStart, plain.size), resp.body!!.bytes())
        }
        assertEquals(1, redirectHits)
    }

    @Test
    fun exoPlayerMediaProfileForwardsRanges1To1AndReusesHttp11KeepAlive() {
        val totalDecoded = 4 * 1024 * 1024
        val customKey = ByteArray(256) { idx -> ((idx * 17 + 11) and 0xFF).toByte() }
        val plain = ByteArray(totalDecoded) { idx ->
            when {
                idx == 0 -> 0x1A
                idx == 1 -> 0x45
                idx == 2 -> 0xDF.toByte()
                idx == 3 -> 0xA3.toByte()
                idx % 73 == 0 -> ((idx * 13) and 0xFF).toByte()
                else -> 0
            }
        }
        val encrypted = plain.copyOf()
        HfXor256Transform(headerSize = 64, xorKey = customKey).transformInPlace(encrypted, 0, encrypted.size, 0L)
        val fullRaw = ByteArray(64) { 0x41 } + encrypted

        val upstreamRangesAfterWarmup = CopyOnWriteArrayList<String>()
        var warmedUp = false
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/entry") -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", upstream.url("/fast-cdn/movie.pth").toString())
                    path.startsWith("/fast-cdn/movie.pth") -> {
                        if (warmedUp) {
                            upstreamRangesAfterWarmup += request.getHeader("Range").orEmpty()
                        }
                        serveRangedBytes(request, fullRaw, "application/octet-stream")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val session = server.registerSession(
            originalUrl = upstream.url("/entry").toString(),
            targetUrl = upstream.url("/entry").toString(),
            mode = LocalProxyMode.HF_XOR_256,
            headers = emptyMap(),
            hints = LocalProxyHints(enabled = true, mode = "hf-xor-256", autoKey = true)
        )
        // Adaptive ExoPlayer profile ("media") must stream 1:1 without hidden sub-splitting
        val mediaUrl = server.buildSessionEntryUrl(session, "media")
        val tcpConnects = AtomicInteger(0)
        val playerClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    tcpConnects.incrementAndGet()
                }
            })
            .build()

        try {
            // Chunk 1 triggers initial probe + autokey + first 1MB range
            playerClient.newCall(
                Request.Builder().url(mediaUrl).addHeader("Range", "bytes=0-1048575").get().build()
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("keep-alive", resp.header("Connection")?.lowercase())
                assertArrayEquals(plain.copyOfRange(0, 1048576), resp.body!!.bytes())
            }
            warmedUp = true

            // Chunk 2 & 3 simulate ExoPlayer AutoSpeedup 1:1 range requests over the same Keep-Alive socket
            playerClient.newCall(
                Request.Builder().url(mediaUrl).addHeader("Range", "bytes=1048576-2097151").get().build()
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("keep-alive", resp.header("Connection")?.lowercase())
                assertArrayEquals(plain.copyOfRange(1048576, 2097152), resp.body!!.bytes())
            }
            playerClient.newCall(
                Request.Builder().url(mediaUrl).addHeader("Range", "bytes=2097152-3145727").get().build()
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("keep-alive", resp.header("Connection")?.lowercase())
                assertArrayEquals(plain.copyOfRange(2097152, 3145728), resp.body!!.bytes())
            }

            // Strictly 1:1 upstream Range forwarding (no sub-splitting on "media") and 1 reused socket
            assertEquals(
                listOf("bytes=1048640-2097215", "bytes=2097216-3145791"),
                upstreamRangesAfterWarmup
            )
            assertEquals(1, tcpConnects.get())
        } finally {
            playerClient.connectionPool.evictAll()
            playerClient.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun hlsProxyRewritesPlaylistsPreservesRefererAndUnwrapsPngTsSegments() {
        val tsPayload = ByteArray(188 * 4) { idx -> (idx and 0xFF).toByte() }.apply {
            this[0] = 0x47
            this[188] = 0x47
            this[376] = 0x47
            this[564] = 0x47
        }
        val disguisedSegment = "\u0089PNG\r\n\u001a\nHIDDEN_IMAGE_PREFIX".encodeToByteArray() + tsPayload
        val initSegment = byteArrayOf(0, 0, 0, 8) + "ftyp".encodeToByteArray()
        val aesKey = ByteArray(16) { (it + 1).toByte() }

        var segReferer: String? = null
        var keyCookie: String? = null

        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/master.m3u8" -> MockResponse()
                        .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            """
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=1200000
                            sub/variant.m3u8?auth=1
                            """.trimIndent()
                        )
                    "/sub/variant.m3u8?auth=1" -> MockResponse()
                        .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            """
                            #EXTM3U
                            #EXT-X-KEY:METHOD=AES-128,URI="secret.key"
                            #EXT-X-MAP:URI="init.mp4"
                            #EXTINF:5.0,
                            seg0.png
                            #EXT-X-ENDLIST
                            """.trimIndent()
                        )
                    "/sub/secret.key" -> {
                        keyCookie = request.getHeader("Cookie")
                        MockResponse()
                            .addHeader("Content-Type", "application/octet-stream")
                            .setBody(Buffer().write(aesKey))
                    }
                    "/sub/init.mp4" -> MockResponse()
                        .addHeader("Content-Type", "video/mp4")
                        .setBody(Buffer().write(initSegment))
                    "/sub/seg0.png" -> {
                        segReferer = request.getHeader("Referer")
                        MockResponse()
                            .addHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(disguisedSegment))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val session = server.registerSession(
            originalUrl = upstream.url("/master.m3u8").toString(),
            targetUrl = upstream.url("/master.m3u8").toString(),
            mode = LocalProxyMode.HLS_REWRITE,
            headers = mapOf("Cookie" to "sid=999")
        )
        val masterProxyUrl = server.buildSessionEntryUrl(session, "hls")
        val rewrittenMaster = client.newCall(Request.Builder().url(masterProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
        val variantProxyUrl = rewrittenMaster.lineSequence().first { it.startsWith("http://127.0.0.1:") }

        val rewrittenVariant = client.newCall(Request.Builder().url(variantProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }

        val keyProxyUrl = Regex("""URI="(http://127\.0\.0\.1:[^"]+/key[^"]+)"""").find(rewrittenVariant)!!.groupValues[1]
        val initProxyUrl = Regex("""URI="(http://127\.0\.0\.1:[^"]+/seg[^"]+)"""").find(rewrittenVariant)!!.groupValues[1]
        val segProxyUrl = rewrittenVariant.lineSequence().first { it.startsWith("http://127.0.0.1:") }

        client.newCall(Request.Builder().url(keyProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            assertArrayEquals(aesKey, it.body!!.bytes())
        }
        assertEquals("sid=999", keyCookie)

        client.newCall(Request.Builder().url(initProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            assertArrayEquals(initSegment, it.body!!.bytes())
        }

        client.newCall(Request.Builder().url(segProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("video/mp2t"))
            assertArrayEquals(tsPayload, it.body!!.bytes())
        }
        assertEquals(upstream.url("/sub/variant.m3u8?auth=1").toString(), segReferer)
    }

    @Test
    fun corsOptionsPrefixSkipAndDrmBlockingWorkAsExpected() {
        val prefixed = ByteArray(100) { 0xEE.toByte() } + "REAL_PAYLOAD_DATA".encodeToByteArray()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/prefixed.bin" -> serveRangedBytes(request, prefixed, "video/mp4")
                    "/drm.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\nseg.ts")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val skipSession = server.registerSession(
            originalUrl = upstream.url("/prefixed.bin").toString(),
            targetUrl = upstream.url("/prefixed.bin").toString(),
            mode = LocalProxyMode.PREFIX_SKIP,
            headers = emptyMap(),
            hints = LocalProxyHints(enabled = true, mode = "prefix-skip", skipBytes = 100L)
        )
        val skipUrl = server.buildSessionEntryUrl(skipSession, "media")
        client.newCall(Request.Builder().url(skipUrl).method("OPTIONS", null).build()).execute().use { resp ->
            assertEquals(204, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
        }
        client.newCall(Request.Builder().url(skipUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("REAL_PAYLOAD_DATA", resp.body!!.string())
        }

        val drmSession = server.registerSession(
            originalUrl = upstream.url("/drm.m3u8").toString(),
            targetUrl = upstream.url("/drm.m3u8").toString(),
            mode = LocalProxyMode.HLS_REWRITE,
            headers = emptyMap()
        )
        val drmUrl = server.buildSessionEntryUrl(drmSession, "hls")
        client.newCall(Request.Builder().url(drmUrl).get().build()).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("DRM"))
        }
    }

    @Test
    fun dashProxyResolvesRelativeSegmentsWithHeaders() {
        val mpdBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD type="static">
              <BaseURL>tracks/</BaseURL>
            </MPD>
        """.trimIndent()
        var segOrigin: String? = null
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/video/manifest.mpd" -> MockResponse().setBody(mpdBody)
                    "/video/tracks/seg-1.m4s" -> {
                        segOrigin = request.getHeader("Origin")
                        MockResponse().setBody("SEGMENT_1_BYTES")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val session = server.registerSession(
            originalUrl = upstream.url("/video/manifest.mpd").toString(),
            targetUrl = upstream.url("/video/manifest.mpd").toString(),
            mode = LocalProxyMode.DASH_REWRITE,
            headers = mapOf("Origin" to "https://player.example.com")
        )
        val mpdProxyUrl = server.buildSessionEntryUrl(session, "dash")
        val rewrittenMpd = client.newCall(Request.Builder().url(mpdProxyUrl).get().build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
        val baseUrlPrefix = Regex("""<BaseURL>(http://127\.0\.0\.1:[^<]+)</BaseURL>""").find(rewrittenMpd)!!.groupValues[1]
        client.newCall(Request.Builder().url("${baseUrlPrefix}seg-1.m4s").get().build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("SEGMENT_1_BYTES", it.body!!.string())
        }
        assertEquals("https://player.example.com", segOrigin)
    }

    @Test
    fun hfXor256FlushesHeadersAndPartialBodyBeforeUpstreamCompletes() {
        val plain = ByteArray(16 * 1024) { idx -> ((idx * 13 + 5) and 0xFF).toByte() }
        val encryptedBody = plain.copyOf()
        HfXor256Transform().transformInPlace(encryptedBody, 0, encryptedBody.size, 0L)
        val fullObject = ByteArray(64) { 0x55 } + encryptedBody

        val splitOffset = 4096
        val releaseTail = java.util.concurrent.CountDownLatch(1)
        val upstreamSocket = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            try {
                while (!upstreamSocket.isClosed) {
                    val conn = upstreamSocket.accept()
                    Thread {
                        conn.use { s ->
                            val reader = s.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                            val requestLine = reader.readLine() ?: return@use
                            var reqRangeHeader: String? = null
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) {
                                    reqRangeHeader = line.substringAfter(':').trim()
                                }
                            }
                            val os = s.getOutputStream()
                            if (requestLine.startsWith("HEAD ")) {
                                os.write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                            "Accept-Ranges: bytes\r\n" +
                                            "Content-Length: ${fullObject.size}\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).toByteArray(StandardCharsets.US_ASCII)
                                )
                                os.flush()
                            } else {
                                val (start, end) = reqRangeHeader
                                    ?.removePrefix("bytes=")
                                    ?.split('-')
                                    ?.let { parts ->
                                        val sPos = parts[0].toInt()
                                        val ePos = parts.getOrNull(1)?.toIntOrNull() ?: (fullObject.size - 1)
                                        sPos to minOf(ePos, fullObject.size - 1)
                                    } ?: (0 to (fullObject.size - 1))
                                val len = end - start + 1
                                os.write(
                                    (
                                        "HTTP/1.1 206 Partial Content\r\n" +
                                            "Accept-Ranges: bytes\r\n" +
                                            "Content-Range: bytes $start-$end/${fullObject.size}\r\n" +
                                            "Content-Length: $len\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).toByteArray(StandardCharsets.US_ASCII)
                                )
                                if (len <= splitOffset) {
                                    os.write(fullObject, start, len)
                                    os.flush()
                                } else {
                                    os.write(fullObject, start, splitOffset)
                                    os.flush()
                                    releaseTail.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                    os.write(fullObject, start + splitOffset, len - splitOffset)
                                    os.flush()
                                }
                            }
                        }
                    }.start()
                }
            } catch (_: Throwable) {
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val rawUrl = "http://127.0.0.1:${upstreamSocket.localPort}/slow-stream.bin"
            val session = server.registerSession(
                originalUrl = rawUrl,
                targetUrl = rawUrl,
                mode = LocalProxyMode.HF_XOR_256,
                headers = emptyMap(),
                hints = LocalProxyHints(enabled = true, mode = "hf-xor-256", autoKey = false, headerSize = 64)
            )
            val entryUrl = server.buildSessionEntryUrl(session, "media")
            client.newCall(
                Request.Builder().url(entryUrl).header("Range", "bytes=0-${plain.size - 1}").get().build()
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                val bodyStream = resp.body!!.byteStream()
                val firstChunk = ByteArray(splitOffset - 64)
                var got = 0
                while (got < firstChunk.size) {
                    val r = bodyStream.read(firstChunk, got, firstChunk.size - got)
                    assertTrue(r > 0)
                    got += r
                }
                assertArrayEquals(plain.copyOfRange(0, firstChunk.size), firstChunk)
                releaseTail.countDown()
                val rest = bodyStream.readBytes()
                assertArrayEquals(plain.copyOfRange(firstChunk.size, plain.size), rest)
            }
        } finally {
            releaseTail.countDown()
            runCatching { upstreamSocket.close() }
        }
    }

    @Test
    fun filtersHlsMasterQualityAndUnwrapsPngDisguisedFmp4SegmentsAndServesLocalAddonManifest() {
        fun beBox(type: String, payloadSize: Int, brand: String? = null): ByteArray {
            val total = 8 + payloadSize
            val out = ByteArray(total) { 0x1A }
            out[0] = ((total ushr 24) and 0xFF).toByte()
            out[1] = ((total ushr 16) and 0xFF).toByte()
            out[2] = ((total ushr 8) and 0xFF).toByte()
            out[3] = (total and 0xFF).toByte()
            type.toByteArray(StandardCharsets.US_ASCII).copyInto(out, 4)
            brand?.toByteArray(StandardCharsets.US_ASCII)?.copyInto(out, 8)
            return out
        }

        val pngPrefix = ByteArray(67) { idx -> (idx + 1).toByte() }
        pngPrefix[0] = 0x89.toByte()
        pngPrefix[1] = 'P'.code.toByte()
        pngPrefix[2] = 'N'.code.toByte()
        pngPrefix[3] = 'G'.code.toByte()
        pngPrefix[4] = '\r'.code.toByte()
        pngPrefix[5] = '\n'.code.toByte()
        pngPrefix[6] = 0x1A.toByte()
        pngPrefix[7] = '\n'.code.toByte()

        val initFmp4 = beBox("ftyp", 16, "iso5") + beBox("moov", 40)
        val segFmp4 = beBox("styp", 16, "msdh") + beBox("sidx", 24) + beBox("moof", 48)
        val disguisedInit = pngPrefix + initFmp4
        val disguisedSeg = pngPrefix + segFmp4
        val subtitleBody = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nXin chao\n".toByteArray(StandardCharsets.UTF_8)

        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Tieng Viet",LANGUAGE="vie",DEFAULT=YES,AUTOSELECT=YES,URI="sub_vi.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="eng",DEFAULT=NO,AUTOSELECT=NO,URI="sub_en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160,SUBTITLES="subs"
            4k/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,SUBTITLES="subs"
            1080/index.m3u8
        """.trimIndent()

        val variant4kPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="https://cdn.example.com/init.image"
            #EXTINF:6.0,
            https://cdn.example.com/seg1.image
            #EXT-X-ENDLIST
        """.trimIndent().replace("https://cdn.example.com", upstream.url("/cdn").toString().trimEnd('/'))

        val subViPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            vi.vtt
            #EXT-X-ENDLIST
        """.trimIndent()

        val segReferers = CopyOnWriteArrayList<String?>()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/hls/pacific-rim/master.m3u8") -> {
                        assertTrue("Fragment must not leak to wire URL", request.requestUrl?.fragment == null)
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody(masterPlaylist)
                    }
                    path.startsWith("/api/hls/pacific-rim/4k/index.m3u8") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody(variant4kPlaylist)
                    }
                    path.startsWith("/api/hls/pacific-rim/sub_vi.m3u8") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody(subViPlaylist)
                    }
                    path.startsWith("/api/hls/pacific-rim/vi.vtt") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "text/vtt; charset=utf-8")
                            .setBody(Buffer().write(subtitleBody))
                    }
                    path.startsWith("/cdn/init.image") -> {
                        segReferers.add(request.getHeader("Referer"))
                        serveRangedBytes(request, disguisedInit, "image/png")
                    }
                    path.startsWith("/cdn/seg1.image") -> {
                        segReferers.add(request.getHeader("Referer"))
                        serveRangedBytes(request, disguisedSeg, "image/png")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val masterWithFragment = upstream.url("/api/hls/pacific-rim/master.m3u8").toString() + "#aio_quality=4k"
        val session = server.registerSession(
            originalUrl = masterWithFragment,
            targetUrl = masterWithFragment,
            mode = LocalProxyMode.HLS_REWRITE,
            headers = mapOf("Referer" to "https://fiml4k.fun/"),
            hints = LocalProxyHints(enabled = true, mode = "hls-rewrite", autoUnwrapTs = true)
        )
        val masterProxyUrl = server.buildSessionEntryUrl(session, "hls")
        val rewrittenMaster = client.newCall(Request.Builder().url(masterProxyUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }
        assertTrue(rewrittenMaster.contains("RESOLUTION=3840x2160"))
        assertTrue(!rewrittenMaster.contains("RESOLUTION=1920x1080"))
        assertTrue(rewrittenMaster.contains("LANGUAGE=\"vie\""))
        assertTrue(!rewrittenMaster.contains("LANGUAGE=\"eng\""))

        val variantProxyUrl = rewrittenMaster.lineSequence().first { it.trim().startsWith("http://127.0.0.1:") }.trim()
        val rewrittenVariant = client.newCall(Request.Builder().url(variantProxyUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }

        val mapProxyUrl = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(rewrittenVariant)!!.groupValues[1]
        val segProxyUrl = rewrittenVariant.lineSequence().first {
            val t = it.trim()
            t.startsWith("http://127.0.0.1:") && t.contains("seg1.image")
        }.trim()

        client.newCall(Request.Builder().url(mapProxyUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("video/mp4", resp.header("Content-Type"))
            assertArrayEquals(initFmp4, resp.body!!.bytes())
        }

        client.newCall(Request.Builder().url(segProxyUrl).head().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("video/mp4", resp.header("Content-Type"))
            assertEquals(segFmp4.size.toString(), resp.header("Content-Length"))
        }

        client.newCall(
            Request.Builder().url(segProxyUrl).header("Range", "bytes=8-31").get().build()
        ).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("video/mp4", resp.header("Content-Type"))
            assertEquals("bytes 8-31/${segFmp4.size}", resp.header("Content-Range"))
            assertArrayEquals(segFmp4.copyOfRange(8, 32), resp.body!!.bytes())
        }

        val expectedVariantUrl = upstream.url("/api/hls/pacific-rim/4k/index.m3u8").toString()
        assertTrue(segReferers.all { it == expectedVariantUrl })

        val subPlaylistUrl = Regex("""URI="(http://127\.0\.0\.1:[^"]+)"""").find(rewrittenMaster)!!.groupValues[1]
        val rewrittenSubPlaylist = client.newCall(Request.Builder().url(subPlaylistUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }
        val vttProxyUrl = rewrittenSubPlaylist.lineSequence().first { it.trim().startsWith("http://127.0.0.1:") }.trim()
        client.newCall(Request.Builder().url(vttProxyUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.header("Content-Type").orEmpty().contains("text/vtt"))
            assertArrayEquals(subtitleBody, resp.body!!.bytes())
        }

        val manifestUrl = "http://127.0.0.1:${server.listeningPort()}${Fiml4kLocalAddonHandler.BASE_PATH}/manifest.json"
        client.newCall(Request.Builder().url(manifestUrl).get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body!!.string()
            assertTrue(body.contains(Fiml4kLocalAddonHandler.ADDON_ID))
            assertTrue(body.contains(Fiml4kLocalAddonHandler.ADDON_NAME))
        }
    }

    private fun serveRangedBytes(
        request: RecordedRequest,
        data: ByteArray,
        contentType: String
    ): MockResponse {
        val total = data.size.toLong()
        val parsed = RangeHeaderParser.parse(request.getHeader("Range"), total)
        if (request.method.equals("HEAD", ignoreCase = true)) {
            return MockResponse()
                .setResponseCode(200)
                .addHeader("Accept-Ranges", "bytes")
                .addHeader("Content-Length", total.toString())
                .addHeader("Content-Type", contentType)
        }
        return when (parsed) {
            is RangeParseResult.NoRange -> MockResponse()
                .setResponseCode(200)
                .addHeader("Accept-Ranges", "bytes")
                .addHeader("Content-Type", contentType)
                .setBody(Buffer().write(data))
            is RangeParseResult.SingleRange -> {
                val start = parsed.spec.start.toInt()
                val end = (parsed.spec.endInclusive ?: (total - 1L)).toInt()
                val slice = data.copyOfRange(start, end + 1)
                MockResponse()
                    .setResponseCode(206)
                    .addHeader("Accept-Ranges", "bytes")
                    .addHeader("Content-Range", "bytes $start-$end/$total")
                    .addHeader("Content-Type", contentType)
                    .setBody(Buffer().write(slice))
            }
            is RangeParseResult.Unsatisfiable -> MockResponse()
                .setResponseCode(416)
                .addHeader("Content-Range", "bytes */$total")
        }
    }
}
