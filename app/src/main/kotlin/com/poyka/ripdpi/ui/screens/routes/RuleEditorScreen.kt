package com.poyka.ripdpi.ui.screens.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/**
 * @param ruleId identifier carried by the Navigation 3 route key.
 */
@Composable
fun RuleEditorRoute(
    ruleId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RuleEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, ruleId) {
        viewModel.initialize(ruleId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RuleEditorScreen(
        state = state,
        onBack = onBack,
        onNameChange = viewModel::setName,
        onEnabledChange = viewModel::setEnabled,
        onDomainsChange = viewModel::setDomains,
        onIpCidrsChange = viewModel::setIpCidrs,
        onPortsChange = viewModel::setPorts,
        onSourcePortsChange = viewModel::setSourcePorts,
        onNetworkChange = viewModel::setNetwork,
        onProcessNameChange = viewModel::setProcessName,
        onPackagesChange = viewModel::setPackages,
        onOutboundChange = viewModel::setOutboundTag,
        onSave = { viewModel.save(onBack) },
        modifier = modifier,
    )
}

@Composable
internal fun RuleEditorScreen(
    state: RuleEditorUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDomainsChange: (String) -> Unit,
    onIpCidrsChange: (String) -> Unit,
    onPortsChange: (String) -> Unit,
    onSourcePortsChange: (String) -> Unit,
    onNetworkChange: (RuleNetwork) -> Unit,
    onProcessNameChange: (String) -> Unit,
    onPackagesChange: (Set<String>) -> Unit,
    onOutboundChange: (OutboundTag) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAppPicker by remember { mutableStateOf(false) }

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.RuleEditor()))
                .fillMaxSize(),
        title = stringResource(R.string.title_rule_editor),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        if (state.isEmpty) {
            item(key = "rule_editor_empty_warning") {
                WarningBanner(
                    title = stringResource(R.string.rule_editor_empty_error_title),
                    message = stringResource(R.string.rule_editor_empty_error_body),
                    tone = WarningBannerTone.Warning,
                )
            }
        }
        identitySection(state = state, onNameChange = onNameChange, onEnabledChange = onEnabledChange)
        matchersSection(
            state = state,
            onDomainsChange = onDomainsChange,
            onIpCidrsChange = onIpCidrsChange,
            onPortsChange = onPortsChange,
            onSourcePortsChange = onSourcePortsChange,
        )
        advancedSection(
            state = state,
            onNetworkChange = onNetworkChange,
            onProcessNameChange = onProcessNameChange,
            onOpenAppPicker = { showAppPicker = true },
        )
        outboundSection(state = state, onOutboundChange = onOutboundChange)
        actionsSection(state = state, onBack = onBack, onSave = onSave)
    }

    if (showAppPicker) {
        AppPickerSheet(
            apps = state.installedApps,
            initialSelection = state.packages,
            onConfirm = {
                onPackagesChange(it)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.identitySection(
    state: RuleEditorUiState,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    item(key = "rule_editor_identity") {
        RipDpiCard {
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = state.name,
                        onValueChange = onNameChange,
                        replacementKey = state.textFieldReplacementRevision,
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.rule_editor_name_label),
                        testTag = RipDpiTestTags.RuleEditorName,
                    ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.rule_editor_enabled),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.matchersSection(
    state: RuleEditorUiState,
    onDomainsChange: (String) -> Unit,
    onIpCidrsChange: (String) -> Unit,
    onPortsChange: (String) -> Unit,
    onSourcePortsChange: (String) -> Unit,
) {
    item(key = "rule_editor_matchers") {
        RipDpiCard {
            MultilineMatcher(
                value = state.domains,
                onValueChange = onDomainsChange,
                label = stringResource(R.string.rule_editor_domains_label),
                helperText = stringResource(R.string.rule_editor_domains_helper),
                replacementKey = state.textFieldReplacementRevision,
            )
            MultilineMatcher(
                value = state.ipCidrs,
                onValueChange = onIpCidrsChange,
                label = stringResource(R.string.rule_editor_ip_label),
                helperText = stringResource(R.string.rule_editor_ip_helper),
                replacementKey = state.textFieldReplacementRevision,
            )
            MultilineMatcher(
                value = state.ports,
                onValueChange = onPortsChange,
                label = stringResource(R.string.rule_editor_ports_label),
                helperText = stringResource(R.string.rule_editor_ports_helper),
                replacementKey = state.textFieldReplacementRevision,
            )
            MultilineMatcher(
                value = state.sourcePorts,
                onValueChange = onSourcePortsChange,
                label = stringResource(R.string.rule_editor_source_ports_label),
                helperText = stringResource(R.string.rule_editor_ports_helper),
                replacementKey = state.textFieldReplacementRevision,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSection(
    state: RuleEditorUiState,
    onNetworkChange: (RuleNetwork) -> Unit,
    onProcessNameChange: (String) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    item(key = "rule_editor_advanced") {
        RipDpiCard {
            RipDpiDropdown(
                options = networkOptions(),
                selectedValue = state.network,
                onValueSelected = onNetworkChange,
                label = stringResource(R.string.rule_editor_network_label),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = state.processName,
                        onValueChange = onProcessNameChange,
                        replacementKey = state.textFieldReplacementRevision,
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.rule_editor_process_label),
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.rule_editor_apps_selected, state.packages.size),
                onClick = onOpenAppPicker,
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Outline,
                density = RipDpiControlDensity.Compact,
                leadingIcon = RipDpiIcons.Search,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.outboundSection(
    state: RuleEditorUiState,
    onOutboundChange: (OutboundTag) -> Unit,
) {
    item(key = "rule_editor_outbound") {
        RipDpiCard {
            RipDpiDropdown(
                options =
                    state.outboundTargets
                        .map { RipDpiDropdownOption(it.tag, it.label) }
                        .toImmutableList(),
                selectedValue = state.outboundTag,
                onValueSelected = onOutboundChange,
                label = stringResource(R.string.rule_editor_outbound_label),
                testTag = RipDpiTestTags.RuleEditorOutbound,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.actionsSection(
    state: RuleEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    item(key = "rule_editor_actions") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.rule_editor_cancel),
                onClick = onBack,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Outline,
                leadingIcon = RipDpiIcons.Close,
            )
            RipDpiButton(
                text = stringResource(R.string.rule_editor_save),
                onClick = onSave,
                modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.RuleEditorSave),
                enabled = !state.isEmpty,
                leadingIcon = RipDpiIcons.Check,
            )
        }
    }
}

@Composable
private fun MultilineMatcher(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helperText: String,
    replacementKey: Any,
) {
    RipDpiConfigTextField(
        state =
            rememberRipDpiTextFieldState(
                value = value,
                onValueChange = onValueChange,
                replacementKey = replacementKey,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = label,
                helperText = helperText,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            ),
        lineLimits = TextFieldLineLimits.MultiLine(),
    )
}

@Composable
private fun networkOptions() =
    listOf(
        RipDpiDropdownOption(RuleNetwork.BOTH, stringResource(R.string.rule_editor_network_both)),
        RipDpiDropdownOption(RuleNetwork.TCP, stringResource(R.string.rule_editor_network_tcp)),
        RipDpiDropdownOption(RuleNetwork.UDP, stringResource(R.string.rule_editor_network_udp)),
    ).toImmutableList()

@Preview(showBackground = true)
@Composable
private fun previewRuleEditorScreen() {
    RipDpiTheme(themePreference = "light") {
        RuleEditorScreen(
            state =
                RuleEditorUiState(
                    name = "Streaming direct",
                    domains = "domain_suffix:netflix.com\ngeosite:netflix",
                    network = RuleNetwork.BOTH,
                    packages = setOf("com.netflix.mediaclient").toImmutableSet(),
                    outboundTag = OutboundTag.Bypass,
                    outboundTargets =
                        listOf(
                            OutboundTarget(OutboundTag.Proxy, "Proxy"),
                            OutboundTarget(OutboundTag.Bypass, "Bypass (direct)"),
                            OutboundTarget(OutboundTag.Block, "Block"),
                        ).toImmutableList(),
                    loaded = true,
                ),
            onBack = {},
            onNameChange = {},
            onEnabledChange = {},
            onDomainsChange = {},
            onIpCidrsChange = {},
            onPortsChange = {},
            onSourcePortsChange = {},
            onNetworkChange = {},
            onProcessNameChange = {},
            onPackagesChange = {},
            onOutboundChange = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRuleEditorScreenEmptyDark() {
    RipDpiTheme(themePreference = "dark") {
        RuleEditorScreen(
            state =
                RuleEditorUiState(
                    outboundTargets =
                        listOf(OutboundTarget(OutboundTag.Proxy, "Proxy")).toImmutableList(),
                    loaded = true,
                ),
            onBack = {},
            onNameChange = {},
            onEnabledChange = {},
            onDomainsChange = {},
            onIpCidrsChange = {},
            onPortsChange = {},
            onSourcePortsChange = {},
            onNetworkChange = {},
            onProcessNameChange = {},
            onPackagesChange = {},
            onOutboundChange = {},
            onSave = {},
        )
    }
}
