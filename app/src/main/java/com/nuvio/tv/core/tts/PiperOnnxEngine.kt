package com.nuvio.tv.core.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

data class SynthesizedPcm(
    val samples: FloatArray,
    val sampleRate: Int
)

/**
 * Offline Piper VITS ONNX synthesizer (`Lym6` / `Lxm6` / `Lat3` parity).
 * Maintains an LRU cache of at most 2 loaded `OrtSession` instances (for dual-voice playback)
 * to stay comfortably within TV memory budgets.
 */
class PiperOnnxEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionLock = Any()
    private val released = AtomicBoolean(false)

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

    private val loadedVoices = LinkedHashMap<String, LoadedVoice>(4, 0.75f, true)

    suspend fun synthesize(
        voiceId: String,
        normalizedText: String,
        speechSpeed: Float
    ): SynthesizedPcm? = withContext(Dispatchers.Default) {
        if (normalizedText.isBlank()) return@withContext null
        val loaded = getOrLoadVoice(voiceId) ?: return@withContext null

        val ipa = VietnameseG2P.textToIpa(normalizedText)
        if (ipa.isBlank()) return@withContext null

        val phonemeIds = phonemesToIds(ipa, loaded.phonemeIdMap)
        if (phonemeIds.size <= 3) return@withContext null

        val effectiveSpeed = speechSpeed.coerceIn(0.6f, 2.0f)
        val lengthScale = (loaded.baseLengthScale / effectiveSpeed).coerceIn(0.4f, 2.5f)
        val scales = floatArrayOf(loaded.noiseScale, lengthScale, loaded.noiseW)

        var inputTensor: OnnxTensor? = null
        var lengthTensor: OnnxTensor? = null
        var scalesTensor: OnnxTensor? = null
        var sidTensor: OnnxTensor? = null

        try {
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

            val pcm = synchronized(loaded.session) {
                if (released.get() || loaded.isClosed) return@withContext null
                loaded.session.run(inputs).use { res ->
                    val outputTensor = res[0] as? OnnxTensor
                    val outputValue = res[0].value
                    extractAudioFloatArray(outputValue)
                        ?: outputTensor?.floatBuffer?.let { fb ->
                            val arr = FloatArray(fb.remaining())
                            fb.get(arr)
                            arr
                        }
                }
            } ?: return@withContext null
            if (pcm.isEmpty()) return@withContext null
            SynthesizedPcm(samples = pcm, sampleRate = loaded.sampleRate)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { inputTensor?.close() }
            runCatching { lengthTensor?.close() }
            runCatching { scalesTensor?.close() }
            runCatching { sidTensor?.close() }
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

    private fun getOrLoadVoice(voiceId: String): LoadedVoice? {
        if (released.get()) return null
        synchronized(sessionLock) {
            loadedVoices[voiceId]?.let { return it }
        }

        if (!TtsVoiceCatalog.isVoiceReady(appContext, voiceId)) return null
        val onnxFile = TtsVoiceCatalog.onnxFile(appContext, voiceId)
        val configFile = TtsVoiceCatalog.resolveConfigFile(appContext, voiceId) ?: return null

        return try {
            val json = JSONObject(configFile.readText(Charsets.UTF_8))
            val audioObj = json.optJSONObject("audio")
            val sampleRate = audioObj?.optInt("sample_rate", DEFAULT_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE

            val infObj = json.optJSONObject("inference")
            val noiseScale = (infObj?.optDouble("noise_scale", 0.667) ?: 0.667).toFloat()
            val lengthScale = (infObj?.optDouble("length_scale", 1.0) ?: 1.0).toFloat()
            val noiseW = (infObj?.optDouble("noise_w", 0.8) ?: 0.8).toFloat()

            val phonemeMapJson = json.optJSONObject("phoneme_id_map") ?: return null
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

            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(onnxFile.absolutePath, options)
            }
            val loaded = LoadedVoice(
                voiceId = voiceId,
                session = session,
                sampleRate = sampleRate,
                noiseScale = noiseScale,
                baseLengthScale = lengthScale,
                noiseW = noiseW,
                phonemeIdMap = phonemeMap
            )

            var evicted: LoadedVoice? = null
            val resultVoice = synchronized(sessionLock) {
                if (released.get()) {
                    evicted = loaded
                    null
                } else {
                    loadedVoices[voiceId]?.let { existing ->
                        evicted = loaded
                        existing
                    } ?: run {
                        if (loadedVoices.size >= MAX_CACHED_SESSIONS) {
                            val eldestKey = loadedVoices.keys.firstOrNull()
                            if (eldestKey != null) {
                                evicted = loadedVoices.remove(eldestKey)
                            }
                        }
                        loadedVoices[voiceId] = loaded
                        loaded
                    }
                }
            }
            evicted?.close()
            resultVoice
        } catch (_: Throwable) {
            null
        }
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

    fun release() {
        released.set(true)
        val toClose = synchronized(sessionLock) {
            val copy = loadedVoices.values.toList()
            loadedVoices.clear()
            copy
        }
        toClose.forEach { it.close() }
    }

    companion object {
        private const val MAX_CACHED_SESSIONS = 2
        private const val DEFAULT_SAMPLE_RATE = 22050
    }
}
