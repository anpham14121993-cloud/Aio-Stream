package com.nuvio.tv.core.proxy

import com.nuvio.tv.domain.model.LocalProxyHints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfXor256TransformTest {

    @Test
    fun defaultKeyMatchesExact256ByteReference() {
        val key = HfXor256Transform.DEFAULT_KEY
        assertEquals(256, key.size)
        assertEquals(0x48.toByte(), key[0])
        assertEquals(0x21.toByte(), key[1])
        assertEquals(0x0c.toByte(), key[2])
        assertEquals(0xf2.toByte(), key[3])
        assertEquals(0x00.toByte(), key[252])
        assertEquals(0xe3.toByte(), key[253])
        assertEquals(0x16.toByte(), key[254])
        assertEquals(0xdb.toByte(), key[255])
    }

    @Test
    fun transformDecryptsArbitraryByteRangesIdentically() {
        val plain = ByteArray(1500) { idx -> ((idx * 37 + 11) and 0xFF).toByte() }
        val transform = HfXor256Transform()

        // Encrypt using exact reverse of stream cipher at decoded offset 0
        val encrypted = plain.copyOf()
        transform.transformInPlace(encrypted, 0, encrypted.size, decodedOffset = 0L)

        // Verify range math from arbitrary unaligned offsets across 256-byte boundary
        for (start in listOf(0, 1, 63, 64, 67, 255, 256, 257, 777, 1024)) {
            val end = (start + 137).coerceAtMost(plain.size - 1)
            val slice = encrypted.copyOfRange(start, end + 1)
            transform.transformInPlace(slice, 0, slice.size, decodedOffset = start.toLong())
            assertArrayEquals(
                "Mismatch for range $start..$end",
                plain.copyOfRange(start, end + 1),
                slice
            )
            val rawReq = transform.toRawRange(start.toLong(), end.toLong())
            assertEquals(64L + start, rawReq.start)
            assertEquals(64L + end, rawReq.endInclusive)
        }
        assertEquals(1500L, transform.decodedTotalSize(1564L))
    }

    @Test
    fun probeDetectsRawContainersVersusXorContainers() {
        val rawMp4 = ByteArray(128)
        "ftyp".encodeToByteArray().copyInto(rawMp4, destinationOffset = 4)
        val rawResult = HfXor256Transform.probeFromInitialBytes(rawMp4)
        assertTrue(rawResult.isRaw)
        assertEquals(0, rawResult.headerSize)
        assertEquals(5000L, rawResult.decodedTotalSize(5000L))

        val rawMkv = ByteArray(128)
        rawMkv[0] = 0x1A
        rawMkv[1] = 0x45
        rawMkv[2] = 0xDF.toByte()
        rawMkv[3] = 0xA3.toByte()
        assertTrue(HfXor256Transform.probeFromInitialBytes(rawMkv).isRaw)

        // 64-byte header + XORed MP4 header
        val plain = ByteArray(128)
        "ftyp".encodeToByteArray().copyInto(plain, destinationOffset = 4)
        val obfuscated = ByteArray(64 + plain.size) { 0x55 }
        val xorEnc = plain.copyOf()
        HfXor256Transform().transformInPlace(xorEnc, 0, xorEnc.size, 0L)
        xorEnc.copyInto(obfuscated, destinationOffset = 64)

        val xorResult = HfXor256Transform.probeFromInitialBytes(obfuscated)
        assertFalse(xorResult.isRaw)
        assertEquals(64, xorResult.headerSize)
        assertEquals(128L, xorResult.decodedTotalSize(192L))
    }

    @Test
    fun decodesCustomHexBase64AndBase64UrlKeys() {
        val bytes = ByteArray(32) { it.toByte() }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val hintsHex = LocalProxyHints(xorKey = hex)
        assertArrayEquals(bytes, hintsHex.decodedXorKey())

        val b64Url = HlsAndDashRewriter.encodeBase64Url(bytes.toString(Charsets.ISO_8859_1))
        val hintsB64 = LocalProxyHints(xorKey = b64Url)
        assertEquals(bytes.size, hintsB64.decodedXorKey()?.size)
    }

    @Test
    fun parsesRangesAndPrefixSkipCorrectly() {
        val open = RangeHeaderParser.parse("bytes=120-", totalLength = 1000L) as RangeParseResult.SingleRange
        assertEquals(120L, open.spec.start)
        assertEquals(999L, open.spec.endInclusive)

        val suffix = RangeHeaderParser.parse("bytes=-200", totalLength = 1000L) as RangeParseResult.SingleRange
        assertEquals(800L, suffix.spec.start)
        assertEquals(999L, suffix.spec.endInclusive)

        val oob = RangeHeaderParser.parse("bytes=1500-1600", totalLength = 1000L)
        assertTrue(oob is RangeParseResult.Unsatisfiable)

        assertEquals(45678L, RangeHeaderParser.parseContentRangeTotal("bytes 0-511/45678"))
        assertNull(RangeHeaderParser.parseContentRangeTotal("bytes 0-511/*"))

        val skip = PrefixSkipTransform(128L)
        assertEquals(872L, skip.decodedTotalSize(1000L))
        val raw = skip.toRawRange(10L, 99L)
        assertEquals(138L, raw.start)
        assertEquals(227L, raw.endInclusive)
    }
}
