package com.nuvio.tv.core.tts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class SpeakerGender {
    MALE,
    FEMALE,
    UNSPECIFIED
}

enum class TtsBackendType {
    PIPER_ONNX,
    GOOGLE_TRANSLATE
}

data class TtsVoiceOption(
    val id: String,
    val displayName: String,
    val regionLabel: String,
    val gender: SpeakerGender,
    val backend: TtsBackendType,
    val sizeMb: Double = 63.5,
    val defaultSpeed: Float = 1.0f
)

data class TtsDownloadState(
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val errorMessage: String? = null
)

/**
 * Catalog of offline Piper VITS voices and Google Translate online TTS.
 * Piper ONNX + JSON model pairs are hosted on HuggingFace (`dinhphuongvn/ttsvn`).
 */
object TtsVoiceCatalog {
    const val GOOGLE_VOICE_ID = "google_online"
    const val DEFAULT_FEMALE_VOICE_ID = "ngoc_huyen_new"
    const val DEFAULT_MALE_VOICE_ID = "minh_quang"

    private const val HF_BASE_URL = "https://huggingface.co/dinhphuongvn/ttsvn/resolve/main"
    private const val SAMPLE_MIRROR_BASE_URL = "https://raw.githubusercontent.com/Darkrai9x/vbook-settings/main/tts/sample"
    private const val MIN_VALID_ONNX_BYTES = 10_485_760L // 10 MB sanity floor matching VAX

    private val remoteSampleFilenames: Map<String, String> = mapOf(
        "ngoc_huyen_new" to "ngochuyen_0.m4a",
        "ban_mai" to "banmai_0.m4a",
        "lac_phi" to "lacphi_0.m4a",
        "mai_phuong" to "maiphuong_0.m4a",
        "manh_dung" to "manhdung_0.m4a",
        "minh_quang" to "minhquang_0.m4a",
        "ngoc_ngan" to "ngocngan_0.m4a",
        "phuong_trang" to "phuongtrang_0.m4a"
    )

    val voices: List<TtsVoiceOption> = listOf(
        // Female voices (Miền Bắc)
        TtsVoiceOption("ngoc_huyen_new", "Ngọc Huyền (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("ban_mai", "Ban Mai (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("lac_phi", "Lạc Phi (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("mai_phuong", "Mai Phương (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("my_tam", "Mỹ Tâm (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("phuong_trang", "Phương Trang (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("tai_an", "Tài An (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("thanh_phuong", "Thanh Phương (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("thien_tam", "Thiên Tâm (Nữ)", "Miền Bắc", SpeakerGender.FEMALE, TtsBackendType.PIPER_ONNX),
        // Male voices (Miền Bắc & Miền Nam)
        TtsVoiceOption("minh_quang", "Minh Quang (Nam)", "Miền Bắc", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("duy_oryx", "Duy Oryx (Nam)", "Miền Bắc", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("chieu_thanh", "Chiêu Thanh (Nam)", "Miền Bắc", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("manh_dung", "Mạnh Dũng (Nam)", "Miền Bắc", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("minh_khang", "Minh Khang (Nam)", "Miền Bắc", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("ngoc_ngan", "Ngọc Ngạn (Nam)", "Miền Nam", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("tran_thanh", "Trấn Thành (Nam)", "Miền Nam", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        TtsVoiceOption("viet_thao", "Việt Thảo (Nam)", "Miền Nam", SpeakerGender.MALE, TtsBackendType.PIPER_ONNX),
        // Built-in online backend
        TtsVoiceOption(
            id = GOOGLE_VOICE_ID,
            displayName = "Google Dịch (Online)",
            regionLabel = "Trực tuyến • Cần mạng",
            gender = SpeakerGender.FEMALE,
            backend = TtsBackendType.GOOGLE_TRANSLATE,
            sizeMb = 0.0
        )
    )

    val piperVoices: List<TtsVoiceOption> = voices.filter { it.backend == TtsBackendType.PIPER_ONNX }

    fun findVoice(voiceId: String): TtsVoiceOption =
        voices.firstOrNull { it.id == voiceId }
            ?: voices.first { it.id == GOOGLE_VOICE_ID }

    fun modelDir(context: Context, voiceId: String): File =
        File(context.filesDir, "models/$voiceId")

    fun onnxFile(context: Context, voiceId: String): File =
        File(modelDir(context, voiceId), "$voiceId.onnx")

    fun resolveConfigFile(context: Context, voiceId: String): File? {
        val dir = modelDir(context, voiceId)
        val primary = File(dir, "$voiceId.onnx.json")
        if (primary.exists() && primary.length() > 128L) return primary
        val legacy = File(dir, "config.json")
        if (legacy.exists() && legacy.length() > 128L) return legacy
        return null
    }

    fun isVoiceReady(context: Context, voiceId: String): Boolean {
        val voice = findVoice(voiceId)
        if (voice.backend != TtsBackendType.PIPER_ONNX) return true
        val onnx = onnxFile(context.applicationContext, voiceId)
        return onnx.isFile && onnx.canRead() && onnx.length() > MIN_VALID_ONNX_BYTES &&
            resolveConfigFile(context.applicationContext, voiceId) != null
    }

    fun installedPiperVoiceIds(context: Context): Set<String> =
        piperVoices.asSequence()
            .filter { isVoiceReady(context, it.id) }
            .map { it.id }
            .toSet()

    fun onnxDownloadUrl(voiceId: String): String = "$HF_BASE_URL/$voiceId.onnx"

    fun configDownloadUrl(voiceId: String): String = "$HF_BASE_URL/$voiceId.onnx.json"

    fun samplePreviewAssetPath(voiceId: String): String = "tts_samples/$voiceId.m4a"

    fun samplePreviewRemoteUrl(voiceId: String): String? =
        remoteSampleFilenames[voiceId]?.let { "$SAMPLE_MIRROR_BASE_URL/$it" }
}

/**
 * Downloads, validates, and deletes Piper ONNX voice models on disk with atomic `.tmp` writes.
 */
object TtsModelManager {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, TtsDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, TtsDownloadState>> = _downloadStates.asStateFlow()

    fun refreshInstalled(context: Context) {
        _installedIds.value = TtsVoiceCatalog.installedPiperVoiceIds(context.applicationContext)
    }

    fun deleteVoice(context: Context, voiceId: String): Boolean {
        val appContext = context.applicationContext
        val dir = TtsVoiceCatalog.modelDir(appContext, voiceId)
        val deleted = if (dir.exists()) dir.deleteRecursively() else true
        refreshInstalled(appContext)
        return deleted
    }

    /**
     * Starts an application-scoped download so closing a dialog/panel mid-transfer
     * does not abort a 63.5 MB Piper ONNX download or leave partial state behind.
     */
    fun startDownload(
        context: Context,
        voiceId: String,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        downloadScope.launch {
            val ok = downloadVoice(appContext, voiceId)
            if (onCompleted != null) {
                withContext(Dispatchers.Main) {
                    onCompleted(ok)
                }
            }
        }
    }

    suspend fun downloadVoice(context: Context, voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val option = TtsVoiceCatalog.findVoice(voiceId)
        if (option.backend != TtsBackendType.PIPER_ONNX) return@withContext true

        if (_downloadStates.value[voiceId]?.isDownloading == true) {
            return@withContext false
        }

        val dir = TtsVoiceCatalog.modelDir(appContext, voiceId)
        if (!dir.exists() && !dir.mkdirs()) {
            setDownloadState(voiceId, TtsDownloadState(errorMessage = "Không thể tạo thư mục lưu giọng đọc"))
            return@withContext false
        }

        setDownloadState(voiceId, TtsDownloadState(isDownloading = true, progressPercent = 0))

        val onnxTarget = TtsVoiceCatalog.onnxFile(appContext, voiceId)
        val configTarget = File(dir, "$voiceId.onnx.json")

        try {
            downloadFile(TtsVoiceCatalog.configDownloadUrl(voiceId), configTarget) { /* tiny JSON */ }
            val cfgJson = runCatching { JSONObject(configTarget.readText(Charsets.UTF_8)) }.getOrNull()
            if (cfgJson?.optJSONObject("phoneme_id_map") == null) {
                configTarget.delete()
                throw IllegalStateException("File cấu hình giọng đọc không hợp lệ")
            }

            downloadFile(TtsVoiceCatalog.onnxDownloadUrl(voiceId), onnxTarget) { pct ->
                setDownloadState(voiceId, TtsDownloadState(isDownloading = true, progressPercent = pct))
            }

            if (!TtsVoiceCatalog.isVoiceReady(appContext, voiceId)) {
                throw IllegalStateException("File mô hình tải về không đầy đủ hoặc bị hỏng")
            }

            refreshInstalled(appContext)
            setDownloadState(voiceId, TtsDownloadState(isDownloading = false, progressPercent = 100))
            true
        } catch (t: Throwable) {
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (!TtsVoiceCatalog.isVoiceReady(appContext, voiceId)) {
                onnxTarget.delete()
            }
            setDownloadState(
                voiceId,
                TtsDownloadState(
                    isDownloading = false,
                    progressPercent = 0,
                    errorMessage = t.message ?: "Lỗi tải giọng đọc"
                )
            )
            false
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AioStream/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} khi tải ${target.name}")
            }
            val body = response.body ?: throw IllegalStateException("Phản hồi rỗng từ máy chủ")
            val totalBytes = body.contentLength()
            var downloaded = 0L
            var lastReportedPct = -1

            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        if (target.exists() && !target.delete()) {
            tmpFile.delete()
            throw IllegalStateException("Không thể ghi đè ${target.name}")
        }
        if (!tmpFile.renameTo(target)) {
            tmpFile.delete()
            throw IllegalStateException("Không thể lưu ${target.name}")
        }
    }

    private fun setDownloadState(voiceId: String, state: TtsDownloadState) {
        _downloadStates.update { current -> current + (voiceId to state) }
    }
}
