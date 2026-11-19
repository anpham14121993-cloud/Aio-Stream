package com.nuvio.tv.ui.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArcStartHandoffTest {

    @Test
    fun `only Android 8 to 11 carry the mark in the start window`() {
        assertFalse(ArcStartHandoff.windowCarriesMark(24))
        assertFalse(ArcStartHandoff.windowCarriesMark(25))
        assertTrue(ArcStartHandoff.windowCarriesMark(26))
        assertTrue(ArcStartHandoff.windowCarriesMark(30))
        assertFalse(ArcStartHandoff.windowCarriesMark(31))
        assertFalse(ArcStartHandoff.windowCarriesMark(34))
    }

    @Test
    fun `hand-off starts with the plate at full strength`() {
        assertEquals(1f, ArcStartHandoff.plateFade(0f, handoff = true), 0f)
        assertEquals(0f, ArcStartHandoff.plateFade(0f, handoff = false), 0f)
        assertTrue(ArcStartHandoff.plateFade(0.45f, handoff = false) in 0.4f..0.6f)
        assertEquals(1f, ArcStartHandoff.plateFade(0.9f, handoff = false), 0f)
    }

    @Test
    fun `hand-off fades the whole frame in over 300 ms`() {
        assertEquals(0f, ArcStartHandoff.frameAlpha(0f, handoff = true, reducedMotion = false), 0f)
        assertTrue(ArcStartHandoff.frameAlpha(0.15f, handoff = true, reducedMotion = false) in 0.4f..0.6f)
        assertEquals(1f, ArcStartHandoff.frameAlpha(0.3f, handoff = true, reducedMotion = false), 0f)
        assertEquals(0.3f, ArcStartHandoff.CrossfadeSeconds, 0f)
    }

    @Test
    fun `without a hand-off the frame is full at once unless motion is reduced`() {
        assertEquals(1f, ArcStartHandoff.frameAlpha(0f, handoff = false, reducedMotion = false), 0f)
        assertEquals(0f, ArcStartHandoff.frameAlpha(0f, handoff = false, reducedMotion = true), 0f)
        assertEquals(1f, ArcStartHandoff.frameAlpha(0.3f, handoff = false, reducedMotion = true), 0f)
    }

    @Test
    fun `every start window puts the launcher mark on the intro's logo plate`() {
        val dir = resDir("drawable-v26")
        val windows = dir.listFiles { f -> f.name.matches(Regex("""start_window(_[a-z_]+)?\.xml""")) && f.name != "start_window_stage_fill.xml" }!!
        assertEquals(16, windows.size)
        for (file in windows) {
            val xml = file.readText()
            assertTrue(file.name, xml.contains("@drawable/aio_splash_badge"))
            assertTrue(file.name, xml.contains("""android:gravity="center""""))
        }
    }

    @Test
    fun `each launcher colour opens on its own start window`() {
        val splash = resDir("values-v31").resolve("themes.xml").readText()
        val window = resDir("values").resolve("themes.xml").readText()
        val style = Regex("""<style name="(Theme\.MyApplication\.Launcher[A-Za-z.]*)"[^>]*>\s*(?:<item[^>]*>[^<]*</item>\s*)*?<item name="([^"]+)">@drawable/([a-z_]+)</item>""")
        fun icons(text: String, attr: String) = style.findAll(text).filter { it.groupValues[2] == attr }
            .associate { it.groupValues[1] to it.groupValues[3] }
        val splashIcons = icons(splash, "windowSplashScreenAnimatedIcon")
        val windowArt = icons(window, "android:windowBackground")
        assertEquals(16, splashIcons.size)
        assertEquals(splashIcons.keys, windowArt.keys)
        for ((name, icon) in splashIcons) {
            assertEquals(name, "aio_splash_icon", icon)
            assertTrue(name, windowArt[name]!!.startsWith("start_window"))
        }
    }

    private fun resDir(name: String): File =
        listOf(File("src/main/res", name), File("app/src/main/res", name)).first { it.isDirectory }
}
