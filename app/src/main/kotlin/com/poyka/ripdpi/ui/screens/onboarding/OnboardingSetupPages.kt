package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import com.poyka.ripdpi.activities.ConnectionTestState
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
internal fun OnboardingConnectionTestContent(
    testState: ConnectionTestState,
    onRunTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_setup_test_hint),
            style = type.introBody,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = testState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentAlignment = Alignment.Center,
            label = "connection-test",
        ) { state ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is ConnectionTestState.Idle -> {
                        RipDpiButton(
                            text = stringResource(R.string.onboarding_setup_test_run),
                            onClick = onRunTest,
                            variant = RipDpiButtonVariant.Outline,
                            modifier =
                                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingRunTest),
                        )
                    }

                    is ConnectionTestState.Running -> {
                        CircularProgressIndicator(
                            color = colors.foreground,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_setup_test_running),
                            style = type.introBody,
                            color = colors.mutedForeground,
                        )
                    }

                    is ConnectionTestState.Success -> {
                        Text(
                            text = stringResource(R.string.onboarding_setup_test_success, state.latencyMs),
                            style = type.introBody,
                            color = colors.foreground,
                            modifier =
                                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingTestResult),
                        )
                        RipDpiButton(
                            text = stringResource(R.string.onboarding_setup_test_run),
                            onClick = onRunTest,
                            variant = RipDpiButtonVariant.Outline,
                            modifier =
                                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingRunTest),
                        )
                    }

                    is ConnectionTestState.Failed -> {
                        Text(
                            text = stringResource(R.string.onboarding_setup_test_failed, state.reason),
                            style = type.introBody,
                            color = colors.foreground,
                            modifier =
                                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingTestResult),
                        )
                        RipDpiButton(
                            text = stringResource(R.string.onboarding_setup_test_run),
                            onClick = onRunTest,
                            variant = RipDpiButtonVariant.Outline,
                            modifier =
                                Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingRunTest),
                        )
                    }
                }
            }
        }
    }
}
