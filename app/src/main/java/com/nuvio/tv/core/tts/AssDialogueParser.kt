package com.nuvio.tv.core.tts

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import java.util.Locale

/**
 * Parses raw Matroska ASS/SSA dialogue blocks and full `.ass` / `.ssa` scripts into [SubtitleSyncCue]s,
 * and merges overlapping simultaneous dialogue entries so dual-line captions are spoken completely.
 */
object AssDialogueParser {
    private val TIME_REGEX = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})""")
    private val OVERRIDE_TAG_REGEX = Regex("""\{[^}]*}""")

    /**
     * Parses a Matroska ASS/SSA `Block` dialogue payload (`ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`).
     */
    fun parseMatroskaDialogueText(rawDialogue: String): String {
        if (rawDialogue.isBlank()) return ""
        var commas = 0
        var textStart = 0
        for (i in rawDialogue.indices) {
            if (rawDialogue[i] == ',') {
                commas++
                if (commas == 8) {
                    textStart = i + 1
                    break
                }
            }
        }
        val payload = if (commas >= 8) rawDialogue.substring(textStart) else rawDialogue
        return cleanAssText(payload)
    }

    /**
     * Parses an external `.ass`/`.ssa` script into sorted, non-empty [SubtitleSyncCue] items.
     */
    fun parseAssScript(scriptText: String): List<SubtitleSyncCue> {
        if (scriptText.isBlank()) return emptyList()
        var startCol = 1
        var endCol = 2
        var textCol = 9
        var inEvents = false
        val rawCues = ArrayList<SubtitleSyncCue>(256)

        for (rawLine in scriptText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                cols.indexOf("start").takeIf { it >= 0 }?.let { startCol = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { endCol = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { textCol = it }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val body = line.substringAfter(':').trimStart()
                val limit = maxOf(startCol, endCol, textCol) + 1
                val parts = body.split(',', limit = limit)
                if (parts.size <= maxOf(startCol, endCol, textCol)) continue
                val startMs = parseAssTimeMs(parts[startCol]) ?: continue
                val endMs = (parseAssTimeMs(parts[endCol]) ?: (startMs + 2500L)).coerceAtLeast(startMs + 250L)
                val cleanText = cleanAssText(parts[textCol])
                if (cleanText.isNotEmpty()) {
                    rawCues += SubtitleSyncCue(startTimeMs = startMs, endTimeMs = endMs, text = cleanText)
                }
            }
        }

        return mergeSimultaneousCues(rawCues)
    }

    fun mergeSimultaneousCues(cues: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return emptyList()

        val merged = ArrayList<SubtitleSyncCue>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startTimeMs - current.startTimeMs <= 120L) {
                val combinedText = if (current.text == next.text) {
                    current.text
                } else {
                    "${current.text}\n${next.text}"
                }
                current = current.copy(
                    endTimeMs = maxOf(current.endTimeMs, next.endTimeMs),
                    text = combinedText
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    internal fun cleanAssText(raw: String): String {
        return raw
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replace("\\h", " ")
            .let { OVERRIDE_TAG_REGEX.replace(it, "") }
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseAssTimeMs(raw: String): Long? {
        val m = TIME_REGEX.find(raw.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val sec = m.groupValues[3].toLongOrNull() ?: return null
        val fracRaw = m.groupValues[4]
        val ms = when (fracRaw.length) {
            1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((h * 3600L + min * 60L + sec) * 1000L) + ms
    }
}
