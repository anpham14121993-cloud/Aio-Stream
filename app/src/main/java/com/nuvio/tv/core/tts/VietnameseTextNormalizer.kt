package com.nuvio.tv.core.tts

import java.text.Normalizer

data class ParsedSubtitleCueText(
    val speakableText: String,
    val tagGender: SpeakerGender = SpeakerGender.UNSPECIFIED,
    val delayMs: Long = 0L,
    val emotion: String? = null
)

/**
 * Subtitle cue sanitizer and Vietnamese text normalizer (`Lb36` / `Lmm` parity).
 * Extracts `[M]` / `[F]` / `[D:ms]` / `[E:emotion]` metadata tags, strips formatting markup,
 * and converts numbers, dates, times, percentages, currencies, and common abbreviations to spoken Vietnamese.
 */
object VietnameseTextNormalizer {
    private val LEADING_TAGS_REGEX =
        Regex("""^\s*(?:\[([MFmf])]\s*)?(?:\[D:(\d+)]\s*)?(?:\[E:([a-zA-Z\s\-]+)]\s*)?""")
    private val HTML_TAG_REGEX = Regex("""<[^>]*>""")
    private val ASS_OVERRIDE_REGEX = Regex("""\{\\[^}]*}""")
    private val STAGE_NOTES_REGEX =
        Regex("""\[.*?]|\(.*?\)|\{.*?}|【.*?】|（.*?）""")
    private val MUSIC_SYMBOLS_REGEX = Regex("""[♪♫♩♬✦★☆•▪▫►◄◆◇■□●○※→←↑↓]""")
    private val SPEAKER_DASH_PREFIX_REGEX = Regex("""(?:^|\n)\s*[-–—]+\s*""")
    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

    private val DATE_DMY_REGEX = Regex("""\b(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})\b""")
    private val DATE_DM_REGEX = Regex("""\b(\d{1,2})/(\d{1,2})\b""")
    private val TIME_HMS_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")
    private val TIME_VH_REGEX = Regex("""\b(\d{1,2})[hH](\d{2})?\b""")
    private val CURRENCY_PREFIX_REGEX = Regex("""([\$€£¥₫])\s*(\d+(?:[.,]\d+)?)""")
    private val CURRENCY_SUFFIX_REGEX =
        Regex("""(\d+(?:[.,]\d+)?)\s*(USD|EUR|VND|VNĐ|đ|₫|triệu|tỷ|k)\b""", RegexOption.IGNORE_CASE)
    private val PERCENT_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
    private val DECIMAL_REGEX = Regex("""\b(\d+)[.,](\d+)\b""")
    private val NUMBER_REGEX = Regex("""(-?\d+)""")

    private val ONES = arrayOf(
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    )

    private val UNIT_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("""\bTP\.?\s*HCM\b""", RegexOption.IGNORE_CASE) to "thành phố Hồ Chí Minh",
        Regex("""\bTp\.\s*""", RegexOption.IGNORE_CASE) to "thành phố ",
        Regex("""(\d+)\s*km/h\b""", RegexOption.IGNORE_CASE) to "$1 ki lô mét trên giờ",
        Regex("""(\d+)\s*m/s\b""", RegexOption.IGNORE_CASE) to "$1 mét trên giây",
        Regex("""(\d+)\s*°C\b""", RegexOption.IGNORE_CASE) to "$1 độ xê",
        Regex("""(\d+)\s*°F\b""", RegexOption.IGNORE_CASE) to "$1 độ ép",
        Regex("""(\d+)\s*km\b""", RegexOption.IGNORE_CASE) to "$1 ki lô mét",
        Regex("""(\d+)\s*cm\b""", RegexOption.IGNORE_CASE) to "$1 xen ti mét",
        Regex("""(\d+)\s*mm\b""", RegexOption.IGNORE_CASE) to "$1 mi li mét",
        Regex("""(\d+)\s*kg\b""", RegexOption.IGNORE_CASE) to "$1 ki lô gam",
        Regex("""(\d+)\s*GB\b""", RegexOption.IGNORE_CASE) to "$1 ghi ga bai",
        Regex("""(\d+)\s*MB\b""", RegexOption.IGNORE_CASE) to "$1 mê ga bai",
        Regex("""(\d+)\s*KB\b""", RegexOption.IGNORE_CASE) to "$1 ki lô bai",
        Regex("""(\d+)\s*kHz\b""", RegexOption.IGNORE_CASE) to "$1 ki lô héc",
        Regex("""(\d+)\s*Hz\b""", RegexOption.IGNORE_CASE) to "$1 héc",
        Regex("""\bDr\.\s*""", RegexOption.IGNORE_CASE) to "Bác sĩ ",
        Regex("""\bMr\.\s*""", RegexOption.IGNORE_CASE) to "Ông ",
        Regex("""\bMrs\.\s*""", RegexOption.IGNORE_CASE) to "Bà ",
        Regex("""\bMs\.\s*""", RegexOption.IGNORE_CASE) to "Cô ",
        Regex("""\s*&\s*""") to " và ",
        Regex("""\s*\+\s*""") to " cộng "
    )

    private val LOANWORD_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("""\bOK\b""", RegexOption.IGNORE_CASE) to "ô kê",
        Regex("""\bFBI\b""") to "ép bi ai",
        Regex("""\bCIA\b""") to "xi ai ây",
        Regex("""\bDNA\b""", RegexOption.IGNORE_CASE) to "đi en ây",
        Regex("""\bCEO\b""") to "xi i ô",
        Regex("""\bVIP\b""") to "víp",
        Regex("""\bTV\b""") to "ti vi",
        Regex("""\bAI\b""") to "ây ai",
        Regex("""\bGPS\b""") to "gi pi ét"
    )

    fun parseAndCleanCue(rawText: String): ParsedSubtitleCueText {
        if (rawText.isBlank()) return ParsedSubtitleCueText("")

        var tagGender = SpeakerGender.UNSPECIFIED
        var delayMs = 0L
        var emotion: String? = null
        var body = rawText

        val tagMatch = LEADING_TAGS_REGEX.find(body)
        if (tagMatch != null && tagMatch.value.isNotEmpty()) {
            tagGender = when (tagMatch.groupValues[1].uppercase()) {
                "M" -> SpeakerGender.MALE
                "F" -> SpeakerGender.FEMALE
                else -> SpeakerGender.UNSPECIFIED
            }
            delayMs = tagMatch.groupValues[2].toLongOrNull() ?: 0L
            emotion = tagMatch.groupValues[3].trim().takeIf { it.isNotEmpty() }
            body = body.substring(tagMatch.range.last + 1)
        }

        val cleaned = cleanSubtitleFormatting(body)
        if (cleaned.isEmpty()) {
            return ParsedSubtitleCueText("", tagGender, delayMs, emotion)
        }

        val normalized = normalizeForSpeech(cleaned)
        return ParsedSubtitleCueText(
            speakableText = normalized,
            tagGender = tagGender,
            delayMs = delayMs,
            emotion = emotion
        )
    }

    fun cleanSubtitleFormatting(rawText: String): String {
        var text = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        text = ASS_OVERRIDE_REGEX.replace(text, " ")
        text = HTML_TAG_REGEX.replace(text, " ")
        text = STAGE_NOTES_REGEX.replace(text, " ")
        text = MUSIC_SYMBOLS_REGEX.replace(text, " ")
        text = SPEAKER_DASH_PREFIX_REGEX.replace(text, ", ")
        text = text.replace('\n', ' ').replace('\r', ' ')
        text = text.replace("…", "...").replace('“', '"').replace('”', '"')
        text = MULTI_WHITESPACE_REGEX.replace(text, " ").trim().trimStart(',', ' ')

        val hasAlphanumeric = text.any { it.isLetterOrDigit() }
        return if (hasAlphanumeric) text else ""
    }

    fun normalizeForSpeech(text: String): String {
        if (text.isBlank()) return ""
        var out = Normalizer.normalize(text, Normalizer.Form.NFC)

        // Normalize ellipsis before numeric rules
        out = out.replace("...", " , ")

        // Dates dd/mm/yyyy or dd-mm-yyyy
        out = DATE_DMY_REGEX.replace(out) { m ->
            val d = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val y = m.groupValues[3].toIntOrNull()
            if (d != null && mo != null && y != null && d in 1..31 && mo in 1..12) {
                val fullYear = if (y < 100) 2000 + y else y
                "ngày $d tháng $mo năm $fullYear"
            } else {
                m.value
            }
        }

        // Dates dd/mm
        out = DATE_DM_REGEX.replace(out) { m ->
            val d = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            if (d != null && mo != null && d in 1..31 && mo in 1..12) {
                "ngày $d tháng $mo"
            } else {
                m.value
            }
        }

        // Times HH:MM(:SS) and XhYY
        out = TIME_HMS_REGEX.replace(out) { m ->
            val h = m.groupValues[1]
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val sec = m.groupValues[3].toIntOrNull()
            if (sec != null) "$h giờ $min phút $sec giây" else "$h giờ $min phút"
        }
        out = TIME_VH_REGEX.replace(out) { m ->
            val h = m.groupValues[1]
            val min = m.groupValues[2]
            if (min.isNotEmpty()) "$h giờ $min" else "$h giờ"
        }

        // Currencies
        out = CURRENCY_PREFIX_REGEX.replace(out) { m ->
            val unit = when (m.groupValues[1]) {
                "$" -> "đô la"
                "€" -> "ơ rô"
                "£" -> "bảng Anh"
                "¥" -> "yên"
                else -> "đồng"
            }
            "${m.groupValues[2]} $unit"
        }
        out = CURRENCY_SUFFIX_REGEX.replace(out) { m ->
            val unit = when (m.groupValues[2].lowercase()) {
                "usd" -> "đô la"
                "eur" -> "ơ rô"
                "k" -> "nghìn"
                "triệu" -> "triệu"
                "tỷ" -> "tỷ"
                else -> "đồng"
            }
            "${m.groupValues[1]} $unit"
        }

        // Percentages and decimals
        out = PERCENT_REGEX.replace(out) { m -> "${m.groupValues[1]} phần trăm" }
        out = DECIMAL_REGEX.replace(out) { m -> "${m.groupValues[1]} phẩy ${m.groupValues[2]}" }

        // Units, titles, symbols, loanwords
        for ((regex, replacement) in UNIT_REPLACEMENTS) {
            out = regex.replace(out, replacement)
        }
        for ((regex, replacement) in LOANWORD_REPLACEMENTS) {
            out = regex.replace(out, replacement)
        }

        // Numbers -> Vietnamese spoken words
        out = NUMBER_REGEX.replace(out) { m ->
            val value = m.groupValues[1].toLongOrNull()
            if (value != null) numberToVietnameseWords(value) else m.value
        }

        // Normalize punctuation spacing
        out = out.replace('–', ',').replace('—', ',').replace('/', ' ')
        out = MULTI_WHITESPACE_REGEX.replace(out, " ").trim()
        return out
    }

    /**
     * Converts integers (up to `triệu tỷ`) to natural Vietnamese spoken words.
     */
    fun numberToVietnameseWords(value: Long): String {
        if (value == 0L) return "không"
        if (value < 0L) {
            if (value == Long.MIN_VALUE) return "âm vô cực"
            return "âm ${numberToVietnameseWords(-value)}"
        }

        val scaleNames = arrayOf("", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ")
        val groups = ArrayList<Int>(6)
        var remaining = value
        while (remaining > 0L) {
            groups.add((remaining % 1000L).toInt())
            remaining /= 1000L
        }

        val parts = ArrayList<String>()
        val topIndex = groups.lastIndex
        for (i in topIndex downTo 0) {
            val triplet = groups[i]
            if (triplet == 0) continue
            val isHighest = (i == topIndex)
            val tripletText = readTriplet(triplet, readZeroHundreds = !isHighest)
            val scale = scaleNames.getOrElse(i) { "" }
            if (scale.isNotEmpty()) {
                parts.add("$tripletText $scale")
            } else {
                parts.add(tripletText)
            }
        }
        return parts.joinToString(" ")
    }

    private fun readTriplet(value: Int, readZeroHundreds: Boolean): String {
        val hundreds = value / 100
        val tens = (value % 100) / 10
        val units = value % 10
        val out = ArrayList<String>(5)

        if (hundreds > 0 || readZeroHundreds) {
            out.add(ONES[hundreds])
            out.add("trăm")
        }

        when {
            tens > 1 -> {
                out.add(ONES[tens])
                out.add("mươi")
                when (units) {
                    1 -> out.add("mốt")
                    4 -> out.add("tư")
                    5 -> out.add("lăm")
                    in 2..9 -> out.add(ONES[units])
                }
            }
            tens == 1 -> {
                out.add("mười")
                when (units) {
                    5 -> out.add("lăm")
                    in 1..9 -> out.add(ONES[units])
                }
            }
            else -> {
                if (units > 0) {
                    if (out.isNotEmpty()) {
                        out.add("lẻ")
                    }
                    out.add(ONES[units])
                }
            }
        }
        return out.joinToString(" ")
    }
}
