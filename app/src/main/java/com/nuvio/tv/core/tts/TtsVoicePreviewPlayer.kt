package com.nuvio.tv.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class TtsVoicePreviewState(
    val activeVoiceId: String? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Plays a short voice sample when user taps "Nghe thử" BEFORE or AFTER downloading a voice model.
 * Priority:
 * 1) Downloaded local Piper model (synthesizes live with user speed)
 * 2) Built-in backend (Google Translate Online / Android System TTS)
 * 3) Bundled APK sample clip (`assets/tts_samples/<voiceId>.m4a`)
 * 4) Remote lightweight preview clip (cached in `cacheDir/tts_preview_cache/`)
 */
object TtsVoicePreviewPlayer {
    const val DEFAULT_PREVIEW_TEXT = "Xin chào, đây là giọng đọc thử của tôi."

    private val _state = MutableStateFlow(TtsVoicePreviewState())
    val state: StateFlow<TtsVoicePreviewState> = _state.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, _ ->
        _state.value = TtsVoicePreviewState(errorMessage = "Không thể phát mẫu giọng đọc")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val handlerThread: HandlerThread by lazy {
        HandlerThread("TtsVoicePreviewPlayer", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    }
    private val playerHandler: Handler by lazy { Handler(handlerThread.looper) }

    private val previewAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activePlayer: MediaPlayer? = null

    @Volatile
    private var playbackGeneration = 0L

    fun togglePreview(
        context: Context,
        voiceId: String,
        speechSpeed: Float = 1.0f,
        previewText: String = DEFAULT_PREVIEW_TEXT
    ) {
        val current = _state.value
        if (current.activeVoiceId == voiceId && (current.isLoading || current.isPlaying)) {
            stop()
            return
        }

        val appContext = context.applicationContext
        val generation = synchronized(this) {
            playbackGeneration += 1L
            playbackGeneration
        }
        activeJob?.cancel()
        stopPlayerAsync()

        _state.value = TtsVoicePreviewState(
            activeVoiceId = voiceId,
            isLoading = true,
            isPlaying = false,
            errorMessage = null
        )

        activeJob = scope.launch {
            val file = resolvePreviewAudioFile(appContext, voiceId, speechSpeed, previewText)
            if (playbackGeneration != generation) return@launch

            if (file == null || !file.exists() || file.length() <= 64L) {
                _state.value = TtsVoicePreviewState(
                    activeVoiceId = null,
                    isLoading = false,
                    isPlaying = false,
                    errorMessage = "Chưa tải được mẫu nghe thử cho giọng này"
                )
                return@launch
            }

            playAudioFile(file, voiceId, speechSpeed, generation)
        }
    }

    fun stop() {
        synchronized(this) {
            playbackGeneration += 1L
        }
        activeJob?.cancel()
        activeJob = null
        stopPlayerAsync()
        _state.value = TtsVoicePreviewState()
    }

    private suspend fun resolvePreviewAudioFile(
        appContext: Context,
        voiceId: String,
        speechSpeed: Float,
        previewText: String
    ): File? {
        val option = TtsVoiceCatalog.findVoice(voiceId)
        val previewCacheDir = File(appContext.cacheDir, "tts_preview_cache").apply { mkdirs() }

        when (option.backend) {
            TtsBackendType.GOOGLE_TRANSLATE -> {
                val google = GoogleTranslateTtsEngine(appContext)
                return google.synthesizeToMp3(previewText, "preview_${option.id}")
            }
            TtsBackendType.SYSTEM_TTS -> {
                val system = SystemTtsEngine(appContext)
                return try {
                    system.synthesizeToWav(
                        text = previewText,
                        gender = option.gender,
                        speechRate = speechSpeed,
                        cacheKey = "preview_sys_${(speechSpeed * 100).toInt()}"
                    )
                } finally {
                    system.release()
                }
            }
            TtsBackendType.PIPER_ONNX -> {
                if (TtsVoiceCatalog.isVoiceReady(appContext, option.id)) {
                    val outWav = File(previewCacheDir, "live_${option.id}_${(speechSpeed * 100).toInt()}.wav")
                    if (outWav.exists() && outWav.length() > 256L) return outWav
                    val piper = PiperOnnxEngine(appContext)
                    try {
                        val speakable = VietnameseTextNormalizer.parseAndCleanCue(previewText).speakableText
                        val synth = piper.synthesize(option.id, speakable, speechSpeed)
                        if (synth != null) {
                            val trimmed = TtsAudioPostProcessor.trimSilence(synth.samples, synth.sampleRate)
                            if (TtsAudioPostProcessor.writeNormalizedWav(trimmed, synth.sampleRate, 100, outWav)) {
                                return outWav
                            }
                        }
                    } finally {
                        piper.release()
                    }
                }

                // Voice NOT downloaded yet: play lightweight bundled sample or remote mirror clip
                val cachedSample = File(previewCacheDir, "sample_${option.id}.m4a")
                if (cachedSample.exists() && cachedSample.length() > 512L) {
                    return cachedSample
                }

                extractBundledSample(appContext, option.id, cachedSample)?.let { return it }
                downloadRemoteSample(option.id, cachedSample)?.let { return it }
                return null
            }
        }
    }

    private fun extractBundledSample(appContext: Context, voiceId: String, target: File): File? {
        val assetPath = TtsVoiceCatalog.samplePreviewAssetPath(voiceId)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (tmp.length() <= 512L) {
                tmp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        } catch (_: Throwable) {
            tmp.delete()
            null
        }
    }

    private fun downloadRemoteSample(voiceId: String, target: File): File? {
        val url = TtsVoiceCatalog.samplePreviewRemoteUrl(voiceId) ?: return null
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AioStream/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            if (tmp.length() <= 512L) {
                tmp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        } catch (_: Throwable) {
            tmp.delete()
            null
        }
    }

    private fun playAudioFile(file: File, voiceId: String, speechSpeed: Float, generation: Long) {
        playerHandler.post {
            if (playbackGeneration != generation) return@post
            releaseActiveOnPlayerThread()

            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(previewAttributes)
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                runCatching {
                    val speed = speechSpeed.coerceIn(0.7f, 1.5f)
                    if (kotlin.math.abs(speed - 1.0f) > 0.02f) {
                        val params = mp.playbackParams ?: PlaybackParams()
                        params.speed = speed
                        mp.playbackParams = params
                    }
                }
                mp.setOnCompletionListener { finished ->
                    playerHandler.post {
                        if (activePlayer === finished) {
                            releaseActiveOnPlayerThread()
                        } else {
                            runCatching { finished.release() }
                        }
                        if (playbackGeneration == generation) {
                            _state.value = TtsVoicePreviewState()
                        }
                    }
                }
                mp.setOnErrorListener { errored, _, _ ->
                    playerHandler.post {
                        if (activePlayer === errored) {
                            releaseActiveOnPlayerThread()
                        } else {
                            runCatching { errored.release() }
                        }
                        if (playbackGeneration == generation) {
                            _state.value = TtsVoicePreviewState(errorMessage = "Không thể phát mẫu giọng đọc")
                        }
                    }
                    true
                }
                if (playbackGeneration != generation) {
                    runCatching { mp.release() }
                    return@post
                }
                activePlayer = mp
                mp.start()
                _state.value = TtsVoicePreviewState(
                    activeVoiceId = voiceId,
                    isLoading = false,
                    isPlaying = true,
                    errorMessage = null
                )
            } catch (_: Throwable) {
                runCatching { mp.release() }
                if (activePlayer === mp) activePlayer = null
                if (playbackGeneration == generation) {
                    _state.value = TtsVoicePreviewState(errorMessage = "Lỗi phát mẫu giọng đọc")
                }
            }
        }
    }

    private fun stopPlayerAsync() {
        runCatching {
            playerHandler.post { releaseActiveOnPlayerThread() }
        }
    }

    private fun releaseActiveOnPlayerThread() {
        activePlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        activePlayer = null
    }
}
