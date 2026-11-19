package com.nuvio.tv.ui.monolith

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioAvatar
import com.nuvio.tv.ui.v2.components.NuvioGlassSurface
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.navigation.LocalV2TopChrome
import kotlinx.coroutines.delay
import java.util.Date

val LocalMonolithBottomInset = compositionLocalOf { 0.dp }
val LocalMonolithTopInset = compositionLocalOf { 0.dp }

private object MonolithChromePalette {
    val DeepObsidian = Color(0xFF0A0B0F)
    val DockObsidian = Color(0xFF111319)
    val SlateElevated = Color(0xFF1D2029)
    val ActivePillStart = Color(0xFF2E333F)
    val ActivePillEnd = Color(0xFF1A1D24)
    val SilverRimTop = Color(0x52F4F7FB)
    val SilverRimBottom = Color(0x14B7BFCC)
    val SilverBright = Color(0xFFF4F7FB)
    val SilverMuted = Color(0xFF9EA6B5)
    val AccentSteel = Color(0xFFCED5E2)

    val HairlineBorderBrush = Brush.verticalGradient(
        listOf(SilverRimTop, SilverRimBottom)
    )
    val ActivePillBrush = Brush.horizontalGradient(
        listOf(ActivePillStart, ActivePillEnd)
    )
}

/**
 * Adaptive Tri-Mode Obsidian Monolith navigation chrome for AioStream:
 * - TV / TV Box: Floating Top Lens Pill with D-Pad Up/Down focus sync & clock
 * - Mobile: Slim Top Brand/Search Header + Floating Bottom Obsidian Glass Dock
 * - Tablet: Floating Left Micro-Rail capsule supporting both Touch and D-Pad
 */
@Composable
fun MonolithAdaptiveNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    when (LocalDeviceFormFactor.current) {
        DeviceFormFactor.TV -> MonolithTopLensNavigation(
            navigationContent = navigationContent,
            currentRoute = currentRoute,
            rootRoutes = rootRoutes,
            items = items,
            selectedRoute = selectedRoute,
            profile = profile,
            onNavigate = onNavigate,
            onSwitchProfile = onSwitchProfile,
            onExit = onExit
        )
        DeviceFormFactor.MOBILE -> MonolithBottomDockNavigation(
            navigationContent = navigationContent,
            currentRoute = currentRoute,
            rootRoutes = rootRoutes,
            items = items,
            selectedRoute = selectedRoute,
            profile = profile,
            onNavigate = onNavigate,
            onSwitchProfile = onSwitchProfile,
            onExit = onExit
        )
        DeviceFormFactor.TABLET -> MonolithMicroRailNavigation(
            navigationContent = navigationContent,
            currentRoute = currentRoute,
            rootRoutes = rootRoutes,
            items = items,
            selectedRoute = selectedRoute,
            profile = profile,
            onNavigate = onNavigate,
            onSwitchProfile = onSwitchProfile,
            onExit = onExit
        )
    }
}

@Composable
private fun MonolithBrandEmblem(
    compact: Boolean = false,
    showText: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 28.dp else 34.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF31353F), MonolithChromePalette.DeepObsidian)
                    )
                )
                .border(0.75.dp, MonolithChromePalette.HairlineBorderBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.arc_launcher_foreground),
                contentDescription = "AioStream",
                modifier = Modifier.size(if (compact) 22.dp else 26.dp),
                contentScale = ContentScale.Fit
            )
        }
        if (showText) {
            Text(
                text = "AIOSTREAM",
                color = MonolithChromePalette.SilverBright,
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (compact) 1.4.sp else 2.0.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MonolithNavItemIcon(
    item: DrawerItem,
    tint: Color,
    size: Dp
) {
    when {
        item.icon != null -> Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size)
        )
        item.iconRes != null -> Icon(
            painter = rememberRawSvgPainter(item.iconRes, size),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size)
        )
        else -> Icon(
            imageVector = when (item.route) {
                Screen.Search.route -> Icons.Rounded.Search
                Screen.Settings.route -> Icons.Rounded.Settings
                else -> Icons.Rounded.Home
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun rememberFormattedClock(): String {
    val context = LocalContext.current
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        val formatter = DateFormat.getTimeFormat(context)
        while (true) {
            clockText = formatter.format(Date())
            val nowMs = System.currentTimeMillis()
            val delayMs = (60_000L - (nowMs % 60_000L)).coerceIn(5_000L, 60_000L)
            delay(delayMs)
        }
    }
    return clockText
}

@Composable
private fun MonolithTopLensNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    val visible = currentRoute in rootRoutes
    val contentFocus = remember { FocusRequester() }
    val routes = remember(items) { items.map { it.route } }
    val routeFocus = remember(routes) { routes.associateWith { FocusRequester() } }
    var lastRoute by remember { mutableStateOf(selectedRoute) }
    var navFocused by remember { mutableStateOf(false) }
    var returnToContent by remember { mutableStateOf(false) }
    val entryFocus = routeFocus[lastRoute] ?: routeFocus[selectedRoute] ?: routeFocus.values.firstOrNull()
    val clockText = rememberFormattedClock()
    val inputMode = LocalInputInteractionMode.current

    BackHandler(enabled = visible) {
        if (inputMode == InputInteractionMode.TOUCH) {
            if (currentRoute != Screen.Home.route) {
                onNavigate(Screen.Home.route)
            } else {
                onExit()
            }
        } else {
            if (navFocused || entryFocus == null) onExit() else runCatching { entryFocus.requestFocus() }
        }
    }
    LaunchedEffect(returnToContent, currentRoute) {
        if (returnToContent) {
            repeat(2) { withFrameNanos { } }
            runCatching { contentFocus.requestFocus() }
            returnToContent = false
        }
    }

    val topContentInset by animateDpAsState(
        targetValue = if (visible && currentRoute != Screen.Home.route) 66.dp else 0.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "tv_lens_top_inset"
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = topContentInset)
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusProperties {
                    this.onExit = {
                        if (visible && requestedFocusDirection == FocusDirection.Up && entryFocus != null) {
                            runCatching { entryFocus.requestFocus() }
                        }
                    }
                }
                .focusGroup()
        ) {
            CompositionLocalProvider(
                LocalContentFocusRequester provides contentFocus,
                LocalSidebarExpanded provides navFocused,
                LocalV2TopChrome provides visible,
                LocalMonolithTopInset provides if (visible) 66.dp else 0.dp,
                LocalMonolithBottomInset provides 0.dp
            ) {
                navigationContent(true)
            }
        }

        if (visible) {
            // Subtle obsidian top fade so floating lens stays readable over hero cinema art.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MonolithChromePalette.DeepObsidian.copy(alpha = 0.72f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 14.dp)
                    .onFocusChanged { navFocused = it.hasFocus }
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Brand slab
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MonolithChromePalette.DockObsidian.copy(alpha = 0.86f))
                        .border(0.75.dp, MonolithChromePalette.HairlineBorderBrush, CircleShape)
                        .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonolithBrandEmblem(compact = true, showText = true)
                }

                // Floating Obsidian Monolith Center Lens Pill
                NuvioGlassSurface(
                    role = GlassRole.NAVIGATION,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        items(items, key = { it.route }) { item ->
                            val isSelected = item.route == selectedRoute
                            var itemFocused by remember { mutableStateOf(false) }
                            val scale by animateFloatAsState(
                                targetValue = if (itemFocused) 1.04f else 1f,
                                animationSpec = tween(140, easing = FastOutSlowInEasing),
                                label = "lens_tab_scale"
                            )
                            val tint by animateColorAsState(
                                targetValue = when {
                                    itemFocused -> Color.White
                                    isSelected -> MonolithChromePalette.SilverBright
                                    else -> MonolithChromePalette.SilverMuted
                                },
                                animationSpec = tween(140),
                                label = "lens_tab_tint"
                            )

                            Box(
                                modifier = Modifier
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .then(routeFocus[item.route]?.let { Modifier.focusRequester(it) } ?: Modifier)
                                    .focusProperties { down = contentFocus }
                                    .onFocusChanged {
                                        itemFocused = it.isFocused
                                        if (it.isFocused) lastRoute = item.route
                                    }
                                    .nuvioV2Focus(itemFocused, RoundedCornerShape(999.dp))
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        when {
                                            itemFocused -> Brush.horizontalGradient(
                                                listOf(Color(0xFF3E4453), Color(0xFF272B35))
                                            )
                                            isSelected -> MonolithChromePalette.ActivePillBrush
                                            else -> Brush.horizontalGradient(
                                                listOf(Color.Transparent, Color.Transparent)
                                            )
                                        }
                                    )
                                    .then(
                                        if (isSelected && !itemFocused) {
                                            Modifier.border(
                                                0.75.dp,
                                                MonolithChromePalette.SilverRimTop,
                                                RoundedCornerShape(999.dp)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        if (item.route == selectedRoute) {
                                            returnToContent = true
                                        } else {
                                            onNavigate(item.route)
                                            returnToContent = true
                                        }
                                    }
                                    .focusable()
                                    .semantics { selected = isSelected }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        MonolithNavItemIcon(item = item, tint = tint, size = 14.dp)
                                        Text(
                                            text = item.label,
                                            color = tint,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected || itemFocused) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Clock + Profile Slab
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MonolithChromePalette.DockObsidian.copy(alpha = 0.86f))
                        .border(0.75.dp, MonolithChromePalette.HairlineBorderBrush, CircleShape)
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (clockText.isNotEmpty()) {
                        Text(
                            text = clockText,
                            color = MonolithChromePalette.SilverMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (profile != null) {
                        var profileFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .focusProperties { down = contentFocus }
                                .onFocusChanged { profileFocused = it.isFocused }
                                .clip(CircleShape)
                                .clickable(onClick = onSwitchProfile)
                                .focusable()
                        ) {
                            NuvioAvatar(
                                avatar = profile,
                                size = 30.dp,
                                focused = profileFocused
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonolithBottomDockNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    val visible = currentRoute in rootRoutes
    val contentFocus = remember { FocusRequester() }
    val dockItems = items

    BackHandler(enabled = visible) {
        if (currentRoute != Screen.Home.route) {
            onNavigate(Screen.Home.route)
        } else {
            onExit()
        }
    }

    val density = LocalDensity.current
    var measuredBottomInset by remember { mutableStateOf(88.dp) }

    val bottomPadding by animateDpAsState(
        targetValue = if (visible && currentRoute != Screen.Home.route) measuredBottomInset else 0.dp,
        animationSpec = tween(180),
        label = "mobile_bottom_inset"
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (visible && currentRoute != Screen.Home.route) Modifier.statusBarsPadding() else Modifier)
                .padding(bottom = bottomPadding)
                .focusRequester(contentFocus)
        ) {
            CompositionLocalProvider(
                LocalContentFocusRequester provides contentFocus,
                LocalSidebarExpanded provides false,
                LocalV2TopChrome provides visible,
                LocalMonolithTopInset provides 0.dp,
                LocalMonolithBottomInset provides if (visible) measuredBottomInset else 0.dp
            ) {
                navigationContent(false)
            }
        }

        if (visible) {
            // Bottom Floating Obsidian Glass Dock
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged {
                        if (it.height > 0) measuredBottomInset = with(density) { it.height.toDp() }
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MonolithChromePalette.DeepObsidian.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MonolithChromePalette.DockObsidian.copy(alpha = 0.94f),
                                    MonolithChromePalette.DeepObsidian.copy(alpha = 0.97f)
                                )
                            )
                        )
                        .border(0.75.dp, MonolithChromePalette.HairlineBorderBrush, RoundedCornerShape(22.dp))
                ) {
                    val tightWidth = maxWidth < 340.dp && dockItems.size > 5
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (tightWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dockItems.forEach { item ->
                            val isSelected = item.route == selectedRoute
                            val tint by animateColorAsState(
                                targetValue = if (isSelected) MonolithChromePalette.SilverBright else MonolithChromePalette.SilverMuted,
                                animationSpec = tween(160),
                                label = "mobile_dock_tint"
                            )
                            Box(
                                modifier = Modifier
                                    .then(if (tightWidth) Modifier.width(62.dp) else Modifier.weight(1f))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isSelected) MonolithChromePalette.ActivePillBrush
                                        else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                    )
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                0.6.dp,
                                                MonolithChromePalette.SilverRimTop,
                                                RoundedCornerShape(16.dp)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onNavigate(item.route) }
                                    )
                                    .padding(vertical = 7.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    MonolithNavItemIcon(item = item, tint = tint, size = 20.dp)
                                    Text(
                                        text = item.label,
                                        color = tint,
                                        fontSize = if (dockItems.size > 5) 9.5.sp else 10.5.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonolithCircleActionButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (selected) MonolithChromePalette.SlateElevated
                else MonolithChromePalette.DockObsidian.copy(alpha = 0.85f)
            )
            .border(
                0.75.dp,
                if (selected) MonolithChromePalette.SilverBright.copy(alpha = 0.45f)
                else MonolithChromePalette.SilverRimBottom,
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun MonolithMicroRailNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    val visible = currentRoute in rootRoutes
    val contentFocus = remember { FocusRequester() }
    val routes = remember(items) { items.map { it.route } }
    val routeFocus = remember(routes) { routes.associateWith { FocusRequester() } }
    var railFocused by remember { mutableStateOf(false) }

    val startPadding by animateDpAsState(
        targetValue = if (visible) 90.dp else 0.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "tablet_rail_inset"
    )

    BackHandler(enabled = visible) {
        if (currentRoute != Screen.Home.route) {
            onNavigate(Screen.Home.route)
        } else {
            onExit()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = startPadding)
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup()
        ) {
            CompositionLocalProvider(
                LocalContentFocusRequester provides contentFocus,
                LocalSidebarExpanded provides railFocused,
                LocalV2TopChrome provides false,
                LocalMonolithTopInset provides 0.dp,
                LocalMonolithBottomInset provides 0.dp
            ) {
                navigationContent(true)
            }
        }

        if (visible) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                    .padding(start = 12.dp, top = 16.dp, bottom = 16.dp)
                    .width(72.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MonolithChromePalette.DockObsidian.copy(alpha = 0.94f),
                                MonolithChromePalette.DeepObsidian.copy(alpha = 0.96f)
                            )
                        )
                    )
                    .border(0.75.dp, MonolithChromePalette.HairlineBorderBrush, RoundedCornerShape(26.dp))
                    .onFocusChanged { railFocused = it.hasFocus }
                    .focusGroup()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 14.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    MonolithBrandEmblem(compact = true, showText = false)

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(items, key = { it.route }) { item ->
                            val isSelected = item.route == selectedRoute
                            var focused by remember { mutableStateOf(false) }
                            val tint by animateColorAsState(
                                targetValue = when {
                                    focused || isSelected -> MonolithChromePalette.SilverBright
                                    else -> MonolithChromePalette.SilverMuted
                                },
                                animationSpec = tween(140),
                                label = "rail_tint"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(routeFocus[item.route]?.let { Modifier.focusRequester(it) } ?: Modifier)
                                    .focusProperties { right = contentFocus }
                                    .onFocusChanged { focused = it.isFocused }
                                    .nuvioV2Focus(focused, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        when {
                                            focused || isSelected -> MonolithChromePalette.ActivePillBrush
                                            else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                        }
                                    )
                                    .clickable { onNavigate(item.route) }
                                    .focusable()
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                MonolithNavItemIcon(item = item, tint = tint, size = 22.dp)
                                Text(
                                    text = item.label,
                                    color = tint,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (profile != null) {
                        var profileFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .onFocusChanged { profileFocused = it.isFocused }
                                .clip(CircleShape)
                                .clickable(onClick = onSwitchProfile)
                                .focusable()
                        ) {
                            NuvioAvatar(avatar = profile, size = 36.dp, focused = profileFocused)
                        }
                    }
                }
            }
        }
    }
}
