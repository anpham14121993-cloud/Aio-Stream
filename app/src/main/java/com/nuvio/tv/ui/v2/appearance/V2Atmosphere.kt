package com.nuvio.tv.ui.v2.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.SettingsBackground
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.screens.home.PageHeroArtwork
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.v2GlassSource

/** Shared cached artwork source for bounded frosted panels; no video capture. */
@Composable
fun V2Atmosphere(
    modifier: Modifier = Modifier,
    rich: Boolean = true,
    background: SettingsBackground = SettingsBackground.STYLE_DEFAULT
) {
    val cinematic = when (background) {
        SettingsBackground.STYLE_DEFAULT -> LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS
        SettingsBackground.HERO -> true
        else -> false
    }
    val showArtwork = background == SettingsBackground.HERO || (rich && cinematic)
    val liquid = background == SettingsBackground.PURE_LIQUID_DARK ||
        (background == SettingsBackground.STYLE_DEFAULT && !cinematic)
    val base = when (background) {
        SettingsBackground.MIDNIGHT -> Color(0xFF0B0C0F)
        SettingsBackground.CHARCOAL -> Color(0xFF181A1F)
        else -> Color(0xFF07080A)
    }
    val neutral = NuvioTheme.currentTheme == com.nuvio.tv.domain.model.AppTheme.GLASS
    val clearGlass = com.nuvio.tv.ui.v2.components.usesClearGlassTheme()
    val accent = if (neutral) Color(0xFFB7BFCC) else NuvioTheme.colors.Secondary
    val context = LocalContext.current
    val artwork = HeroBackdropState.pageArtwork
    val settledArtwork by produceState<PageHeroArtwork?>(null, artwork, showArtwork) {
        value = null
        if (showArtwork && artwork.url != null) {
            delay(250)
            value = artwork
        }
    }
    val request = remember(context, artwork, settledArtwork, showArtwork) {
        artwork.url?.takeIf { showArtwork && settledArtwork == artwork }?.let {
            ImageRequest.Builder(context).data(it).size(960, 540)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    Box(modifier.fillMaxSize().background(base).v2GlassSource().v2StaticBackdrop()) {
        if (background == SettingsBackground.POSTERS) {
            com.nuvio.tv.ui.v2.profile.ProfilePosterWallBackground(pageBackground = true)
        }
        if (showArtwork && request != null) {
            key(artwork) {
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                    alpha = .82f, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val ambience = Brush.radialGradient(listOf(accent.copy(alpha = if(rich) .14f else .05f),Color.Transparent),
                center=Offset(size.width*.77f,size.height*.15f), radius=size.width*.8f)
            val shade = Brush.horizontalGradient(if (background == SettingsBackground.POSTERS)
                listOf(Color(0x8808090C), Color(0x2008090C), Color(0x4508090C))
                else if (clearGlass) listOf(Color(0xED08090C), Color(0x4408090C), Color(0x7408090C))
                else listOf(Color(0xED08090C),Color(0x4408090C),Color(0x7808090C)))
            val folds = (0..2).map { i -> Path().apply {
                val x=size.width*(.50f+i*.14f)
                moveTo(x,size.height)
                cubicTo(x-size.width*.24f,size.height*.56f,x+size.width*.34f,size.height*.38f,x+size.width*.12f,0f)
                lineTo(x+size.width*.17f,0f)
                cubicTo(x+size.width*.4f,size.height*.36f,x-size.width*.17f,size.height*.6f,x+size.width*.08f,size.height)
                close()
            } }
            val silk = Brush.linearGradient(listOf(Color.Transparent,Color(0x16B7BFCC),Color(0x080B0C0F)))
            onDrawBehind {
                if (!clearGlass) drawRect(ambience)
                if (liquid) folds.forEach { drawPath(it,silk) }
                drawRect(shade)
            }
        })
    }
}

/** Paints a still page background once and reuses it while the page above it moves. Keep it inside
 * [v2GlassSource] so frosted panels sample the same kept layer. */
fun Modifier.v2StaticBackdrop(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
