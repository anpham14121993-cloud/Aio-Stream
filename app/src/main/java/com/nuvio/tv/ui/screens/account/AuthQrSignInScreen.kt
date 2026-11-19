@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.tvhybrid.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.ui.support.SupportNuvioDialog
import com.nuvio.tv.ui.support.SupportNuvioViewModel
import com.nuvio.tv.ui.support.SupportStatus
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.SkeletonBar
import com.nuvio.tv.ui.components.rememberShimmerFill
import com.nuvio.tv.ui.monolith.DeviceFormFactor
import com.nuvio.tv.ui.monolith.LocalDeviceFormFactor
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

private val AuthTextPrimary = Color(0xFFF5F7F8)
private val AuthTextSecondary = Color(0xFF969CA3)
private val AuthPaneBackground = Color.White.copy(alpha = 0.022f)
private val AuthPaneBorder = Color.White.copy(alpha = 0.07f)
private val AuthSecondaryButtonBackground = Color.White.copy(alpha = 0.05f)
private val AuthSecondaryButtonBorder = Color.White.copy(alpha = 0.09f)

private enum class AuthLoginMethod {
    EMAIL,
    QR
}

@Composable
fun AuthQrSignInScreen(
    onBackPress: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val fullAccount = uiState.authState as? AuthState.FullAccount
    val isSignedIn = fullAccount != null
    val isOnboardingMode = onContinue != null
    val formFactor = LocalDeviceFormFactor.current
    val isHandheld = formFactor != DeviceFormFactor.TV
    val defaultMethod = when {
        uiState.supportsEmailPassword && !uiState.supportsQrLogin -> AuthLoginMethod.EMAIL
        !uiState.supportsEmailPassword && uiState.supportsQrLogin -> AuthLoginMethod.QR
        isHandheld -> AuthLoginMethod.EMAIL
        else -> AuthLoginMethod.QR
    }
    var selectedMethod by rememberSaveable(
        uiState.supportsEmailPassword,
        uiState.supportsQrLogin,
        isHandheld
    ) {
        mutableStateOf(defaultMethod)
    }
    val useEmailLogin = when {
        !uiState.supportsQrLogin && uiState.supportsEmailPassword -> true
        !uiState.supportsEmailPassword && uiState.supportsQrLogin -> false
        else -> selectedMethod == AuthLoginMethod.EMAIL
    }
    val useQrLogin = uiState.supportsQrLogin && !useEmailLogin
    val isApproved = remember(uiState.qrLoginStatus) {
        uiState.qrLoginStatus?.contains("approved", ignoreCase = true) == true
    }
    var onboardingTransitionHandled by remember(isOnboardingMode) { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    val loginFocusRequester = remember { FocusRequester() }
    val supportViewModel: SupportNuvioViewModel = hiltViewModel()
    val supportState by supportViewModel.uiState.collectAsState()
    var showSupportDialog by remember { mutableStateOf(false) }
    var refocusLogin by remember { mutableStateOf(false) }
    val backupViewModel: com.nuvio.tv.ui.screens.backup.BackupViewModel = hiltViewModel()
    val backupState by backupViewModel.state.collectAsState()
    val restoreFocus = remember { FocusRequester() }
    com.nuvio.tv.ui.screens.backup.ReturnFocusWhenClosed(backupState, restoreFocus)

    fun leaveAuthScreen() {
        exitRequested = true
        viewModel.clearQrLoginSession()
        onBackPress()
    }

    fun continueFromAuthScreen() {
        exitRequested = true
        if (onContinue != null && !isSignedIn) {
            viewModel.continueWithoutAccount()
        }
        viewModel.clearQrLoginSession()
        if (onContinue != null) {
            onContinue()
        } else {
            onBackPress()
        }
    }

    BackHandler {
        leaveAuthScreen()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(useQrLogin, uiState.authState, uiState.isServerConfigured, isSignedIn, uiState.qrLoginCode, uiState.isLoading, uiState.error, exitRequested) {
        if (
            useQrLogin &&
            uiState.isServerConfigured &&
            !exitRequested &&
            uiState.authState !is AuthState.Loading &&
            !isSignedIn &&
            uiState.qrLoginCode.isNullOrBlank() &&
            uiState.error.isNullOrBlank() &&
            !uiState.isLoading
        ) {
            viewModel.startQrLogin()
        }
    }

    LaunchedEffect(useQrLogin, isSignedIn) {
        if (useQrLogin && isSignedIn && !uiState.qrLoginCode.isNullOrBlank()) {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(useQrLogin, isApproved, uiState.isLoading) {
        if (useQrLogin && isApproved && !uiState.isLoading) {
            viewModel.exchangeQrLogin()
        }
    }

    LaunchedEffect(isOnboardingMode, isSignedIn) {
        if (!isOnboardingMode || onboardingTransitionHandled) return@LaunchedEffect
        if (isSignedIn) {
            onboardingTransitionHandled = true
            exitRequested = true
            viewModel.clearQrLoginSession()
            onContinue.invoke()
        }
    }

    val onSelectMethod: (AuthLoginMethod) -> Unit = { method ->
        if (selectedMethod != method) {
            selectedMethod = method
            viewModel.clearError()
            viewModel.clearInfoMessage()
            if (method == AuthLoginMethod.EMAIL) {
                viewModel.clearQrLoginSession()
            }
        }
    }
    val onClearFeedback: () -> Unit = {
        viewModel.clearError()
        viewModel.clearInfoMessage()
    }
    val onRefreshOrSignOut: () -> Unit = {
        if (isSignedIn) {
            showSignOutConfirmation = true
        } else {
            viewModel.startQrLogin()
        }
    }
    val onBackOrContinue: () -> Unit = {
        if (isOnboardingMode) {
            continueFromAuthScreen()
        } else {
            leaveAuthScreen()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .authGradientBackground()
    ) {
        val isCompactLayout = maxWidth < 720.dp || (formFactor == DeviceFormFactor.MOBILE && maxWidth < maxHeight)
        val safePaddingModifier = if (isHandheld) {
            Modifier.statusBarsPadding().navigationBarsPadding()
        } else {
            Modifier
        }

        if (isCompactLayout) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(safePaddingModifier)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(modifier = Modifier.height(28.dp))
                AuthQrBrandPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 440.dp),
                    isSignedIn = isSignedIn,
                    fullAccount = fullAccount,
                    useEmailLogin = useEmailLogin,
                    showRestore = isOnboardingMode && !isSignedIn,
                    onRestore = backupViewModel::openWhereFrom,
                    restoreFocusRequester = restoreFocus,
                    compact = true
                )

                AuthQrLoginPane(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 440.dp)
                        .background(AuthPaneBackground, RoundedCornerShape(18.dp))
                        .border(1.dp, AuthPaneBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    uiState = uiState,
                    isSignedIn = isSignedIn,
                    isOnboardingMode = isOnboardingMode,
                    useEmailLogin = useEmailLogin,
                    selectedMethod = selectedMethod,
                    onSelectMethod = onSelectMethod,
                    isCompactMobile = true,
                    isHandheld = isHandheld,
                    onSignIn = viewModel::signIn,
                    onSignUp = viewModel::signUp,
                    onClearFeedback = onClearFeedback,
                    onRefreshOrSignOut = onRefreshOrSignOut,
                    onBackOrContinue = onBackOrContinue,
                    initialFocusRequester = loginFocusRequester,
                    showSupportButton = supportState.showSupportButton && !(isSignedIn && isOnboardingMode),
                    supporterTier = supportState.tier.takeIf { supportState.status == SupportStatus.SUPPORTER },
                    onSupport = { showSupportDialog = true }
                )
            }
        } else {
            val isNarrowWide = maxWidth < 900.dp
            val horizontalBrandPad = if (maxWidth < 960.dp) 28.dp else 56.dp
            val paneWidth = if (isNarrowWide) 360.dp else 440.dp
            val paneHorizPad = if (isNarrowWide) 24.dp else 40.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(safePaddingModifier)
            ) {
                AuthQrBrandPanel(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = horizontalBrandPad, end = horizontalBrandPad, top = 48.dp, bottom = 32.dp),
                    isSignedIn = isSignedIn,
                    fullAccount = fullAccount,
                    useEmailLogin = useEmailLogin,
                    showRestore = isOnboardingMode && !isSignedIn,
                    onRestore = backupViewModel::openWhereFrom,
                    restoreFocusRequester = restoreFocus,
                    compact = isNarrowWide
                )

                AuthQrLoginPane(
                    modifier = Modifier
                        .width(paneWidth)
                        .fillMaxHeight()
                        .background(AuthPaneBackground)
                        .drawBehind {
                            drawLine(
                                color = AuthPaneBorder,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = paneHorizPad, vertical = 28.dp),
                    uiState = uiState,
                    isSignedIn = isSignedIn,
                    isOnboardingMode = isOnboardingMode,
                    useEmailLogin = useEmailLogin,
                    selectedMethod = selectedMethod,
                    onSelectMethod = onSelectMethod,
                    isCompactMobile = isNarrowWide,
                    isHandheld = isHandheld,
                    onSignIn = viewModel::signIn,
                    onSignUp = viewModel::signUp,
                    onClearFeedback = onClearFeedback,
                    onRefreshOrSignOut = onRefreshOrSignOut,
                    onBackOrContinue = onBackOrContinue,
                    initialFocusRequester = loginFocusRequester,
                    showSupportButton = supportState.showSupportButton && !(isSignedIn && isOnboardingMode),
                    supporterTier = supportState.tier.takeIf { supportState.status == SupportStatus.SUPPORTER },
                    onSupport = { showSupportDialog = true }
                )
            }
        }

        if (BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) {
            ServerOptionsMenuHost(
                viewModel = hiltViewModel(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(if (isHandheld) Modifier.statusBarsPadding() else Modifier)
                    .padding(if (isCompactLayout) 12.dp else 24.dp)
            )
        }
        com.nuvio.tv.ui.screens.backup.BackupPhoneOverlay(backupState, backupViewModel)
    }
    com.nuvio.tv.ui.screens.backup.BackupFlow(backupState, backupViewModel)

    LaunchedEffect(Unit) {
        loginFocusRequester.requestFocusAfterFrames(frames = 3)
    }

    LaunchedEffect(refocusLogin) {
        if (refocusLogin) {
            loginFocusRequester.requestFocusAfterFrames(frames = 3)
            refocusLogin = false
        }
    }

    if (showSupportDialog) {
        SupportNuvioDialog(
            onDismiss = { showSupportDialog = false },
            onSignIn = if (!isSignedIn) {
                {
                    showSupportDialog = false
                    refocusLogin = true
                }
            } else null,
            onContinueWithoutAccount = if (isOnboardingMode && !isSignedIn) {
                {
                    showSupportDialog = false
                    continueFromAuthScreen()
                }
            } else null,
            onSuggestionsHidden = { refocusLogin = true },
            viewModel = supportViewModel
        )
    }

    if (showSignOutConfirmation) {
        AccountSignOutConfirmationDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutConfirmation = false
            },
            onDismiss = { showSignOutConfirmation = false }
        )
    }
}

@Composable
private fun AuthQrBrandPanel(
    modifier: Modifier,
    isSignedIn: Boolean,
    fullAccount: AuthState.FullAccount?,
    useEmailLogin: Boolean,
    showRestore: Boolean = false,
    onRestore: () -> Unit = {},
    restoreFocusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        BrandWordmark(
            contentDescription = stringResource(R.string.cd_nuvio_logo),
            modifier = Modifier.height(if (compact) 46.dp else 60.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(if (compact) 16.dp else 32.dp))
        Text(
            text = stringResource(R.string.auth_qr_tagline),
            modifier = Modifier.widthIn(max = 440.dp),
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
            style = MaterialTheme.typography.displayLarge.copy(
                color = AuthTextPrimary,
                fontSize = if (compact) 26.sp else 40.sp,
                lineHeight = if (compact) 32.sp else 45.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        if (isSignedIn || useEmailLogin) {
            Spacer(modifier = Modifier.height(if (compact) 10.dp else 18.dp))
            Text(
                text = if (isSignedIn) {
                    stringResource(R.string.auth_qr_connected)
                } else {
                    stringResource(R.string.auth_email_hint)
                },
                modifier = Modifier.widthIn(max = 400.dp),
                textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = AuthTextSecondary,
                    fontSize = if (compact) 14.sp else 17.sp,
                    lineHeight = if (compact) 21.sp else 26.sp,
                    fontWeight = FontWeight.Normal
                )
            )
        }
        if (isSignedIn && fullAccount != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = fullAccount.email,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF7CFF9B)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = fullAccount.userId,
                style = MaterialTheme.typography.bodySmall,
                color = AuthTextSecondary
            )
        }
        if (showRestore) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.backup_signin_hint),
                modifier = Modifier.widthIn(max = 400.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = AuthTextSecondary,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Normal
                )
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRestore,
                modifier = if (restoreFocusRequester != null) Modifier.focusRequester(restoreFocusRequester) else Modifier,
                colors = ButtonDefaults.colors(
                    containerColor = AuthSecondaryButtonBackground,
                    focusedContainerColor = Color.White,
                    contentColor = AuthTextPrimary,
                    focusedContentColor = Color.Black
                ),
                border = ButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                        shape = RoundedCornerShape(16.dp)
                    )
                )
            ) {
                Text(stringResource(R.string.backup_signin_button))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthQrLoginPane(
    modifier: Modifier,
    uiState: AccountUiState,
    isSignedIn: Boolean,
    isOnboardingMode: Boolean,
    useEmailLogin: Boolean,
    selectedMethod: AuthLoginMethod,
    onSelectMethod: (AuthLoginMethod) -> Unit,
    isCompactMobile: Boolean,
    isHandheld: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onClearFeedback: () -> Unit,
    onRefreshOrSignOut: () -> Unit,
    onBackOrContinue: () -> Unit,
    initialFocusRequester: FocusRequester,
    showSupportButton: Boolean,
    supporterTier: MemberTier?,
    onSupport: () -> Unit
) {
    val context = LocalContext.current
    var isSignUpMode by rememberSaveable { mutableStateOf(false) }
    val focusEmail = useEmailLogin && uiState.isServerConfigured && !isSignedIn
    val focusMainAction = !focusEmail && uiState.isServerConfigured && !uiState.isLoading
    val showMethodSwitcher = !isSignedIn && uiState.isServerConfigured && uiState.supportsEmailPassword && uiState.supportsQrLogin
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showMethodSwitcher) {
            AuthLoginMethodSwitcher(
                selectedMethod = selectedMethod,
                onSelectMethod = onSelectMethod
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = when {
                isSignedIn -> stringResource(R.string.auth_qr_synced_data)
                !uiState.isServerConfigured -> stringResource(R.string.auth_cloud_not_configured)
                useEmailLogin && isSignUpMode -> stringResource(R.string.auth_email_signup_instruction)
                useEmailLogin -> stringResource(R.string.auth_email_instruction)
                else -> stringResource(R.string.auth_qr_scan_instruction)
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                color = if (!isSignedIn && !uiState.isServerConfigured) AuthTextPrimary else AuthTextSecondary,
                fontSize = if (isCompactMobile) 16.sp else 15.sp,
                lineHeight = 22.sp,
                fontWeight = if (!isSignedIn && !uiState.isServerConfigured) FontWeight.SemiBold else FontWeight.Normal
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (isSignedIn && !isOnboardingMode) {
            AccountConnectedStatsStrip(
                stats = uiState.connectedStats,
                isLoading = uiState.isStatsLoading
            )
        } else if (isSignedIn && isOnboardingMode) {
            StatusPill(
                text = stringResource(R.string.auth_qr_finishing),
                containerColor = AuthSecondaryButtonBackground,
                contentColor = AuthTextSecondary
            )
        } else if (!uiState.isServerConfigured) {
            Text(
                text = stringResource(R.string.auth_cloud_not_configured_desc),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = AuthTextSecondary,
                    lineHeight = 21.sp
                ),
                textAlign = TextAlign.Center
            )
        } else if (useEmailLogin) {
            AuthEmailLoginForm(
                uiState = uiState,
                isSignUpMode = isSignUpMode,
                onToggleSignUp = { next ->
                    if (isSignUpMode != next) {
                        isSignUpMode = next
                        onClearFeedback()
                    }
                },
                onSignIn = onSignIn,
                onSignUp = onSignUp,
                initialFocusRequester = initialFocusRequester
            )
        } else {
            AuthQrCodeBlock(
                uiState = uiState,
                qrSize = if (isCompactMobile) 180.dp else 206.dp
            )
            if (isHandheld && !uiState.qrLoginUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.qrLoginUrl)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White,
                        focusedContainerColor = Color.White,
                        contentColor = Color.Black,
                        focusedContentColor = Color.Black
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(16.dp))
                ) {
                    Text(
                        text = stringResource(R.string.auth_open_browser_signin),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterVertically)
            ) {
                if (isSignedIn || (!useEmailLogin && uiState.supportsQrLogin && uiState.isServerConfigured)) {
                    Button(
                        onClick = onRefreshOrSignOut,
                        enabled = !uiState.isLoading,
                        modifier = if (focusMainAction) Modifier.focusRequester(initialFocusRequester) else Modifier,
                        colors = ButtonDefaults.colors(
                            containerColor = AuthSecondaryButtonBackground,
                            focusedContainerColor = Color.White,
                            contentColor = AuthTextPrimary,
                            focusedContentColor = Color.Black,
                            disabledContainerColor = AuthSecondaryButtonBackground.copy(alpha = 0.45f)
                        ),
                        border = ButtonDefaults.border(
                            border = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                                shape = RoundedCornerShape(16.dp)
                            )
                        )
                    ) {
                        Text(
                            when {
                                isSignedIn -> stringResource(R.string.account_sign_out)
                                uiState.isLoading -> stringResource(R.string.auth_qr_please_wait)
                                else -> stringResource(R.string.auth_qr_refresh)
                            }
                        )
                    }
                }
                Button(
                    onClick = onBackOrContinue,
                    modifier = if (!focusEmail && !focusMainAction) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (!uiState.isServerConfigured && !isSignedIn) Color.White else AuthSecondaryButtonBackground,
                        focusedContainerColor = Color.White,
                        contentColor = if (!uiState.isServerConfigured && !isSignedIn) Color.Black else AuthTextPrimary,
                        focusedContentColor = Color.Black
                    ),
                    border = ButtonDefaults.border(
                        border = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                            shape = RoundedCornerShape(16.dp)
                        )
                    )
                ) {
                    Text(
                        if (isOnboardingMode) {
                            if (isSignedIn) stringResource(R.string.auth_qr_continue) else stringResource(R.string.auth_qr_continue_without_account)
                        } else {
                            stringResource(R.string.auth_qr_back)
                        }
                    )
                }
            }
            if (showSupportButton) {
                AuthSupportButton(supporterTier = supporterTier, onClick = onSupport)
            }
        }
    }
}

@Composable
private fun AuthSupportButton(
    supporterTier: MemberTier?,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = AuthSecondaryButtonBackground,
            focusedContainerColor = Color.White,
            contentColor = AuthTextPrimary,
            focusedContentColor = Color.Black
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (supporterTier != null) Icons.Filled.CheckCircle else Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                when (supporterTier) {
                    MemberTier.SUPPORTER -> stringResource(R.string.supporter_membership_tier_supporter)
                    MemberTier.SUPPORTER_PLUS -> stringResource(R.string.supporter_membership_tier_supporter_plus)
                    null -> stringResource(R.string.support_nuvio_button)
                }
            )
        }
    }
}

@Composable
private fun AuthModeTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color.White else AuthSecondaryButtonBackground,
            focusedContainerColor = Color.White,
            contentColor = if (selected) Color.Black else AuthTextPrimary,
            focusedContentColor = Color.Black
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) Color.White else AuthSecondaryButtonBorder
                ),
                shape = RoundedCornerShape(14.dp)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AuthLoginMethodSwitcher(
    selectedMethod: AuthLoginMethod,
    onSelectMethod: (AuthLoginMethod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AuthModeTabButton(
            label = stringResource(R.string.auth_method_email),
            selected = selectedMethod == AuthLoginMethod.EMAIL,
            onClick = { onSelectMethod(AuthLoginMethod.EMAIL) },
            modifier = Modifier.weight(1f)
        )
        AuthModeTabButton(
            label = stringResource(R.string.auth_method_qr),
            selected = selectedMethod == AuthLoginMethod.QR,
            onClick = { onSelectMethod(AuthLoginMethod.QR) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AuthEmailLoginForm(
    uiState: AccountUiState,
    isSignUpMode: Boolean,
    onToggleSignUp: (Boolean) -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    initialFocusRequester: FocusRequester
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val minPasswordLength = if (isSignUpMode) 6 else 1
    val canSubmit = email.isNotBlank() && password.length >= minPasswordLength && !uiState.isLoading
    val submit = {
        if (canSubmit) {
            if (isSignUpMode) {
                onSignUp(email.trim(), password)
            } else {
                onSignIn(email.trim(), password)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AuthModeTabButton(
                label = stringResource(R.string.auth_email_sign_in),
                selected = !isSignUpMode,
                onClick = { onToggleSignUp(false) },
                modifier = Modifier.weight(1f)
            )
            AuthModeTabButton(
                label = stringResource(R.string.auth_email_sign_up),
                selected = isSignUpMode,
                onClick = { onToggleSignUp(true) },
                modifier = Modifier.weight(1f)
            )
        }
        InputField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.auth_email_placeholder),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier.focusRequester(initialFocusRequester)
        )
        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.auth_password_placeholder),
            keyboardType = KeyboardType.Password,
            isPassword = !showPassword,
            imeAction = ImeAction.Done,
            onImeAction = submit,
            trailingContent = {
                Box(
                    modifier = Modifier
                        .clickable { showPassword = !showPassword }
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.auth_password_toggle_cd),
                        tint = AuthTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )
        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = Color.White,
                focusedContainerColor = Color.White,
                contentColor = Color.Black,
                focusedContentColor = Color.Black,
                disabledContainerColor = Color.White.copy(alpha = 0.12f),
                disabledContentColor = AuthTextPrimary.copy(alpha = 0.58f)
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(16.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        uiState.isLoading && isSignUpMode -> stringResource(R.string.auth_email_signing_up)
                        uiState.isLoading -> stringResource(R.string.auth_email_signing_in)
                        isSignUpMode -> stringResource(R.string.auth_email_sign_up)
                        else -> stringResource(R.string.auth_email_sign_in)
                    },
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
        AuthTermsAcknowledgement()
        uiState.infoMessage?.takeIf { it.isNotBlank() }?.let { message ->
            StatusPill(
                text = message,
                containerColor = Color(0x2E2E7D32),
                contentColor = Color(0xFF9EFFA9)
            )
        }
        uiState.error?.takeIf { it.isNotBlank() }?.let { error ->
            StatusPill(
                text = error,
                containerColor = Color(0x33C62828),
                contentColor = Color(0xFFFF6E6E)
            )
        }
    }
}

@Composable
private fun AuthQrCodeBlock(
    uiState: AccountUiState,
    qrSize: androidx.compose.ui.unit.Dp = 206.dp
) {
    val qrBitmap = remember(uiState.qrLoginBitmap) { uiState.qrLoginBitmap?.asImageBitmap() }
    if (qrBitmap != null) {
        Image(
            bitmap = qrBitmap,
            contentDescription = stringResource(R.string.cd_qr_login),
            modifier = Modifier
                .size(qrSize)
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(qrSize)
                .background(AuthSecondaryButtonBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AuthSecondaryButtonBorder, RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (uiState.isLoading) stringResource(R.string.auth_qr_generating) else stringResource(R.string.auth_qr_unavailable),
                color = AuthTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }

    AuthQrManualCodeDetails(
        verificationUri = uiState.qrLoginVerificationUri,
        qrLoginCode = uiState.qrLoginUserCode ?: uiState.qrLoginCode,
        expiresAtMillis = uiState.qrLoginExpiresAtMillis,
        isLoading = uiState.isLoading
    )

    Spacer(modifier = Modifier.height(12.dp))
    AuthTermsAcknowledgement()

    val statusText = uiState.error ?: uiState.qrLoginStatus
    if (!statusText.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(14.dp))
        if (uiState.error != null) {
            StatusPill(
                text = statusText,
                containerColor = Color(0x33C62828),
                contentColor = Color(0xFFFF6E6E)
            )
        } else {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = AuthTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AuthQrManualCodeDetails(
    verificationUri: String?,
    qrLoginCode: String?,
    expiresAtMillis: Long?,
    isLoading: Boolean
) {
    val displayUri = verificationUri?.takeIf { it.isNotBlank() }
    val displayCode = qrLoginCode?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (displayUri != null && displayCode != null) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.auth_qr_manual_instruction, displayVerificationUri(displayUri)),
                style = MaterialTheme.typography.bodyMedium,
                color = AuthTextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = formatDeviceLoginCode(displayCode),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = if (displayCode.length == 6) 24.sp else 14.sp,
                    letterSpacing = if (displayCode.length == 6) 3.sp else 0.sp
                ),
                color = AuthTextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (expiresAtMillis != null) {
                Spacer(modifier = Modifier.height(6.dp))
                AuthQrExpiresText(expiresAtMillis = expiresAtMillis)
            }
        } else if (isLoading) {
            val shimmerBrush = rememberShimmerFill(backdropAware = true)
            Spacer(modifier = Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                SkeletonBar(width = 238.dp, height = 12.dp, brush = shimmerBrush, cornerRadius = 6.dp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                SkeletonBar(width = 116.dp, height = 22.dp, brush = shimmerBrush, cornerRadius = 8.dp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                SkeletonBar(width = 84.dp, height = 8.dp, brush = shimmerBrush, cornerRadius = 4.dp)
            }
        }
    }
}

// Owns the 1 s ticker so only this line recomposes while the code counts down.
@Composable
private fun AuthQrExpiresText(expiresAtMillis: Long) {
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = expiresAtMillis) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingMillis = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
    Text(
        text = stringResource(R.string.auth_qr_expires, formatDuration(remainingMillis)),
        style = MaterialTheme.typography.bodySmall,
        color = AuthTextSecondary
    )
}

private fun formatDeviceLoginCode(value: String): String =
    if (value.length == 6) "${value.take(3)}-${value.drop(3)}" else value

private fun displayVerificationUri(value: String): String = value
    .removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')

@Composable
private fun AuthTermsAcknowledgement() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.auth_qr_terms_prefix),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AuthTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.auth_qr_terms_link),
            modifier = Modifier
                .clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nuvio.tv/terms")))
                }
                .semantics { role = Role.Button },
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AuthTextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), RoundedCornerShape(NuvioTheme.radii.md))
            .background(containerColor, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentHeight()
        )
    }
}

@Composable
private fun AccountConnectedStatsStrip(
    stats: AccountConnectedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("...", "...", "...", "...")
    } else {
        listOf(
            (stats?.addons ?: 0).toString(),
            (stats?.plugins ?: 0).toString(),
            (stats?.library ?: 0).toString(),
            (stats?.watchProgress ?: 0).toString()
        )
    }
    val labels = listOf(
        stringResource(R.string.account_stat_addons),
        stringResource(R.string.account_stat_plugins),
        stringResource(R.string.account_stat_library),
        stringResource(R.string.account_stat_progress)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(values.size) { index ->
                AccountStatItem(
                    value = values[index],
                    label = labels[index],
                    modifier = Modifier.weight(1f)
                )
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .width(NuvioTheme.spacing.hairline)
                            .background(NuvioTheme.colors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
        )
    }
}

@Composable
private fun AccountStatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun Modifier.authGradientBackground(): Modifier = drawWithCache {
    val angleRadians = 122.0 * PI / 180.0
    val directionX = sin(angleRadians).toFloat()
    val directionY = (-cos(angleRadians)).toFloat()
    val halfLength = (abs(size.width * directionX) + abs(size.height * directionY)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = Offset(
        x = center.x - directionX * halfLength,
        y = center.y - directionY * halfLength
    )
    val end = Offset(
        x = center.x + directionX * halfLength,
        y = center.y + directionY * halfLength
    )
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFF22252E),
            0.16f to Color(0xFF1A1D24),
            0.32f to Color(0xFF12141A),
            0.52f to Color(0xFF0B0C10),
            0.72f to Color(0xFF060709),
            1f to Color.Black
        ),
        start = start,
        end = end
    )
    onDrawBehind {
        drawRect(brush = brush)
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
