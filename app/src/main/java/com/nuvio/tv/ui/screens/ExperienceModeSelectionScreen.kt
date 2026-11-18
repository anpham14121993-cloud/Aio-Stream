package com.nuvio.tv.ui.screens

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.ui.tvhybrid.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.HomeLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ExperienceModeSelectionViewModel @Inject constructor(
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {
    suspend fun choose(mode: ExperienceMode) {
        experienceModeDataStore.setMode(mode)
        // Both modes use the Modern layout; setting it here marks
        // has_chosen_layout so the layout-picker step is skipped.
        layoutPreferenceDataStore.setLayout(HomeLayout.MODERN)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExperienceModeSelectionScreen(
    onContinue: (ExperienceMode) -> Unit,
    viewModel: ExperienceModeSelectionViewModel = hiltViewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val essentialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        essentialFocusRequester.requestFocus()
    }

    fun choose(mode: ExperienceMode) {
        coroutineScope.launch {
            viewModel.choose(mode)
            onContinue(mode)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val isCompact = maxWidth < 700.dp
        val horizontalPadding = if (isCompact) 20.dp else 64.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = if (isCompact) 28.dp else NuvioTheme.spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.experience_mode_choose_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.experience_mode_choose_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 640.dp)
            )
            Spacer(modifier = Modifier.height(if (isCompact) 24.dp else 36.dp))
            if (isCompact) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    ExperienceModeCard(
                        title = stringResource(R.string.experience_mode_essential),
                        subtitle = stringResource(R.string.experience_mode_essential_card_subtitle),
                        icon = Icons.Default.VideoSettings,
                        onClick = { choose(ExperienceMode.ESSENTIAL) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(essentialFocusRequester)
                    )
                    ExperienceModeCard(
                        title = stringResource(R.string.experience_mode_advanced),
                        subtitle = stringResource(R.string.experience_mode_advanced_card_subtitle),
                        icon = Icons.Default.Tune,
                        onClick = { choose(ExperienceMode.ADVANCED) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ExperienceModeCard(
                        title = stringResource(R.string.experience_mode_essential),
                        subtitle = stringResource(R.string.experience_mode_essential_card_subtitle),
                        icon = Icons.Default.VideoSettings,
                        onClick = { choose(ExperienceMode.ESSENTIAL) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(essentialFocusRequester)
                    )
                    ExperienceModeCard(
                        title = stringResource(R.string.experience_mode_advanced),
                        subtitle = stringResource(R.string.experience_mode_advanced_card_subtitle),
                        icon = Icons.Default.Tune,
                        onClick = { choose(ExperienceMode.ADVANCED) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExperienceModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 148.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}
