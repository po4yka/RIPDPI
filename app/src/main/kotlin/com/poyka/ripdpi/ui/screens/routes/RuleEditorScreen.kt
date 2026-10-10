package com.poyka.ripdpi.ui.screens.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
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
 * @param ruleId informational for callers/NavHost; the ViewModel reads it from [SavedStateHandle],
 *   so it is intentionally not consumed here.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RuleEditorRoute(
    ruleId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RuleEditorViewModel = hiltViewModel(),
) {
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
        persistenceActions =
            RuleEditorPersistenceActions(
                save = { viewModel.save(onBack) },
                retryLoad = viewModel::retryLoad,
            ),
        modifier = modifier,
    )
}

internal class RuleEditorPersistenceActions(
    val save: () -> Unit,
    val retryLoad: () -> Unit = {},
)

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
    persistenceActions: RuleEditorPersistenceActions,
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
        state.failure?.let { failure ->
            item(key = "rule_editor_failure") {
                RuleEditorFailureCard(failure, persistenceActions.retryLoad)
            }
        }
        if (!state.loaded && state.failure == null) {
            item(key = "rule_editor_loading") { RipDpiSpinner() }
        } else if (state.loaded) {
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
            actionsSection(state = state, onBack = onBack, onSave = persistenceActions.save)
        }
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

@Composable
private fun RuleEditorFailureCard(
    failure: RuleEditorFailure,
    onRetryLoad: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        WarningBanner(
            title =
                stringResource(
                    if (failure ==
                        RuleEditorFailure.Load
                    ) {
                        R.string.ui_rule_load_failed_title
                    } else {
                        R.string.ui_rule_save_failed_title
                    },
                ),
            message =
                stringResource(
                    if (failure ==
                        RuleEditorFailure.Load
                    ) {
                        R.string.ui_rule_load_failed_body
                    } else {
                        R.string.ui_rule_save_failed_body
                    },
                ),
            tone = WarningBannerTone.Error,
        )
        if (failure == RuleEditorFailure.Load) {
            RipDpiButton(
                text = stringResource(R.string.startup_recovery_retry),
                onClick = onRetryLoad,
                modifier = Modifier.fillMaxWidth(),
                wrapLabel = true,
            )
        }
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
                value = state.name,
                onValueChange = onNameChange,
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
                RipDpiSwitch(
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                    accessibilityLabel = stringResource(R.string.rule_editor_enabled),
                )
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
            )
            MultilineMatcher(
                value = state.ipCidrs,
                onValueChange = onIpCidrsChange,
                label = stringResource(R.string.rule_editor_ip_label),
                helperText = stringResource(R.string.rule_editor_ip_helper),
            )
            MultilineMatcher(
                value = state.ports,
                onValueChange = onPortsChange,
                label = stringResource(R.string.rule_editor_ports_label),
                helperText = stringResource(R.string.rule_editor_ports_helper),
            )
            MultilineMatcher(
                value = state.sourcePorts,
                onValueChange = onSourcePortsChange,
                label = stringResource(R.string.rule_editor_source_ports_label),
                helperText = stringResource(R.string.rule_editor_ports_helper),
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
                value = state.processName,
                onValueChange = onProcessNameChange,
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
                enabled = state.loaded && !state.saving && !state.isEmpty,
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
) {
    RipDpiConfigTextField(
        value = value,
        onValueChange = onValueChange,
        decoration =
            RipDpiTextFieldDecoration(
                label = label,
                helperText = helperText,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            ),
        multiline = true,
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
            persistenceActions = RuleEditorPersistenceActions(save = {}),
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
            persistenceActions = RuleEditorPersistenceActions(save = {}),
        )
    }
}
