package com.nuvio.tv.core.assessment

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssessmentWordingTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(dir: File): Map<String, String> {
        val file = File(dir, "strings.xml")
        if (!file.isFile) return emptyMap()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val defaultVi = strings(File(res, "values"))

    @Test
    fun `suggestions read as suggestions in Vietnamese`() {
        assertEquals("Đề xuất: %1\$s", defaultVi["assessment_suggested"])
        assertEquals("Bật · Tự động, tối đa ≈%1\$s", defaultVi["assessment_value_on_auto"])
        assertEquals("Tự động (theo Tăng tốc tự động)", defaultVi["assessment_value_connections_auto"])
        assertFalse(defaultVi.getValue("assessment_grounds_vod_auto").contains("right now"))
    }

    @Test
    fun `vietnamese resources retain all assessment wording keys`() {
        val keys = listOf(
            "assessment_suggested",
            "assessment_value_connections_auto",
            "assessment_grounds_connections_auto",
            "assessment_grounds_connections_auto_one",
            "assessment_grounds_connections_auto_untested",
            "assessment_grounds_strip_sdr"
        )
        for (dir in listOf(File(res, "values"), File(res, "values-vi"))) {
            val local = strings(dir)
            for (key in keys) assertTrue("${dir.name} $key", key in local)
            assertEquals(dir.name, 1, local.getValue("assessment_suggested").split("%1\$s").size - 1)
            val grounds = local.getValue("assessment_grounds_connections_auto")
            for (ph in listOf("%1\$s", "%2\$s", "%3\$d", "%4\$d")) assertTrue("${dir.name} $ph", grounds.contains(ph))
        }
    }
}
