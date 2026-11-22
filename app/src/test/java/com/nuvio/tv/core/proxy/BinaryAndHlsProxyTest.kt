package com.nuvio.tv.core.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAndHlsProxyTest {

    @Test
    fun detectsAndUnwrapsPngDisguisedMpegTs() {
        val tsPayload = ByteArray(188 * 3) { 0x11 }
        tsPayload[0] = 0x47
        tsPayload[188] = 0x47
        tsPayload[376] = 0x47

        val pngHeader = "\u0089PNG\r\n\u001a\nFAKE_IHDR_METADATA_BLOCK".encodeToByteArray()
        val disguised = pngHeader + tsPayload

        val offset = MpegTsSyncDetector.detectSyncOffset(disguised, disguised.size)
        assertEquals(pngHeader.size, offset)
        assertArrayEquals(tsPayload, MpegTsSyncDetector.unwrapSegmentBytes(disguised))
        assertEquals(0, MpegTsSyncDetector.detectSyncOffset(tsPayload, tsPayload.size))

        val noise = ByteArray(600) { 0x22 }
        assertEquals(-1, MpegTsSyncDetector.detectSyncOffset(noise, noise.size))
    }

    @Test
    fun rewritesMasterAndMediaHlsPlaylistsCompletely() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Vietnamese",URI="audio/vi.m3u8"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=400000,URI="iframes.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720p/index.m3u8?token=abc
        """.trimIndent()

        val masterUrl = "https://cdn.example.com/hls/movie/master.m3u8?sig=xyz"
        val rewrittenMaster = HlsAndDashRewriter.rewriteHlsPlaylist(master, masterUrl) { kind, target, ref ->
            "http://127.0.0.1:4444/${kind.name.lowercase()}?u=${HlsAndDashRewriter.urlEncode(target)}&ref=${HlsAndDashRewriter.urlEncode(ref.orEmpty())}"
        }
        assertTrue(rewrittenMaster.contains("URI=\"http://127.0.0.1:4444/hls_playlist?u=https%3A%2F%2Fcdn.example.com%2Fhls%2Fmovie%2Faudio%2Fvi.m3u8"))
        assertTrue(rewrittenMaster.contains("URI=\"http://127.0.0.1:4444/hls_playlist?u=https%3A%2F%2Fcdn.example.com%2Fhls%2Fmovie%2Fiframes.m3u8"))
        assertTrue(rewrittenMaster.contains("http://127.0.0.1:4444/hls_playlist?u=https%3A%2F%2Fcdn.example.com%2Fhls%2Fmovie%2F720p%2Findex.m3u8%3Ftoken%3Dabc&ref=https%3A%2F%2Fcdn.example.com%2Fhls%2Fmovie%2Fmaster.m3u8%3Fsig%3Dxyz"))

        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/main.key",IV=0x01
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg_0001.png
            #EXT-X-PART:DURATION=1.0,URI="part_0002.ts"
            #EXT-X-ENDLIST
        """.trimIndent()
        val mediaUrl = "https://cdn.example.com/hls/movie/720p/index.m3u8"
        val rewrittenMedia = HlsAndDashRewriter.rewriteHlsPlaylist(media, mediaUrl) { kind, target, _ ->
            "http://127.0.0.1:4444/${kind.name.lowercase()}?u=$target"
        }
        assertTrue(rewrittenMedia.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:4444/hls_key?u=https://cdn.example.com/hls/movie/keys/main.key\",IV=0x01"))
        assertTrue(rewrittenMedia.contains("#EXT-X-MAP:URI=\"http://127.0.0.1:4444/hls_segment?u=https://cdn.example.com/hls/movie/720p/init.mp4\""))
        assertTrue(rewrittenMedia.contains("http://127.0.0.1:4444/hls_segment?u=https://cdn.example.com/hls/movie/720p/seg_0001.png"))
        assertTrue(rewrittenMedia.contains("#EXT-X-PART:DURATION=1.0,URI=\"http://127.0.0.1:4444/hls_segment?u=https://cdn.example.com/hls/movie/720p/part_0002.ts\""))
    }

    @Test(expected = DrmProtectedStreamException::class)
    fun refusesSampleAesHlsPlaylists() {
        val drmPlaylist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://license.example.com/fps",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:4.0,
            seg0.m4s
        """.trimIndent()
        assertTrue(HlsAndDashRewriter.containsHlsDrm(drmPlaylist))
        HlsAndDashRewriter.rewriteHlsPlaylist(drmPlaylist, "https://cdn.example.com/drm.m3u8") { _, target, _ -> target }
    }

    @Test
    fun rewritesNonDrmDashAndBlocksDrmDash() {
        val mpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <BaseURL>segments/</BaseURL>
              <Period><AdaptationSet/></Period>
            </MPD>
        """.trimIndent()
        val rewritten = HlsAndDashRewriter.rewriteDashManifest(mpd, "https://cdn.example.com/dash/manifest.mpd") { base ->
            "http://127.0.0.1:4444/rel/${HlsAndDashRewriter.encodeBase64Url(base)}/"
        }
        assertFalse(rewritten.contains("<BaseURL>segments/</BaseURL>"))
        assertTrue(rewritten.contains("http://127.0.0.1:4444/rel/"))

        val drmMpd = """
            <MPD><Period><AdaptationSet><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/></AdaptationSet></Period></MPD>
        """.trimIndent()
        assertTrue(HlsAndDashRewriter.containsDashDrm(drmMpd))
        var threw = false
        try {
            HlsAndDashRewriter.rewriteDashManifest(drmMpd, "https://cdn.example.com/drm.mpd") { it }
        } catch (_: DrmProtectedStreamException) {
            threw = true
        }
        assertTrue(threw)
    }
}
