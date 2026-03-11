package com.poyka.ripdpi.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    val pinErrorText = stringResource(R.string.biometric_prompt_pin_error)
    var stage by rememberSaveable { mutableStateOf(BiometricPromptStage.Biometric) }
    var pin by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.isHydrated, uiState.biometricEnabled) {
        if (uiState.isHydrated && !uiState.biometricEnabled) {
            onAuthenticated()
        }
    }

    BiometricPromptScreen(
        uiState = uiState,
        stage = stage,
        pin = pin,
        pinError = pinError,
        modifier = modifier,
        onAuthenticate = onAuthenticated,
        onUseBackupPin = {
            stage = BiometricPromptStage.Pin
            pinError = null
        },
        onBackToBiometric = {
            stage = BiometricPromptStage.Biometric
            pin = ""
            pinError = null
        },
        onPinChanged = { value ->
            pin = value.filter(Char::isDigit)
            pinError = null
        },
        onSubmitPin = {
            if (pin == uiState.backupPin) {
                onAuthenticated()
            } else {
                pinError = pinErrorText
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
) {
    val hasBackupPin = uiState.backupPin.isNotBlank()
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
        modifier = modifier,
        banner = {
            if (!isPinStage && !hasBackupPin) {
                WarningBanner(
                    title = stringResource(R.string.biometric_prompt_no_pin_title),
                    message = stringResource(R.string.biometric_prompt_no_pin_body),
                    tone = WarningBannerTone.Restricted,
                )
            }
        },
        content = {
            if (isPinStage) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = introLayout.bodyHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RipDpiTextField(
                        value = pin,
                        onValueChange = onPinChanged,
                        label = stringResource(R.string.biometric_prompt_pin_label),
                        placeholder = stringResource(R.string.biometric_prompt_pin_placeholder),
                        helperText = stringResource(R.string.biometric_prompt_pin_helper),
                        errorText = pinError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        },
        footer = {
            RipDpiButton(
                text = stringResource(R.string.biometric_prompt_action),
                onClick = if (isPinStage) onSubmitPin else onAuthenticate,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = introLayout.footerButtonHorizontalInset)
                        .heightIn(min = introLayout.footerButtonMinHeight),
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
                            .heightIn(min = introLayout.footerButtonMinHeight),
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
                            .heightIn(min = introLayout.footerButtonMinHeight),
                )
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun BiometricPromptScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        BiometricPromptScreen(
            uiState =
                SettingsUiState(
                    biometricEnabled = true,
                    backupPin = "1234",
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
                    backupPin = "1234",
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
