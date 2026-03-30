package com.poyka.ripdpi.ui.screens.permissions

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.security.BiometricAuthResult
import com.poyka.ripdpi.security.PinVerifyResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MsPerSecond = 1_000L

enum class BiometricPromptStage {
    Biometric,
    Pin,
}

@Composable
fun BiometricPromptRoute(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val biometricAuthManager = remember { BiometricAuthManager() }
    val coroutineScope = rememberCoroutineScope()
    val pinErrorText = stringResource(R.string.biometric_prompt_pin_error)
    val biometricTitle = stringResource(R.string.biometric_dialog_title)
    val biometricSubtitle = stringResource(R.string.biometric_dialog_subtitle)
    val biometricCancel = stringResource(R.string.biometric_dialog_cancel)
    var stage by rememberSaveable { mutableStateOf(BiometricPromptStage.Biometric) }
    var pin by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var lockoutRemainingMs by rememberSaveable { mutableLongStateOf(0L) }
    var biometricError by rememberSaveable { mutableStateOf<String?>(null) }

    val launchBiometric: () -> Unit = {
        biometricError = null
        coroutineScope.launch {
            val result =
                biometricAuthManager.authenticate(
                    context as FragmentActivity,
                    biometricTitle,
                    biometricSubtitle,
                    biometricCancel,
                )
            if (result is BiometricAuthResult.Success) {
                onAuthenticated()
            } else if (result is BiometricAuthResult.Error) {
                biometricError = result.message
            }
        }
    }

    LaunchedEffect(uiState.isHydrated, uiState.biometricEnabled) {
        if (uiState.isHydrated && !uiState.biometricEnabled) onAuthenticated()
    }

    LaunchedEffect(stage, uiState.isHydrated) {
        if (!uiState.isHydrated || stage != BiometricPromptStage.Biometric) return@LaunchedEffect
        if (biometricAuthManager.canAuthenticate(context) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (uiState.hasBackupPin) stage = BiometricPromptStage.Pin
        } else {
            launchBiometric()
        }
    }

    LaunchedEffect(stage) {
        if (stage == BiometricPromptStage.Pin && viewModel.isPinLockedOut()) {
            lockoutRemainingMs = viewModel.pinLockoutRemainingMs()
        }
    }

    LaunchedEffect(lockoutRemainingMs) {
        if (lockoutRemainingMs <= 0L) return@LaunchedEffect
        delay(timeMillis = 1_000L)
        lockoutRemainingMs = viewModel.pinLockoutRemainingMs()
    }

    BiometricPromptScreen(
        uiState = uiState,
        stage = stage,
        pin = pin,
        pinError = pinError,
        lockoutRemainingMs = lockoutRemainingMs,
        biometricError = biometricError,
        modifier = modifier,
        onAuthenticate = launchBiometric,
        onUseBackupPin = {
            stage = BiometricPromptStage.Pin
            pinError = null
        },
        onBackToBiometric = {
            stage = BiometricPromptStage.Biometric
            pin = ""
            pinError = null
            biometricError = null
        },
        onPinChanged = { value ->
            pin = value.filter(Char::isDigit)
            pinError = null
        },
        onSubmitPin = {
            when (val result = viewModel.verifyBackupPin(pin)) {
                is PinVerifyResult.Success -> {
                    onAuthenticated()
                }

                is PinVerifyResult.Failed -> {
                    pinError = pinErrorText
                }

                is PinVerifyResult.LockedOut -> {
                    lockoutRemainingMs = result.remainingMs
                    pinError = null
                }
            }
        },
    )
}

@Composable
fun BiometricPromptScreen(
    uiState: SettingsUiState,
    stage: BiometricPromptStage,
    pin: String,
    pinError: String?,
    onAuthenticate: () -> Unit,
    onUseBackupPin: () -> Unit,
    onBackToBiometric: () -> Unit,
    onPinChanged: (String) -> Unit,
    onSubmitPin: () -> Unit,
    modifier: Modifier = Modifier,
    lockoutRemainingMs: Long = 0L,
    biometricError: String? = null,
) {
    val isLockedOut = lockoutRemainingMs > 0L
    val hasBackupPin = uiState.hasBackupPin
    val isPinStage = stage == BiometricPromptStage.Pin && hasBackupPin
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    AuthPromptScaffold(
        title =
            stringResource(
                if (isPinStage) {
                    R.string.biometric_prompt_pin_title
                } else {
                    R.string.biometric_prompt_title
                },
            ),
        message =
            stringResource(
                if (isPinStage) {
                    R.string.biometric_prompt_pin_body
                } else {
                    R.string.biometric_prompt_body
                },
            ),
        illustration =
            if (isPinStage) {
                AuthPromptIllustration.Pin
            } else {
                AuthPromptIllustration.Biometric
            },
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.BiometricPrompt)),
        banner = {
            BiometricPromptBanner(
                isPinStage = isPinStage,
                isLockedOut = isLockedOut,
                hasBackupPin = hasBackupPin,
                lockoutRemainingMs = lockoutRemainingMs,
                biometricError = biometricError,
            )
        },
        content = {
            if (isPinStage) {
                BiometricPromptPinInput(
                    pin = pin,
                    onPinChanged = onPinChanged,
                    pinError = pinError,
                    horizontalPadding = introLayout.bodyHorizontalPadding,
                )
            }
        },
        footer = {
            BiometricPromptFooter(
                isPinStage = isPinStage,
                isLockedOut = isLockedOut,
                hasBackupPin = hasBackupPin,
                onSubmitPin = onSubmitPin,
                onAuthenticate = onAuthenticate,
                onBackToBiometric = onBackToBiometric,
                onUseBackupPin = onUseBackupPin,
            )
        },
    )
}

@Composable
private fun BiometricPromptBanner(
    isPinStage: Boolean,
    isLockedOut: Boolean,
    hasBackupPin: Boolean,
    lockoutRemainingMs: Long,
    biometricError: String?,
) {
    if (isPinStage && isLockedOut) {
        val remainingSeconds = ((lockoutRemainingMs + 999) / 1_000).toInt()
        WarningBanner(
            title = stringResource(R.string.biometric_prompt_pin_lockout_title),
            message = stringResource(R.string.biometric_prompt_pin_lockout_body, remainingSeconds),
            tone = WarningBannerTone.Restricted,
        )
    } else if (!isPinStage && biometricError != null) {
        WarningBanner(
            title = stringResource(R.string.biometric_auth_error_title),
            message = biometricError,
            tone = WarningBannerTone.Restricted,
        )
    } else if (!isPinStage && !hasBackupPin) {
        WarningBanner(
            title = stringResource(R.string.biometric_prompt_no_pin_title),
            message = stringResource(R.string.biometric_prompt_no_pin_body),
            tone = WarningBannerTone.Restricted,
        )
    }
}

@Composable
private fun BiometricPromptFooter(
    isPinStage: Boolean,
    isLockedOut: Boolean,
    hasBackupPin: Boolean,
    onSubmitPin: () -> Unit,
    onAuthenticate: () -> Unit,
    onBackToBiometric: () -> Unit,
    onUseBackupPin: () -> Unit,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    RipDpiButton(
        text = stringResource(R.string.biometric_prompt_action),
        onClick = if (isPinStage) onSubmitPin else onAuthenticate,
        enabled = !isPinStage || !isLockedOut,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = introLayout.footerButtonHorizontalInset)
                .heightIn(min = introLayout.footerButtonMinHeight)
                .ripDpiTestTag(RipDpiTestTags.BiometricPromptPrimaryAction),
        trailingIcon = if (isPinStage) null else com.poyka.ripdpi.ui.theme.RipDpiIcons.ChevronRight,
    )

    if (isPinStage) {
        RipDpiButton(
            text = stringResource(R.string.biometric_prompt_use_biometric),
            onClick = onBackToBiometric,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.footerButtonHorizontalInset)
                    .heightIn(min = introLayout.footerButtonMinHeight)
                    .ripDpiTestTag(RipDpiTestTags.BiometricPromptSecondaryAction),
        )
    } else if (hasBackupPin) {
        RipDpiButton(
            text = stringResource(R.string.biometric_prompt_use_pin),
            onClick = onUseBackupPin,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.footerButtonHorizontalInset)
                    .heightIn(min = introLayout.footerButtonMinHeight)
                    .ripDpiTestTag(RipDpiTestTags.BiometricPromptSecondaryAction),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun BiometricPromptScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        BiometricPromptScreen(
            uiState =
                SettingsUiState(
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                ),
            stage = BiometricPromptStage.Biometric,
            pin = "",
            pinError = null,
            onAuthenticate = {},
            onUseBackupPin = {},
            onBackToBiometric = {},
            onPinChanged = {},
            onSubmitPin = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun BiometricPromptPinDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        BiometricPromptScreen(
            uiState =
                SettingsUiState(
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                ),
            stage = BiometricPromptStage.Pin,
            pin = "0000",
            pinError = "The backup PIN does not match.",
            onAuthenticate = {},
            onUseBackupPin = {},
            onBackToBiometric = {},
            onPinChanged = {},
            onSubmitPin = {},
        )
    }
}
