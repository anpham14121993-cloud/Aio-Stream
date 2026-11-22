package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ParallelRangeByteBufferReadTest {

    private val file = ByteArray(600 * 1024) { (it * 31 + 7).toByte() }
    private val server = MockWebServer()

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        mockkStatic(SystemClock::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(SystemClock::class)
        unmockkStatic(Uri::class)
    }

    private fun serve(ranges: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (!ranges || range == null) {
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                }
                val (from, to) = range.removePrefix("bytes=").split("-").let { parts ->
                    val start = parts[0].toInt()
                    val end = parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1)
                    start to minOf(end, file.size - 1)
                }
                return MockResponse().setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    private fun readAll(): ByteArray {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(OkHttpClient()),
            parallelConnections = 2,
            chunkSize = 128L * 1024L,
            isolateSession = true,
        )
        val spec = DataSpec.Builder().setUri(uriOf(server.url("/file").toString())).build()
        val out = ByteArrayOutputStream()
        val target = ByteBuffer.allocateDirect(64 * 1024)
        try {
            source.open(spec)
            while (true) {
                target.clear()
                val read = source.read(target, target.capacity())
                if (read == C.RESULT_END_OF_INPUT) break
                target.flip()
                val bytes = ByteArray(target.remaining())
                target.get(bytes)
                out.write(bytes)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `ranged file reads back byte for byte into direct buffers`() {
        serve(ranges = true)
        assertArrayEquals(file, readAll())
    }

    @Test
    fun `single connection fallback reads back byte for byte into direct buffers`() {
        serve(ranges = false)
        assertArrayEquals(file, readAll())
    }

    @Test
    fun `bytebuffer reads serve in-flight bytes and unlock lookahead before chunk finishes`() {
        val chunkBytes = 2 * 1024 * 1024
        val totalBytes = 6 * 1024 * 1024
        val payload = ByteArray(totalBytes) { (it * 37 + 11).toByte() }
        val chunk0Split = 1300 * 1024 // > 1 MB EARNED_PREFETCH_BYTES so progressive read unlocks chunk 2
        val chunk0ProgressDelivered = CountDownLatch(1)
        val chunk2Started = CountDownLatch(1)
        val releaseChunk0Tail = CountDownLatch(1)
        val chunk0Finished = AtomicBoolean(false)

        val mockFactory = mockk<OkHttpDataSource.Factory>()
        every { mockFactory.createDataSource() } answers {
            val ds = mockk<OkHttpDataSource>(relaxed = true)
            var specPos = 0L
            var specLen = 0L
            var readPos = 0
            var heldOnce = false
            var isChunk0Request = false
            every { ds.open(any()) } answers {
                val reqSpec = firstArg<DataSpec>()
                specPos = reqSpec.position
                val remaining = totalBytes - specPos
                specLen = if (reqSpec.length == C.LENGTH_UNSET.toLong()) remaining else minOf(reqSpec.length, remaining)
                readPos = specPos.toInt()
                isChunk0Request = (specPos == 0L && specLen == chunkBytes.toLong())
                if (specPos >= 2L * chunkBytes) {
                    chunk2Started.countDown()
                }
                every { ds.uri } returns reqSpec.uri
                every { ds.responseCode } returns 206
                every { ds.responseHeaders } returns mapOf(
                    "Content-Range" to listOf("bytes $specPos-${specPos + specLen - 1}/$totalBytes")
                )
                specLen
            }
            every { ds.read(any(), any(), any()) } answers {
                val target = firstArg<ByteArray>()
                val targetOffset = secondArg<Int>()
                val requested = thirdArg<Int>()
                val maxEnd = (specPos + specLen).toInt()
                if (readPos >= maxEnd) {
                    if (isChunk0Request) chunk0Finished.set(true)
                    C.RESULT_END_OF_INPUT
                } else if (isChunk0Request && !heldOnce && readPos >= chunk0Split) {
                    heldOnce = true
                    chunk0ProgressDelivered.countDown()
                    releaseChunk0Tail.await(5, TimeUnit.SECONDS)
                    val n = minOf(requested, maxEnd - readPos)
                    payload.copyInto(target, targetOffset, readPos, readPos + n)
                    readPos += n
                    n
                } else {
                    val limit = if (isChunk0Request && !heldOnce) minOf(requested, chunk0Split - readPos) else requested
                    val n = minOf(limit, maxEnd - readPos)
                    payload.copyInto(target, targetOffset, readPos, readPos + n)
                    readPos += n
                    n
                }
            }
            ds
        }

        val source = ParallelRangeDataSource(
            mockFactory,
            parallelConnections = 2,
            chunkSize = chunkBytes.toLong(),
            shouldAllowBackgroundPrefetch = { true },
            isolateSession = true
        )
        val out = ByteArrayOutputStream()
        val target = ByteBuffer.allocateDirect(64 * 1024)
        var verifiedProgressive = false
        try {
            val spec = DataSpec.Builder().setUri(uriOf("https://example.invalid/progressive.mkv")).build()
            assertEquals(totalBytes.toLong(), source.open(spec))
            while (true) {
                target.clear()
                val n = source.read(target, target.capacity())
                if (n == C.RESULT_END_OF_INPUT) break
                target.flip()
                val chunk = ByteArray(target.remaining())
                target.get(chunk)
                out.write(chunk)
                if (!verifiedProgressive && out.size() >= chunk0Split) {
                    // Reader already received >= 1.3 MB while chunk 0's HTTP body is still paused!
                    assertTrue(chunk0ProgressDelivered.await(3, TimeUnit.SECONDS))
                    assertTrue(!chunk0Finished.get())
                    assertTrue(chunk2Started.await(3, TimeUnit.SECONDS))
                    verifiedProgressive = true
                    releaseChunk0Tail.countDown()
                }
            }
            assertTrue(verifiedProgressive)
            assertArrayEquals(payload, out.toByteArray())
        } finally {
            releaseChunk0Tail.countDown()
            source.close()
        }
    }
}
