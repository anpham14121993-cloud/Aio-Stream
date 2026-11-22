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

        fun looksLikeKnownPlainContainer(bytes: ByteArray, offset: Int = 0): Boolean {
            val avail = bytes.size - offset
            if (avail < 4) return false
            // ISO BMFF / MP4: bytes [offset+4..offset+7] == "ftyp" / "moov" / "free" / "wide"
            if (avail >= 8) {
                val b4 = bytes[offset + 4].toInt() and 0xFF
                val b5 = bytes[offset + 5].toInt() and 0xFF
                val b6 = bytes[offset + 6].toInt() and 0xFF
                val b7 = bytes[offset + 7].toInt() and 0xFF
                val boxType = byteArrayOf(b4.toByte(), b5.toByte(), b6.toByte(), b7.toByte())
                    .decodeToString()
                if (boxType == "ftyp" || boxType == "moov" || boxType == "wide" || boxType == "free") {
                    return true
                }
            }
            // EBML / Matroska / WebM: 1A 45 DF A3
            if ((bytes[offset].toInt() and 0xFF) == 0x1A &&
                (bytes[offset + 1].toInt() and 0xFF) == 0x45 &&
                (bytes[offset + 2].toInt() and 0xFF) == 0xDF &&
                (bytes[offset + 3].toInt() and 0xFF) == 0xA3
            ) {
                return true
            }
            // AVI / RIFF
            if (avail >= 12 &&
                bytes[offset] == 'R'.code.toByte() &&
                bytes[offset + 1] == 'I'.code.toByte() &&
                bytes[offset + 2] == 'F'.code.toByte() &&
                bytes[offset + 3] == 'F'.code.toByte()
            ) {
                return true
            }
            // OggS
            if (bytes[offset] == 'O'.code.toByte() &&
                bytes[offset + 1] == 'g'.code.toByte() &&
                bytes[offset + 2] == 'g'.code.toByte() &&
                bytes[offset + 3] == 'S'.code.toByte()
            ) {
                return true
            }
            // FLV
            if (bytes[offset] == 'F'.code.toByte() &&
                bytes[offset + 1] == 'L'.code.toByte() &&
                bytes[offset + 2] == 'V'.code.toByte() &&
                bytes[offset + 3] == 0x01.toByte()
            ) {
                return true
            }
            // MPEG-TS at offset
            if (avail >= MpegTsSyncDetector.TS_PACKET_SIZE * 2 &&
                bytes[offset] == MpegTsSyncDetector.TS_SYNC_BYTE &&
                bytes[offset + MpegTsSyncDetector.TS_PACKET_SIZE] == MpegTsSyncDetector.TS_SYNC_BYTE
            ) {
                return true
            }
            return false
        }
    }
}
