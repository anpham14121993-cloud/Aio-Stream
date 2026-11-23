package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Power
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToWatchParty: () -> Unit,
    showPlugins: Boolean,
    initialFocusRequester: FocusRequester?
) {
    var lastOpenedRow by rememberSaveable { mutableStateOf(ContentDiscoveryRow.ADDONS) }
    ContentDiscoveryHub(
        onNavigateToAddons = { lastOpenedRow = ContentDiscoveryRow.ADDONS; onNavigateToAddons() },
        onNavigateToPlugins = { lastOpenedRow = ContentDiscoveryRow.PLUGINS; onNavigateToPlugins() },
        onNavigateToWatchParty = { lastOpenedRow = ContentDiscoveryRow.WATCH_PARTY; onNavigateToWatchParty() },
        showPlugins = showPlugins,
        initialFocusRequester = initialFocusRequester,
        initialFocusRow = contentDiscoveryFocusRow(lastOpenedRow, showPlugins)
    )
}

@Composable
private fun ContentDiscoveryHub(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToWatchParty: () -> Unit,
    showPlugins: Boolean,
    initialFocusRequester: FocusRequester?,
    initialFocusRow: ContentDiscoveryRow
) {
    fun Modifier.initialFocus(row: ContentDiscoveryRow): Modifier =
        if (initialFocusRequester != null && row == initialFocusRow) focusRequester(initialFocusRequester) else this

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_content_discovery),
            subtitle = stringResource(R.string.settings_content_discovery_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.settings_content_discovery_addons_subtitle),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.Extension,
                modifier = Modifier.initialFocus(ContentDiscoveryRow.ADDONS)
            )
            if (showPlugins) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.settings_content_discovery_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Power,
                    modifier = Modifier.initialFocus(ContentDiscoveryRow.PLUGINS)
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.party_title),
                subtitle = stringResource(R.string.party_settings_row_subtitle),
                onClick = onNavigateToWatchParty,
                leadingIcon = Icons.Default.Groups,
                modifier = Modifier.initialFocus(ContentDiscoveryRow.WATCH_PARTY)
            )
        }
    }
}

internal enum class ContentDiscoveryRow { ADDONS, PLUGINS, WATCH_PARTY }

/** The row that takes focus when the page comes back: the one the user last opened. */
internal fun contentDiscoveryFocusRow(lastOpened: ContentDiscoveryRow, showPlugins: Boolean): ContentDiscoveryRow =
    if (lastOpened == ContentDiscoveryRow.PLUGINS && !showPlugins) ContentDiscoveryRow.ADDONS else lastOpened
