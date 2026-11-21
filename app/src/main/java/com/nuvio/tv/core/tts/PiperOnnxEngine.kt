package com.nuvio.tv.core.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class SynthesizedPcm(
    val samples: FloatArray,
    val sampleRate: Int
)

internal data class PiperRuntimeBudget(
    val isConstrainedHardware: Boolean,
    val maxCachedSessions: Int,
    val intraOpThreads: Int,
    val enableMemoryArena: Boolean
)

/**
 * Offline Piper VITS ONNX synthesizer (`Lym6` / `Lxm6` / `Lat3` parity).
 *
 * Uses a process-wide ref-counted session pool with serialized model loading/inference,
 * pre-creation LRU eviction, chunked synthesis for long subtitles, and hardware-tuned
 * ORT 1.17.3 session options so 32-bit and low-RAM Android TV boxes synthesize smoothly
 * without exceeding memory budgets.
 */
class PiperOnnxEngine(context: Context) {
    private val appContext = context.applicationContext
    private val released = AtomicBoolean(false)

    init {
        SharedPiperPool.acquireRef()
    }

    fun lastErrorMessage(): String? = SharedPiperPool.lastError

    suspend fun warmUp(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        if (released.get()) return@withContext false
        SharedPiperPool.getOrLoadVoice(appContext, voiceId) != null
    }

    suspend fun synthesize(
        voiceId: String,
        normalizedText: String,
        speechSpeed: Float,
        shouldAbort: () -> Boolean = { false }
    ): SynthesizedPcm? = withContext(Dispatchers.Default) {
        if (released.get() || shouldAbort() || normalizedText.isBlank()) return@withContext null
        try {
            val loaded = SharedPiperPool.getOrLoadVoice(appContext, voiceId) ?: return@withContext null
            val chunks = splitIntoSpeakableChunks(normalizedText, MAX_CHARS_PER_CHUNK)
            if (chunks.isEmpty()) return@withContext null

            val effectiveSpeed = speechSpeed.coerceIn(0.6f, 2.0f)
            val lengthScale = (loaded.baseLengthScale / effectiveSpeed).coerceIn(0.4f, 2.5f)
            val scales = floatArrayOf(loaded.noiseScale, lengthScale, loaded.noiseW)

            if (chunks.size == 1) {
                val pcm = synthesizeSingleChunk(loaded, chunks[0], scales, shouldAbort) ?: return@withContext null
                if (pcm.isEmpty()) return@withContext null
                return@withContext SynthesizedPcm(samples = pcm, sampleRate = loaded.sampleRate)
            }

            val pcmChunks = ArrayList<FloatArray>(chunks.size)
            var totalSamples = 0
            val gapSamples = ((loaded.sampleRate * CHUNK_PAUSE_MS) / 1000).coerceAtLeast(0)
            for (chunk in chunks) {
                if (released.get() || shouldAbort() || loaded.isClosed) return@withContext null
                val chunkPcm = synthesizeSingleChunk(loaded, chunk, scales, shouldAbort) ?: continue
                if (chunkPcm.isNotEmpty()) {
                    if (pcmChunks.isNotEmpty() && gapSamples > 0) {
                        totalSamples += gapSamples
                    }
                    pcmChunks.add(chunkPcm)
                    totalSamples += chunkPcm.size
                }
            }
            if (pcmChunks.isEmpty() || totalSamples <= 0) return@withContext null

            val merged = FloatArray(totalSamples)
            var writePos = 0
            for (i in pcmChunks.indices) {
                if (i > 0 && gapSamples > 0) {
                    writePos += gapSamples
                }
                val chunkPcm = pcmChunks[i]
                System.arraycopy(chunkPcm, 0, merged, writePos, chunkPcm.size)
                writePos += chunkPcm.size
            }
            SynthesizedPcm(samples = merged, sampleRate = loaded.sampleRate)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            SharedPiperPool.recordError("Lỗi tổng hợp Piper: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private suspend fun synthesizeSingleChunk(
        loaded: LoadedVoice,
        chunkText: String,
        scales: FloatArray,
        shouldAbort: () -> Boolean
    ): FloatArray? {
        if (released.get() || shouldAbort() || loaded.isClosed) return null
        val ipa = VietnameseG2P.textToIpa(chunkText)
        if (ipa.isBlank()) return null

        val rawPhonemeIds = phonemesToIds(ipa, loaded.phonemeIdMap)
        if (rawPhonemeIds.size <= 3) return null
        val phonemeIds = if (rawPhonemeIds.size > MAX_PHONEME_IDS) {
            rawPhonemeIds.copyOf(MAX_PHONEME_IDS)
        } else {
            rawPhonemeIds
        }

        return SharedPiperPool.runInference(loaded, phonemeIds, scales) {
            released.get() || shouldAbort()
        }
    }

    internal fun phonemesToIds(
        ipa: String,
        phonemeIdMap: Map<String, LongArray>
    ): LongArray {
        val nfd = Normalizer.normalize(ipa, Normalizer.Form.NFD)
        val out = ArrayList<Long>(nfd.length * 2 + 4)

        val bosIds = phonemeIdMap["^"]
        val padIds = phonemeIdMap["_"]
        val eosIds = phonemeIdMap["$"]

        if (bosIds != null) {
            for (id in bosIds) out.add(id)
        }
        if (padIds != null) {
            for (id in padIds) out.add(id)
        }

        var idx = 0
        while (idx < nfd.length) {
            var matchedIds: LongArray? = null
            if (idx + 1 < nfd.length) {
                val twoCharKey = nfd.substring(idx, idx + 2)
                matchedIds = phonemeIdMap[twoCharKey]
                if (matchedIds != null) {
                    idx += 2
                }
            }
            if (matchedIds == null) {
                val oneCharKey = nfd.substring(idx, idx + 1)
                matchedIds = phonemeIdMap[oneCharKey]
                idx += 1
            }

            if (matchedIds != null) {
                for (id in matchedIds) out.add(id)
                if (padIds != null) {
                    for (id in padIds) out.add(id)
                }
            }
        }

        if (eosIds != null) {
            for (id in eosIds) out.add(id)
        }

        return out.toLongArray()
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            SharedPiperPool.releaseRef()
        }
    }

    private class LoadedVoice(
        val voiceId: String,
        val session: OrtSession,
        val sampleRate: Int,
        val noiseScale: Float,
        val baseLengthScale: Float,
        val noiseW: Float,
        val phonemeIdMap: Map<String, LongArray>
    ) {
        @Volatile
        var isClosed: Boolean = false

        fun close() {
            synchronized(session) {
                if (!isClosed) {
                    isClosed = true
                    runCatching { session.close() }
                }
            }
        }
    }

    private object SharedPiperPool {
        private val loadMutex = Mutex()
        private val inferenceMutex = Mutex()
        private val refCountLock = Any()
        private val nativeInitLock = Any()
        private val loadedVoices = LinkedHashMap<String, LoadedVoice>(4, 0.75f, true)

        @Volatile
        private var nativeLoaded = false

        @Volatile
        private var refCount = 0

        @Volatile
        private var cachedEnv: OrtEnvironment? = null

        fun acquireRef() {
            synchronized(refCountLock) {
                refCount += 1
            }
        }

        fun releaseRef() {
            val shouldClear = synchronized(refCountLock) {
                if (refCount > 0) refCount -= 1
                refCount == 0
            }
            if (!shouldClear) return

            val toClose = synchronized(loadedVoices) {
                val copy = loadedVoices.values.toList()
                loadedVoices.clear()
                copy
            }
            toClose.forEach { it.close() }
        }

        @Volatile
        var lastError: String? = null
            private set

        fun recordError(message: String) {
            lastError = message
        }

        private fun loadNativeLibrary(appContext: Context, libName: String): Throwable? {
            val byName = runCatching { System.loadLibrary(libName) }
            if (byName.isSuccess) return null

            val nativeDir = appContext.applicationInfo?.nativeLibraryDir
            if (!nativeDir.isNullOrBlank()) {
                val mapped = File(nativeDir, "lib$libName.so")
                if (mapped.isFile) {
                    val byPath = runCatching { System.load(mapped.absolutePath) }
                    if (byPath.isSuccess) return null
                    return byPath.exceptionOrNull()
                }
            }
            return byName.exceptionOrNull()
        }

        private fun ensureEnv(appContext: Context): OrtEnvironment {
            if (!nativeLoaded) {
                synchronized(nativeInitLock) {
                    if (!nativeLoaded) {
                        val errCore = loadNativeLibrary(appContext, "onnxruntime")
                        val errJni = loadNativeLibrary(appContext, "onnxruntime4j_jni")
                        if (errJni != null) {
                            val err = errCore ?: errJni
                            throw IllegalStateException(
                                "Không nạp được thư viện ONNX (${err.javaClass.simpleName}: ${err.message})",
                                err
                            )
                        }
                        nativeLoaded = true
                    }
                }
            }
            return cachedEnv ?: synchronized(nativeInitLock) {
                cachedEnv ?: OrtEnvironment.getEnvironment().also { cachedEnv = it }
            }
        }

        suspend fun getOrLoadVoice(appContext: Context, voiceId: String): LoadedVoice? =
            loadMutex.withLock {
                synchronized(loadedVoices) {
                    loadedVoices[voiceId]?.takeIf { !it.isClosed }?.let { return@withLock it }
                }

                if (!TtsVoiceCatalog.isVoiceReady(appContext, voiceId)) {
                    lastError = "Chưa tải gói giọng $voiceId"
                    return@withLock null
                }
                val onnxFile = TtsVoiceCatalog.onnxFile(appContext, voiceId)
                val configFile = TtsVoiceCatalog.resolveConfigFile(appContext, voiceId)
                if (configFile == null) {
                    lastError = "Thiếu file cấu hình $voiceId.onnx.json"
                    return@withLock null
                }

                withContext(Dispatchers.IO) {
                    var createdSession: OrtSession? = null
                    try {
                        val json = JSONObject(configFile.readText(Charsets.UTF_8))
                        val audioObj = json.optJSONObject("audio")
                        val sampleRate = audioObj?.optInt("sample_rate", DEFAULT_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE

                        val infObj = json.optJSONObject("inference")
                        val noiseScale = (infObj?.optDouble("noise_scale", 0.667) ?: 0.667).toFloat()
                        val lengthScale = (infObj?.optDouble("length_scale", 1.0) ?: 1.0).toFloat()
                        val noiseW = (infObj?.optDouble("noise_w", 0.8) ?: 0.8).toFloat()

                        val phonemeMapJson = json.optJSONObject("phoneme_id_map") ?: run {
                            lastError = "Cấu hình $voiceId thiếu phoneme_id_map"
                            return@withContext null
                        }
                        val phonemeMap = HashMap<String, LongArray>(phonemeMapJson.length())
                        val keys = phonemeMapJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val arr = phonemeMapJson.optJSONArray(key) ?: continue
                            val ids = LongArray(arr.length()) { i -> arr.optLong(i) }
                            if (ids.isNotEmpty()) {
                                phonemeMap[key] = ids
                            }
                        }

                        val budget = computeRuntimeBudget(appContext)
                        val evictedBeforeLoad = ArrayList<LoadedVoice>()
                        synchronized(loadedVoices) {
                            while (loadedVoices.size >= budget.maxCachedSessions) {
                                val eldestKey = loadedVoices.keys.firstOrNull() ?: break
                                loadedVoices.remove(eldestKey)?.let { evictedBeforeLoad.add(it) }
                            }
                        }
                        // Close evicted sessions BEFORE loading the new 63 MB model so native RSS never doubles.
                        evictedBeforeLoad.forEach { it.close() }

                        val env = ensureEnv(appContext)
                        createdSession = OrtSession.SessionOptions().use { options ->
                            options.setIntraOpNumThreads(budget.intraOpThreads)
                            options.setInterOpNumThreads(1)
                            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                            options.setOptimizationLevel(
                                if (budget.enableMemoryArena) {
                                    OrtSession.SessionOptions.OptLevel.ALL_OPT
                                } else {
                                    OrtSession.SessionOptions.OptLevel.BASIC_OPT
                                }
                            )
                            options.setCPUArenaAllocator(budget.enableMemoryArena)
                            options.setMemoryPatternOptimization(budget.enableMemoryArena)
                            env.createSession(onnxFile.absolutePath, options)
                        }

                        val loaded = LoadedVoice(
                            voiceId = voiceId,
                            session = createdSession,
                            sampleRate = sampleRate,
                            noiseScale = noiseScale,
                            baseLengthScale = lengthScale,
                            noiseW = noiseW,
                            phonemeIdMap = phonemeMap
                        )
                        synchronized(loadedVoices) {
                            loadedVoices[voiceId] = loaded
                        }
                        createdSession = null
                        lastError = null
                        loaded
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        lastError = "Lỗi nạp $voiceId: ${t.javaClass.simpleName}: ${t.message}"
                        runCatching { createdSession?.close() }
                        null
                    }
                }
            }

        suspend fun runInference(
            loaded: LoadedVoice,
            phonemeIds: LongArray,
            scales: FloatArray,
            isCallerReleased: () -> Boolean
        ): FloatArray? = inferenceMutex.withLock {
            if (isCallerReleased() || loaded.isClosed) return@withLock null
            var inputTensor: OnnxTensor? = null
            var lengthTensor: OnnxTensor? = null
            var scalesTensor: OnnxTensor? = null
            var sidTensor: OnnxTensor? = null
            try {
                val env = cachedEnv ?: OrtEnvironment.getEnvironment().also { cachedEnv = it }
                inputTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(phonemeIds),
                    longArrayOf(1L, phonemeIds.size.toLong())
                )
                lengthTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(phonemeIds.size.toLong())),
                    longArrayOf(1L)
                )
                scalesTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(scales),
                    longArrayOf(3L)
                )

                val inputs = HashMap<String, OnnxTensor>(4)
                inputs["input"] = inputTensor
                inputs["input_lengths"] = lengthTensor
                inputs["scales"] = scalesTensor
                if ("sid" in loaded.session.inputNames) {
                    sidTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1L))
                    inputs["sid"] = sidTensor
                }

                synchronized(loaded.session) {
                    if (isCallerReleased() || loaded.isClosed) return@withLock null
                    val pcm = loaded.session.run(inputs).use { res ->
                        val outputTensor = res[0] as? OnnxTensor
                        // Read flat native floatBuffer BEFORE .value so ART never allocates a 4D boxed array.
                        outputTensor?.floatBuffer?.let { fb ->
                            val arr = FloatArray(fb.remaining())
                            fb.get(arr)
                            arr
                        } ?: extractAudioFloatArray(res[0].value)
                    }
                    if (pcm == null || pcm.isEmpty()) {
                        lastError = "Model ${loaded.voiceId} trả về âm thanh rỗng"
                        return@withLock null
                    }
                    lastError = null
                    pcm
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = "Lỗi ONNX (${loaded.voiceId}): ${t.javaClass.simpleName}: ${t.message}"
                null
            } finally {
                runCatching { inputTensor?.close() }
                runCatching { lengthTensor?.close() }
                runCatching { scalesTensor?.close() }
                runCatching { sidTensor?.close() }
            }
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 22050
        private const val SMALL_RAM_THRESHOLD_MB = 2_560L
        private const val VERY_LOW_RAM_THRESHOLD_MB = 1_536L
        private const val MAX_CHARS_PER_CHUNK = 160
        private const val MAX_PHONEME_IDS = 512
        private const val CHUNK_PAUSE_MS = 45

        internal fun computeBudget(
            is64Bit: Boolean,
            totalMemMb: Long,
            isLowRamDevice: Boolean,
            cpuCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ): PiperRuntimeBudget {
            val constrained = !is64Bit || isLowRamDevice || totalMemMb in 1..SMALL_RAM_THRESHOLD_MB
            val maxSessions = if (constrained) 1 else 2
            val veryLowRam = isLowRamDevice || totalMemMb in 1..VERY_LOW_RAM_THRESHOLD_MB
            // Measured on ORT 1.17.3 armeabi-v7a: 2 threads + ALL_OPT + arena speeds up inference
            // ~40% (1435ms -> 869ms) with only +564 kB peak RSS on multi-core devices.
            val threads = when {
                cpuCores <= 1 || veryLowRam -> 1
                constrained -> 2.coerceAtMost(cpuCores)
                else -> cpuCores.coerceIn(2, 3)
            }
            return PiperRuntimeBudget(
                isConstrainedHardware = constrained,
                maxCachedSessions = maxSessions,
                intraOpThreads = threads,
                enableMemoryArena = !veryLowRam
            )
        }

        internal fun runtimeBudgetFor(
            is64Bit: Boolean,
            totalMemMb: Long,
            isLowRamDevice: Boolean,
            cpuCores: Int = 4
        ): Triple<Boolean, Int, Int> {
            val budget = computeBudget(
                is64Bit = is64Bit,
                totalMemMb = totalMemMb,
                isLowRamDevice = isLowRamDevice,
                cpuCores = cpuCores
            )
            return Triple(budget.isConstrainedHardware, budget.maxCachedSessions, budget.intraOpThreads)
        }

        private fun computeRuntimeBudget(context: Context): PiperRuntimeBudget {
            val is64Bit = runCatching { Process.is64Bit() }.getOrDefault(false)
            val am = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
            val lowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
            val totalMb = runCatching {
                val info = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(info)
                info.totalMem shr 20
            }.getOrDefault(0L)
            return computeBudget(is64Bit = is64Bit, totalMemMb = totalMb, isLowRamDevice = lowRam)
        }

        internal fun splitIntoSpeakableChunks(
            text: String,
            maxChars: Int = MAX_CHARS_PER_CHUNK
        ): List<String> {
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()
            if (clean.length <= maxChars) return listOf(clean)

            val chunks = ArrayList<String>(clean.length / maxChars + 1)
            var start = 0
            while (start < clean.length) {
                while (start < clean.length && clean[start].isWhitespace()) start++
                if (start >= clean.length) break

                val maxEnd = min(start + maxChars, clean.length)
                if (maxEnd == clean.length) {
                    val tail = clean.substring(start).trim()
                    if (tail.isNotEmpty()) chunks.add(tail)
                    break
                }

                var splitAt = -1
                for (i in maxEnd downTo start + maxChars / 3) {
                    val ch = clean[i - 1]
                    if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '\n') {
                        splitAt = i
                        break
                    }
                }
                if (splitAt < 0) {
                    for (i in maxEnd downTo start + maxChars / 3) {
                        val ch = clean[i - 1]
                        if (ch == ',' || ch == ':' || ch.isWhitespace()) {
                            splitAt = i
                            break
                        }
                    }
                }
                if (splitAt <= start) {
                    splitAt = maxEnd
                }
                val part = clean.substring(start, splitAt).trim()
                if (part.isNotEmpty()) {
                    chunks.add(part)
                }
                start = splitAt
            }
            return chunks
        }

        @Suppress("UNCHECKED_CAST")
        private fun extractAudioFloatArray(value: Any?): FloatArray? {
            return when (value) {
                is FloatArray -> value
                is Array<*> -> {
                    val first = value.firstOrNull() ?: return null
                    extractAudioFloatArray(first)
                }
                else -> null
            }
        }
    }
}
