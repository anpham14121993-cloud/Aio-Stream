package com.nuvio.tv.core.tts

import java.text.Normalizer
import java.util.Locale

/**
 * Pure-Kotlin Vietnamese grapheme-to-phoneme (G2P) converter (`Llj6` parity).
 * Produces stressed IPA with tone digits compatible with `dinhphuongvn/ttsvn` Piper ONNX models,
 * eliminating any native `libespeak-ng.so` dependency.
 */
object VietnameseG2P {
    private val INITIALS: Map<String, String> = mapOf(
        "ngh" to "ŋ",
        "nh" to "ɲ",
        "ng" to "ŋ",
        "gh" to "ɣ",
        "gi" to "z",
        "ch" to "tʃ",
        "kh" to "x",
        "ph" to "f",
        "th" to "t",
        "tr" to "tʃ",
        "qu" to "kw",
        "b" to "b",
        "c" to "k",
        "d" to "z",
        "đ" to "ɗ",
        "g" to "ɣ",
        "h" to "h",
        "k" to "k",
        "l" to "l",
        "m" to "m",
        "n" to "n",
        "p" to "p",
        "q" to "k",
        "r" to "ɹ",
        "s" to "s",
        "t" to "t̪",
        "v" to "v",
        "x" to "s"
    )

    private val SINGLE_VOWELS: Map<String, String> = mapOf(
        "a" to "aː",
        "ă" to "a",
        "â" to "ə",
        "e" to "ɛ",
        "ê" to "e",
        "i" to "i",
        "o" to "ɔ",
        "ô" to "o",
        "ơ" to "əː",
        "u" to "u",
        "ư" to "y",
        "y" to "i"
    )

    private val COMPOUND_VOWELS: Map<String, String> = mapOf(
        "ươi" to "yəj",
        "ười" to "yəj",
        "uôi" to "uəj",
        "uối" to "uəj",
        "iêu" to "iɛw",
        "yêu" to "iɛw",
        "ươu" to "yəw",
        "uây" to "wəɪ",
        "oai" to "waːj",
        "oay" to "waj",
        "oeo" to "wɛw",
        "uêu" to "wew",
        "uya" to "wiə",
        "uyê" to "wiɛ",
        "oao" to "waːw",
        "uyu" to "wiw",
        "ươ" to "yə",
        "uô" to "uə",
        "iê" to "iɛ",
        "yê" to "iɛ",
        "ia" to "iə",
        "ua" to "uə",
        "ưa" to "yə",
        "ai" to "aːj",
        "ao" to "aːw",
        "au" to "aw",
        "âu" to "əw",
        "ay" to "aj",
        "ây" to "əɪ",
        "eo" to "ɛw",
        "êu" to "ew",
        "iu" to "iw",
        "oa" to "waː",
        "oă" to "wa",
        "oe" to "wɛ",
        "oi" to "ɔj",
        "ôi" to "oj",
        "ơi" to "əːj",
        "oo" to "ɔː",
        "ou" to "ɔw",
        "ôô" to "oː",
        "ui" to "uj",
        "ưi" to "yj",
        "uy" to "wi",
        "uê" to "we",
        "uơ" to "wəː",
        "uâ" to "wə",
        "ưu" to "yw"
    )

    private val CODAS: Map<String, String> = mapOf(
        "nh" to "ɲ",
        "ng" to "ŋ",
        "ch" to "c",
        "c" to "k",
        "m" to "m",
        "n" to "n",
        "p" to "p",
        "t" to "t̪"
    )

    private val TONE_MARKS: Map<Char, String> = mapOf(
        '\u0300' to "2", // huyền
        '\u0301' to "ɜ", // sắc
        '\u0309' to "4", // hỏi
        '\u0303' to "5", // ngã
        '\u0323' to "6"  // nặng
    )

    private val FUNCTION_WORDS: Set<String> = setOf(
        "và", "là", "thì", "mà", "của", "cho", "để", "với", "như", "nếu",
        "vì", "bởi", "từ", "tới", "khi", "nhưng", "hoặc", "ở", "bị", "sẽ"
    )

    private val LEADING_PUNCT_REGEX = Regex("""^([^\p{L}\p{N}]+)(.*)$""")
    private val TRAILING_PUNCT_REGEX = Regex("""^(.*?)([^\p{L}\p{N}]+)$""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun textToIpa(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val tokens = trimmed.split(WHITESPACE_REGEX)
        val result = ArrayList<String>(tokens.size)

        for (rawToken in tokens) {
            if (rawToken.isEmpty()) continue
            var token = rawToken
            var leadingPunct = ""
            var trailingPunct = ""

            LEADING_PUNCT_REGEX.find(token)?.let { m ->
                leadingPunct = filterPunctuation(m.groupValues[1])
                token = m.groupValues[2]
            }
            if (token.isNotEmpty()) {
                TRAILING_PUNCT_REGEX.find(token)?.let { m ->
                    token = m.groupValues[1]
                    trailingPunct = filterPunctuation(m.groupValues[2])
                }
            }

            if (token.isEmpty()) {
                val p = leadingPunct + trailingPunct
                if (p.isNotEmpty()) result.add(p)
                continue
            }

            val lower = token.lowercase(Locale.ROOT)
            val ipa = if ('-' in lower) {
                lower.split('-')
                    .mapNotNull { part -> convertSingleWord(part).takeIf { it.isNotEmpty() } }
                    .joinToString(" ")
            } else {
                convertSingleWord(lower)
            }

            if (ipa.isNotEmpty()) {
                result.add(buildString {
                    if (leadingPunct.isNotEmpty()) append(leadingPunct)
                    append(ipa)
                    if (trailingPunct.isNotEmpty()) append(trailingPunct)
                })
            } else {
                val p = leadingPunct + trailingPunct
                if (p.isNotEmpty()) result.add(p)
            }
        }

        return result.joinToString(" ")
    }

    internal fun convertSingleWord(word: String): String {
        if (word.isBlank()) return ""

        val nfd = Normalizer.normalize(word, Normalizer.Form.NFD)
        var tone = ""
        val baseBuilder = StringBuilder(nfd.length)

        for (ch in nfd) {
            val mappedTone = TONE_MARKS[ch]
            if (mappedTone != null) {
                if (tone.isEmpty()) tone = mappedTone
            } else {
                baseBuilder.append(ch)
            }
        }

        val toneless = Normalizer.normalize(baseBuilder.toString(), Normalizer.Form.NFC)
        val isFunctionWord = word in FUNCTION_WORDS

        var initialKey = ""
        var remainder = toneless

        if (toneless.startsWith("gi")) {
            initialKey = "gi"
            val afterGi = toneless.substring(2)
            remainder = when {
                afterGi.isEmpty() -> "i"
                afterGi.startsWith("ê") -> "i$afterGi"
                SINGLE_VOWELS.containsKey(afterGi.substring(0, 1)) -> afterGi
                else -> "i$afterGi"
            }
        } else {
            for (len in 3 downTo 1) {
                if (toneless.length >= len) {
                    val candidate = toneless.substring(0, len)
                    if (INITIALS.containsKey(candidate)) {
                        initialKey = candidate
                        remainder = toneless.substring(len)
                        break
                    }
                }
            }
        }

        var (vowelKey, codaKey) = splitRime(remainder)

        if (initialKey == "qu" && (vowelKey == "ô" || vowelKey == "ốc" || (vowelKey == "ô" && codaKey == "c"))) {
            initialKey = "q"
            vowelKey = "uô"
        }

        var vowelIpa = COMPOUND_VOWELS[vowelKey]
        if (vowelIpa == null) {
            vowelIpa = buildString {
                for (ch in vowelKey) {
                    append(SINGLE_VOWELS[ch.toString()] ?: "")
                }
            }
        }

        if (vowelIpa.isBlank() && initialKey.isNotBlank() && remainder.isEmpty()) {
            vowelIpa = "əː"
        }

        if (codaKey == "ch" || codaKey == "nh") {
            vowelIpa = when {
                vowelIpa.endsWith("aː") -> vowelIpa.dropLast(2) + "e-"
                vowelIpa.endsWith("a") -> vowelIpa.dropLast(1) + "e-"
                else -> vowelIpa
            }
        }

        if (vowelKey == "ay" && tone.isNotEmpty()) {
            vowelIpa = "aj"
        }
        if (vowelKey == "âu" && tone.isEmpty()) {
            tone = "1"
        }

        val stress = if (isFunctionWord) "ˌ" else "ˈ"
        var stressedVowel = if (vowelIpa.startsWith("w")) {
            "w$stress" + vowelIpa.substring(1)
        } else {
            "$stress$vowelIpa"
        }

        var offglide = ""
        when {
            stressedVowel.endsWith("j") -> {
                stressedVowel = stressedVowel.dropLast(1)
                offglide = "j"
            }
            stressedVowel.endsWith("w") -> {
                stressedVowel = stressedVowel.dropLast(1)
                offglide = "w"
            }
        }

        var codaIpa = CODAS[codaKey] ?: ""
        if (codaKey == "c" && (vowelKey == "o" || vowelKey == "ô" || vowelKey == "oo")) {
            codaIpa = "k"
        }

        val initialIpa = if (initialKey.isNotEmpty()) INITIALS[initialKey].orEmpty() else ""

        // Fallback if token is unmapped foreign/acronym characters with no Vietnamese phonemes
        if (initialIpa.isEmpty() && vowelIpa.isEmpty() && codaIpa.isEmpty()) {
            return ""
        }

        return buildString {
            append(initialIpa)
            append(stressedVowel)
            if (tone.isNotEmpty()) append(tone)
            if (offglide.isNotEmpty()) append(offglide)
            if (codaIpa.isNotEmpty()) append(codaIpa)
        }
    }

    private fun splitRime(rime: String): Pair<String, String> {
        if (rime.isEmpty()) return "" to ""
        for (codaLen in 2 downTo 1) {
            if (rime.length > codaLen) {
                val codaCandidate = rime.takeLast(codaLen)
                if (CODAS.containsKey(codaCandidate)) {
                    val vowelPart = rime.dropLast(codaLen)
                    if (vowelPart.isNotEmpty() && vowelPart.all { SINGLE_VOWELS.containsKey(it.toString()) }) {
                        return vowelPart to codaCandidate
                    }
                }
            }
        }
        return rime to ""
    }

    internal fun filterPunctuation(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '!' -> sb.append('!')
                '?' -> sb.append('?')
                '.' -> sb.append('.')
                ',', ':', ';' -> sb.append(',')
            }
        }
        return sb.toString()
    }
}
