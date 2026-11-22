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
    private val httpClientProvider: () -> OkHttpClient,
    private val defaultPreferredPort: Int = 0,
    internal val fiml4kAddonHandler: Fiml4kLocalAddonHandler = Fiml4kLocalAddonHandler(httpClientProvider)
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
    private val disguisedPrefixCache = ConcurrentHashMap<String, DetectedSegmentPayload>()
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadCounter = AtomicInteger(1)
    private val bytesServedCounter = AtomicLong(0L)

    fun start(preferredPort: Int = defaultPreferredPort): Int {
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
            socket.soTimeout = 90_000
            socket.tcpNoDelay = true
            runCatching { socket.sendBufferSize = 1024 * 1024 }
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (running && !socket.isClosed) {
                val request = parseHttpRequest(input) ?: break
                val canKeepAlive = dispatchRequest(request, output)
                output.flush()
                if (!canKeepAlive) break
            }
        } catch (_: SocketTimeoutException) {
            // Client idle or stalled on keep-alive / request line
        } catch (_: EOFException) {
            // Client closed connection / short upstream read
        } catch (_: IOException) {
            // Client disconnected during streaming
        } catch (_: Throwable) {
            // Protect accept loop from per-request crashes
        } finally {
            activeClientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    /**
     * Handles one HTTP request and returns `true` only if HTTP/1.1 Keep-Alive was requested AND
     * an exact `Content-Length`-framed response completed intact so the socket can safely serve
     * subsequent chunk requests.
     */
    private fun dispatchRequest(req: ParsedHttpRequest, output: OutputStream): Boolean {
        val wantKeepAlive = req.httpVersion == "HTTP/1.1" &&
            !req.headers["connection"].orEmpty().contains("close", ignoreCase = true)

        val method = req.method.uppercase()
        if (method == "OPTIONS") {
            return sendCorsPreflight(output, wantKeepAlive)
        }
        if (method != "GET" && method != "HEAD") {
            return sendTextError(output, 405, "Method Not Allowed", "Only GET, HEAD, and OPTIONS are supported", wantKeepAlive)
        }

        val isHead = method == "HEAD"
        val cleanPath = req.path.substringBefore('?')
        if (cleanPath == "/health") {
            val json = "{\"ok\":true,\"port\":$port}"
            return sendBytes(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8), isHead, wantKeepAlive)
        }
        if (cleanPath == Fiml4kLocalAddonHandler.BASE_PATH ||
            cleanPath.startsWith("${Fiml4kLocalAddonHandler.BASE_PATH}/")
        ) {
            val reply = fiml4kAddonHandler.handleRequest(req.rawTarget, cleanPath)
            return sendBytes(
                output = output,
                statusCode = reply.statusCode,
                reason = statusReason(reply.statusCode),
                contentType = "application/json; charset=utf-8",
                body = reply.bodyBytes,
                isHead = isHead,
                keepAlive = wantKeepAlive,
                extraHeaders = mapOf("Cache-Control" to reply.cacheControl)
            )
        }

        // Expected: /p/<sessionId>/<route>[/...]
        val trimmed = cleanPath.removePrefix("/")
        val segments = trimmed.split('/')
        if (segments.size < 3 || segments[0] != "p") {
            return sendTextError(output, 404, "Not Found", "Unknown proxy endpoint", wantKeepAlive)
        }

        val sessionId = segments[1]
        val route = segments[2]
        val session = getSession(sessionId) ?: run {
            return sendTextError(output, 404, "Not Found", "Proxy session expired or not found", wantKeepAlive)
        }

        val queryParams = parseQueryParams(req.rawTarget)
        val rangeHeader = req.headers["range"]

        return when (route) {
            "media" -> handleMediaRequest(
                session = session,
                targetUrl = queryParams["u"] ?: session.activeTargetUrl,
                refererOverride = queryParams["ref"],
                rangeHeader = rangeHeader,
                isHead = isHead,
                allowAdaptiveMultiLane = false,
                wantKeepAlive = wantKeepAlive,
                output = output
            )
            "smedia" -> handleMediaRequest(
                session = session,
                targetUrl = queryParams["u"] ?: session.activeTargetUrl,
                refererOverride = queryParams["ref"],
                rangeHeader = rangeHeader,
                isHead = isHead,
                allowAdaptiveMultiLane = true,
                wantKeepAlive = wantKeepAlive,
                output = output
            )
            "hls" -> handleHlsPlaylistRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], isHead, wantKeepAlive, output)
            "seg" -> handleHlsSegmentRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, output)
            "key" -> handleRawAssetRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], rangeHeader, isHead, "application/octet-stream", output)
            "dash" -> handleDashManifestRequest(session, queryParams["u"] ?: session.activeTargetUrl, queryParams["ref"], isHead, wantKeepAlive, output)
            "rel" -> {
                if (segments.size < 4) {
                    return sendTextError(output, 400, "Bad Request", "Missing relative base token", wantKeepAlive)
                }
                val base = HlsAndDashRewriter.decodeBase64Url(segments[3]) ?: run {
                    return sendTextError(output, 400, "Bad Request", "Invalid relative base token", wantKeepAlive)
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
            else -> sendTextError(output, 404, "Not Found", "Unsupported proxy route: $route", wantKeepAlive)
        }
    }

    private fun handleMediaRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        allowAdaptiveMultiLane: Boolean,
        wantKeepAlive: Boolean,
        output: OutputStream
    ): Boolean {
        return when (session.mode) {
            LocalProxyMode.HLS_REWRITE -> handleHlsPlaylistRequest(session, targetUrl, refererOverride, isHead, wantKeepAlive, output)
            LocalProxyMode.DASH_REWRITE -> handleDashManifestRequest(session, targetUrl, refererOverride, isHead, wantKeepAlive, output)
            LocalProxyMode.HF_XOR_256 -> handleHfXorRequest(session, rangeHeader, isHead, allowAdaptiveMultiLane, wantKeepAlive, output)
            LocalProxyMode.PREFIX_SKIP -> {
                val skip = (session.hints?.skipBytes ?: 0L).coerceAtLeast(0L)
                handleTransformedProgressiveRequest(
                    session = session,
                    transform = PrefixSkipTransform(skip),
                    rangeHeader = rangeHeader,
                    isHead = isHead,
                    allowAdaptiveMultiLane = allowAdaptiveMultiLane,
                    wantKeepAlive = wantKeepAlive,
                    overrideContentType = session.hints?.contentType,
                    output = output
                )
            }
            LocalProxyMode.MPEG_TS_UNWRAP -> {
                handleBufferedTsUnwrapRequest(session, targetUrl, refererOverride, rangeHeader, isHead, output)
                false
            }
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
        wantKeepAlive: Boolean,
        output: OutputStream
    ): Boolean {
        val fragment = targetUrl.substringAfter('#', "")
        val wireUrl = targetUrl.substringBefore('#')
        val requestedQuality = parseFragmentParam(fragment, "aio_quality")
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, finalUrl) = executeWithRedirects(wireUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                return relayErrorResponse(resp, isHead, wantKeepAlive, output)
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            val text = bodyBytes.toString(StandardCharsets.UTF_8)
            if (HlsAndDashRewriter.containsHlsDrm(text)) {
                return sendTextError(output, 403, "Forbidden", "DRM-protected streams are not supported by local proxy", wantKeepAlive)
            }
            val filteredText = if (!requestedQuality.isNullOrBlank() &&
                text.contains("#EXT-X-STREAM-INF", ignoreCase = true)
            ) {
                HlsAndDashRewriter.filterMasterPlaylistByQuality(text, requestedQuality)
            } else {
                text
            }
            val rewritten = HlsAndDashRewriter.rewriteHlsPlaylist(
                playlistText = filteredText,
                playlistUrl = finalUrl
            ) { kind, resolvedTarget, playlistRef ->
                buildAssetUrl(session.id, kind, resolvedTarget, playlistRef)
            }
            val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
            return sendBytes(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentType = "application/vnd.apple.mpegurl; charset=utf-8",
                body = bytes,
                isHead = isHead,
                keepAlive = wantKeepAlive
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
    ): Boolean {
        val allowUnwrap = session.hints?.autoUnwrapTs != false
        if (!allowUnwrap || isSubtitleSegmentUrl(targetUrl)) {
            return handleRawAssetRequest(session, targetUrl, refererOverride, rangeHeader, isHead, session.hints?.contentType, output)
        }

        val prefixKey = "${session.id}|${targetUrl.substringBefore('?')}"
        if (isHead || !rangeHeader.isNullOrBlank()) {
            val cached = disguisedPrefixCache[prefixKey]
                ?: if (!looksLikeKnownPlainSegmentUrl(targetUrl)) probeDisguisedSegmentPrefix(session, targetUrl, refererOverride) else null
            if (cached != null && cached.syncOffset > 0) {
                return handlePrefixStrippedHlsRangeOrHead(
                    session = session,
                    targetUrl = targetUrl,
                    refererOverride = refererOverride,
                    rangeHeader = rangeHeader,
                    isHead = isHead,
                    detected = cached,
                    output = output
                )
            }
            return handleRawAssetRequest(session, targetUrl, refererOverride, rangeHeader, isHead, session.hints?.contentType, output)
        }

        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, _) = executeWithRedirects(targetUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead = false, wantKeepAlive = false, output = output)
                return false
            }
            val upstreamType = resp.header("Content-Type")?.trim().orEmpty()
            if (upstreamType.startsWith("text/", ignoreCase = true) ||
                (looksLikeInitOrMediaMp4Url(targetUrl) &&
                    !upstreamType.startsWith("image/", ignoreCase = true) &&
                    !upstreamType.equals("application/octet-stream", ignoreCase = true))
            ) {
                val outHeaders = LinkedHashMap<String, String>()
                resp.header("Accept-Ranges")?.let { outHeaders["Accept-Ranges"] = it }
                resp.header("Content-Range")?.let { outHeaders["Content-Range"] = it }
                resp.header("Content-Length")?.let { outHeaders["Content-Length"] = it }
                resp.header("Cache-Control")?.let { outHeaders["Cache-Control"] = it }
                val effectiveType = session.hints?.contentType ?: upstreamType.ifEmpty { "application/octet-stream" }
                writeResponseHeader(output, resp.code, statusReason(resp.code), effectiveType, outHeaders, keepAlive = false)
                val stream = resp.body?.byteStream() ?: return false
                copyStreamWithCancelCheck(stream, output, resp)
                return false
            }

            val stream = resp.body?.byteStream() ?: run {
                sendBytes(output, 200, "OK", upstreamType.ifEmpty { "video/mp2t" }, ByteArray(0), isHead = false, keepAlive = false)
                return false
            }
            // Peek first 8KB to detect PNG/JPEG/GIF/HTML wrappers around MPEG-TS (`0x47`) OR ISOBMFF/fMP4 (`ftyp`/`styp`/`moov`/`moof`).
            val peekBuffer = ByteArray(8192)
            var peekRead = 0
            while (peekRead < peekBuffer.size) {
                val n = stream.read(peekBuffer, peekRead, peekBuffer.size - peekRead)
                if (n < 0) break
                peekRead += n
                if (peekRead >= 1024 && DisguisedMediaDetector.detectPayload(peekBuffer, peekRead, upstreamType) != null) {
                    break
                }
            }
            val detected = DisguisedMediaDetector.detectPayload(peekBuffer, peekRead, upstreamType)
            val syncOffset = detected?.syncOffset ?: 0
            if (syncOffset > 0 && detected != null) {
                cacheDisguisedPrefix(prefixKey, detected)
            }
            val contentLength = resp.header("Content-Length")?.toLongOrNull()
            val adjustedLength = if (syncOffset > 0 && contentLength != null && contentLength > syncOffset) {
                contentLength - syncOffset
            } else {
                contentLength
            }
            val resolvedType = when {
                detected != null && upstreamType.startsWith("image/", ignoreCase = true) -> detected.contentType
                upstreamType.isNotEmpty() -> upstreamType
                detected != null -> detected.contentType
                else -> "application/octet-stream"
            }

            val extraHeaders = LinkedHashMap<String, String>()
            extraHeaders["Accept-Ranges"] = resp.header("Accept-Ranges") ?: "none"
            if (adjustedLength != null) {
                extraHeaders["Content-Length"] = adjustedLength.toString()
            }
            writeResponseHeader(output, resp.code, statusReason(resp.code), resolvedType, extraHeaders, keepAlive = false)

            val skipOffset = if (syncOffset > 0) syncOffset else 0
            val firstPayloadLen = (peekRead - skipOffset).coerceAtLeast(0)
            if (firstPayloadLen > 0) {
                output.write(peekBuffer, skipOffset, firstPayloadLen)
                output.flush()
                bytesServedCounter.addAndGet(firstPayloadLen.toLong())
            }
            copyStreamWithCancelCheck(stream, output, resp)
            return false
        }
    }

    private fun handleDashManifestRequest(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        isHead: Boolean,
        wantKeepAlive: Boolean,
        output: OutputStream
    ): Boolean {
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride)
        val (response, finalUrl) = executeWithRedirects(targetUrl, headers, isHead = false)
        response.use { resp ->
            if (!resp.isSuccessful) {
                return relayErrorResponse(resp, isHead, wantKeepAlive, output)
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            val text = bodyBytes.toString(StandardCharsets.UTF_8)
            if (HlsAndDashRewriter.containsDashDrm(text)) {
                return sendTextError(output, 403, "Forbidden", "DRM-protected DASH streams are not supported by local proxy", wantKeepAlive)
            }
            val rewritten = HlsAndDashRewriter.rewriteDashManifest(text, finalUrl) { resolvedBase ->
                buildDashBaseUrlPrefix(session.id, resolvedBase)
            }
            return sendBytes(
                output = output,
                statusCode = 200,
                reason = "OK",
                contentType = "application/dash+xml; charset=utf-8",
                body = rewritten.toByteArray(StandardCharsets.UTF_8),
                isHead = isHead,
                keepAlive = wantKeepAlive
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
        allowAdaptiveMultiLane: Boolean,
        wantKeepAlive: Boolean,
        output: OutputStream
    ): Boolean {
        val (transform, _) = ensureHfResolved(session)
        return handleTransformedProgressiveRequest(
            session = session,
            transform = transform,
            rangeHeader = rangeHeader,
            isHead = isHead,
            allowAdaptiveMultiLane = allowAdaptiveMultiLane,
            wantKeepAlive = wantKeepAlive,
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
        allowAdaptiveMultiLane: Boolean,
        wantKeepAlive: Boolean,
        overrideContentType: String?,
        output: OutputStream
    ): Boolean {
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
                writeResponseHeader(output, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", headers, keepAlive = wantKeepAlive)
                return wantKeepAlive
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
                writeResponseHeader(output, 206, "Partial Content", contentType, headers, keepAlive = wantKeepAlive)
            } else {
                if (decodedTotalSize != null) {
                    headers["Content-Length"] = decodedTotalSize.toString()
                }
                writeResponseHeader(output, 200, "OK", contentType, headers, keepAlive = wantKeepAlive)
            }
            return wantKeepAlive
        }

        val isPartial = rangeHeader != null
        val requestedLen = requestedRange.contentLengthOrNull()
        val isOpenEndedStream = rangeHeader == null || rangeHeader.trim().endsWith("-")

        // Only single-connection clients (`libmpv`, VLC, MX Player via `/smedia`) use the proxy's
        // internal adaptive multi-lane chunk scheduler. ExoPlayer (`/media`) manages its own chunking &
        // parallelism via `AutoSpeedup` and `ParallelRangeDataSource` without any fixed threshold.
        if (allowAdaptiveMultiLane &&
            decodedTotalSize != null &&
            requestedLen != null &&
            requestedLen > ADAPTIVE_SINGLE_CLIENT_MIN_BYTES &&
            isOpenEndedStream
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
            writeResponseHeader(output, code, statusReason(code), contentType, respHeaders, keepAlive = wantKeepAlive)
            output.flush()
            streamAdaptiveSingleClient(
                session = session,
                transform = transform,
                decodedStart = requestedRange.start,
                totalBytesToSend = requestedLen,
                output = output
            )
            return wantKeepAlive
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
                return relayErrorResponse(resp, isHead = false, wantKeepAlive = wantKeepAlive, output = output)
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
            val keepAliveForThisResponse = wantKeepAlive && effectiveLen != null
            if (effectiveLen != null) {
                respHeaders["Content-Length"] = effectiveLen.toString()
            }

            val code = if (isPartial) 206 else 200
            writeResponseHeader(output, code, statusReason(code), contentType, respHeaders, keepAlive = keepAliveForThisResponse)

            val stream = resp.body?.byteStream() ?: run {
                if (effectiveLen != null && effectiveLen > 0L) {
                    throw EOFException("Empty upstream body while expecting $effectiveLen bytes")
                }
                return keepAliveForThisResponse
            }
            if (skippedBytes > 0L) {
                skipFully(stream, skippedBytes)
            }

            val buf = ByteArray(256 * 1024)
            var decodedPos = requestedRange.start
            var remaining = effectiveLen ?: Long.MAX_VALUE
            var written = 0L
            val flushTracker = StreamFlushTracker(output)
            while (remaining > 0L) {
                val maxRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, maxRead)
                if (n <= 0) break
                transform.transformInPlace(buf, 0, n, decodedPos)
                output.write(buf, 0, n)
                decodedPos += n
                remaining -= n
                written += n
                bytesServedCounter.addAndGet(n.toLong())
                flushTracker.onBytesWritten(n)
            }
            output.flush()
            if (effectiveLen != null && written != effectiveLen) {
                throw EOFException("Upstream truncated response ($written/$effectiveLen bytes)")
            }
            return keepAliveForThisResponse
        }
    }

    /**
     * Adaptive multi-lane chunk pipeline for single-connection players (`/smedia`, e.g. MPV/VLC/MX Player).
     * Dynamically scales lanes (`1..maxHeapLanes`) and chunk sizes based on measured throughput,
     * upstream throttling feedback, and available JVM heap headroom.
     */
    private fun streamAdaptiveSingleClient(
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
        val targetLanes = AtomicInteger(2)
        val ewmaLaneBps = AtomicLong(0L)
        val cooldownChunks = AtomicInteger(0)

        data class ChunkTask(val size: Int, val future: Future<ByteArray>)

        val window = ArrayDeque<ChunkTask>(MAX_ADAPTIVE_LANES)
        var submitOffset = decodedStart
        var submitRemaining = totalBytesToSend
        var chunkIndex = 0

        fun maxHeapSafeLanes(): Int {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val freeMb = (rt.maxMemory() - used).coerceAtLeast(0L) / (1024L * 1024L)
            return when {
                freeMb >= 72L -> MAX_ADAPTIVE_LANES
                freeMb >= 40L -> 4
                else -> 2
            }
        }

        fun nextChunkSize(index: Int, lanes: Int): Long = when (index) {
            0 -> FIRST_CHUNK_BYTES
            1 -> RAMP_CHUNK_BYTES
            else -> when {
                lanes >= 5 && ewmaLaneBps.get() in 1L until 20_000_000L -> 4L * 1024L * 1024L
                maxHeapSafeLanes() >= 4 -> 3L * 1024L * 1024L
                else -> 2L * 1024L * 1024L
            }
        }

        fun enqueueLookahead() {
            val desired = targetLanes.get().coerceIn(1, maxHeapSafeLanes())
            while (!cancelled.get() && window.size < desired && submitRemaining > 0L) {
                val size = minOf(submitRemaining, nextChunkSize(chunkIndex++, desired)).toInt()
                val off = submitOffset
                submitOffset += size
                submitRemaining -= size
                val future = workers.submit(Callable {
                    val t0 = System.nanoTime()
                    try {
                        val bytes = fetchAndTransformChunkWithRetry(
                            session = session,
                            transform = transform,
                            decodedOffset = off,
                            chunkSize = size,
                            cancelled = cancelled,
                            activeCalls = activeCalls
                        )
                        val elapsedNs = (System.nanoTime() - t0).coerceAtLeast(1_000_000L)
                        val chunkBps = (bytes.size.toLong() * 8_000_000_000L) / elapsedNs
                        val prev = ewmaLaneBps.get()
                        val updated = if (prev <= 0L) chunkBps else (prev * 7 + chunkBps * 3) / 10
                        ewmaLaneBps.set(updated)

                        val cool = cooldownChunks.get()
                        if (cool > 0) {
                            cooldownChunks.decrementAndGet()
                        } else {
                            val currentLanes = targetLanes.get()
                            val estAggregateBps = updated * currentLanes
                            val ceiling = maxHeapSafeLanes()
                            if (estAggregateBps < TARGET_AGGREGATE_BPS && currentLanes < ceiling) {
                                targetLanes.compareAndSet(currentLanes, (currentLanes + 1).coerceAtMost(ceiling))
                            }
                        }
                        bytes
                    } catch (e: IOException) {
                        if (!cancelled.get()) {
                            cooldownChunks.set(4)
                            targetLanes.updateAndGet { cur -> (cur / 2).coerceAtLeast(1) }
                        }
                        throw e
                    }
                })
                window.addLast(ChunkTask(size, future))
            }
        }

        var writtenTotal = 0L
        try {
            while (window.isNotEmpty() || submitRemaining > 0L) {
                enqueueLookahead()
                val task = window.removeFirst()
                val data = try {
                    task.future.get()
                } catch (ee: ExecutionException) {
                    val cause = ee.cause
                    throw if (cause is IOException) cause else IOException("Upstream chunk fetch failed", cause)
                }
                enqueueLookahead()
                output.write(data)
                output.flush()
                writtenTotal += data.size
                bytesServedCounter.addAndGet(data.size.toLong())
            }
            if (writtenTotal != totalBytesToSend) {
                throw EOFException("Adaptive multi-lane short write ($writtenTotal/$totalBytesToSend)")
            }
        } finally {
            cancelled.set(true)
            window.forEach { it.future.cancel(true) }
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
    ): Boolean {
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

            writeResponseHeader(output, resp.code, statusReason(resp.code), contentType, outHeaders, keepAlive = false)
            if (isHead) return false
            val stream = resp.body?.byteStream() ?: return false
            copyStreamWithCancelCheck(stream, output, resp)
            return false
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
        const val ADAPTIVE_SINGLE_CLIENT_MIN_BYTES = 2L * 1024L * 1024L
        const val FIRST_CHUNK_BYTES = 512L * 1024L
        const val RAMP_CHUNK_BYTES = 2L * 1024L * 1024L
        const val MAX_ADAPTIVE_LANES = 6
        const val TARGET_AGGREGATE_BPS = 150_000_000L
        const val STREAM_FLUSH_THRESHOLD_BYTES = 32 * 1024
        const val STREAM_FLUSH_INTERVAL_NS = 25_000_000L
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

    private fun relayErrorResponse(resp: Response, isHead: Boolean, wantKeepAlive: Boolean, output: OutputStream): Boolean {
        val msg = if (isHead) ByteArray(0) else (resp.body?.bytes() ?: ByteArray(0)).let {
            if (it.size > 8192) it.copyOf(8192) else it
        }
        val contentType = resp.header("Content-Type") ?: "text/plain; charset=utf-8"
        return sendBytes(output, resp.code, statusReason(resp.code), contentType, msg, isHead, wantKeepAlive)
    }

    private fun sendCorsPreflight(output: OutputStream, keepAlive: Boolean): Boolean {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 204 No Content\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Max-Age: 86400\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append(if (keepAlive) "Connection: keep-alive\r\nKeep-Alive: timeout=90\r\n\r\n" else "Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        return keepAlive
    }

    private fun sendTextError(output: OutputStream, code: Int, reason: String, message: String, keepAlive: Boolean): Boolean {
        return sendBytes(output, code, reason, "text/plain; charset=utf-8", message.toByteArray(StandardCharsets.UTF_8), isHead = false, keepAlive = keepAlive)
    }

    private fun sendBytes(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        isHead: Boolean,
        keepAlive: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): Boolean {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Length"] = body.size.toString()
        headers["Accept-Ranges"] = "none"
        headers.putAll(extraHeaders)
        writeResponseHeader(output, statusCode, reason, contentType, headers, keepAlive = keepAlive)
        if (!isHead && body.isNotEmpty()) {
            output.write(body)
            bytesServedCounter.addAndGet(body.size.toLong())
        }
        return keepAlive
    }

    private fun writeResponseHeader(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        extraHeaders: Map<String, String>,
        keepAlive: Boolean = false
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
        if (keepAlive) {
            sb.append("Connection: keep-alive\r\n")
            sb.append("Keep-Alive: timeout=90\r\n\r\n")
        } else {
            sb.append("Connection: close\r\n\r\n")
        }
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun copyStreamWithCancelCheck(stream: InputStream, output: OutputStream, resp: Response) {
        val buf = ByteArray(64 * 1024)
        val flushTracker = StreamFlushTracker(output)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
                bytesServedCounter.addAndGet(n.toLong())
                flushTracker.onBytesWritten(n)
            }
            output.flush()
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
        val httpVersion = if (parts.size >= 3) parts[2].trim().uppercase() else "HTTP/1.0"
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
            httpVersion = httpVersion,
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

    private fun probeDisguisedSegmentPrefix(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?
    ): DetectedSegmentPayload? {
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride).toMutableMap()
        headers["Range"] = "bytes=0-8191"
        val prefixKey = "${session.id}|${targetUrl.substringBefore('?')}"
        return runCatching {
            val (response, _) = executeWithRedirects(targetUrl, headers, isHead = false)
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val upstreamType = resp.header("Content-Type")?.trim().orEmpty()
                val probeBytes = resp.body?.byteStream()?.let { readUpTo(it, 8192) } ?: ByteArray(0)
                val detected = DisguisedMediaDetector.detectPayload(probeBytes, probeBytes.size, upstreamType)
                if (detected != null && detected.syncOffset > 0) {
                    cacheDisguisedPrefix(prefixKey, detected)
                    detected
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun handlePrefixStrippedHlsRangeOrHead(
        session: ProxySession,
        targetUrl: String,
        refererOverride: String?,
        rangeHeader: String?,
        isHead: Boolean,
        detected: DetectedSegmentPayload,
        output: OutputStream
    ): Boolean {
        val skip = detected.syncOffset.toLong()
        val headers = mergeRequestHeaders(session.activeHeaders, refererOverride).toMutableMap()
        if (!rangeHeader.isNullOrBlank()) {
            when (val parsed = RangeHeaderParser.parse(rangeHeader, null)) {
                is RangeParseResult.SingleRange -> {
                    val upStart = parsed.spec.start + skip
                    val upEnd = parsed.spec.endInclusive?.let { it + skip }
                    headers["Range"] = if (upEnd != null) "bytes=$upStart-$upEnd" else "bytes=$upStart-"
                }
                is RangeParseResult.Unsatisfiable -> {
                    return sendTextError(output, 416, "Range Not Satisfiable", "Requested range unsatisfiable", wantKeepAlive = false)
                }
                RangeParseResult.NoRange -> Unit
            }
        }
        val (response, _) = executeWithRedirects(targetUrl, headers, isHead = isHead)
        response.use { resp ->
            if (!resp.isSuccessful) {
                relayErrorResponse(resp, isHead = isHead, wantKeepAlive = false, output = output)
                return false
            }
            val outHeaders = LinkedHashMap<String, String>()
            outHeaders["Accept-Ranges"] = resp.header("Accept-Ranges") ?: "bytes"
            val upstreamCr = resp.header("Content-Range")
            if (upstreamCr != null) {
                val parsedCr = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).matchEntire(upstreamCr.trim())
                if (parsedCr != null) {
                    val s = (parsedCr.groupValues[1].toLong() - skip).coerceAtLeast(0L)
                    val e = (parsedCr.groupValues[2].toLong() - skip).coerceAtLeast(s)
                    val total = parsedCr.groupValues[3].toLongOrNull()?.let { (it - skip).coerceAtLeast(0L).toString() } ?: "*"
                    outHeaders["Content-Range"] = "bytes $s-$e/$total"
                } else {
                    outHeaders["Content-Range"] = upstreamCr
                }
            }
            val upstreamLen = resp.header("Content-Length")?.toLongOrNull()
            if (upstreamLen != null) {
                val adjLen = if (rangeHeader.isNullOrBlank()) (upstreamLen - skip).coerceAtLeast(0L) else upstreamLen
                outHeaders["Content-Length"] = adjLen.toString()
            }
            writeResponseHeader(output, resp.code, statusReason(resp.code), detected.contentType, outHeaders, keepAlive = false)
            if (isHead) return false
            val stream = resp.body?.byteStream() ?: return false
            if (rangeHeader.isNullOrBlank() || resp.code == 200) {
                skipFully(stream, skip)
            }
            copyStreamWithCancelCheck(stream, output, resp)
            return false
        }
    }

    private fun cacheDisguisedPrefix(prefixKey: String, detected: DetectedSegmentPayload) {
        if (disguisedPrefixCache.size > 1024) {
            disguisedPrefixCache.clear()
        }
        disguisedPrefixCache[prefixKey] = detected
    }

    private fun parseFragmentParam(fragment: String, name: String): String? {
        if (fragment.isBlank()) return null
        for (pair in fragment.split('&')) {
            val eq = pair.indexOf('=')
            val key = decodeComponent(if (eq >= 0) pair.substring(0, eq) else pair)
            if (key == name && eq >= 0) {
                return decodeComponent(pair.substring(eq + 1))
            }
        }
        return null
    }

    private fun isSubtitleSegmentUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".vtt") || clean.endsWith(".srt") || clean.endsWith(".ass") || clean.endsWith(".ssa")
    }

    private fun looksLikeKnownPlainSegmentUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".ts") || clean.endsWith(".aac") || looksLikeInitOrMediaMp4Url(url)
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
        val httpVersion: String,
        val headers: Map<String, String>
    )

    private class StreamFlushTracker(private val output: OutputStream) {
        private var unflushedBytes = 0
        private var lastFlushNs = System.nanoTime()

        fun onBytesWritten(count: Int) {
            unflushedBytes += count
            val nowNs = System.nanoTime()
            if (unflushedBytes >= STREAM_FLUSH_THRESHOLD_BYTES ||
                nowNs - lastFlushNs >= STREAM_FLUSH_INTERVAL_NS
            ) {
                output.flush()
                unflushedBytes = 0
                lastFlushNs = nowNs
            }
        }
    }
}
