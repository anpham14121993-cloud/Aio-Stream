package com.nuvio.tv.core.proxy

import com.nuvio.tv.core.player.thumbnail.LedgerKey
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.domain.model.LocalProxyHints
import com.nuvio.tv.domain.model.LocalProxyMode
import com.nuvio.tv.ui.screens.player.HostSpeeds
import com.squareup.moshi.Moshi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalProxyGatewayAndSettingsTest {

    private lateinit var proxy: LocalStreamProxy

    @Before
    fun setUp() {
        proxy = LocalStreamProxy()
        proxy.updateSettings(localProxyEnabled = true, localProxyHeadersEnabled = true)
        LocalProxyHintsStore.clear()
    }

    @After
    fun tearDown() {
        proxy.stop()
        LocalProxyHintsStore.clear()
    }

    @Test
    fun directAndDebridStreamsPassThroughCompletelyUntouched() {
        val debridUrl = "https://download.real-debrid.com/d/ABCDEF/Movie.2025.2160p.mkv"
        val res = proxy.prepareStreamForPlayback(url = debridUrl, headers = emptyMap())
        assertFalse(res.proxied)
        assertEquals(LocalProxyMode.NONE, res.mode)
        assertEquals(debridUrl, res.playUrl)
        assertFalse(proxy.isProxyUrl(res.playUrl))

        val torrentUrl = "http://127.0.0.1:8090/stream/infohash/0"
        val torrentRes = proxy.prepareStreamForPlayback(url = torrentUrl)
        assertFalse(torrentRes.proxied)
        assertEquals(torrentUrl, torrentRes.playUrl)
    }

    @Test
    fun unwrapsLegacyHfWrapperUrlDirectlyWithoutExternalProxyServer() {
        val wrapper = "https://oracle-or-worker.example.com/hf/datasets/owner/repo/resolve/main/ep1.mp4?token=tmp&download=true"
        val unwrapped = LocalProxyRuleRegistry.unwrapHfWrapperUrl(wrapper)
        assertEquals("https://huggingface.co/datasets/owner/repo/resolve/main/ep1.mp4?download=true", unwrapped)

        val doubleExt = "https://phim4k-addon.notthingp.workers.dev/hf/buckets/u/b/resolve/VID123.pth.mkv?autokey=1"
        assertEquals(
            "https://huggingface.co/buckets/u/b/resolve/VID123.pth",
            LocalProxyRuleRegistry.unwrapHfWrapperUrl(doubleExt)
        )

        val prep = proxy.prepareStreamForPlayback(url = wrapper)
        assertTrue(prep.proxied)
        assertEquals(LocalProxyMode.HF_XOR_256, prep.mode)
        assertTrue(prep.playUrl.startsWith("http://127.0.0.1:"))
        assertEquals(wrapper, proxy.originalUrlOf(prep.playUrl))
    }

    @Test
    fun respectsGlobalMasterToggleAndExplicitDisableHint() {
        val hintedUrl = "https://huggingface.co/buckets/team/bucket/resolve/video.bin"
        val hints = LocalProxyHints(enabled = true, mode = "hf-xor-256")
        val enabledRes = proxy.prepareStreamForPlayback(url = hintedUrl, hints = hints)
        assertTrue(enabledRes.proxied)
        assertEquals(LocalProxyMode.HF_XOR_256, enabledRes.mode)

        proxy.updateSettings(localProxyEnabled = false, localProxyHeadersEnabled = true)
        val disabledRes = proxy.prepareStreamForPlayback(url = hintedUrl, hints = hints)
        assertFalse(disabledRes.proxied)
        assertEquals(hintedUrl, disabledRes.playUrl)

        proxy.updateSettings(localProxyEnabled = true, localProxyHeadersEnabled = true)
        val explicitOff = proxy.prepareStreamForPlayback(
            url = "https://cdn.example.com/playlist.m3u8",
            headers = mapOf("Referer" to "https://site.example/"),
            hints = LocalProxyHints(enabled = false)
        )
        assertFalse(explicitOff.proxied)
    }

    @Test
    fun routesHlsDashAndExternalPlayersAndSupportsCustomExtensionRules() {
        val hls = proxy.prepareStreamForPlayback(
            url = "https://edge.example.net/master.m3u8",
            headers = mapOf("Referer" to "https://embed.example.net/")
        )
        assertTrue(hls.proxied)
        assertEquals(LocalProxyMode.HLS_REWRITE, hls.mode)
        assertTrue(hls.playHeaders.isEmpty())

        // With header relay off, generic HLS falls back to direct unless explicit localProxy hint is set
        proxy.updateSettings(localProxyEnabled = true, localProxyHeadersEnabled = false)
        val hlsOff = proxy.prepareStreamForPlayback(
            url = "https://edge.example.net/master.m3u8",
            headers = mapOf("Referer" to "https://embed.example.net/")
        )
        assertFalse(hlsOff.proxied)

        proxy.updateSettings(localProxyEnabled = true, localProxyHeadersEnabled = true)
        val extDirect = proxy.prepareStreamForPlayback(
            url = "https://cdn.example.net/video.mp4",
            headers = mapOf("Referer" to "https://site.example/"),
            forExternalPlayer = true
        )
        assertTrue(extDirect.proxied)
        assertEquals(LocalProxyMode.HTTP_HEADERS, extDirect.mode)

        val customRule = LocalProxyRule { ctx ->
            if (ctx.url.contains("custom-addon-cdn.example")) {
                ProxyDecision(mode = LocalProxyMode.PREFIX_SKIP, targetUrl = ctx.url, hints = LocalProxyHints(skipBytes = 32L))
            } else null
        }
        proxy.rules.registerRule(customRule)
        val customRes = proxy.prepareStreamForPlayback("https://custom-addon-cdn.example/video.mp4")
        assertTrue(customRes.proxied)
        assertEquals(LocalProxyMode.PREFIX_SKIP, customRes.mode)
        proxy.rules.unregisterRule(customRule)

        val afterUnregister = proxy.prepareStreamForPlayback("https://custom-addon-cdn.example/video.mp4")
        assertFalse(afterUnregister.proxied)
    }

    @Test
    fun deduplicatesIdenticalActiveSessionsAndRecoversOriginalUrl() {
        val url = "https://huggingface.co/datasets/a/b/resolve/main/f.mp4"
        val hints = LocalProxyHints(enabled = true, mode = "hf-xor-256")
        val first = proxy.prepareStreamForPlayback(url = url, headers = mapOf("Authorization" to "Bearer t"), hints = hints)
        val second = proxy.prepareStreamForPlayback(url = url, headers = mapOf("Authorization" to "Bearer t"))
        assertEquals(first.playUrl, second.playUrl)
        assertEquals(url, proxy.originalUrlOf(first.playUrl))
    }

    @Test
    fun clientProfileRoutesAdaptiveVsSingleStreamAndPreservesRemoteHostKeys() {
        val hfUrl = "https://huggingface.co/datasets/owner/repo/resolve/main/movie.pth"
        val hints = LocalProxyHints(enabled = true, mode = "hf-xor-256")

        val exoPrep = proxy.prepareStreamForPlayback(
            url = hfUrl,
            hints = hints,
            clientProfile = ProxyClientProfile.ADAPTIVE_EXOPLAYER
        )
        assertTrue(exoPrep.proxied)
        assertTrue(exoPrep.playUrl.endsWith("/media"))

        val singlePrep = proxy.prepareStreamForPlayback(
            url = hfUrl,
            hints = hints,
            clientProfile = ProxyClientProfile.SINGLE_STREAM
        )
        assertTrue(singlePrep.proxied)
        assertTrue(singlePrep.playUrl.endsWith("/smedia"))

        val extPrep = proxy.prepareStreamForPlayback(
            url = "https://cdn.example.net/video.mp4",
            headers = mapOf("Referer" to "https://site.example/"),
            forExternalPlayer = true
        )
        assertTrue(extPrep.proxied)
        assertTrue(extPrep.playUrl.endsWith("/smedia"))

        val shared = LocalStreamProxy.shared
        try {
            shared.updateSettings(localProxyEnabled = true, localProxyHeadersEnabled = true)
            val sharedPrep = shared.prepareStreamForPlayback(
                url = hfUrl,
                hints = hints,
                clientProfile = ProxyClientProfile.ADAPTIVE_EXOPLAYER
            )
            assertEquals(hfUrl, shared.effectiveTargetUrlOf(sharedPrep.playUrl))
            assertEquals("huggingface.co", HostSpeeds.keyOf(sharedPrep.playUrl))
            assertEquals("huggingface.co", LedgerKey.of(sharedPrep.playUrl))
            assertEquals("127.0.0.1", HostSpeeds.keyOf("http://127.0.0.1:8090/stream/infohash/0"))
        } finally {
            shared.stop()
        }
    }

    @Test
    fun moshiParsesBehaviorHintsLocalProxyJsonCleanly() {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(BehaviorHintsDto::class.java)
        val json = """
            {
              "notWebReady": false,
              "localProxy": {
                "enabled": true,
                "mode": "hf-xor-256",
                "autoKey": true,
                "headerSize": 64,
                "contentType": "video/mp4"
              }
            }
        """.trimIndent()
        val dto: BehaviorHintsDto = adapter.fromJson(json)!!
        val domain = dto.toDomain()
        assertEquals(LocalProxyMode.HF_XOR_256, domain.localProxy?.resolvedMode())
        assertEquals(true, domain.localProxy?.autoKey)
        assertEquals(64, domain.localProxy?.headerSize)
        assertEquals("video/mp4", domain.localProxy?.contentType)
        assertNotNull(domain.localProxy)
    }
}
