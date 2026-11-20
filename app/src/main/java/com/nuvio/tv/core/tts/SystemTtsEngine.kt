package com.nuvio.tv.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline Android System `TextToSpeech` synthesizer with Vietnamese (`vi-VN` / `vi`) fallback
 * and gender-mapped pitch adjustment (`0.88f` Male, `1.12f` Female, `1.0f` Unspecified).
 */
class SystemTtsEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val synthMutex = Mutex()
    private val initMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceSeq = AtomicInteger(0)

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val cacheDir: File by lazy {
        File(appContext.cacheDir, "tts_cache").apply { mkdirs() }
    }

    suspend fun synthesizeToWav(
        text: String,
        gender: SpeakerGender,
        speechRate: Float,
        cacheKey: String
    ): File? {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null

        val outFile = File(cacheDir, "sys_$cacheKey.wav")
        if (outFile.exists() && outFile.length() > 256L) {
            return outFile
        }

        val tts = ensureInitialized() ?: return null

        return synthMutex.withLock {
            if (outFile.exists() && outFile.length() > 256L) {
                return@withLock outFile
            }
            val tmpFile = File(cacheDir, "sys_$cacheKey.wav.tmp")
            val utteranceId = "aio_tts_${utteranceSeq.incrementAndGet()}"
            val deferred = CompletableDeferred<Boolean>()
            pendingUtterances[utteranceId] = deferred

            try {
                val pitch = when (gender) {
                    SpeakerGender.MALE -> 0.88f
                    SpeakerGender.FEMALE -> 1.12f
                    SpeakerGender.UNSPECIFIED -> 1.0f
                }
                tts.setPitch(pitch)
                tts.setSpeechRate(speechRate.coerceIn(0.6f, 2.0f))

                val result = withContext(Dispatchers.IO) {
                    tts.synthesizeToFile(cleanText, null, tmpFile, utteranceId)
                }
                if (result != TextToSpeech.SUCCESS) {
                    tmpFile.delete()
                    return@withLock null
                }

                val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { deferred.await() } == true
                if (!ok || !tmpFile.exists() || tmpFile.length() <= 44L) {
                    tmpFile.delete()
                    return@withLock null
                }

                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete()
                    return@withLock null
                }
                outFile
            } catch (_: Throwable) {
                tmpFile.delete()
                null
            } finally {
                pendingUtterances.remove(utteranceId)
            }
        }
    }

    private suspend fun ensureInitialized(): TextToSpeech? {
        if (ready && engine != null) return engine

        return initMutex.withLock {
            if (ready && engine != null) return@withLock engine

            val initDeferred = CompletableDeferred<Boolean>()
            var created: TextToSpeech? = null
            withContext(Dispatchers.Main) {
                created = TextToSpeech(appContext) { status ->
                    initDeferred.complete(status == TextToSpeech.SUCCESS)
                }
            }

            val success = withTimeoutOrNull(INIT_TIMEOUT_MS) { initDeferred.await() } == true
            val instance = created
            if (!success || instance == null) {
                runCatching { instance?.shutdown() }
                return@withLock null
            }

            configureLocale(instance)
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(false)
                    }
                }
            })

            engine = instance
            ready = true
            instance
        }
    }

    private fun configureLocale(tts: TextToSpeech) {
        val viVn = Locale("vi", "VN")
        val viVnRes = tts.isLanguageAvailable(viVn)
        if (viVnRes >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = viVn
            return
        }
        val vi = Locale("vi")
        if (tts.isLanguageAvailable(vi) >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = vi
        }
    }

    fun release() {
        ready = false
        pendingUtterances.values.forEach { it.complete(false) }
        pendingUtterances.clear()
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 12_000L
    }
}
