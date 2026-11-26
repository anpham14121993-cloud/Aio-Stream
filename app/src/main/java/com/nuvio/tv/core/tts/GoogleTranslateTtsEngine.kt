package com.nuvio.tv.core.tts

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Online Google Translate TTS fetcher (`Ll02` / `Lk02` parity).
 * Splits long Vietnamese text into <=190-char whitespace chunks, fetches MP3 segments with
 * coroutine-cancellable OkHttp calls & striped deduplication, and maintains a bounded disk cache
 * for smooth rolling ("cuốn chiếu") prefetching.
 */
class GoogleTranslateTtsEngine(private val context: Context) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
            .followRedirects(true)
            .build()
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "tts_cache").apply { mkdirs() }
    }

    private val stripedLocks = Array(32) { Mutex() }
    private val writeCounter = AtomicInteger(0)

    private sealed interface FetchOutcome {
        data class Success(val bytes: ByteArray) : FetchOutcome
        data class HttpError(val code: Int) : FetchOutcome
        data class NetworkError(val error: IOException) : FetchOutcome
        data object InvalidPayload : FetchOutcome
    }

    suspend fun synthesizeToMp3(
        text: String,
        targetCacheKey: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext null

        val key = targetCacheKey ?: sha256Short("gt|$cleanText")
        val outFile = File(cacheDir, "gt_$key.mp3")
        if (outFile.exists() && outFile.length() > 256L) {
            runCatching { outFile.setLastModified(System.currentTimeMillis()) }
            return@withContext outFile
        }

        val lock = stripedLocks[(key.hashCode() and 0x7FFF_FFFF) % stripedLocks.size]
        lock.withLock {
            currentCoroutineContext().ensureActive()
            if (outFile.exists() && outFile.length() > 256L) {
                runCatching { outFile.setLastModified(System.currentTimeMillis()) }
                return@withLock outFile
            }

            val chunks = splitIntoChunks(cleanText, MAX_CHUNK_CHARS)
            if (chunks.isEmpty()) return@withLock null

            val mergedAudio = ByteArrayOutputStream(chunks.size * 8192)
            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()
                val bytes = fetchChunkWithRetry(chunk) ?: return@withLock null
                mergedAudio.write(bytes)
            }

            val payload = mergedAudio.toByteArray()
            if (payload.size <= 128) return@withLock null

            val tmpFile = File(cacheDir, "gt_$key.mp3.tmp")
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(payload)
                    fos.fd.sync()
                }
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete()
                    return@withLock null
                }
                if (writeCounter.incrementAndGet() % PRUNE_INTERVAL_WRITES == 0) {
                    pruneDiskCacheLocked()
                }
                outFile
            } catch (ce: CancellationException) {
                tmpFile.delete()
                throw ce
            } catch (_: Throwable) {
                tmpFile.delete()
                null
            }
        }
    }

    private suspend fun fetchChunkWithRetry(chunk: String): ByteArray? {
        val encoded = URLEncoder.encode(chunk, "UTF-8")
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$encoded"
        for (attempt in 0..MAX_TRANSIENT_RETRIES) {
            currentCoroutineContext().ensureActive()
            when (val outcome = executeGetBytes(url)) {
                is FetchOutcome.Success -> return outcome.bytes
                is FetchOutcome.HttpError -> {
                    // Back off in coordinator on 429/4xx; only retry 5xx once briefly.
                    if (outcome.code in 500..599 && attempt < MAX_TRANSIENT_RETRIES) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return null
                }
                is FetchOutcome.NetworkError -> {
                    if (attempt < MAX_TRANSIENT_RETRIES) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return null
                }
                FetchOutcome.InvalidPayload -> return null
            }
        }
        return null
    }

    private suspend fun executeGetBytes(url: String): FetchOutcome = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.8")
            .build()

        val call = httpClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) {
                    cont.resume(FetchOutcome.NetworkError(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (!cont.isCompleted) {
                            cont.resume(FetchOutcome.HttpError(resp.code))
                        }
                        return
                    }
                    val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                    if (contentType.startsWith("text/")) {
                        if (!cont.isCompleted) {
                            cont.resume(FetchOutcome.InvalidPayload)
                        }
                        return
                    }
                    val bytes = runCatching { resp.body?.bytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        if (!cont.isCompleted) {
                            cont.resume(FetchOutcome.InvalidPayload)
                        }
                        return
                    }
                    // Guard against accidental HTML consent/error page instead of MP3/MPEG stream
                    val sniffHead = bytes.take(16).toByteArray().decodeToString().trimStart().lowercase()
                    if (sniffHead.startsWith("<html") || sniffHead.startsWith("<!doctype")) {
                        if (!cont.isCompleted) {
                            cont.resume(FetchOutcome.InvalidPayload)
                        }
                        return
                    }
                    if (!cont.isCompleted) {
                        cont.resume(FetchOutcome.Success(bytes))
                    }
                }
            }
        })
    }

    private fun pruneDiskCacheLocked() {
        runCatching {
            val now = System.currentTimeMillis()
            val files = cacheDir.listFiles() ?: return
            val mp3Files = ArrayList<File>()
            var totalBytes = 0L
            for (file in files) {
                if (file.name.endsWith(".tmp")) {
                    if (now - file.lastModified() > 60_000L) {
                        file.delete()
                    }
                } else if (file.name.startsWith("gt_") && file.name.endsWith(".mp3")) {
                    mp3Files.add(file)
                    totalBytes += file.length()
                }
            }
            if (mp3Files.size <= MAX_CACHED_FILES && totalBytes <= MAX_CACHE_BYTES) return

            mp3Files.sortBy { it.lastModified() }
            var remainingCount = mp3Files.size
            var remainingBytes = totalBytes
            val targetCount = (MAX_CACHED_FILES * 3) / 4
            val targetBytes = (MAX_CACHE_BYTES * 3L) / 4L
            for (file in mp3Files) {
                if (remainingCount <= targetCount && remainingBytes <= targetBytes) break
                val len = file.length()
                if (file.delete()) {
                    remainingCount--
                    remainingBytes -= len
                }
            }
        }
    }

    internal fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val words = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val chunks = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
        }

        for (word in words) {
            if (word.length > maxChars) {
                flush()
                var start = 0
                while (start < word.length) {
                    val end = minOf(start + maxChars, word.length)
                    chunks.add(word.substring(start, end))
                    start = end
                }
                continue
            }

            val needed = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (needed > maxChars) {
                flush()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        flush()
        return chunks
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 190
        private const val MAX_TRANSIENT_RETRIES = 1
        private const val RETRY_DELAY_MS = 250L
        private const val PRUNE_INTERVAL_WRITES = 30
        private const val MAX_CACHED_FILES = 300
        private const val MAX_CACHE_BYTES = 24L * 1024L * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
