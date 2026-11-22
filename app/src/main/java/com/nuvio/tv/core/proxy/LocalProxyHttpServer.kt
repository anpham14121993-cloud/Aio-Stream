package com.nuvio.tv.core.proxy

import com.nuvio.tv.domain.model.LocalProxyHints
import com.nuvio.tv.domain.model.LocalProxyMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Registered local proxy stream session tying a loopback URL `/p/<id>/...` back to its
 * upstream target URL, headers, and optional local transform hints.
 */
data class ProxySession(
    val id: String,
    val originalUrl: String,
    val targetUrl: String,
    val mode: LocalProxyMode,
    val headers: Map<String, String>,
    val hints: LocalProxyHints? = null,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val metaLock = Any()
    @Volatile var lastAccessMs: Long = createdAtMs
    @Volatile var activeTargetUrl: String = targetUrl
    @Volatile var activeHeaders: Map<String, String> = headers
    @Volatile var resolvedTransform: HfXor256Transform? = null
    @Volatile var resolvedRawTotalSize: Long? = null
    @Volatile var resolvedContentType: String? = null
    @Volatile var metadataRefreshedOnce: Boolean = false
    @Volatile var cachedCdnUrl: String? = null
    @Volatile var cachedCdnUrlAtMs: Long = 0L
    @Volatile var cachedCdnHeaders: Map<String, String> = headers

    fun validCdnTarget(nowMs: Long = System.currentTimeMillis()): Pair<String, Map<String, String>>? {
        val cdn = cachedCdnUrl ?: return null
        if (nowMs - cachedCdnUrlAtMs > CDN_CACHE_TTL_MS) return null
        return cdn to cachedCdnHeaders
    }

    fun updateCdnTarget(url: String, resolvedHeaders: Map<String, String>) {
        cachedCdnUrl = url
        cachedCdnHeaders = resolvedHeaders
        cachedCdnUrlAtMs = System.currentTimeMillis()
    }

    fun invalidateCdnCache() {
        cachedCdnUrl = null
        cachedCdnUrlAtMs = 0L
    }

    private companion object {
        const val CDN_CACHE_TTL_MS = 10L * 60L * 1000L
    }
}

/**
 * Embedded HTTP/1.1 loopback proxy running 100% inside Aio-Stream on `127.0.0.1:<port>`.
 *
 * Supports:
 * - Generic HTTP header & cookie preservation across redirects (`GET`/`HEAD`/`OPTIONS`/`Range`/`206`)
 * - Non-DRM HLS playlist, variant, `#EXT-X-MAP`, `#EXT-X-MEDIA`, AES-128 key, and segment proxying
 *   with automatic `0x47` stride-188 TS unwrapping for disguised segments
 * - Non-DRM DASH MPD `<BaseURL>` proxying
 * - Progressive byte transforms (`HF_XOR_256`, `PREFIX_SKIP`, `MPEG_TS_UNWRAP`)
 * - Sidecar subtitle header proxying
 */
class LocalProxyHttpServer(
    private val httpClientProvider: () -> OkHttpClient
) {
    private val lock = Any()
    private val clientLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var requestExecutor: ExecutorService? = null
    private var laneExecutor: ExecutorService? = null
    @Volatile private var running = false
    @Volatile var port: Int = 0
        private set

    @Volatile private var cachedBaseClientId: Int = 0
    @Volatile private var cachedStreamingClient: OkHttpClient? = null

    private val sessions = ConcurrentHashMap<String, ProxySession>()
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadCounter = AtomicInteger(1)
    private val bytesServedCounter = AtomicLong(0L)

    fun start(preferredPort: Int = 0): Int {
        synchronized(lock) {
            if (running && serverSocket != null && serverSocket?.isClosed == false && port > 0) {
                return port
            }
            val bindPort = if (preferredPort in 1024..65535) preferredPort else 0
            val socket = bindLoopbackSocket(bindPort)
            serverSocket = socket
            port = socket.localPort
            running = true

            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "LocalStreamProxy-Req-${threadCounter.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            requestExecutor = executor

            val workers = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "LocalStreamProxy-Lane-${threadCounter.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            laneExecutor = workers

            val acceptor = Thread({
                acceptLoop(socket, executor)
            }, "LocalStreamProxy-Accept").apply {
                isDaemon = true
                start()
            }
            acceptThread = acceptor
            return port
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            port = 0
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptThread?.interrupt()
            acceptThread = null
            activeClientSockets.forEach { socket -> runCatching { socket.close() } }
            activeClientSockets.clear()
            requestExecutor?.shutdownNow()
            requestExecutor = null
            laneExecutor?.shutdownNow()
            laneExecutor = null
        }
    }

    fun registerSession(
        originalUrl: String,
        targetUrl: String,
        mode: LocalProxyMode,
        headers: Map<String, String>,
        hints: LocalProxyHints? = null
    ): ProxySession {
        evictExpiredSessions()
        val sanitized = HttpHeaderSanitizer.sanitizeMap(headers)
        val mergedHeaders = if (hints?.headers != null) {
            HttpHeaderSanitizer.merge(sanitized, hints.headers)
        } else {
            sanitized
        }
        val existing = sessions.values.firstOrNull { s ->
            s.originalUrl == originalUrl &&
                s.targetUrl == targetUrl &&
                s.mode == mode &&
                s.headers == mergedHeaders &&
                s.hints == hints &&
                !s.metadataRefreshedOnce
        }
        if (existing != null) {
            existing.lastAccessMs = System.currentTimeMillis()
            return existing
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val session = ProxySession(
            id = id,
            originalUrl = originalUrl,
            targetUrl = targetUrl,
            mode = mode,
            headers = mergedHeaders,
            hints = hints
        )
        sessions[id] = session
        return session
    }

    fun getSession(id: String): ProxySession? = sessions[id]?.also {
        it.lastAccessMs = System.currentTimeMillis()
    }

    fun clearSessions() {
        sessions.clear()
    }

    fun activeSessionCount(): Int = sessions.size

    fun totalBytesServed(): Long = bytesServedCounter.get()

    fun buildSessionEntryUrl(session: ProxySession, pathType: String): String {
        val currentPort = start()
        return "http://127.0.0.1:$currentPort/p/${session.id}/$pathType"
    }

    fun buildAssetUrl(
        sessionId: String,
        kind: ProxyAssetKind,
        targetUrl: String,
        refererUrl: String?
    ): String {
        val currentPort = port.takeIf { it > 0 } ?: start()
        val route = when (kind) {
            ProxyAssetKind.HLS_PLAYLIST -> "hls"
            ProxyAssetKind.HLS_SEGMENT -> "seg"
            ProxyAssetKind.HLS_KEY -> "key"
            ProxyAssetKind.DASH_ASSET -> "dash"
            ProxyAssetKind.SUBTITLE -> "sub"
            ProxyAssetKind.MEDIA -> "media"
        }
        val sb = StringBuilder("http://127.0.0.1:$currentPort/p/$sessionId/$route?u=")
        sb.append(HlsAndDashRewriter.urlEncode(targetUrl))
        if (!refererUrl.isNullOrBlank()) {
            sb.append("&ref=").append(HlsAndDashRewriter.urlEncode(refererUrl))
        }
        return sb.toString()
    }

    fun buildDashBaseUrlPrefix(sessionId: String, resolvedBaseUrl: String): String {
        val currentPort = port.takeIf { it > 0 } ?: start()
        val normalized = if (resolvedBaseUrl.endsWith("/")) resolvedBaseUrl else "$resolvedBaseUrl/"
        val encoded = HlsAndDashRewriter.encodeBase64Url(normalized)
        return "http://127.0.0.1:$currentPort/p/$sessionId/rel/$encoded/"
    }

    private fun bindLoopbackSocket(requestedPort: Int): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        return runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, requestedPort), 64)
            }
        }.getOrElse {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, 0), 64)
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                if (!running || socket.isClosed) break
                continue
            }
            activeClientSockets.add(client)
            try {
                executor.execute {
                    handleClientSocket(client)
                }
            } catch (_: Throwable) {
                activeClientSockets.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            runCatching { socket.sendBufferSize = 1024 * 1024 }
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            val request = parseHttpRequest(input) ?: return
            dispatchRequest(request, output)
            output.flush()
        } catch (_: SocketTimeoutException) {
            // Client stalled before completing HTTP request line
        } catch (_: EOFException) {
            // Client closed connection
        } catch (_: IOException) {
            // Client disconnected during streaming
        } catch (_: Throwable) {
            // Protect accept loop from per-request crashes
        } finally {
            activeClientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun dispatchRequest(req: ParsedHttpRequest, output: OutputStream) {
        val method = req.method.uppercase()
        if (method == "OPTIONS") {
            sendCorsPreflight(output)
            return
        }
        if (method != "GET" && method != "HEAD") {
            sendTextError(output, 405, "Method Not Allowed", "Only GET, HEAD, and OPTIONS are supported")
            return
        }

        val isHead = method == "HEAD"
        val cleanPath = req.path.substringBefore('?')
        if (cleanPath == "/health") {
            val json = "{\"ok\":true,\"port\":$port}"
            sendBytes(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8), isHead)
            return
        }

        // Expected: /p/<sessionId>/<route>[/...]
        val trimmed = cleanPath.removePrefix("/")
        val segments = trimmed.split('/')
        if (segments.size < 3 || segments[0] != "p") {
            sendTextError(output, 404, "Not Found", "Unknown proxy endpoint")
            return
        }

        val sessionId = segments[1]
        val route = segments[2]
        val session = getSession(sessionId) ?: run {
            sendTextError(output, 404, "Not Found", "Proxy session expired or not found")
            return
        }

        val queryParams = parseQueryParams(req.rawTarget)
        val rangeHeader = req.headers["range"]

        when (route) {
            "media" -> handleMediaRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, output)
            "hls" -> handleHlsPlaylistRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], isHead, output)
            "seg" -> handleHlsSegmentRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, output)
            "key" -> handleRawAssetRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, "application/octet-stream", output)
            "dash" -> handleDashManifestRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], isHead, output)
            "rel" -> {
                if (segments.size < 4) {
                    sendTextError(output, 400, "Bad Request", "Missing relative base token")
                    return
                }
                val base = HlsAndDashRewriter.decodeBase64Url(segments[3]) ?: run {
                    sendTextError(output, 400, "Bad Request", "Invalid relative base token")
                    return
                }
                val suffixPath = segments.drop(4).joinToString("/")
                val querySuffix = req.rawTarget.substringAfter('?', "")
                val fullTarget = buildString {
                    append(if (base.endsWith("/")) base else "$base/")
                    append(suffixPath)
                    if (querySuffix.isNotEmpty()) {
                        append(if ('?' in this) "&" else "?").append(querySuffix)
                    }
                }
                handleRawAssetRequest(session, fullTarget, session.activeTargetUrl, rangeHeader, isHead, null, output)
            }
            "sub" -> handleRawAssetRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, session.hints?.contentType, output)
            else -> sendTextError(output, 404, "Not Found", "Unsupported proxy route: $route")
        }
    }

    private fun handleMediaRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        when (session.mode) {
            LocalProxyMode.HLS_REWRITE -> handleHlsPlaylistRequest(session, targetUrl, refererOverride, isHead, output)
            LocalProxyMode.DASH_REWRITE -> handleDashManifestRequest(session, targetUrl, refererOverride, isHead, output)
            LocalProxyMode.HF_XOR_256 -> handleHfXorRequest(session, rangeHeader, isHead, output)
            LocalProxyMode.PREFIX_SKIP -> {
                val skip = (session.hints?.skipBytes ?: 0L).coerceAtLeast(0L)
                handleTransformedProgressiveRequest(
                    session = session,
                    transform = PrefixSkipTransform(skip),
                    rangeHeader = rangeHeader,
                    isHead = isHead,
                    overrideContentType = session.hints?.contentType,
                    output = output
                )
            }
            LocalProxyMode.MPEG_TS_UNWRAP -> handleBufferedTsUnwrapRequest(session, targetUrl, refererOverride, rangeHeader, isHead, output)
            LocalProxyMode.HTTP_HEADERS, LocalProxyMode.NONE -> {
                handleRawAssetRequest(session, targetUrl, refererOverride, rangeHeader, isHead, session.hints?.contentType, output)
            }
        }
    }

    private fun handleHlsPlaylistRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, finalUrl) = executeWithRedirects(targetUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead, output)
                return
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            val text = bodyBytes.toString(StandardCharsets.UTF_8)
            if (HlsAndDashRewriter.containsHlsDrm(text)) {
                sendTextError(output, 403, "Forbidden", "DRM-protected streams are not supported by local proxy")
                return
            }
            val rewritten = HlsAndDashRewriter.rewriteHlsPlaylist(
                playlistText = text,
                playlistUrl = finalUrl
            ) { kind, resolvedTarget, playlistRef ->
                buildAssetUrl(session.id, kind, resolvedTarget, playlistRef)
            }
            val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
            sendBytes(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentType = "application/vnd.apple.mpegurl; charset=utf-8",
                body = bytes,
                isHead = isHead
            )
        }
    }

    private fun handleHlsSegmentRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        val shouldCheckTsUnwrap = !isHead &&
            rangeHeader.isNullOrBlank() &&
            (session.hints?.autoUnwrapTs != false) &&
            !looksLikeInitOrMediaMp4Url(targetUrl)

        if (!shouldCheckTsUnwrap) {
            handleRawAssetRequest(session, targetUrl, refererOverride, rangeHeader, isHead, session.hints?.contentType, output)
            return
        }

        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, _) = executeWithRedirects(targetUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead = false, output = output)
                return
            }
            val upstreamType = resp.header("Content-Type")?.trim().orEmpty()
            val stream = resp.body?.byteStream() ?: run {
                sendBytes(output, 200, "OK", upstreamType.ifEmpty { "video/mp2t" }, ByteArray(0), isHead = false)
                return
            }
            // Peek first 8KB to detect PNG/JPEG/HTML wrapper prefixes around 0x47 stride-188 TS packets.
            val peekBuffer = ByteArray(8192)
            var peekRead = 0
            while (peekRead < peekBuffer.size) {
                val n = stream.read(peekBuffer, peekRead, peekBuffer.size - peekRead)
                if (n < 0) break
                peekRead += n
                if (peekRead >= 1024 && MpegTsSyncDetector.detectSyncOffset(peekBuffer, peekRead) >= 0) {
                    break
                }
            }
            val syncOffset = MpegTsSyncDetector.detectSyncOffset(peekBuffer, peekRead)
            val contentLength = resp.header("Content-Length")?.toLongOrNull()
            val adjustedLength = if (syncOffset > 0 && contentLength != null && contentLength > syncOffset) {
                contentLength - syncOffset
            } else {
                contentLength
            }
            val resolvedType = when {
                syncOffset >= 0 && upstreamType.startsWith("image/", ignoreCase = true) -> "video/mp2t"
                upstreamType.isNotEmpty() -> upstreamType
                syncOffset >= 0 -> "video/mp2t"
                else -> "application/octet-stream"
            }

            val extraHeaders = LinkedHashMap<String, String>()
            extraHeaders["Accept-Ranges"] = resp.header("Accept-Ranges") ?: "none"
            if (adjustedLength != null) {
                extraHeaders["Content-Length"] = adjustedLength.toString()
            }
            writeResponseHeader(output, resp.code, statusReason(resp.code), resolvedType, extraHeaders)

            val skipOffset = if (syncOffset > 0) syncOffset else 0
            val firstPayloadLen = (peekRead - skipOffset).coerceAtLeast(0)
            if (firstPayloadLen > 0) {
                output.write(peekBuffer, skipOffset, firstPayloadLen)
                bytesServedCounter.addAndGet(firstPayloadLen.toLong())
            }
            copyStreamWithCancelCheck(stream, output, resp)
        }
    }

    private fun handleDashManifestRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, finalUrl) = executeWithRedirects(targetUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead, output)
                return
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            val text = bodyBytes.toString(StandardCharsets.UTF_8)
            if (HlsAndDashRewriter.containsDashDrm(text)) {
                sendTextError(output, 403, "Forbidden", "DRM-protected DASH streams are not supported by local proxy")
                return
            }
            val rewritten = HlsAndDashRewriter.rewriteDashManifest(text, finalUrl) { resolvedBase ->
                buildDashBaseUrlPrefix(session.id, resolvedBase)
            }
            sendBytes(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentType = "application/dash+xml; charset=utf-8",
                body = rewritten.toByteArray(StandardCharsets.UTF_8),
                isHead = isHead
            )
        }
    }

    private fun handleBufferedTsUnwrapRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        handleHlsSegmentRequest(session, targetUrl, refererOverride, rangeHeader, isHead, output)
    }

    private fun handleHfXorRequest(
        session: ProxySession,
        rangeHeader: String?,
        isHead: Boolean,
        output: OutputStream
    ) {
        val (transform, _) = ensureHfResolved(session)
        handleTransformedProgressiveRequest(
            session = session,
            transform = transform,
            rangeHeader = rangeHeader,
            isHead = isHead,
            overrideContentType = session.hints?.contentType ?: session.resolvedContentType ?: "video/x-matroska",
            output = output
        )
    }

    private fun ensureHfResolved(session: ProxySession): Pair<HfXor256Transform, Long?> {
        val existingTransform = session.resolvedTransform
        if (existingTransform != null && session.resolvedRawTotalSize != null && session.validCdnTarget() != null) {
            return existingTransform to session.resolvedRawTotalSize
        }

        synchronized(session.metaLock) {
            val cachedTransform = session.resolvedTransform
            if (cachedTransform != null && session.resolvedRawTotalSize != null && session.validCdnTarget() != null) {
                return cachedTransform to session.resolvedRawTotalSize
            }

            val hints = session.hints
            val explicitKey = hints?.decodedXorKey()
            val explicitHeader = hints?.headerSize
            val explicitRaw = hints?.raw

            if (explicitRaw == true) {
                val total = probeUpstreamTotalLength(session)
                val t = HfXor256Transform(headerSize = 0, xorKey = explicitKey ?: HfXor256Transform.DEFAULT_KEY, isRaw = true)
                session.resolvedTransform = t
                session.resolvedRawTotalSize = total
                return t to total
            }

            // Probe first 512 bytes and cache direct 302 CDN URL so subsequent chunk/seek opens skip redirect stalls.
            val probeHeaders = session.activeHeaders.toMutableMap().apply {
                put("Range", "bytes=0-511")
            }
            val (resp, finalUrl) = executeWithHfRefreshFallback(session, probeHeaders, isHead = false)
            var resolved: HfXor256Transform
            var rawTotal: Long?
            var probeBytes: ByteArray
            resp.use { r ->
                rawTotal = RangeHeaderParser.parseContentRangeTotal(r.header("Content-Range"))
                    ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
                probeBytes = if (r.isSuccessful) {
                    r.body?.byteStream()?.let { readUpTo(it, 512) } ?: ByteArray(0)
                } else {
                    ByteArray(0)
                }
                if (r.isSuccessful) {
                    session.updateCdnTarget(finalUrl, filterHeadersForRedirect(session.activeTargetUrl, finalUrl, session.activeHeaders))
                }
                resolved = HfXor256Transform.probeFromInitialBytes(
                    probeBytes = probeBytes,
                    explicitHeaderSize = explicitHeader,
                    explicitXorKey = explicitKey,
                    explicitRaw = explicitRaw
                )
            }

            var detectedMime = detectTransformedMimeType(probeBytes, resolved)

            // If autoKey is enabled and neither raw nor DEFAULT_KEY decoded a known container signature,
            // sample 64KB of payload straight from cached CDN URL and recover per-file 256B XOR key.
            if (detectedMime == null &&
                !resolved.isRaw &&
                explicitKey == null &&
                hints?.autoKey == true &&
                probeBytes.size > resolved.headerSize + 64
            ) {
                val sampleStart = resolved.headerSize.toLong()
                val sampleEnd = sampleStart + 65535L
                val keySampleHeaders = session.activeHeaders.toMutableMap().apply {
                    put("Range", "bytes=$sampleStart-$sampleEnd")
                }
                runCatching {
                    val (sampleResp, _) = executeWithSessionCdn(session, keySampleHeaders, isHead = false)
                    sampleResp.use { sr ->
                        if (sr.isSuccessful) {
                            val sampleBytes = sr.body?.byteStream()?.let { readUpTo(it, 65536) } ?: ByteArray(0)
                            val recoveredKey = HfXor256Transform.recoverCustomKeyFromSample(sampleBytes)
                            if (recoveredKey != null) {
                                resolved = HfXor256Transform(
                                    headerSize = resolved.headerSize,
                                    xorKey = recoveredKey,
                                    isRaw = false
                                )
                                val testDec = sampleBytes.copyOf(minOf(sampleBytes.size, 64))
                                resolved.transformInPlace(testDec, 0, testDec.size, 0L)
                                detectedMime = HfXor256Transform.detectDecodedContentType(testDec, 0)
                            }
                        }
                    }
                }
            }

            session.resolvedTransform = resolved
            if (detectedMime != null) {
                session.resolvedContentType = detectedMime
            }
            if (rawTotal != null) {
                session.resolvedRawTotalSize = rawTotal
            }
            return resolved to (session.resolvedRawTotalSize ?: rawTotal)
        }
    }

    private fun detectTransformedMimeType(probeBytes: ByteArray, transform: HfXor256Transform): String? {
        if (probeBytes.isEmpty()) return null
        if (transform.isRaw) {
            return HfXor256Transform.detectDecodedContentType(probeBytes, 0)
        }
        val payloadLen = probeBytes.size - transform.headerSize
        if (payloadLen < 16) return null
        val slice = probeBytes.copyOfRange(transform.headerSize, minOf(probeBytes.size, transform.headerSize + 64))
        transform.transformInPlace(slice, 0, slice.size, 0L)
        return HfXor256Transform.detectDecodedContentType(slice, 0)
    }

    private fun probeUpstreamTotalLength(session: ProxySession): Long? {
        session.resolvedRawTotalSize?.let { return it }
        val (resp, finalUrl) = executeWithHfRefreshFallback(session, session.activeHeaders, isHead = true)
        resp.use { r ->
            val len = r.header("Content-Length")?.toLongOrNull()
                ?: RangeHeaderParser.parseContentRangeTotal(r.header("Content-Range"))
            if (r.isSuccessful) {
                session.updateCdnTarget(finalUrl, filterHeadersForRedirect(session.activeTargetUrl, finalUrl, session.activeHeaders))
            }
            if (len != null && len >= 0L) {
                session.resolvedRawTotalSize = len
            }
            return len
        }
    }

    private fun handleTransformedProgressiveRequest(
        session: ProxySession,
        transform: RangeByteTransform,
        rangeHeader: String?,
        isHead: Boolean,
        overrideContentType: String?,
        output: OutputStream
    ) {
        val rawTotalSize = session.resolvedRawTotalSize ?: probeUpstreamTotalLength(session)
        val decodedTotalSize = transform.decodedTotalSize(rawTotalSize)

        val rangeResult = RangeHeaderParser.parse(rangeHeader, decodedTotalSize)
        val requestedRange = when (rangeResult) {
            is RangeParseResult.NoRange -> RangeSpec(
                start = 0L,
                endInclusive = decodedTotalSize?.let { it - 1L }
            )
            is RangeParseResult.SingleRange -> rangeResult.spec
            is RangeParseResult.Unsatisfiable -> {
                val headers = LinkedHashMap<String, String>()
                if (decodedTotalSize != null) {
                    headers["Content-Range"] = "bytes */$decodedTotalSize"
                }
                headers["Content-Length"] = "0"
                writeResponseHeader(output, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", headers)
                return
            }
        }

        if (isHead) {
            val contentType = overrideContentType ?: session.resolvedContentType ?: "video/mp4"
            val headers = LinkedHashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            if (rangeHeader != null && decodedTotalSize != null && requestedRange.endInclusive != null) {
                val len = (requestedRange.endInclusive - requestedRange.start + 1L).coerceAtLeast(0L)
                headers["Content-Range"] = "bytes ${requestedRange.start}-${requestedRange.endInclusive}/$decodedTotalSize"
                headers["Content-Length"] = len.toString()
                writeResponseHeader(output, 206, "Partial Content", contentType, headers)
            } else {
                if (decodedTotalSize != null) {
                    headers["Content-Length"] = decodedTotalSize.toString()
                }
                writeResponseHeader(output, 200, "OK", contentType, headers)
            }
            return
        }

        val isPartial = rangeHeader != null
        val requestedLen = requestedRange.contentLengthOrNull()
        val clientRequestedOpenRange = rangeHeader == null ||
            rangeHeader.trim().endsWith("-") ||
            (requestedLen != null && requestedLen > 24L * 1024L * 1024L)

        // Multi-lane concurrent CDN chunk pipeline: saturates multi-connection throughput and prevents
        // 15s idle-timeout drops when player pauses or buffers fill, without splitting already-chunked
        // bounded requests from ParallelRangeDataSource.
        if (decodedTotalSize != null &&
            requestedLen != null &&
            requestedLen > MULTI_LANE_MIN_BYTES &&
            clientRequestedOpenRange
        ) {
            val contentType = overrideContentType ?: session.resolvedContentType ?: "video/x-matroska"
            val respHeaders = LinkedHashMap<String, String>()
            respHeaders["Accept-Ranges"] = "bytes"
            if (isPartial) {
                val endByte = requestedRange.start + requestedLen - 1L
                respHeaders["Content-Range"] = "bytes ${requestedRange.start}-$endByte/$decodedTotalSize"
            }
            respHeaders["Content-Length"] = requestedLen.toString()
            val code = if (isPartial) 206 else 200
            writeResponseHeader(output, code, statusReason(code), contentType, respHeaders)
            output.flush()
            streamMultiLaneTransformed(
                session = session,
                transform = transform,
                decodedStart = requestedRange.start,
                totalBytesToSend = requestedLen,
                output = output
            )
            return
        }

        val rawReq = transform.toRawRange(requestedRange.start, requestedRange.endInclusive)
        val upstreamHeaders = session.activeHeaders.toMutableMap()
        if (rawReq.start > 0L || rawReq.endInclusive != null) {
            val endStr = rawReq.endInclusive?.toString() ?: ""
            upstreamHeaders["Range"] = "bytes=${rawReq.start}-$endStr"
        }

        val (response, finalUrl) = executeWithSessionCdn(session, upstreamHeaders, isHead = false)

        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead = false, output = output)
                return
            }
            session.updateCdnTarget(finalUrl, filterHeadersForRedirect(session.activeTargetUrl, finalUrl, session.activeHeaders))

            val rawRespTotal = RangeHeaderParser.parseContentRangeTotal(resp.header("Content-Range"))
                ?: (if (resp.code == 200) resp.header("Content-Length")?.toLongOrNull() else null)
                ?: rawTotalSize
            if (rawRespTotal != null) {
                session.resolvedRawTotalSize = rawRespTotal
            }
            val effectiveDecodedTotal = transform.decodedTotalSize(rawRespTotal)
            val contentType = overrideContentType
                ?: session.resolvedContentType
                ?: resp.header("Content-Type")?.takeIf { !it.equals("application/octet-stream", ignoreCase = true) }
                ?: "video/mp4"

            val respHeaders = LinkedHashMap<String, String>()
            respHeaders["Accept-Ranges"] = "bytes"

            val rawBodyLen = resp.header("Content-Length")?.toLongOrNull()
            // If upstream ignores Range and returns 200 full stream while requesting rawStart > 0, skip forward.
            val skippedBytes = if (resp.code == 200 && rawReq.start > 0L) rawReq.start else 0L
            val effectiveLen = when {
                requestedRange.endInclusive != null -> (requestedRange.endInclusive - requestedRange.start + 1L).coerceAtLeast(0L)
                rawBodyLen != null -> (rawBodyLen - skippedBytes).coerceAtLeast(0L)
                effectiveDecodedTotal != null -> (effectiveDecodedTotal - requestedRange.start).coerceAtLeast(0L)
                else -> null
            }

            if (isPartial) {
                if (effectiveDecodedTotal != null && effectiveLen != null && effectiveLen > 0L) {
                    val endByte = requestedRange.start + effectiveLen - 1L
                    respHeaders["Content-Range"] = "bytes ${requestedRange.start}-$endByte/$effectiveDecodedTotal"
                } else if (effectiveLen != null && effectiveLen > 0L) {
                    val endByte = requestedRange.start + effectiveLen - 1L
                    respHeaders["Content-Range"] = "bytes ${requestedRange.start}-$endByte/*"
                }
            }
            if (effectiveLen != null) {
                respHeaders["Content-Length"] = effectiveLen.toString()
            }

            val code = if (isPartial) 206 else 200
            writeResponseHeader(output, code, statusReason(code), contentType, respHeaders)

            val stream = resp.body?.byteStream() ?: return
            if (skippedBytes > 0L) {
                skipFully(stream, skippedBytes)
            }

            val buf = ByteArray(256 * 1024)
            var decodedPos = requestedRange.start
            var remaining = effectiveLen ?: Long.MAX_VALUE
            while (remaining > 0L) {
                val maxRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, maxRead)
                if (n <= 0) break
                transform.transformInPlace(buf, 0, n, decodedPos)
                output.write(buf, 0, n)
                decodedPos += n
                remaining -= n
                bytesServedCounter.addAndGet(n.toLong())
            }
            output.flush()
        }
    }

    private fun streamMultiLaneTransformed(
        session: ProxySession,
        transform: RangeByteTransform,
        decodedStart: Long,
        totalBytesToSend: Long,
        output: OutputStream
    ) {
        val workers = laneExecutor ?: Executors.newCachedThreadPool { r ->
            Thread(r, "LocalStreamProxy-Lane-${threadCounter.getAndIncrement()}").apply { isDaemon = true }
        }.also { laneExecutor = it }

        val cancelled = AtomicBoolean(false)
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val window = ArrayDeque<Future<ByteArray>>(MULTI_LANE_IN_FLIGHT)
        var submitOffset = decodedStart
        var submitRemaining = totalBytesToSend
        var chunkIndex = 0

        fun chunkSizeFor(index: Int): Long = when (index) {
            0 -> FIRST_CHUNK_BYTES
            1 -> RAMP_CHUNK_BYTES
            else -> STEADY_CHUNK_BYTES
        }

        fun enqueueLookahead() {
            while (!cancelled.get() && window.size < MULTI_LANE_IN_FLIGHT && submitRemaining > 0L) {
                val size = minOf(submitRemaining, chunkSizeFor(chunkIndex++)).toInt()
                val off = submitOffset
                submitOffset += size
                submitRemaining -= size
                val future = workers.submit(Callable {
                    fetchAndTransformChunkWithRetry(
                        session = session,
                        transform = transform,
                        decodedOffset = off,
                        chunkSize = size,
                        cancelled = cancelled,
                        activeCalls = activeCalls
                    )
                })
                window.addLast(future)
            }
        }

        try {
            while (window.isNotEmpty() || submitRemaining > 0L) {
                enqueueLookahead()
                val nextFuture = window.removeFirst()
                val data = try {
                    nextFuture.get()
                } catch (ee: ExecutionException) {
                    val cause = ee.cause
                    throw if (cause is IOException) cause else IOException("Upstream chunk fetch failed", cause)
                }
                // Schedule replacement chunk before potentially blocking on loopback socket write.
                enqueueLookahead()
                output.write(data)
                output.flush()
                bytesServedCounter.addAndGet(data.size.toLong())
            }
        } finally {
            cancelled.set(true)
            window.forEach { it.cancel(true) }
            window.clear()
            activeCalls.forEach { runCatching { it.cancel() } }
            activeCalls.clear()
        }
    }

    private fun fetchAndTransformChunkWithRetry(
        session: ProxySession,
        transform: RangeByteTransform,
        decodedOffset: Long,
        chunkSize: Int,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>
    ): ByteArray {
        val rawReq = transform.toRawRange(decodedOffset, decodedOffset + chunkSize - 1L)
        var lastError: IOException? = null

        for (attempt in 0..2) {
            if (cancelled.get()) throw IOException("Proxy request cancelled")
            val headers = session.activeHeaders.toMutableMap().apply {
                put("Range", "bytes=${rawReq.start}-${rawReq.endInclusive}")
            }
            var activeCall: Call? = null
            try {
                val (resp, finalUrl) = executeWithSessionCdn(
                    session = session,
                    headers = headers,
                    isHead = false,
                    onCallCreated = { call ->
                        activeCall = call
                        activeCalls.add(call)
                    }
                )
                resp.use { r ->
                    if (r.code == 401 || r.code == 403 || r.code == 404 || r.code == 410) {
                        session.invalidateCdnCache()
                        throw IOException("HTTP ${r.code} fetching chunk")
                    }
                    if (!r.isSuccessful) {
                        throw IOException("Unexpected HTTP ${r.code} fetching chunk")
                    }
                    session.updateCdnTarget(
                        finalUrl,
                        filterHeadersForRedirect(session.activeTargetUrl, finalUrl, session.activeHeaders)
                    )
                    val stream = r.body?.byteStream() ?: throw IOException("Empty upstream chunk body")
                    if (r.code == 200 && rawReq.start > 0L) {
                        skipFully(stream, rawReq.start)
                    }
                    val out = ByteArray(chunkSize)
                    var filled = 0
                    while (filled < chunkSize) {
                        if (cancelled.get()) throw IOException("Proxy request cancelled")
                        val n = stream.read(out, filled, chunkSize - filled)
                        if (n < 0) break
                        filled += n
                    }
                    if (filled != chunkSize) {
                        throw IOException("Truncated upstream chunk ($filled/$chunkSize bytes)")
                    }
                    transform.transformInPlace(out, 0, chunkSize, decodedOffset)
                    return out
                }
            } catch (e: IOException) {
                lastError = e
                if (cancelled.get()) throw e
                if (attempt == 1) {
                    session.invalidateCdnCache()
                }
            } finally {
                activeCall?.let { activeCalls.remove(it) }
            }
        }
        throw lastError ?: IOException("Failed to fetch chunk at offset $decodedOffset")
    }

    private fun handleRawAssetRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        overrideContentType: String?,
        output: OutputStream
    ) {
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride).toMutableMap()
        if (!rangeHeader.isNullOrBlank()) {
            headers["Range"] = rangeHeader
        }
        val (response, _) = executeWithRedirects(targetUrl, headers, isHead = isHead)
        response.use { resp ->
            val contentType = overrideContentType ?: resp.header("Content-Type") ?: "application/octet-stream"
            val outHeaders = LinkedHashMap<String, String>()
            resp.header("Accept-Ranges")?.let { outHeaders["Accept-Ranges"] = it }
            resp.header("Content-Range")?.let { outHeaders["Content-Range"] = it }
            resp.header("Content-Length")?.let { outHeaders["Content-Length"] = it }
            resp.header("Content-Disposition")?.let { outHeaders["Content-Disposition"] = it }
            resp.header("Cache-Control")?.let { outHeaders["Cache-Control"] = it }

            writeResponseHeader(output, resp.code, statusReason(resp.code), contentType, outHeaders)
            if (isHead) return
            val stream = resp.body?.byteStream() ?: return
            copyStreamWithCancelCheck(stream, output, resp)
        }
    }

    private fun executeWithSessionCdn(
        session: ProxySession,
        headers: Map<String, String>,
        isHead: Boolean,
        onCallCreated: ((Call) -> Unit)? = null
    ): Pair<Response, String> {
        val cached = session.validCdnTarget()
        if (cached != null) {
            val (cdnUrl, cdnBaseHeaders) = cached
            val filteredExtra = filterHeadersForRedirect(session.activeTargetUrl, cdnUrl, headers)
            val merged = mergeRequestHeaders(cdnBaseHeaders, null, filteredExtra)
            try {
                val (resp, finalUrl) = executeWithRedirects(cdnUrl, merged, isHead, onCallCreated)
                if (resp.code != 401 && resp.code != 403 && resp.code != 404 && resp.code != 410) {
                    return resp to finalUrl
                }
                resp.close()
                session.invalidateCdnCache()
            } catch (e: IOException) {
                session.invalidateCdnCache()
            }
        }
        return if (session.mode == LocalProxyMode.HF_XOR_256) {
            executeWithHfRefreshFallback(session, headers, isHead, onCallCreated)
        } else {
            executeWithRedirects(session.activeTargetUrl, headers, isHead, onCallCreated)
        }
    }

    private fun executeWithHfRefreshFallback(
        session: ProxySession,
        headers: Map<String, String>,
        isHead: Boolean,
        onCallCreated: ((Call) -> Unit)? = null
    ): Pair<Response, String> {
        val first = executeWithRedirects(session.activeTargetUrl, headers, isHead, onCallCreated)
        val firstResp = first.first
        if ((firstResp.code == 401 || firstResp.code == 403) &&
            !session.metadataRefreshedOnce &&
            !session.hints?.metadataUrl.isNullOrBlank()
        ) {
            firstResp.close()
            session.metadataRefreshedOnce = true
            session.invalidateCdnCache()
            if (refreshHfSessionFromMetadata(session)) {
                return executeWithRedirects(
                    session.activeTargetUrl,
                    mergeRequestHeaders(session.activeHeaders, null, headers),
                    isHead,
                    onCallCreated
                )
            }
        }
        return first
    }

    private fun refreshHfSessionFromMetadata(session: ProxySession): Boolean {
        val metadataUrl = session.hints?.metadataUrl ?: return false
        return runCatching {
            val (resp, _) = executeWithRedirects(metadataUrl, session.activeHeaders, isHead = false)
            resp.use { r ->
                if (!r.isSuccessful) return@use false
                val body = r.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return@use false
                val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                @Suppress("UNCHECKED_CAST")
                val parsed = moshi.adapter<Map<String, Any?>>(mapType).fromJson(body) ?: return@use false
                val nextUrl = (parsed["url"] as? String)?.takeIf { it.isNotBlank() }
                if (nextUrl != null) {
                    session.activeTargetUrl = nextUrl
                }
                val rawHeaders = parsed["headers"] as? Map<*, *>
                if (rawHeaders != null) {
                    val refreshedHeaders = LinkedHashMap<String, String>()
                    for ((k, v) in rawHeaders) {
                        val key = k?.toString()?.takeIf { it.isNotBlank() } ?: continue
                        val valStr = v?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                        refreshedHeaders[key] = valStr
                    }
                    if (refreshedHeaders.isNotEmpty()) {
                        session.activeHeaders = HttpHeaderSanitizer.merge(session.activeHeaders, refreshedHeaders)
                    }
                }
                val newKey = LocalProxyHints.decodeKeyMaterial((parsed["xorKey"] as? String)?.ifBlank { null })
                val newHeaderSize = (parsed["headerSize"] as? Number)?.toInt() ?: session.resolvedTransform?.headerSize
                val newRaw = parsed["raw"] as? Boolean
                if (newKey != null || newRaw != null || newHeaderSize != null) {
                    session.resolvedTransform = HfXor256Transform(
                        headerSize = newHeaderSize ?: HfXor256Transform.DEFAULT_HEADER_SIZE,
                        xorKey = newKey ?: session.resolvedTransform?.xorKey ?: HfXor256Transform.DEFAULT_KEY,
                        isRaw = newRaw ?: false
                    )
                }
                true
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val MULTI_LANE_MIN_BYTES = 6L * 1024L * 1024L
        const val FIRST_CHUNK_BYTES = 512L * 1024L
        const val RAMP_CHUNK_BYTES = 2L * 1024L * 1024L
        const val STEADY_CHUNK_BYTES = 3L * 1024L * 1024L
        const val MULTI_LANE_IN_FLIGHT = 4
        val moshi: Moshi = Moshi.Builder().build()
    }

    private fun streamingClient(): OkHttpClient {
        val base = httpClientProvider()
        val id = System.identityHashCode(base)
        cachedStreamingClient?.takeIf { cachedBaseClientId == id }?.let { return it }
        synchronized(clientLock) {
            cachedStreamingClient?.takeIf { cachedBaseClientId == id }?.let { return it }
            val built = base.newBuilder()
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 32
                    }
                )
                .protocols(listOf(Protocol.HTTP_1_1))
                .eventListener(EventListener.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            cachedBaseClientId = id
            cachedStreamingClient = built
            return built
        }
    }

    /**
     * Executes an upstream HTTP request and manually follows up to 5 redirects while preserving
     * `Referer`, `Origin`, `User-Agent`, `Cookie`, and (when appropriate) `Authorization`.
     */
    private fun executeWithRedirects(
        initialUrl: String,
        headers: Map<String, String>,
        isHead: Boolean,
        onCallCreated: ((Call) -> Unit)? = null
    ): Pair<Response, String> {
        val client = streamingClient()

        var currentUrl = initialUrl
        var currentHeaders = HttpHeaderSanitizer.sanitizeMap(headers)
        var useHead = isHead

        for (hop in 0..5) {
            val reqBuilder = Request.Builder().url(currentUrl)
            for ((k, v) in currentHeaders) {
                reqBuilder.header(k, v)
            }
            if (useHead) reqBuilder.head() else reqBuilder.get()
            val call: Call = client.newCall(reqBuilder.build())
            onCallCreated?.invoke(call)
            val response = call.execute()
            val code = response.code
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")?.trim()
                if (!location.isNullOrEmpty() && hop < 5) {
                    val nextUrl = HlsAndDashRewriter.resolveUrl(currentUrl, location)
                    currentHeaders = filterHeadersForRedirect(currentUrl, nextUrl, currentHeaders)
                    if (code == 303) useHead = false
                    response.close()
                    currentUrl = nextUrl
                    continue
                }
            }
            return response to currentUrl
        }
        throw IOException("Too many redirects for $initialUrl")
    }

    private fun filterHeadersForRedirect(
        fromUrl: String,
        toUrl: String,
        headers: Map<String, String>
    ): Map<String, String> {
        val fromUri = runCatching { URI(fromUrl) }.getOrNull()
        val toUri = runCatching { URI(toUrl) }.getOrNull()
        val sameHost = fromUri?.host.equals(toUri?.host, ignoreCase = true)
        val hasSignedQuery = toUrl.contains("X-Amz-Signature=", ignoreCase = true) ||
            toUrl.contains("X-Goog-Signature=", ignoreCase = true) ||
            toUrl.contains("Signature=", ignoreCase = true)

        if (sameHost || !hasSignedQuery) {
            return headers
        }
        // Cross-origin presigned S3/CDN URLs (e.g. HF -> cas-bridge.xethub.hf.co) reject conflicting Bearer headers.
        return headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
    }

    private fun mergeRequestHeaders(
        sessionHeaders: Map<String, String>,
        refererOverride: String?,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>(sessionHeaders.size + extra.size + 1)
        merged.putAll(sessionHeaders)
        for ((k, v) in extra) {
            if (!k.equals("Range", ignoreCase = true) && !HttpHeaderSanitizer.hasHeaderIgnoreCase(merged, k)) {
                merged[k] = v
            } else if (k.equals("Range", ignoreCase = true)) {
                merged["Range"] = v
            }
        }
        if (!refererOverride.isNullOrBlank() && !HttpHeaderSanitizer.hasHeaderIgnoreCase(merged, "Referer")) {
            merged["Referer"] = refererOverride
        }
        return HttpHeaderSanitizer.sanitizeMap(merged)
    }

    private fun relayErrorResponse(resp: Response, isHead: Boolean, output: OutputStream) {
        val msg = if (isHead) ByteArray(0) else (resp.body?.bytes() ?: ByteArray(0)).let {
            if (it.size > 8192) it.copyOf(8192) else it
        }
        val contentType = resp.header("Content-Type") ?: "text/plain; charset=utf-8"
        sendBytes(output, resp.code, statusReason(resp.code), contentType, msg, isHead)
    }

    private fun sendCorsPreflight(output: OutputStream) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 204 No Content\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Max-Age: 86400\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun sendTextError(output: OutputStream, code: Int, reason: String, message: String) {
        sendBytes(output, code, reason, "text/plain; charset=utf-8", message.toByteArray(StandardCharsets.UTF_8), isHead = false)
    }

    private fun sendBytes(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        isHead: Boolean
    ) {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Length"] = body.size.toString()
        headers["Accept-Ranges"] = "none"
        writeResponseHeader(output, statusCode, reason, contentType, headers)
        if (!isHead && body.isNotEmpty()) {
            output.write(body)
            bytesServedCounter.addAndGet(body.size.toLong())
        }
    }

    private fun writeResponseHeader(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        extraHeaders: Map<String, String>
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        for ((k, v) in extraHeaders) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun copyStreamWithCancelCheck(stream: InputStream, output: OutputStream, resp: Response) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
                bytesServedCounter.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            runCatching { resp.close() }
            throw e
        }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val maxRead = minOf(scratch.size.toLong(), remaining).toInt()
            val n = stream.read(scratch, 0, maxRead)
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readUpTo(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(out, total, maxBytes - total)
            if (n < 0) break
            total += n
        }
        return if (total == out.size) out else out.copyOf(total)
    }

    private fun parseHttpRequest(input: InputStream): ParsedHttpRequest? {
        val headerBytes = readUntilHeaderEnd(input) ?: return null
        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0]
        val rawTarget = parts[1]
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        return ParsedHttpRequest(
            method = method,
            rawTarget = rawTarget,
            path = rawTarget.substringBefore('?'),
            headers = headers
        )
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray? {
        val maxBytes = 64 * 1024
        val buf = ByteArray(maxBytes)
        var len = 0
        while (len < maxBytes) {
            val b = input.read()
            if (b < 0) return if (len == 0) null else buf.copyOf(len)
            buf[len++] = b.toByte()
            if (len >= 4 &&
                buf[len - 4] == '\r'.code.toByte() &&
                buf[len - 3] == '\n'.code.toByte() &&
                buf[len - 2] == '\r'.code.toByte() &&
                buf[len - 1] == '\n'.code.toByte()
            ) {
                return buf.copyOf(len - 4)
            }
        }
        return null
    }

    private fun parseQueryParams(rawTarget: String): Map<String, String> {
        val query = rawTarget.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) decodeComponent(pair.substring(0, eq)) else decodeComponent(pair)
            val value = if (eq >= 0) decodeComponent(pair.substring(eq + 1)) else ""
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun decodeComponent(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun looksLikeInitOrMediaMp4Url(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".m4s") || clean.endsWith(".m4a") || clean.endsWith(".m4v")
    }

    private fun evictExpiredSessions() {
        val now = System.currentTimeMillis()
        val maxTtlMs = 6L * 60L * 60L * 1000L
        sessions.entries.removeIf { (_, session) ->
            now - session.lastAccessMs > maxTtlMs
        }
        if (sessions.size > 256) {
            val sorted = sessions.values.sortedBy { it.lastAccessMs }
            val excess = sessions.size - 256
            for (i in 0 until excess) {
                sessions.remove(sorted[i].id)
            }
        }
    }

    private fun statusReason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "HTTP $code"
    }

    private data class ParsedHttpRequest(
        val method: String,
        val rawTarget: String,
        val path: String,
        val headers: Map<String, String>
    )
}
