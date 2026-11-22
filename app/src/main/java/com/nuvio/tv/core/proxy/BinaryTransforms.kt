package com.nuvio.tv.core.proxy

/**
 * Mapping between a decoded byte range requested by the player and the raw byte range on upstream.
 */
data class RawRangeRequest(
    val start: Long,
    val endInclusive: Long?
)

/**
 * Generic range-safe byte transform executed locally inside Aio-Stream.
 */
interface RangeByteTransform {
    fun decodedTotalSize(rawTotalSize: Long?): Long?

    fun toRawRange(
        decodedStart: Long,
        decodedEndInclusive: Long?
    ): RawRangeRequest

    fun transformInPlace(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        decodedOffset: Long
    )
}

/**
 * Strips a fixed [skipBytes] binary prefix from progressive HTTP streams.
 */
class PrefixSkipTransform(
    val skipBytes: Long
) : RangeByteTransform {
    init {
        require(skipBytes >= 0L) { "skipBytes must be non-negative" }
    }

    override fun decodedTotalSize(rawTotalSize: Long?): Long? =
        rawTotalSize?.let { (it - skipBytes).coerceAtLeast(0L) }

    override fun toRawRange(decodedStart: Long, decodedEndInclusive: Long?): RawRangeRequest {
        val safeStart = decodedStart.coerceAtLeast(0L) + skipBytes
        val safeEnd = decodedEndInclusive?.let { it.coerceAtLeast(decodedStart.coerceAtLeast(0L)) + skipBytes }
        return RawRangeRequest(start = safeStart, endInclusive = safeEnd)
    }

    override fun transformInPlace(buffer: ByteArray, offset: Int, length: Int, decodedOffset: Long) {
        // No-op: bytes starting at skipBytes already represent decoded payload.
    }
}

/**
 * Detects disguised MPEG-TS segments (e.g. PNG/JPEG/GIF/HTML prefix prepended before TS packets)
 * by locating the `0x47` sync byte with stride 188.
 */
object MpegTsSyncDetector {
    const val TS_PACKET_SIZE = 188
    const val TS_SYNC_BYTE: Byte = 0x47

    /**
     * Returns the byte offset of the first valid MPEG-TS sync sequence, or -1 if none is found.
     * Offset `0` indicates standard unmasked MPEG-TS.
     */
    fun detectSyncOffset(bytes: ByteArray, length: Int = bytes.size): Int {
        val limit = length.coerceAtMost(bytes.size)
        if (limit <= 0) return -1
        if (bytes[0] == TS_SYNC_BYTE && hasConsecutiveSync(bytes, limit, 0)) {
            return 0
        }
        val maxSearch = limit.coerceAtMost(64 * 1024)
        for (offset in 0 until maxSearch) {
            if (bytes[offset] != TS_SYNC_BYTE) continue
            if (hasConsecutiveSync(bytes, limit, offset)) {
                return offset
            }
        }
        return -1
    }

    fun unwrapSegmentBytes(bytes: ByteArray): ByteArray {
        val offset = detectSyncOffset(bytes, bytes.size)
        return if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
    }

    private fun hasConsecutiveSync(bytes: ByteArray, limit: Int, offset: Int): Boolean {
        val next1 = offset + TS_PACKET_SIZE
        val next2 = offset + TS_PACKET_SIZE * 2
        if (next2 < limit) {
            return bytes[next1] == TS_SYNC_BYTE && bytes[next2] == TS_SYNC_BYTE
        }
        if (next1 < limit) {
            return bytes[next1] == TS_SYNC_BYTE
        }
        return offset == 0 && limit == TS_PACKET_SIZE
    }
}

/**
 * Detects ISOBMFF / fMP4 containers (init `ftyp`/`moov` and media `styp`/`sidx`/`moof`) even when
 * disguised behind an image or wrapper prefix (such as a 67-byte PNG header).
 */
object IsobmffSyncDetector {
    private const val MAX_REASONABLE_BOX_BYTES = 64L * 1024L * 1024L
    private const val MAX_SCAN_OFFSET = 64 * 1024
    private val START_BOX_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx")
    private val FOLLOW_BOX_TYPES = START_BOX_TYPES + setOf("mdat", "free", "skip", "emsg", "prft")

    fun detectSyncOffset(bytes: ByteArray, length: Int = bytes.size): Int {
        val limit = length.coerceAtMost(bytes.size)
        if (limit < 12) return -1
        if (isBoxBoundary(bytes, limit, 0, requireFollowUp = false)) {
            return 0
        }
        val disguisedWrapper = looksLikeWrapperPrefix(bytes, limit)
        val maxOffset = minOf(limit - 8, MAX_SCAN_OFFSET)
        for (offset in 1 until maxOffset) {
            if (isBoxBoundary(bytes, limit, offset, requireFollowUp = !disguisedWrapper)) {
                return offset
            }
        }
        return -1
    }

    private fun isBoxBoundary(
        bytes: ByteArray,
        limit: Int,
        offset: Int,
        requireFollowUp: Boolean
    ): Boolean {
        val header = readBoxHeader(bytes, limit, offset) ?: return false
        val boxSize = header.first
        val boxType = header.second
        if (boxType !in START_BOX_TYPES) return false
        val nextOffset = offset + boxSize
        if (nextOffset + 8L <= limit.toLong()) {
            val follow = readBoxHeader(bytes, limit, nextOffset.toInt()) ?: return false
            return follow.second in FOLLOW_BOX_TYPES
        }
        if (requireFollowUp) return false
        if (boxType == "ftyp" && offset + 12 <= limit) {
            return isPrintableBrand(bytes, offset + 8)
        }
        return false
    }

    private fun readBoxHeader(bytes: ByteArray, limit: Int, offset: Int): Pair<Long, String>? {
        if (offset < 0 || offset + 8 > limit) return null
        val size = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        if (size < 8L || size > MAX_REASONABLE_BOX_BYTES) return null
        val type = byteArrayOf(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7])
        for (b in type) {
            val code = b.toInt() and 0xFF
            if (code !in 0x20..0x7E) return null
        }
        return size to String(type, Charsets.ISO_8859_1)
    }

    private fun isPrintableBrand(bytes: ByteArray, offset: Int): Boolean {
        for (i in 0 until 4) {
            val c = bytes[offset + i].toInt() and 0xFF
            if (c !in 0x20..0x7E) return false
        }
        return true
    }

    internal fun looksLikeWrapperPrefix(bytes: ByteArray, limit: Int): Boolean {
        if (limit >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        ) return true
        if (limit >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) return true
        if (limit >= 4 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte()
        ) return true
        if (limit >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return true
        return false
    }
}

data class DetectedSegmentPayload(
    val syncOffset: Int,
    val contentType: String
)

/**
 * Unifies detection of disguised media segments across MPEG-TS (`0x47` stride 188) and
 * ISOBMFF/fMP4 (`ftyp`/`styp`/`moov`/`moof`/`sidx`) payloads.
 */
object DisguisedMediaDetector {
    fun detectPayload(
        bytes: ByteArray,
        length: Int,
        upstreamContentType: String? = null
    ): DetectedSegmentPayload? {
        val lowerType = upstreamContentType?.trim()?.lowercase().orEmpty()
        if (lowerType.startsWith("text/") ||
            lowerType.contains("mpegurl") ||
            lowerType.contains("json")
        ) {
            return null
        }
        val tsOffset = MpegTsSyncDetector.detectSyncOffset(bytes, length)
        if (tsOffset >= 0) {
            return DetectedSegmentPayload(syncOffset = tsOffset, contentType = "video/mp2t")
        }
        val mp4Offset = IsobmffSyncDetector.detectSyncOffset(bytes, length)
        if (mp4Offset >= 0) {
            return DetectedSegmentPayload(syncOffset = mp4Offset, contentType = "video/mp4")
        }
        return null
    }
}

/**
 * HuggingFace container obfuscation decoder (64-byte header + rotated 256-byte XOR keystream).
 * Runs 100% locally inside Aio-Stream.
 */
class HfXor256Transform(
    val headerSize: Int = DEFAULT_HEADER_SIZE,
    val xorKey: ByteArray = DEFAULT_KEY,
    val isRaw: Boolean = false
) : RangeByteTransform {
    init {
        require(headerSize >= 0) { "headerSize must be non-negative" }
        require(xorKey.isNotEmpty() || isRaw) { "xorKey must not be empty when XOR is enabled" }
    }

    override fun decodedTotalSize(rawTotalSize: Long?): Long? {
        if (isRaw) return rawTotalSize
        return rawTotalSize?.let { (it - headerSize.toLong()).coerceAtLeast(0L) }
    }

    override fun toRawRange(decodedStart: Long, decodedEndInclusive: Long?): RawRangeRequest {
        val effHeader = if (isRaw) 0L else headerSize.toLong()
        val safeStart = decodedStart.coerceAtLeast(0L) + effHeader
        val safeEnd = decodedEndInclusive?.let { it.coerceAtLeast(decodedStart.coerceAtLeast(0L)) + effHeader }
        return RawRangeRequest(start = safeStart, endInclusive = safeEnd)
    }

    override fun transformInPlace(buffer: ByteArray, offset: Int, length: Int, decodedOffset: Long) {
        if (isRaw || length <= 0 || xorKey.isEmpty()) return
        val keyLen = xorKey.size
        val end = (offset + length).coerceAtMost(buffer.size)
        if (keyLen == KEY_LENGTH && decodedOffset >= 0L) {
            var keyIdx = (decodedOffset.toInt() and 0xFF)
            for (i in offset until end) {
                buffer[i] = (buffer[i].toInt() xor xorKey[keyIdx].toInt()).toByte()
                keyIdx = (keyIdx + 1) and 0xFF
            }
            return
        }
        var pos = decodedOffset
        for (i in offset until end) {
            val keyIdx = ((pos % keyLen + keyLen) % keyLen).toInt()
            buffer[i] = (buffer[i].toInt() xor xorKey[keyIdx].toInt()).toByte()
            pos++
        }
    }

    companion object {
        const val DEFAULT_HEADER_SIZE = 64
        const val KEY_LENGTH = 256

        private val DEFAULT_KEY_HEX = (
            "48210cf2c12721f7f11e129b984db6d7" +
                "a931e3ed2fd03c466743c9bbc978f61b" +
                "fb280b598c8c9255f49d68b5f7413d33" +
                "fec82cd308b44ee5efc4f11a7029641f" +
                "86230a9cf62b92892d2b6ee15a0b0587" +
                "95bd2bbc2fa97e45b43481d91aa47686" +
                "f64b720b30296a9a7bd89ce505411cb4" +
                "3efa331ffa40b2cfd3feb596ba2aa7c2" +
                "3acee179fbbd04d082471ab5a3ef7377" +
                "00fee8acf8c698d52b9d49e1dc371e0c" +
                "127105fe29955547a62bd7db7ff12d96" +
                "1a215f73610ffefa0ab9df51fa16e633" +
                "5d72f25ac1015207875dc344cdd75d15" +
                "fab9e4f929f5155b2c71a9890ee2dfce" +
                "b1a33fa2f47e3a29e01000cf55a653fe" +
                "ad86e14046dd25e947e9ca3c00e316db"
            )

        val DEFAULT_KEY: ByteArray = ByteArray(KEY_LENGTH) { idx ->
            val hi = Character.digit(DEFAULT_KEY_HEX[idx * 2], 16)
            val lo = Character.digit(DEFAULT_KEY_HEX[idx * 2 + 1], 16)
            ((hi shl 4) or lo).toByte()
        }

        /**
         * Inspects the first bytes of a remote HF object to determine whether it is already a
         * standard unencrypted container (raw MP4/MKV/AVI/TS/FLV/Ogg) or uses header + XOR.
         */
        fun probeFromInitialBytes(
            probeBytes: ByteArray,
            explicitHeaderSize: Int? = null,
            explicitXorKey: ByteArray? = null,
            explicitRaw: Boolean? = null
        ): HfXor256Transform {
            if (explicitRaw == true) {
                return HfXor256Transform(
                    headerSize = 0,
                    xorKey = explicitXorKey ?: DEFAULT_KEY,
                    isRaw = true
                )
            }
            if (explicitRaw == null && looksLikeKnownPlainContainer(probeBytes, 0)) {
                return HfXor256Transform(
                    headerSize = 0,
                    xorKey = explicitXorKey ?: DEFAULT_KEY,
                    isRaw = true
                )
            }
            val headerSize = (explicitHeaderSize ?: DEFAULT_HEADER_SIZE).coerceAtLeast(0)
            val key = explicitXorKey ?: DEFAULT_KEY
            if (explicitRaw == null && probeBytes.size > headerSize + 12 &&
                looksLikeKnownPlainContainer(probeBytes, headerSize)
            ) {
                // Headered without XOR layer
                return HfXor256Transform(
                    headerSize = headerSize,
                    xorKey = ByteArray(1) { 0 },
                    isRaw = false
                )
            }
            return HfXor256Transform(
                headerSize = headerSize,
                xorKey = key,
                isRaw = false
            )
        }

        /**
         * Attempts to recover a custom 256-byte repeating XOR key using zero-byte column frequency
         * over a ~64KB encrypted sample (matching P4k Worker `autokey=1`), accepting the recovered
         * key only when it produces a valid MKV/MP4/AVI/TS container signature at offset 0.
         */
        fun recoverCustomKeyFromSample(sampleAtPayload: ByteArray): ByteArray? {
            if (sampleAtPayload.size < KEY_LENGTH) return null
            val recovered = ByteArray(KEY_LENGTH)
            val counts = IntArray(256)
            for (col in 0 until KEY_LENGTH) {
                counts.fill(0)
                var maxCount = -1
                var bestByte = 0
                var i = col
                while (i < sampleAtPayload.size) {
                    val b = sampleAtPayload[i].toInt() and 0xFF
                    val c = ++counts[b]
                    if (c > maxCount) {
                        maxCount = c
                        bestByte = b
                    }
                    i += KEY_LENGTH
                }
                recovered[col] = bestByte.toByte()
            }
            val probeHead = ByteArray(minOf(sampleAtPayload.size, 32)) { idx ->
                (sampleAtPayload[idx].toInt() xor recovered[idx % KEY_LENGTH].toInt()).toByte()
            }
            return recovered.takeIf { looksLikeKnownPlainContainer(probeHead, 0) }
        }

        fun detectDecodedContentType(bytes: ByteArray, offset: Int = 0): String? {
            val avail = bytes.size - offset
            if (avail < 4) return null
            if ((bytes[offset].toInt() and 0xFF) == 0x1A &&
                (bytes[offset + 1].toInt() and 0xFF) == 0x45 &&
                (bytes[offset + 2].toInt() and 0xFF) == 0xDF &&
                (bytes[offset + 3].toInt() and 0xFF) == 0xA3
            ) {
                return "video/x-matroska"
            }
            if (avail >= 8) {
                val boxType = byteArrayOf(
                    bytes[offset + 4],
                    bytes[offset + 5],
                    bytes[offset + 6],
                    bytes[offset + 7]
                ).decodeToString()
                if (boxType == "ftyp" || boxType == "moov" || boxType == "wide" || boxType == "free") {
                    return "video/mp4"
                }
            }
            if (avail >= 12 &&
                bytes[offset] == 'R'.code.toByte() &&
                bytes[offset + 1] == 'I'.code.toByte() &&
                bytes[offset + 2] == 'F'.code.toByte() &&
                bytes[offset + 3] == 'F'.code.toByte()
            ) {
                return "video/x-msvideo"
            }
            if (bytes[offset] == 'O'.code.toByte() &&
                bytes[offset + 1] == 'g'.code.toByte() &&
                bytes[offset + 2] == 'g'.code.toByte() &&
                bytes[offset + 3] == 'S'.code.toByte()
            ) {
                return "video/ogg"
            }
            if (bytes[offset] == 'F'.code.toByte() &&
                bytes[offset + 1] == 'L'.code.toByte() &&
                bytes[offset + 2] == 'V'.code.toByte() &&
                bytes[offset + 3] == 0x01.toByte()
            ) {
                return "video/x-flv"
            }
            if (avail >= MpegTsSyncDetector.TS_PACKET_SIZE * 2 &&
                bytes[offset] == MpegTsSyncDetector.TS_SYNC_BYTE &&
                bytes[offset + MpegTsSyncDetector.TS_PACKET_SIZE] == MpegTsSyncDetector.TS_SYNC_BYTE
            ) {
                return "video/mp2t"
            }
            return null
        }

        fun looksLikeKnownPlainContainer(bytes: ByteArray, offset: Int = 0): Boolean =
            detectDecodedContentType(bytes, offset) != null
    }
}
