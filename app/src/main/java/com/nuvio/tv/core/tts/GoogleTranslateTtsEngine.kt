package com.nuvio.tv.core.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Online Google Translate TTS fetcher (`Ll02` / `Lk02` parity).
 * Splits long Vietnamese text into <=190-char whitespace chunks and concatenates MP3 segments.
 */
class GoogleTranslateTtsEngine(private val context: Context) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "tts_cache").apply { mkdirs() }
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
            return@withContext outFile
        }

        val chunks = splitIntoChunks(cleanText, MAX_CHUNK_CHARS)
        if (chunks.isEmpty()) return@withContext null

        val mergedAudio = ByteArrayOutputStream(chunks.size * 8192)
        try {
            for (chunk in chunks) {
                val encoded = URLEncoder.encode(chunk, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.8")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }
                    val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                    if (contentType.startsWith("text/")) {
                        return@withContext null
                    }
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.isEmpty()) return@withContext null
                    // Guard against accidental HTML consent/error page instead of MP3/MPEG stream
                    val sniffHead = bytes.take(16).toByteArray().decodeToString().trimStart().lowercase()
                    if (sniffHead.startsWith("<html") || sniffHead.startsWith("<!doctype")) {
                        return@withContext null
                    }
                    mergedAudio.write(bytes)
                }
            }
        } catch (_: Throwable) {
            return@withContext null
        }

        val payload = mergedAudio.toByteArray()
        if (payload.size <= 128) return@withContext null

        val tmpFile = File(cacheDir, "gt_$key.mp3.tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(payload)
                fos.fd.sync()
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                return@withContext null
            }
            outFile
        } catch (_: Throwable) {
            tmpFile.delete()
            null
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
