package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AppRoutingPolicyModeOff
import com.poyka.ripdpi.data.AppRoutingPolicyModePrompt
import com.poyka.ripdpi.data.DhtMitigationModeBypass
import com.poyka.ripdpi.data.DhtMitigationModeDropWarn
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Suppress("LongMethod")
internal fun LazyListScope.routingProtectionSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onRoutingPolicyModeSelected: (String) -> Unit,
    onDhtMitigationModeSelected: (String) -> Unit,
    onAntiCorrelationEnabledChanged: (Boolean) -> Unit,
    onAppRoutingPresetEnabledChanged: (String, Boolean) -> Unit,
) {
    item(key = "advanced_routing_protection") {
        val sectionEnabled = visualEditorEnabled && !uiState.enableCmdSettings
        val policyOptions = routingProtectionPolicyOptions()
        val dhtOptions = routingProtectionDhtOptions()
        AdvancedSettingsSection(
            title = stringResource(R.string.routing_protection_section_title),
            testTag = RipDpiTestTags.advancedSection("routing_protection"),
        ) {
            RoutingProtectionSummaryCard(uiState = uiState)
            RipDpiCard {
                AdvancedDropdownSetting(
                    title = stringResource(R.string.routing_protection_policy_title),
                    description = stringResource(R.string.routing_protection_policy_body),
                    value = uiState.routingProtection.policyMode,
                    enabled = sectionEnabled,
                    options = policyOptions,
                    setting = AdvancedOptionSetting.AppRoutingPolicyMode,
                    onSelected = { _, value -> onRoutingPolicyModeSelected(value) },
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.routing_protection_anti_correlation_title),
                    subtitle = stringResource(R.string.routing_protection_anti_correlation_body),
                    checked = uiState.routingProtection.antiCorrelationEnabled,
                    enabled = sectionEnabled && !uiState.fullTunnelMode,
                    onCheckedChange = onAntiCorrelationEnabledChanged,
                    showDivider = true,
                )
                AdvancedDropdownSetting(
                    title = stringResource(R.string.routing_protection_dht_title),
                    description = stringResource(R.string.routing_protection_dht_body),
                    value = uiState.routingProtection.dhtMitigationMode,
                    enabled = sectionEnabled && !uiState.fullTunnelMode,
                    options = dhtOptions,
                    setting = AdvancedOptionSetting.DhtMitigationMode,
                    onSelected = { _, value -> onDhtMitigationModeSelected(value) },
                    showDivider = uiState.routingProtection.presets.isNotEmpty(),
                )
                uiState.routingProtection.presets.forEachIndexed { index, preset ->
                    SettingsRow(
                        title = preset.title,
                        subtitle = routingProtectionPresetSubtitle(preset.fixCoverage, preset.detectionMethod),
                        checked = preset.enabled,
                        enabled = sectionEnabled && !uiState.fullTunnelMode,
                        onCheckedChange = { enabled -> onAppRoutingPresetEnabledChanged(preset.id, enabled) },
                        showDivider = index != uiState.routingProtection.presets.lastIndex,
                    )
                    if (preset.matchedPackages.isNotEmpty()) {
                        Text(
                            text =
                                stringResource(
                                    R.string.routing_protection_detected_packages,
                                    preset.matchedPackages.joinToString(", "),
                                ),
                            style = RipDpiThemeTokens.type.caption,
                            color = RipDpiThemeTokens.colors.mutedForeground,
                            modifier = Modifier.padding(bottom = RipDpiThemeTokens.spacing.sm),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun routingProtectionPolicyOptions(): ImmutableList<RipDpiDropdownOption<String>> =
    persistentListOf(
        RipDpiDropdownOption(
            value = AppRoutingPolicyModePrompt,
            label = stringResource(R.string.routing_protection_policy_prompt_title),
        ),
        RipDpiDropdownOption(
            value = AppRoutingPolicyModeOff,
            label = stringResource(R.string.routing_protection_policy_off_title),
        ),
    )

@Composable
private fun routingProtectionDhtOptions(): ImmutableList<RipDpiDropdownOption<String>> =
    persistentListOf(
        RipDpiDropdownOption(
            value = DhtMitigationModeOff,
            label = stringResource(R.string.routing_protection_dht_off_title),
        ),
        RipDpiDropdownOption(
            value = DhtMitigationModeBypass,
            label = stringResource(R.string.routing_protection_dht_bypass_title),
        ),
        RipDpiDropdownOption(
            value = DhtMitigationModeDropWarn,
            label = stringResource(R.string.routing_protection_dht_drop_warn_title),
        ),
    )

private fun routingProtectionPresetSubtitle(
    fixCoverage: String,
    detectionMethod: String,
): String =
    buildString {
        append(fixCoverage)
        if (detectionMethod.isNotBlank()) {
            append(" (")
            append(detectionMethod)
            append(")")
        }
    }

@Composable
private fun RoutingProtectionSummaryCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard(modifier = modifier.padding(bottom = spacing.sm)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.routing_protection_summary_title),
                style = type.sectionTitle,
                color = colors.foreground,
            )
            Text(
                text =
                    if (uiState.fullTunnelMode) {
                        stringResource(R.string.routing_protection_full_tunnel_active)
                    } else {
                        stringResource(
                            R.string.routing_protection_summary_body,
                            uiState.routingProtection.detectedAppCount,
                            uiState.routingProtection.enabledPresetCount,
                        )
                    },
                style = type.secondaryBody,
                color = colors.foreground,
            )
            Text(
                text = uiState.routingProtection.transportVpnDisclosure,
                style = type.caption,
                color = colors.mutedForeground,
            )
            uiState.routingProtection.suggestions.forEach { suggestion ->
                Text(
                    text = "${suggestion.title}: ${suggestion.body}",
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}
