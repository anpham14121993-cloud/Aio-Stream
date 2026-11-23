package com.nuvio.tv.core.proxy

import android.util.Log
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes downloaded JavaScript Stremio local addons inside QuickJS (`quickjs-kt`), bridging
 * async `fetch()` calls directly to OkHttp on the user's device network.
 */
class QuickJsAddonScriptRunner : JsAddonScriptRunner {

    private val gson = Gson()

    override fun evaluateRequest(
        scriptCode: String,
        action: String,
        requestJson: String,
        httpClient: OkHttpClient
    ): String = runBlocking(Dispatchers.IO) {
        withTimeout(REQUEST_TIMEOUT_MS) {
            executeInQuickJs(scriptCode, action, requestJson, httpClient)
        }
    }

    private suspend fun executeInQuickJs(
        scriptCode: String,
        action: String,
        requestJson: String,
        httpClient: OkHttpClient
    ): String {
        val inFlightCalls = ConcurrentHashMap.newKeySet<Call>()
        var capturedReply = defaultEmptyReply(action)

        try {
            quickJs(Dispatchers.IO) {
                define("console") {
                    function("log") { args ->
                        Log.d(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                        null
                    }
                    function("warn") { args ->
                        Log.w(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                        null
                    }
                    function("error") { args ->
                        Log.e(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                        null
                    }
                }

                function("__addon_fetch") { args ->
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                    val bodyKind = args.getOrNull(3)?.toString() ?: "none"
                    val body = args.getOrNull(4)?.toString().orEmpty()
                    val followRedirects = args.getOrNull(5)?.toString()?.equals("false", ignoreCase = true) != true
                    performFetch(httpClient, inFlightCalls, url, method, headersJson, bodyKind, body, followRedirects)
                }

                function("__addon_parse_url") { args ->
                    parseUrlJson(args.getOrNull(0)?.toString().orEmpty())
                }

                function("__addon_get_request") {
                    requestJson
                }

                function("__addon_get_action") {
                    action
                }

                function("__addon_capture_reply") { args ->
                    val json = args.getOrNull(0)?.toString()
                    if (!json.isNullOrBlank()) {
                        capturedReply = json
                    }
                    null
                }

                evaluate<Any?>(POLYFILL_JS)

                val wrappedAddon = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $scriptCode
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedAddon)

                evaluate<Any?>(DISPATCH_JS)
            }
        } finally {
            inFlightCalls.forEach { runCatching { it.cancel() } }
            inFlightCalls.clear()
        }

        return capturedReply
    }

    private fun performFetch(
        httpClient: OkHttpClient,
        inFlightCalls: MutableSet<Call>,
        url: String,
        method: String,
        headersJson: String,
        bodyKind: String,
        body: String,
        followRedirects: Boolean
    ): String {
        return try {
            val requestBytes = when (bodyKind) {
                "base64" -> Base64.getDecoder().decode(body)
                "text" -> body.toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            val headers = LinkedHashMap<String, String>()
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(headersJson, Map::class.java) as? Map<Any?, Any?>
                parsed?.forEach { (k, v) ->
                    val key = k?.toString()?.trim().orEmpty()
                    val value = v?.toString().orEmpty()
                    if (key.isNotEmpty() && !key.equals("Accept-Encoding", ignoreCase = true)) {
                        headers[key] = value
                    }
                }
            }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DEFAULT_USER_AGENT
            }

            val headersBuilder = Headers.Builder()
            headers.forEach { (k, v) ->
                runCatching { headersBuilder.add(k, v) }
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .headers(headersBuilder.build())

            when (method.uppercase()) {
                "POST" -> reqBuilder.post(requestBytes.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
                "PUT" -> reqBuilder.put(requestBytes.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
                "PATCH" -> reqBuilder.patch(requestBytes.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
                "DELETE" -> if (bodyKind == "none") reqBuilder.delete() else reqBuilder.delete(requestBytes.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
                "HEAD" -> reqBuilder.head()
                else -> reqBuilder.get()
            }

            val client = if (followRedirects) {
                httpClient
            } else {
                httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            }
            val call = client.newCall(reqBuilder.build())
            inFlightCalls.add(call)
            try {
                call.execute().use { resp ->
                    val bytes = resp.body?.byteStream()?.use { stream ->
                        readBounded(stream, MAX_FETCH_BYTES)
                    } ?: ByteArray(0)
                    val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val textBody = runCatching { String(bytes, charset) }
                        .getOrElse { String(bytes, Charsets.UTF_8) }
                    val respHeaders = LinkedHashMap<String, String>()
                    for (name in resp.headers.names()) {
                        respHeaders[name.lowercase()] = resp.headers.values(name).joinToString(", ")
                    }
                    gson.toJson(
                        mapOf(
                            "ok" to resp.isSuccessful,
                            "status" to resp.code,
                            "statusText" to resp.message,
                            "url" to resp.request.url.toString(),
                            "body" to textBody,
                            "bodyBase64" to Base64.getEncoder().encodeToString(bytes),
                            "headers" to respHeaders
                        )
                    )
                }
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed for $url: ${t.message}")
            gson.toJson(
                mapOf(
                    "ok" to false,
                    "status" to 0,
                    "statusText" to (t.message ?: "Fetch failed"),
                    "url" to url,
                    "body" to "",
                    "bodyBase64" to "",
                    "headers" to emptyMap<String, String>()
                )
            )
        }
    }

    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buf = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun parseUrlJson(raw: String): String {
        return runCatching {
            val u = URL(raw)
            gson.toJson(
                mapOf(
                    "protocol" to "${u.protocol}:",
                    "host" to if (u.port > 0) "${u.host}:${u.port}" else u.host,
                    "hostname" to u.host,
                    "port" to if (u.port > 0) u.port.toString() else "",
                    "pathname" to (u.path ?: "/"),
                    "search" to if (u.query != null) "?${u.query}" else "",
                    "hash" to if (u.ref != null) "#${u.ref}" else ""
                )
            )
        }.getOrElse {
            """{"protocol":"","host":"","hostname":"","port":"","pathname":"/","search":"","hash":""}"""
        }
    }

    private fun defaultEmptyReply(action: String): String = when (action) {
        "catalog" -> """{"metas":[]}"""
        "meta" -> """{"meta":null}"""
        "stream" -> """{"streams":[]}"""
        else -> "{}"
    }

    companion object {
        private const val TAG = "QuickJsLocalAddon"
        private const val REQUEST_TIMEOUT_MS = 25_000L
        private const val MAX_FETCH_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

        private val POLYFILL_JS = """
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

            if (typeof atob === 'undefined') {
                globalThis.atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input || '').replace(/=+$/, '');
                    var output = '';
                    for (var bc = 0, bs = 0, buffer, idx = 0; (buffer = str.charAt(idx++)); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                        buffer = chars.indexOf(buffer);
                    }
                    return output;
                };
            }
            if (typeof btoa === 'undefined') {
                globalThis.btoa = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input || '');
                    var output = '';
                    for (var block = 0, charCode, idx = 0, map = chars; str.charAt(idx | 0) || (map = '=', idx % 1); output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
                        charCode = str.charCodeAt(idx += 3 / 4);
                        block = block << 8 | charCode;
                    }
                    return output;
                };
            }
            function __b64ToBytes(b64) {
                var bin = atob(b64 || '');
                var arr = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                return arr;
            }
            var fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var bodyKind = 'none';
                var bodyVal = '';
                if (typeof options.body === 'string') {
                    bodyKind = 'text';
                    bodyVal = options.body;
                } else if (options.body != null) {
                    bodyKind = 'text';
                    bodyVal = String(options.body);
                }
                var followRedirects = options.redirect !== 'manual';
                var raw = __addon_fetch(String(url), method, JSON.stringify(headers), bodyKind, bodyVal, followRedirects);
                var res = JSON.parse(raw);
                var bytes = __b64ToBytes(res.bodyBase64);
                return {
                    ok: !!res.ok,
                    status: res.status || 0,
                    statusText: res.statusText || '',
                    url: res.url || String(url),
                    headers: {
                        get: function(name) {
                            return (res.headers && res.headers[String(name || '').toLowerCase()]) || null;
                        }
                    },
                    text: function() { return Promise.resolve(res.body || ''); },
                    json: function() {
                        try {
                            return Promise.resolve(res.body ? JSON.parse(res.body) : null);
                        } catch (e) {
                            return Promise.resolve(null);
                        }
                    },
                    arrayBuffer: function() {
                        var copy = new Uint8Array(bytes.length);
                        copy.set(bytes);
                        return Promise.resolve(copy.buffer);
                    }
                };
            };
            var URL = function(urlString, base) {
                var fullUrl = String(urlString || '');
                if (base && !/^https?:\/\//i.test(fullUrl)) {
                    var b = typeof base === 'string' ? base : base.href;
                    if (fullUrl.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + fullUrl : fullUrl;
                    } else {
                        fullUrl = b.replace(/\/[^\/]*$/, '/') + fullUrl;
                    }
                }
                var p = JSON.parse(__addon_parse_url(fullUrl));
                this.href = fullUrl;
                this.protocol = p.protocol;
                this.host = p.host;
                this.hostname = p.hostname;
                this.port = p.port;
                this.pathname = p.pathname;
                this.search = p.search;
                this.hash = p.hash;
                this.origin = p.protocol && p.host ? p.protocol + '//' + p.host : '';
            };
        """.trimIndent()

        private val DISPATCH_JS = """
            (async function() {
                try {
                    var exp = (module && module.exports) || {};
                    var action = __addon_get_action();
                    var req = JSON.parse(__addon_get_request() || '{}');
                    if (action === 'manifest') {
                        var mf = exp.manifest || globalThis.manifest || (typeof exp.getManifest === 'function' ? await exp.getManifest(req) : null);
                        __addon_capture_reply(JSON.stringify(mf || {}));
                        return;
                    }
                    var fn = null;
                    if (action === 'catalog') fn = exp.handleCatalog || exp.catalog || globalThis.handleCatalog;
                    else if (action === 'meta') fn = exp.handleMeta || exp.meta || globalThis.handleMeta;
                    else if (action === 'stream') fn = exp.handleStream || exp.stream || globalThis.handleStream;
                    if (!fn && typeof (exp.onRequest || exp.handleRequest) === 'function') {
                        fn = exp.onRequest || exp.handleRequest;
                    }
                    if (typeof fn !== 'function') {
                        return;
                    }
                    var out = await fn(req);
                    if (out && typeof out === 'object') {
                        __addon_capture_reply(JSON.stringify(out));
                    }
                } catch (e) {
                    console.error('Addon execution error:', e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
    }
}
