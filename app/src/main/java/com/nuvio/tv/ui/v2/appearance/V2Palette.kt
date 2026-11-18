package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColorPalette

/** Applies Obsidian Monolith charcoal/slate dark surfaces while keeping the chosen accent. */
fun v2Palette(accent: ThemeColorPalette, appearance: V2AppearancePreferences, theme: AppTheme? = null): ThemeColorPalette {
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    val palette = accent.copy(
        background = if (cinematic) Color(0xFF0B0C0F) else Color(0xFF050608),
        backgroundElevated = if (cinematic) Color(0xFF16181D) else Color(0xFF0E1014),
        backgroundCard = if (cinematic) Color(0xFF1F2127) else Color(0xFF141519),
        surface = if (cinematic) Color(0xFF16181D) else Color(0xFF0E1014),
        surfaceVariant = if (cinematic) Color(0xFF2B2E36) else Color(0xFF1E2026),
        panel = if (cinematic) Color(0xFF131418) else Color(0xFF0B0C0F),
        overlay = Color(0xE8000000),
        field = if (cinematic) Color(0xFF24272E) else Color(0xFF181A20),
        menu = if (cinematic) Color(0xFF181A20) else Color(0xFF101216),
        modal = if (cinematic) Color(0xFF1B1D24) else Color(0xFF121418),
        playerOverlay = Color(0xE8000000),
        focusBackground = accent.secondary.copy(alpha = 0.16f)
    )
    return if (theme == AppTheme.GLASS && cinematic) palette.copy(
        background = Color(0xFF08090C),
        backgroundElevated = Color(0xFF141519),
        backgroundCard = Color(0xFF1C1E24),
        surface = Color(0xFF141519),
        surfaceVariant = Color(0xFF282B33),
        panel = Color(0xFF101115),
        field = Color(0xFF22242A),
        menu = Color(0xFF16181D),
        modal = Color(0xFF181A20),
        focusBackground = Color.White.copy(alpha = .10f)
    ) else palette
}
