package com.nuvio.tv.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseG2PTest {

    @Test
    fun `maps Vietnamese tones accurately`() {
        val huyen = VietnameseG2P.textToIpa("hòa")
        val sac = VietnameseG2P.textToIpa("hóa")
        val hoi = VietnameseG2P.textToIpa("hỏa")
        val nga = VietnameseG2P.textToIpa("hõa")
        val nang = VietnameseG2P.textToIpa("họa")

        assertTrue("huyền tone 2 expected in $huyen", huyen.contains("2"))
        assertTrue("sắc tone ɜ expected in $sac", sac.contains("ɜ"))
        assertTrue("hỏi tone 4 expected in $hoi", hoi.contains("4"))
        assertTrue("ngã tone 5 expected in $nga", nga.contains("5"))
        assertTrue("nặng tone 6 expected in $nang", nang.contains("6"))
    }

    @Test
    fun `applies function word secondary stress and content word primary stress`() {
        val sentence = VietnameseG2P.textToIpa("và đi")
        val tokens = sentence.split(" ")
        assertEquals(2, tokens.size)
        assertTrue("Function word 'và' should use secondary stress ˌ", tokens[0].contains("ˌ"))
        assertTrue("Content word 'đi' should use primary stress ˈ", tokens[1].contains("ˈ"))
    }

    @Test
    fun `handles gi qu and palatal ch nh coda rules`() {
        val gi = VietnameseG2P.textToIpa("giếng")
        assertTrue("Expected z initial + iɛ nucleus in $gi", gi.startsWith("z") && gi.contains("iɛ"))

        val quoc = VietnameseG2P.textToIpa("quốc")
        assertTrue("Expected qu+ô -> k + uə in $quoc", quoc.startsWith("k") && quoc.contains("uə"))

        val anh = VietnameseG2P.textToIpa("anh")
        assertTrue("Expected a before nh -> e- in $anh", anh.contains("e-") && anh.endsWith("ɲ"))
    }

    @Test
    fun `moves offglide after tone and preserves sentence punctuation`() {
        val mai = VietnameseG2P.textToIpa("mái?")
        assertEquals("mˈaːɜj?", mai)

        val dau = VietnameseG2P.textToIpa("đâu.")
        assertTrue("Unaccented âu gets tone 1 and trailing w in $dau", dau.contains("1w."))
    }
}
