package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.nuvio.tv.ui.tvhybrid.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.server.AddonWebConfigMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EssentialAddonSetupScreen(
    onSkip: () -> Unit,
    viewModel: AddonManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val isCompact = maxWidth < 720.dp
        val horizontalPadding = if (isCompact) 20.dp else 58.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = if (isCompact) 24.dp else 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.essential_addon_setup_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.essential_addon_setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            val urlInstallCard: @Composable (Modifier) -> Unit = { cardMod ->
                Card(
                    modifier = cardMod.heightIn(min = 200.dp),
                    colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_install_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                            color = NuvioTheme.colors.TextPrimary,
                        )
                        BasicTextField(
                            value = uiState.installUrl,
                            onValueChange = viewModel::onInstallUrlChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NuvioTheme.spacing.xxxl)
                                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(10.dp))
                                .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp)),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    viewModel.installAddon()
                                    keyboardController?.hide()
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = NuvioTheme.colors.TextPrimary,
                                textDirection = TextDirection.Content
                            ),
                            cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (uiState.installUrl.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.addon_install_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NuvioTheme.colors.TextTertiary
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Button(
                            onClick = {
                                viewModel.installAddon()
                                keyboardController?.hide()
                            },
                            enabled = !uiState.isInstalling,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Secondary,
                                contentColor = NuvioTheme.colors.OnSecondary,
                                focusedContainerColor = NuvioTheme.colors.SecondaryVariant
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = if (uiState.isInstalling) {
                                    stringResource(R.string.addon_installing)
                                } else {
                                    stringResource(R.string.addon_install_btn)
                                }
                            )
                        }
                        AnimatedVisibility(visible = uiState.error != null) {
                            Text(
                                text = uiState.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.Error
                            )
                        }
                    }
                }
            }
            val phoneQrCard: @Composable (Modifier) -> Unit = { cardMod ->
                Card(
                    modifier = cardMod.heightIn(min = 200.dp),
                    colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = NuvioTheme.colors.Primary
                        )
                        Text(
                            text = stringResource(R.string.addon_manage_from_phone_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                            color = NuvioTheme.colors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.addon_manage_addons_only_from_phone_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        Button(
                            onClick = { viewModel.startQrMode(AddonWebConfigMode.ADDONS_ONLY) },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundElevated,
                                contentColor = NuvioTheme.colors.TextPrimary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Icon(imageVector = Icons.Default.QrCode2, contentDescription = null)
                            Text(text = stringResource(R.string.essential_addon_show_qr))
                        }
                    }
                }
            }

            if (isCompact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    urlInstallCard(Modifier.fillMaxWidth())
                    phoneQrCard(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    urlInstallCard(Modifier.weight(1f))
                    phoneQrCard(Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            Button(
                onClick = onSkip,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(
                    text = stringResource(R.string.essential_addon_continue_for_now),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }
        }

        if (uiState.isQrModeActive || uiState.pendingChange != null) {
            if (uiState.isQrModeActive) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = stringResource(R.string.addon_qr_addons_only_scan_instruction),
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }

            uiState.pendingChange?.let { pending ->
                ConfirmAddonChangesDialog(
                    pendingChange = pending,
                    onConfirm = viewModel::confirmPendingChange,
                    onReject = viewModel::rejectPendingChange
                )
            }
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}
