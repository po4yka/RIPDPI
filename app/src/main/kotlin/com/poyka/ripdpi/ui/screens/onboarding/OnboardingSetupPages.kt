package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.activities.OnboardingValidationStep
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun OnboardingModeSelectionContent(
    selectedMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        PresetCard(
            title = stringResource(R.string.onboarding_setup_mode_vpn_title),
            description = stringResource(R.string.onboarding_setup_mode_vpn_body),
            badgeText = stringResource(R.string.onboarding_badge_recommended),
            selected = selectedMode == Mode.VPN,
            onClick = { onModeSelected(Mode.VPN) },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingModeVpn),
        )
        PresetCard(
            title = stringResource(R.string.onboarding_setup_mode_proxy_title),
            description = stringResource(R.string.onboarding_setup_mode_proxy_body),
            badgeText = stringResource(R.string.onboarding_badge_advanced),
            selected = selectedMode == Mode.Proxy,
            onClick = { onModeSelected(Mode.Proxy) },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingModeProxy),
        )
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

@Composable
internal fun OnboardingDnsSelectionContent(
    selectedProviderId: String,
    onDnsSelected: (String) -> Unit,
    onOpenAdvancedDns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val getString: (Int, String, String) -> String = { res, a, b -> context.getString(res, a, b) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OnboardingDnsOptions.forEach { option ->
            val isSystem = option.providerId == OnboardingDnsSystemId
            val title =
                if (isSystem) {
                    stringResource(R.string.onboarding_setup_dns_system)
                } else {
                    dnsProviderById(option.providerId)?.displayName ?: option.providerId
                }
            val selected =
                if (isSystem) {
                    // "System" is selected when no built-in resolver is pinned.
                    dnsProviderById(selectedProviderId) == null
                } else {
                    selectedProviderId == option.providerId
                }
            PresetCard(
                title = title,
                description = stringResource(option.descriptionRes),
                metadata = onboardingDnsMetadataLine(option.providerId, getString),
                badgeText = option.badgeRes?.let { stringResource(it) },
                selected = selected,
                onClick = { onDnsSelected(option.providerId) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.onboardingDnsProvider(option.providerId)),
            )
        }
        TextButton(
            onClick = onOpenAdvancedDns,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.OnboardingAdvancedDns),
        ) {
            Text(
                text = stringResource(R.string.onboarding_dns_advanced),
                style = type.introAction,
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

@Composable
internal fun OnboardingModeValidationContent(
    uiState: OnboardingUiState,
    onAcceptSuggestedMode: () -> Unit,
    onChangeDns: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val state = uiState.validationState

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        when (state) {
            OnboardingValidationState.Idle -> {
                Text(
                    text = stringResource(R.string.onboarding_test_idle_body),
                    style = type.introBody,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Start,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
                )
            }

            is OnboardingValidationState.Success -> {
                Text(
                    text = stringResource(R.string.onboarding_test_success_title),
                    style = type.sectionTitle,
                    color = colors.foreground,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
                )
                Text(
                    text = stringResource(R.string.onboarding_test_success_body),
                    style = type.introBody,
                    color = colors.mutedForeground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is OnboardingValidationState.Failed -> {
                Text(
                    text = stringResource(R.string.onboarding_test_failed_title),
                    style = type.sectionTitle,
                    color = colors.foreground,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.onboarding_validation_failed, state.reason),
                    style = type.introBody,
                    color = colors.mutedForeground,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
                )
            }

            OnboardingValidationState.RequestingVpnConsent -> {
                Text(
                    text = stringResource(R.string.onboarding_test_state_permission),
                    style = type.introBody,
                    color = colors.mutedForeground,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
                )
            }

            OnboardingValidationState.RequestingNotifications,
            is OnboardingValidationState.StartingMode,
            is OnboardingValidationState.CheckingDns,
            is OnboardingValidationState.RunningTrafficCheck,
            -> {
                // Status surfaced through the checklist active row; keep the status tag present.
                Spacer(
                    modifier =
                        Modifier
                            .height(spacing.xs)
                            .ripDpiTestTag(RipDpiTestTags.OnboardingValidationStatus),
                )
            }
        }

        OnboardingValidationChecklist(
            mode = uiState.selectedMode,
            state = state,
        )

        OnboardingValidationContentActions(
            state = state,
            onAcceptSuggestedMode = onAcceptSuggestedMode,
            onChangeDns = onChangeDns,
            onFinishDisconnected = onFinishDisconnected,
            onFinishAnyway = onFinishAnyway,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

private enum class OnboardingRowStatus { Pending, Active, Complete, Failed }

@Composable
private fun OnboardingValidationChecklist(
    mode: Mode,
    state: OnboardingValidationState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        OnboardingChecklistRow(
            label =
                stringResource(
                    when (mode) {
                        Mode.VPN -> R.string.onboarding_test_step_tunnel_vpn
                        Mode.Proxy -> R.string.onboarding_test_step_tunnel_proxy
                    },
                ),
            status = tunnelRowStatus(state),
        )
        OnboardingChecklistRow(
            label = stringResource(R.string.onboarding_test_step_dns),
            status = dnsRowStatus(state),
        )
        OnboardingChecklistRow(
            label = stringResource(R.string.onboarding_test_step_connectivity),
            status = connectivityRowStatus(state),
        )
    }
}

@Composable
private fun OnboardingChecklistRow(
    label: String,
    status: OnboardingRowStatus,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val components = RipDpiThemeTokens.components
    val stage =
        when (status) {
            OnboardingRowStatus.Pending -> RipDpiThemeTokens.stateRoles.actuatorStage.pending
            OnboardingRowStatus.Active -> RipDpiThemeTokens.stateRoles.actuatorStage.active
            OnboardingRowStatus.Complete -> RipDpiThemeTokens.stateRoles.actuatorStage.complete
            OnboardingRowStatus.Failed -> RipDpiThemeTokens.stateRoles.actuatorStage.failed
        }
    val stageStyle = RipDpiThemeTokens.state.actuator.resolveStage(stage)
    val stateDescriptionText =
        stringResource(
            when (status) {
                OnboardingRowStatus.Pending -> R.string.onboarding_test_row_state_pending
                OnboardingRowStatus.Active -> R.string.onboarding_test_row_state_active
                OnboardingRowStatus.Complete -> R.string.onboarding_test_row_state_done
                OnboardingRowStatus.Failed -> R.string.onboarding_test_row_state_failed
            },
        )
    // Only the in-progress row announces; terminal Success/Failed outcomes are announced by the
    // status block above the checklist, avoiding duplicate/overlapping TalkBack announcements.
    val changing = status == OnboardingRowStatus.Active

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = stateDescriptionText
                    if (changing) {
                        liveRegion = LiveRegionMode.Polite
                    }
                },
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(components.feedback.decorativeBadgeSize)
                    .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                OnboardingRowStatus.Active -> {
                    CircularProgressIndicator(
                        color = colors.foreground,
                        modifier = Modifier.size(components.inputs.chipIconSize),
                        strokeWidth = RipDpiThemeTokens.spacing.xs / 2,
                    )
                }

                OnboardingRowStatus.Complete -> {
                    Icon(
                        imageVector = RipDpiIcons.Check,
                        contentDescription = null,
                        tint = stageStyle.content.takeIf { it != Color.Unspecified } ?: colors.foreground,
                        modifier = Modifier.size(components.inputs.chipIconSize),
                    )
                }

                OnboardingRowStatus.Failed -> {
                    Icon(
                        imageVector = RipDpiIcons.Close,
                        contentDescription = null,
                        tint = colors.destructive,
                        modifier = Modifier.size(components.inputs.chipIconSize),
                    )
                }

                OnboardingRowStatus.Pending -> {
                    Box(
                        modifier =
                            Modifier
                                .size(components.indicators.statusMarkerLarge)
                                .clip(CircleShape)
                                .border(
                                    width = RipDpiThemeTokens.spacing.xs / 2,
                                    color = stageStyle.border,
                                    shape = CircleShape,
                                ),
                    )
                }
            }
        }
        Text(
            text = label,
            style = type.bodyEmphasis,
            color =
                when (status) {
                    OnboardingRowStatus.Pending -> colors.mutedForeground
                    OnboardingRowStatus.Failed -> colors.destructive
                    else -> colors.foreground
                },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OnboardingValidationContentActions(
    state: OnboardingValidationState,
    onAcceptSuggestedMode: () -> Unit,
    onChangeDns: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    when (state) {
        is OnboardingValidationState.Success -> {
            RipDpiButton(
                text = stringResource(R.string.onboarding_validation_finish_disconnected),
                onClick = onFinishDisconnected,
                variant = RipDpiButtonVariant.Outline,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.OnboardingFinishDisconnected),
            )
        }

        is OnboardingValidationState.Failed -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (state.failedStep == OnboardingValidationStep.Dns) {
                    RipDpiButton(
                        text = stringResource(R.string.onboarding_test_change_dns),
                        onClick = onChangeDns,
                        variant = RipDpiButtonVariant.Outline,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .ripDpiTestTag(RipDpiTestTags.OnboardingChangeDns),
                    )
                } else {
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
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .ripDpiTestTag(RipDpiTestTags.OnboardingSwitchSuggestedMode),
                        )
                    }
                }
                RipDpiButton(
                    text = stringResource(R.string.onboarding_test_finish_without),
                    onClick = onFinishAnyway,
                    variant = RipDpiButtonVariant.Outline,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.OnboardingFinishAnyway),
                )
            }
        }

        else -> {
            Unit
        }
    }
}

private fun tunnelRowStatus(state: OnboardingValidationState): OnboardingRowStatus =
    when (state) {
        OnboardingValidationState.Idle -> {
            OnboardingRowStatus.Pending
        }

        OnboardingValidationState.RequestingNotifications,
        OnboardingValidationState.RequestingVpnConsent,
        is OnboardingValidationState.StartingMode,
        -> {
            OnboardingRowStatus.Active
        }

        is OnboardingValidationState.CheckingDns,
        is OnboardingValidationState.RunningTrafficCheck,
        is OnboardingValidationState.Success,
        -> {
            OnboardingRowStatus.Complete
        }

        is OnboardingValidationState.Failed -> {
            if (state.failedStep == OnboardingValidationStep.Tunnel) {
                OnboardingRowStatus.Failed
            } else {
                OnboardingRowStatus.Complete
            }
        }
    }

private fun dnsRowStatus(state: OnboardingValidationState): OnboardingRowStatus =
    when (state) {
        OnboardingValidationState.Idle,
        OnboardingValidationState.RequestingNotifications,
        OnboardingValidationState.RequestingVpnConsent,
        is OnboardingValidationState.StartingMode,
        -> {
            OnboardingRowStatus.Pending
        }

        is OnboardingValidationState.CheckingDns -> {
            OnboardingRowStatus.Active
        }

        is OnboardingValidationState.RunningTrafficCheck,
        is OnboardingValidationState.Success,
        -> {
            OnboardingRowStatus.Complete
        }

        is OnboardingValidationState.Failed -> {
            when (state.failedStep) {
                OnboardingValidationStep.Dns -> OnboardingRowStatus.Failed
                OnboardingValidationStep.Connectivity -> OnboardingRowStatus.Complete
                else -> OnboardingRowStatus.Pending
            }
        }
    }

private fun connectivityRowStatus(state: OnboardingValidationState): OnboardingRowStatus =
    when (state) {
        OnboardingValidationState.Idle,
        OnboardingValidationState.RequestingNotifications,
        OnboardingValidationState.RequestingVpnConsent,
        is OnboardingValidationState.StartingMode,
        is OnboardingValidationState.CheckingDns,
        -> {
            OnboardingRowStatus.Pending
        }

        is OnboardingValidationState.RunningTrafficCheck -> {
            OnboardingRowStatus.Active
        }

        is OnboardingValidationState.Success -> {
            OnboardingRowStatus.Complete
        }

        is OnboardingValidationState.Failed -> {
            if (state.failedStep == OnboardingValidationStep.Connectivity) {
                OnboardingRowStatus.Failed
            } else {
                OnboardingRowStatus.Pending
            }
        }
    }

internal fun modeLabelRes(mode: Mode): Int =
    when (mode) {
        Mode.VPN -> R.string.onboarding_setup_mode_vpn_title
        Mode.Proxy -> R.string.onboarding_setup_mode_proxy_title
    }
