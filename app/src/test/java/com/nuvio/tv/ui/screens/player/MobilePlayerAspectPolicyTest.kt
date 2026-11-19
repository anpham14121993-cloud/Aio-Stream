package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class MobilePlayerAspectPolicyTest {

    @Test
    fun mobileNextAspectMode_cyclesFitFillZoomMatchingNuvioMobile() {
        assertEquals(
            AspectMode.STRETCH,
            nextAspectMode(AspectMode.ORIGINAL, useMobileResizeModes = true)
        )
        assertEquals(
            AspectMode.FULL_SCREEN,
            nextAspectMode(AspectMode.STRETCH, useMobileResizeModes = true)
        )
        assertEquals(
            AspectMode.ORIGINAL,
            nextAspectMode(AspectMode.FULL_SCREEN, useMobileResizeModes = true)
        )
        assertEquals(
            AspectMode.ORIGINAL,
            nextAspectMode(AspectMode.CINEMA_ZOOM, useMobileResizeModes = true)
        )
    }

    @Test
    fun mobileExoResizeMode_mapsFitFillZoomToMedia3ResizeModes() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = false,
                aspectMode = AspectMode.ORIGINAL,
                useMobileResizeModes = true
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = false,
                aspectMode = AspectMode.STRETCH,
                useMobileResizeModes = true
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = false,
                aspectMode = AspectMode.FULL_SCREEN,
                useMobileResizeModes = true
            )
        )
    }

    @Test
    fun mobileMpvPanscan_matchesNuvioMobileValues() {
        assertEquals(0.0, mobileMpvPanscan(AspectMode.ORIGINAL), 0.0001)
        assertEquals(1.0, mobileMpvPanscan(AspectMode.STRETCH), 0.0001)
        assertEquals(0.5, mobileMpvPanscan(AspectMode.FULL_SCREEN), 0.0001)
        assertEquals(0.5, mobileMpvPanscan(AspectMode.SLIGHT_ZOOM), 0.0001)
    }
}
