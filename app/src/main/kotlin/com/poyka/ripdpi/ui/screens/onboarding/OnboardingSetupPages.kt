package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.DnsProviderQuad9
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val CardSpacing = 12.dp

@Composable
internal fun OnboardingModeSelectionContent(
    selectedMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        PresetCard(
            title = stringResource(R.string.onboarding_setup_mode_vpn_title),
            description = stringResource(R.string.onboarding_setup_mode_vpn_body),
            selected = selectedMode == Mode.VPN,
            onClick = { onModeSelected(Mode.VPN) },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingModeVpn),
        )
        PresetCard(
            title = stringResource(R.string.onboarding_setup_mode_proxy_title),
            description = stringResource(R.string.onboarding_setup_mode_proxy_body),
            selected = selectedMode == Mode.Proxy,
            onClick = { onModeSelected(Mode.Proxy) },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingModeProxy),
        )
    }
}

@Composable
internal fun OnboardingDnsSelectionContent(
    selectedProviderId: String,
    onDnsSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers =
        listOf(
            DnsProviderCloudflare to
                Pair("Cloudflare", R.string.onboarding_setup_dns_cloudflare_body),
            DnsProviderGoogle to
                Pair("Google", R.string.onboarding_setup_dns_google_body),
            DnsProviderQuad9 to
                Pair("Quad9", R.string.onboarding_setup_dns_quad9_body),
            DnsProviderAdGuard to
                Pair("AdGuard", R.string.onboarding_setup_dns_adguard_body),
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        PresetCard(
            title = stringResource(R.string.onboarding_setup_dns_system),
            description = stringResource(R.string.onboarding_setup_dns_system_body),
            selected = selectedProviderId !in providers.map { it.first },
            onClick = { onDnsSelected("system") },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.onboardingDnsProvider("system")),
        )
        providers.forEach { (id, nameAndDesc) ->
            PresetCard(
                title = nameAndDesc.first,
                description = stringResource(nameAndDesc.second),
                selected = selectedProviderId == id,
                onClick = { onDnsSelected(id) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.onboardingDnsProvider(id)),
            )
        }
    }
}

@Composable
internal fun OnboardingModeValidationContent(
    uiState: OnboardingUiState,
    onRunValidation: () -> Unit,
    onFinishKeepingRunning: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val selectedModeLabel = stringResource(modeLabelRes(uiState.selectedMode))

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_validation_idle_hint, selectedModeLabel),
            style = type.introBody,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = uiState.validationState,
            transitionSpec = { motion.quickFadeContentTransform() },
            contentAlignment = Alignment.Center,
            label = "mode-validation",
        ) { state ->
            ValidationStateContent(
                state = state,
                onRunValidation = onRunValidation,
                onFinishKeepingRunning = onFinishKeepingRunning,
                onFinishDisconnected = onFinishDisconnected,
                onFinishAnyway = onFinishAnyway,
                onAcceptSuggestedMode = onAcceptSuggestedMode,
            )
        }
    }
}

@Composable
private fun ValidationStateContent(
    state: OnboardingValidationState,
    onRunValidation: () -> Unit,
    onFinishKeepingRunning: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            OnboardingValidationState.Idle -> {
                RipDpiButton(
                    text = stringResource(R.string.onboarding_validation_run),
                    onClick = onRunValidation,
                    variant = RipDpiButtonVariant.Primary,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidateAction),
                )
                RipDpiButton(
                    text = stringResource(R.string.onboarding_validation_finish_anyway),
                    onClick = onFinishAnyway,
                    variant = RipDpiButtonVariant.Outline,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishAnyway),
                )
            }

            OnboardingValidationState.RequestingNotifications -> {
                ValidationBusyState(message = stringResource(R.string.onboarding_validation_requesting_notifications))
            }

            OnboardingValidationState.RequestingVpnConsent -> {
                ValidationBusyState(message = stringResource(R.string.onboarding_validation_requesting_vpn_permission))
            }

            is OnboardingValidationState.StartingMode -> {
                ValidationBusyState(
                    message =
                        stringResource(
                            when (state.mode) {
                                Mode.VPN -> R.string.onboarding_validation_starting_vpn
                                Mode.Proxy -> R.string.onboarding_validation_starting_proxy
                            },
                        ),
                )
            }

            is OnboardingValidationState.RunningTrafficCheck -> {
                ValidationBusyState(
                    message =
                        stringResource(
                            when (state.mode) {
                                Mode.VPN -> R.string.onboarding_validation_checking_vpn
                                Mode.Proxy -> R.string.onboarding_validation_checking_proxy
                            },
                        ),
                )
            }

            is OnboardingValidationState.Success -> {
                ValidationSuccessContent(
                    state = state,
                    onFinishKeepingRunning = onFinishKeepingRunning,
                    onFinishDisconnected = onFinishDisconnected,
                )
            }

            is OnboardingValidationState.Failed -> {
                ValidationFailedContent(
                    state = state,
                    onRunValidation = onRunValidation,
                    onAcceptSuggestedMode = onAcceptSuggestedMode,
                    onFinishAnyway = onFinishAnyway,
                )
            }
        }
    }
}

@Composable
private fun ValidationSuccessContent(
    state: OnboardingValidationState.Success,
    onFinishKeepingRunning: () -> Unit,
    onFinishDisconnected: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Text(
        text =
            stringResource(
                R.string.onboarding_validation_success,
                stringResource(modeLabelRes(state.mode)),
                state.latencyMs,
            ),
        style = type.introBody,
        color = colors.foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
    )
    RipDpiButton(
        text = stringResource(R.string.onboarding_validation_finish_keep_running),
        onClick = onFinishKeepingRunning,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishKeepRunning),
    )
    RipDpiButton(
        text = stringResource(R.string.onboarding_validation_finish_disconnected),
        onClick = onFinishDisconnected,
        variant = RipDpiButtonVariant.Outline,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishDisconnected),
    )
}

@Composable
private fun ValidationFailedContent(
    state: OnboardingValidationState.Failed,
    onRunValidation: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
    onFinishAnyway: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Text(
        text = stringResource(R.string.onboarding_validation_failed, state.reason),
        style = type.introBody,
        color = colors.foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
    )
    RipDpiButton(
        text = stringResource(R.string.onboarding_validation_retry),
        onClick = onRunValidation,
        variant = RipDpiButtonVariant.Primary,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidateAction),
    )
    state.suggestedMode?.let { suggestedMode ->
        RipDpiButton(
            text =
                stringResource(
                    when (suggestedMode) {
                        Mode.VPN -> R.string.onboarding_validation_switch_to_vpn
                        Mode.Proxy -> R.string.onboarding_validation_switch_to_proxy
                    },
                ),
            onClick = onAcceptSuggestedMode,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingSwitchSuggestedMode),
        )
    }
    RipDpiButton(
        text = stringResource(R.string.onboarding_validation_finish_anyway),
        onClick = onFinishAnyway,
        variant = RipDpiButtonVariant.Outline,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishAnyway),
    )
}

@Composable
private fun ValidationBusyState(message: String) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(
            color = colors.foreground,
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = message,
            style = type.introBody,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
        )
    }
}

@Composable
private fun modeLabelRes(mode: Mode): Int =
    when (mode) {
        Mode.VPN -> R.string.onboarding_setup_mode_vpn_title
        Mode.Proxy -> R.string.onboarding_setup_mode_proxy_title
    }
