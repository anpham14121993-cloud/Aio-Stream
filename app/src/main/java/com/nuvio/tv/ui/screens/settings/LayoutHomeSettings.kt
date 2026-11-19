@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

@Composable
internal fun LayoutHomeLayoutSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    if (uiState.selectedLayout == HomeLayout.CLASSIC) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_classic_focus_gradient),
            subtitle = stringResource(R.string.layout_classic_focus_gradient_sub),
            checked = uiState.classicFocusGradientEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetClassicFocusGradientEnabled(!uiState.classicFocusGradientEnabled))
            }
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.layout_show_hero),
        subtitle = stringResource(R.string.layout_show_hero_sub),
        checked = uiState.heroSectionEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)) }
    )
    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
        Text(
            text = stringResource(R.string.layout_hero_catalogs),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Text(
            text = stringResource(R.string.layout_hero_catalogs_sub),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            uiState.availableCatalogs.forEach { catalog ->
                SettingsToggleRow(
                    title = catalog.name,
                    subtitle = catalog.addonName.takeIf { it.isNotBlank() },
                    checked = catalog.key in uiState.heroCatalogKeys,
                    onToggle = { onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key)) }
                )
            }
        }
    }
}

@Composable
internal fun LayoutHomeContentSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    if (uiState.selectedLayout != HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_addon_name),
            subtitle = stringResource(R.string.layout_addon_name_sub),
            checked = uiState.catalogAddonNameEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)) }
        )
    }
    CalendarComingUpRowSetting()
}

@Composable
internal fun LayoutSidebarSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val v2 = LocalV2Appearance.current
    if (v2 == null) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_modern_sidebar),
            subtitle = stringResource(R.string.layout_modern_sidebar_sub),
            checked = uiState.modernSidebarEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarEnabled(!uiState.modernSidebarEnabled)) }
        )
        if (uiState.modernSidebarEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_modern_sidebar_blur),
                subtitle = stringResource(R.string.layout_modern_sidebar_blur_sub),
                checked = uiState.modernSidebarBlurEnabled,
                onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarBlurEnabled(!uiState.modernSidebarBlurEnabled)) }
            )
        }
    }
    if ((v2 == null && uiState.modernSidebarEnabled) || v2?.navigationStyle == NavigationStyle.FLOATING_SIDEBAR) {
        SettingsToggleRow(
            title = stringResource(if (v2 != null) R.string.layout_hide_side_icons else R.string.layout_hide_floating_pill),
            subtitle = stringResource(if (v2 != null) R.string.layout_hide_side_icons_sub else R.string.layout_hide_floating_pill_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    } else if (v2 == null) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_collapse_sidebar),
            subtitle = stringResource(R.string.layout_collapse_sidebar_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    }
}
