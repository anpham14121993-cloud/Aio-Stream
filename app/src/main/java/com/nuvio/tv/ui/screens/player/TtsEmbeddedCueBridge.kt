@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import com.nuvio.tv.core.tts.AssDialogueParser
import com.nuvio.tv.core.tts.TtsSubtitleCoordinator
import java.io.EOFException
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure packet-level embedded subtitle decoder used during ExoPlayer extraction so SRT, VTT,
 * ASS/SSA, and MP4 TX3G cues reach [TtsSubtitleCoordinator] 15-60s ahead of display time.
 */
internal object TtsSubtitleTextDecoder {
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val TIMECODE_RANGE_REGEX =
        Regex("^(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3}).*$")

    fun isImageSubtitleMime(mimeType: String?): Boolean {
        val m = mimeType?.lowercase(Locale.US) ?: return false
        return m == "application/pgs" ||
            m == "application/vobsub" ||
            m == "application/dvbsubs" ||
            m == MimeTypes.APPLICATION_PGS ||
            m == MimeTypes.APPLICATION_VOBSUB ||
            m == MimeTypes.APPLICATION_DVBSUBS
    }

    fun isAssFormat(format: Format?): Boolean {
        if (format == null) return false
        if (format.sampleMimeType.equals(MimeTypes.TEXT_SSA, ignoreCase = true)) return true
        val codecs = format.codecs?.lowercase(Locale.US) ?: return false
        return codecs.contains("s_text/ass") ||
            codecs.contains("s_text/ssa") ||
            codecs.contains("x-ssa")
    }

    fun decodeRawSample(
        mimeType: String?,
        codecs: String?,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        startTimeUs: Long
    ): SubtitleSyncCue? {
        if (length <= 0 || offset < 0 || offset + length > bytes.size) return null
        if (isImageSubtitleMime(mimeType)) return null

        val mimeLower = mimeType?.lowercase(Locale.US).orEmpty()
        val codecLower = codecs?.lowercase(Locale.US).orEmpty()
        val startMs = (startTimeUs / 1_000L).coerceAtLeast(0L)

        if (mimeLower == MimeTypes.APPLICATION_TX3G) {
            val text = decodeTx3g(bytes, offset, length)
            return text?.let {
                SubtitleSyncCue(startTimeMs = startMs, endTimeMs = startMs + DEFAULT_EMBEDDED_DURATION_MS, text = it)
            }
        }

        val inflated = maybeInflateZlib(bytes, offset, length)
        val rawStr = if (inflated != null) {
            String(inflated, Charsets.UTF_8)
        } else {
            String(bytes, offset, length, Charsets.UTF_8)
        }.trim()
        if (rawStr.isEmpty()) return null

        val isAss = mimeLower == MimeTypes.TEXT_SSA ||
            codecLower.contains("s_text/ass") ||
            codecLower.contains("s_text/ssa") ||
            codecLower.contains("x-ssa")
        if (isAss) {
            val decoded = decodeAssPacketWithDuration(rawStr) ?: return null
            val durMs = decoded.second ?: DEFAULT_EMBEDDED_DURATION_MS
            return SubtitleSyncCue(
                startTimeMs = startMs,
                endTimeMs = startMs + durMs,
                text = decoded.first
            )
        }

        val decodedPlain = cleanPlainSubtitlePacketWithDuration(rawStr) ?: return null
        val durMs = decodedPlain.second ?: DEFAULT_EMBEDDED_DURATION_MS
        return SubtitleSyncCue(
            startTimeMs = startMs,
            endTimeMs = startMs + durMs,
            text = decodedPlain.first
        )
    }

    internal fun decodeAssPacket(raw: String): String? =
        decodeAssPacketWithDuration(raw)?.first

    internal fun decodeAssPacketWithDuration(raw: String): Pair<String, Long?>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
            val body = trimmed.substringAfter(':').trimStart()
            val firstComma = body.indexOf(',')
            val firstToken = if (firstComma > 0) body.substring(0, firstComma).trim() else ""
            // MatroskaExtractor SSA_PREFIX uses "Dialogue: Start,End,ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text" (10 commas).
            // Standard ASS Dialogue line uses "Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text" (9 commas).
            val isMkvPrefixed = firstToken.contains(':')
            val commasBeforeText = if (isMkvPrefixed) 10 else 9
            val durationMs = extractAssDurationMs(body, startCol = if (isMkvPrefixed) 0 else 1)
            val dialoguePart = substringAfterNthComma(body, commasBeforeText) ?: body
            val clean = AssDialogueParser.parseMatroskaDialogueText(dialoguePart)
            return clean.takeIf { it.isNotBlank() }?.let { it to durationMs }
        }
        val parsed = AssDialogueParser.parseMatroskaDialogueText(trimmed)
        return parsed.takeIf { it.isNotBlank() }?.let { it to null }
    }

    private fun extractAssDurationMs(body: String, startCol: Int): Long? {
        val parts = body.split(',', limit = startCol + 3)
        if (parts.size <= startCol + 1) return null
        val startMs = parseAssClockMs(parts[startCol]) ?: return null
        val endMs = parseAssClockMs(parts[startCol + 1]) ?: return null
        return (endMs - startMs).takeIf { it >= 200L }
    }

    private fun parseAssClockMs(raw: String): Long? {
        val clean = raw.trim()
        val colonParts = clean.split(':')
        if (colonParts.size == 4) {
            val h = colonParts[0].toLongOrNull() ?: return null
            val m = colonParts[1].toLongOrNull() ?: return null
            val s = colonParts[2].toLongOrNull() ?: return null
            val cs = colonParts[3].take(2).padEnd(2, '0').toLongOrNull() ?: return null
            return ((h * 3600L + m * 60L + s) * 1000L) + (cs * 10L)
        }
        if (colonParts.size == 3) {
            val h = colonParts[0].toLongOrNull() ?: return null
            val m = colonParts[1].toLongOrNull() ?: return null
            val secDot = colonParts[2].split('.', ',')
            val s = secDot[0].toLongOrNull() ?: return null
            val frac = secDot.getOrNull(1)?.take(2)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
            return ((h * 3600L + m * 60L + s) * 1000L) + (frac * 10L)
        }
        return null
    }

    private fun substringAfterNthComma(s: String, count: Int): String? {
        var found = 0
        for (i in s.indices) {
            if (s[i] == ',' && ++found == count) {
                return s.substring(i + 1)
            }
        }
        return null
    }

    internal fun decodeTx3g(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length < 3) return null
        val textLen = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        if (textLen <= 0 || textLen > length - 2) return null
        val decoded = String(bytes, offset + 2, textLen, Charsets.UTF_8)
        return cleanPlainSubtitlePacket(decoded)
    }

    internal fun cleanPlainSubtitlePacket(raw: String): String? =
        cleanPlainSubtitlePacketWithDuration(raw)?.first

    internal fun cleanPlainSubtitlePacketWithDuration(raw: String): Pair<String, Long?>? {
        val normalized = raw
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val rawLines = normalized.split('\n').map { it.trim() }
        val kept = ArrayList<String>(rawLines.size)
        var parsedDurationMs: Long? = null
        var i = 0
        while (i < rawLines.size) {
            val line = rawLines[i]
            if (line.isEmpty() || line.equals("WEBVTT", ignoreCase = true)) {
                i++
                continue
            }
            val tcMatch = TIMECODE_RANGE_REGEX.matchEntire(line)
            if (tcMatch != null) {
                val startTc = ((tcMatch.groupValues[1].toLong() * 3600L +
                    tcMatch.groupValues[2].toLong() * 60L +
                    tcMatch.groupValues[3].toLong()) * 1000L) + tcMatch.groupValues[4].toLong()
                val endTc = ((tcMatch.groupValues[5].toLong() * 3600L +
                    tcMatch.groupValues[6].toLong() * 60L +
                    tcMatch.groupValues[7].toLong()) * 1000L) + tcMatch.groupValues[8].toLong()
                if (endTc > startTc + 100L) {
                    parsedDurationMs = endTc - startTc
                }
                i++
                continue
            }
            // Strip SRT/VTT cue index line (e.g. "1" in MatroskaExtractor SUBRIP_PREFIX) ONLY when
            // immediately followed by a timestamp range line, preserving numeric spoken dialogue.
            val nextLine = rawLines.getOrNull(i + 1)
            if (line.all { it.isDigit() } && nextLine != null && TIMECODE_RANGE_REGEX.matches(nextLine)) {
                i++
                continue
            }
            val stripped = line.replace(HTML_TAG_REGEX, "").trim()
            if (stripped.isNotEmpty()) {
                kept.add(stripped)
            }
            i++
        }
        if (kept.isEmpty()) return null
        return kept.joinToString("\n") to parsedDurationMs
    }

    private fun maybeInflateZlib(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < 6) return null
        val cmf = bytes[offset].toInt() and 0xFF
        val flg = bytes[offset + 1].toInt() and 0xFF
        if (cmf != 0x78 || ((cmf shl 8) or flg) % 31 != 0) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArray(max(length * 3, 512))
            val count = inflater.inflate(out)
            if (count > 0) out.copyOf(count) else null
        } catch (_: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    private const val DEFAULT_EMBEDDED_DURATION_MS = 3_600L
}

/**
 * Buffers per-track demuxed embedded subtitle cues (from chunk 0 / LoaderThread ahead of playback)
 * and streams only the currently selected internal subtitle track into [TtsSubtitleCoordinator].
 */
internal class TtsEmbeddedCueBridge(
    private val ttsCoordinator: TtsSubtitleCoordinator
) {
    private data class TrackBuffer(
        val key: String,
        val rawTrackId: Int,
        var formatId: String?,
        var language: String?,
        var label: String?,
        val cues: ArrayList<SubtitleSyncCue> = ArrayList(256),
        val seenKeys: HashSet<Long> = HashSet(256)
    )

    private data class TrackSelectionTarget(
        val trackId: String?,
        val language: String?,
        val label: String?,
        val uiIndex: Int
    )

    private val lock = Any()
    private val tracks = LinkedHashMap<String, TrackBuffer>()
    private var selectionTarget: TrackSelectionTarget? = null
    private var resolvedBridgeKey: String? = null
    private var lastSyncedKey: String? = null
    private var generation: Long = 0L

    fun onStreamReset() {
        synchronized(lock) {
            generation += 1L
            tracks.clear()
            selectionTarget = null
            resolvedBridgeKey = null
            lastSyncedKey = null
        }
    }

    fun registerTrackFormat(rawTrackId: Int, format: Format): String {
        val bridgeKey = "emb:${rawTrackId}:${format.id.orEmpty()}"
        var flushToCoordinator: List<SubtitleSyncCue>? = null
        val resolvedKeyForTrack = synchronized(lock) {
            val matchedExisting = tracks.values.firstOrNull { buf ->
                (!format.id.isNullOrEmpty() && buf.formatId == format.id) || buf.rawTrackId == rawTrackId
            }
            val existing = matchedExisting ?: tracks.getOrPut(bridgeKey) {
                TrackBuffer(
                    key = bridgeKey,
                    rawTrackId = rawTrackId,
                    formatId = format.id,
                    language = format.language,
                    label = format.label
                )
            }
            existing.formatId = format.id ?: existing.formatId
            existing.language = format.language ?: existing.language
            existing.label = format.label ?: existing.label

            val newlyResolved = resolveMatchingTrackKeyLocked()
            if (newlyResolved != null && newlyResolved != lastSyncedKey) {
                lastSyncedKey = newlyResolved
                resolvedBridgeKey = newlyResolved
                flushToCoordinator = tracks[newlyResolved]?.cues?.toList()
            }
            existing.key
        }
        flushToCoordinator?.let { cues ->
            if (cues.isNotEmpty()) {
                ttsCoordinator.updateTimelineCues(cues)
            }
        }
        return resolvedKeyForTrack
    }

    fun onLibassDialogueExtracted(rawTrackIdStr: String?, cue: SubtitleSyncCue) {
        val targetKey: String
        synchronized(lock) {
            val matched = if (!rawTrackIdStr.isNullOrEmpty()) {
                tracks.values.firstOrNull { buf ->
                    buf.formatId == rawTrackIdStr || buf.rawTrackId.toString() == rawTrackIdStr
                }
            } else {
                tracks.values.firstOrNull()
            }
            if (matched != null) {
                targetKey = matched.key
            } else {
                val numericId = rawTrackIdStr?.toIntOrNull() ?: -1
                val synthKey = if (numericId >= 0) "emb:$numericId:${rawTrackIdStr.orEmpty()}" else "ass:${rawTrackIdStr.orEmpty()}"
                tracks.getOrPut(synthKey) {
                    TrackBuffer(
                        key = synthKey,
                        rawTrackId = numericId,
                        formatId = rawTrackIdStr,
                        language = null,
                        label = null
                    )
                }
                targetKey = synthKey
            }
        }
        onCueDemuxed(targetKey, cue)
    }

    fun onCueDemuxed(bridgeKey: String, cue: SubtitleSyncCue) {
        val genSnapshot: Long
        var isActiveTrack = false
        synchronized(lock) {
            genSnapshot = generation
            val buffer = tracks[bridgeKey] ?: return
            val dedupKey = (cue.startTimeMs shl 16) xor (cue.text.hashCode().toLong() and 0xFFFFL)
            if (!buffer.seenKeys.add(dedupKey)) return
            if (buffer.cues.size >= MAX_CUES_PER_TRACK) {
                val drop = buffer.cues.size / 4
                for (i in 0 until drop) {
                    val old = buffer.cues.removeAt(0)
                    val oldKey = (old.startTimeMs shl 16) xor (old.text.hashCode().toLong() and 0xFFFFL)
                    buffer.seenKeys.remove(oldKey)
                }
            }
            val last = buffer.cues.lastOrNull()
            if (last != null && abs(last.startTimeMs - cue.startTimeMs) <= 80L && last.text == cue.text) {
                return
            }
            buffer.cues.add(cue)
            if (resolvedBridgeKey == null && selectionTarget != null) {
                resolvedBridgeKey = resolveMatchingTrackKeyLocked()
            }
            isActiveTrack = (resolvedBridgeKey == bridgeKey)
        }
        if (isActiveTrack && genSnapshot == generation) {
            ttsCoordinator.appendStreamingTimelineCue(cue)
        }
    }

    fun selectInternalTrack(track: TrackInfo?) {
        var cuesToFlush: List<SubtitleSyncCue>? = null
        var shouldClear = false
        synchronized(lock) {
            if (track == null) {
                shouldClear = selectionTarget != null || lastSyncedKey != null
                selectionTarget = null
                resolvedBridgeKey = null
                lastSyncedKey = null
            } else {
                val target = TrackSelectionTarget(
                    trackId = track.trackId,
                    language = track.language,
                    label = track.name,
                    uiIndex = track.index
                )
                val sameTarget = selectionTarget == target
                selectionTarget = target
                val matchedKey = resolveMatchingTrackKeyLocked()
                resolvedBridgeKey = matchedKey
                if (matchedKey != null && (matchedKey != lastSyncedKey || !sameTarget)) {
                    lastSyncedKey = matchedKey
                    cuesToFlush = tracks[matchedKey]?.cues?.toList() ?: emptyList()
                } else if (matchedKey == null && !sameTarget) {
                    lastSyncedKey = null
                    shouldClear = true
                }
            }
        }
        if (shouldClear) {
            ttsCoordinator.clearStreamingTimelineCues()
        }
        cuesToFlush?.let { cues ->
            if (cues.isNotEmpty()) {
                ttsCoordinator.updateTimelineCues(cues)
            }
        }
    }

    private fun resolveMatchingTrackKeyLocked(): String? {
        val target = selectionTarget ?: return null
        if (tracks.isEmpty()) return null
        val targetId = target.trackId?.trim()

        if (!targetId.isNullOrEmpty()) {
            tracks.values.firstOrNull { buf ->
                buf.formatId == targetId || buf.rawTrackId.toString() == targetId
            }?.let { return it.key }

            tracks.values.firstOrNull { buf ->
                val fmtId = buf.formatId ?: return@firstOrNull false
                fmtId.endsWith(":$targetId") || targetId.endsWith(":$fmtId")
            }?.let { return it.key }
        }

        val targetLang = normalizeLang(target.language)
        if (targetLang != null) {
            val langMatches = tracks.values.filter { normalizeLang(it.language) == targetLang }
            if (langMatches.size == 1) return langMatches[0].key
            val targetLabel = target.label?.trim()
            if (!targetLabel.isNullOrEmpty()) {
                langMatches.firstOrNull { it.label?.trim().equals(targetLabel, ignoreCase = true) }
                    ?.let { return it.key }
            }
            if (langMatches.isNotEmpty()) return langMatches[0].key
        }

        val ordered = tracks.values.toList()
        return ordered.getOrNull(target.uiIndex)?.key
    }

    private fun normalizeLang(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.US)?.substringBefore('-')?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_CUES_PER_TRACK = 8_000
    }
}

/**
 * Wraps any Media3 [ExtractorsFactory] (default player OR libass/DolbyVision player) to observe
 * demuxed embedded subtitle packets early during chunk buffering without altering Media3 outputs.
 */
internal class TtsSubtitlePrefetchExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val bridge: TtsEmbeddedCueBridge
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index ->
            TtsObservingExtractor(extractors[index], bridge)
        }
}

private class TtsObservingExtractor(
    private val delegate: Extractor,
    private val bridge: TtsEmbeddedCueBridge
) : RewrappableExtractor {
    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = TtsObservingExtractor(inner, bridge)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.getSniffFailureDetails()
    override fun init(output: ExtractorOutput) = delegate.init(TtsObservingExtractorOutput(output, bridge))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

private class TtsObservingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val bridge: TtsEmbeddedCueBridge
) : ExtractorOutput {
    private val textTracks = HashMap<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) {
            textTracks.getOrPut(id) { TtsObservingTextTrackOutput(output, id, bridge) }
        } else {
            output
        }
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

private class TtsObservingTextTrackOutput(
    private val delegate: TrackOutput,
    private val rawTrackId: Int,
    private val bridge: TtsEmbeddedCueBridge
) : TrackOutput {
    private val cueDecoder = CueDecoder()
    private var format: Format? = null
    private var bridgeKey: String? = null
    private var buffered = ByteArray(0)
    private var start = 0
    private var end = 0

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        this.format = format
        if (!TtsSubtitleTextDecoder.isImageSubtitleMime(format.sampleMimeType)) {
            this.bridgeKey = bridge.registerTrackFormat(rawTrackId, format)
        }
        delegate.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || isImageSub()) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val bytes = ByteArray(length)
        val read = input.read(bytes, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            return if (allowEndOfInput) C.RESULT_END_OF_INPUT else throw EOFException()
        }
        if (read > 0) {
            append(bytes, 0, read)
            delegate.sampleData(ParsableByteArray(bytes, read), read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && !isImageSub() && length > 0) {
            append(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        observe(timeUs, size, offset)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        start = (end - offset).coerceIn(start, end)
        if (start == end) {
            start = 0
            end = 0
        }
    }

    private fun isImageSub(): Boolean = TtsSubtitleTextDecoder.isImageSubtitleMime(format?.sampleMimeType)

    private fun observe(timeUs: Long, size: Int, offset: Int) {
        if (timeUs == C.TIME_UNSET || size <= 0 || isImageSub()) return
        val key = bridgeKey ?: return
        val sampleStart = end - offset - size
        if (sampleStart < start || sampleStart + size > end) return
        val currentFormat = format ?: return

        if (currentFormat.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
            val decoded = runCatching { cueDecoder.decode(timeUs, buffered, sampleStart, size) }.getOrNull()
            if (decoded != null) {
                val text = decoded.cues
                    .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n")
                if (text.isNotEmpty()) {
                    val startMs = ((decoded.startTimeUs.takeIf { it != C.TIME_UNSET } ?: timeUs) / 1_000L)
                        .coerceAtLeast(0L)
                    val endUs = when {
                        decoded.endTimeUs != C.TIME_UNSET -> decoded.endTimeUs
                        decoded.durationUs != C.TIME_UNSET -> (decoded.startTimeUs.takeIf { it != C.TIME_UNSET } ?: timeUs) + decoded.durationUs
                        else -> C.TIME_UNSET
                    }
                    val endMs = if (endUs == C.TIME_UNSET || endUs <= timeUs) {
                        startMs + 3_600L
                    } else {
                        max(startMs + 250L, endUs / 1_000L)
                    }
                    bridge.onCueDemuxed(key, SubtitleSyncCue(startTimeMs = startMs, endTimeMs = endMs, text = text))
                }
            }
            return
        }

        val cue = TtsSubtitleTextDecoder.decodeRawSample(
            mimeType = currentFormat.sampleMimeType,
            codecs = currentFormat.codecs,
            bytes = buffered,
            offset = sampleStart,
            length = size,
            startTimeUs = timeUs
        )
        if (cue != null) {
            bridge.onCueDemuxed(key, cue)
        }
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val retained = end - start
        val required = retained + length
        if (buffered.size - end < length) {
            buffered.copyInto(buffered, 0, start, end)
            start = 0
            end = retained
            if (buffered.size < required) {
                buffered = buffered.copyOf(max(required, max(buffered.size * 2, 256)))
            }
        }
        data.copyInto(buffered, end, offset, offset + length)
        end += length
    }
}
