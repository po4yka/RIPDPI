package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun DetectionSettingsRoute(
    onBack: () -> Unit,
    viewModel: DetectionSettingsViewModel = hiltViewModel(),
) {
    val settingsState by viewModel.uiState.collectAsStateWithLifecycle()
    DetectionSettingsScreen(
        state = settingsState,
        onBack = onBack,
        actions =
            DetectionSettingsActions(
                onNetworkRequestsChange = remember(viewModel) { viewModel::setNetworkRequestsEnabled },
                onCdnPullingChange = remember(viewModel) { viewModel::setCdnPullingEnabled },
                onCdnPullingMeduzaChange = remember(viewModel) { viewModel::setCdnPullingMeduzaEnabled },
                onCallTransportProbeChange = remember(viewModel) { viewModel::setCallTransportProbeEnabled },
                onRttTriangulationChange = remember(viewModel) { viewModel::setRttTriangulationEnabled },
                onProxyScanChange = remember(viewModel) { viewModel::setProxyScanEnabled },
                onXrayApiScanChange = remember(viewModel) { viewModel::setXrayApiScanEnabled },
                onTunProbeModeChange = remember(viewModel) { viewModel::setTunProbeMode },
                onPortRangeModeChange = remember(viewModel) { viewModel::setPortRangeMode },
                onCustomPortStartChange = remember(viewModel) { viewModel::setCustomPortStart },
                onCustomPortEndChange = remember(viewModel) { viewModel::setCustomPortEnd },
                onDnsResolverModeChange = remember(viewModel) { viewModel::setDnsResolverMode },
                onDnsPresetChange = remember(viewModel) { viewModel::selectDnsPreset },
                onDnsDirectServersChange = remember(viewModel) { viewModel::setDnsDirectServers },
                onDnsDohUrlChange = remember(viewModel) { viewModel::setDnsDohUrl },
                onDnsDohBootstrapIpsChange = remember(viewModel) { viewModel::setDnsDohBootstrapIps },
                onDnsRouteThroughProxyChange = remember(viewModel) { viewModel::setDnsRouteThroughProxy },
                onDiagnosticRandomHostnamesChange =
                    remember(viewModel) { viewModel::setDiagnosticRandomHostnamesEnabled },
                onTlsKeylogPathChange = remember(viewModel) { viewModel::setTlsKeylogPath },
                onPrivacyModeChange = remember(viewModel) { viewModel.setPrivacyModeEnabled },
                onDebugModeChange = remember(viewModel) { viewModel.setDebugModeEnabled },
                onColorVisionModeChange = remember(viewModel) { viewModel.setColorVisionMode },
            ),
    )
}

internal data class DetectionSettingsActions(
    val onNetworkRequestsChange: (Boolean) -> Unit = {},
    val onCdnPullingChange: (Boolean) -> Unit = {},
    val onCdnPullingMeduzaChange: (Boolean) -> Unit = {},
    val onCallTransportProbeChange: (Boolean) -> Unit = {},
    val onRttTriangulationChange: (Boolean) -> Unit = {},
    val onProxyScanChange: (Boolean) -> Unit = {},
    val onXrayApiScanChange: (Boolean) -> Unit = {},
    val onTunProbeModeChange: (DetectionTunProbeMode) -> Unit = {},
    val onPortRangeModeChange: (DetectionPortRangeMode) -> Unit = {},
    val onCustomPortStartChange: (String) -> Unit = {},
    val onCustomPortEndChange: (String) -> Unit = {},
    val onDnsResolverModeChange: (DetectionDnsResolverMode) -> Unit = {},
    val onDnsPresetChange: (DetectionDnsPreset) -> Unit = {},
    val onDnsDirectServersChange: (String) -> Unit = {},
    val onDnsDohUrlChange: (String) -> Unit = {},
    val onDnsDohBootstrapIpsChange: (String) -> Unit = {},
    val onDnsRouteThroughProxyChange: (Boolean) -> Unit = {},
    val onDiagnosticRandomHostnamesChange: (Boolean) -> Unit = {},
    val onTlsKeylogPathChange: (String) -> Unit = {},
    val onPrivacyModeChange: (Boolean) -> Unit = {},
    val onDebugModeChange: (Boolean) -> Unit = {},
    val onColorVisionModeChange: (com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode) -> Unit = {},
)

@Composable
internal fun DetectionSettingsScreen(
    state: DetectionSettingsUiState,
    onBack: () -> Unit,
    actions: DetectionSettingsActions = DetectionSettingsActions(),
) {
    var showCdnConfirmation by rememberSaveable { mutableStateOf(false) }

    if (showCdnConfirmation) {
        CdnPullingConfirmationDialog(
            onDismissRequest = { showCdnConfirmation = false },
            onConfirm = {
                showCdnConfirmation = false
                actions.onCdnPullingChange(true)
            },
        )
    }

    RipDpiSettingsScaffold(
        title = stringResource(R.string.title_detection_settings),
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.DetectionSettings)),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
    ) {
        item {
            DetectionNetworkSettingsSection(
                state = state,
                onNetworkRequestsChange = actions.onNetworkRequestsChange,
                onCdnPullingChange = { enabled ->
                    if (enabled) {
                        showCdnConfirmation = true
                    } else {
                        actions.onCdnPullingChange(false)
                    }
                },
                onCdnPullingMeduzaChange = actions.onCdnPullingMeduzaChange,
                onCallTransportProbeChange = actions.onCallTransportProbeChange,
                onRttTriangulationChange = actions.onRttTriangulationChange,
            )
        }
        item {
            DetectionSplitTunnelSettingsSection(
                state = state,
                onProxyScanChange = actions.onProxyScanChange,
                onXrayApiScanChange = actions.onXrayApiScanChange,
                onTunProbeModeChange = actions.onTunProbeModeChange,
                onPortRangeModeChange = actions.onPortRangeModeChange,
                onCustomPortStartChange = actions.onCustomPortStartChange,
                onCustomPortEndChange = actions.onCustomPortEndChange,
            )
        }
        item {
            DetectionDnsSettingsSection(
                state = state,
                onDnsResolverModeChange = actions.onDnsResolverModeChange,
                onDnsPresetChange = actions.onDnsPresetChange,
                onDnsDirectServersChange = actions.onDnsDirectServersChange,
                onDnsDohUrlChange = actions.onDnsDohUrlChange,
                onDnsDohBootstrapIpsChange = actions.onDnsDohBootstrapIpsChange,
                onDnsRouteThroughProxyChange = actions.onDnsRouteThroughProxyChange,
            )
        }
        item {
            DetectionDiagnosticProbeSettingsSection(
                state = state,
                onDiagnosticRandomHostnamesChange = actions.onDiagnosticRandomHostnamesChange,
                onTlsKeylogPathChange = actions.onTlsKeylogPathChange,
            )
        }
        item {
            DetectionPrivacyAppearanceSection(
                state = state,
                onPrivacyModeChange = actions.onPrivacyModeChange,
                onDebugModeChange = actions.onDebugModeChange,
                onColorVisionModeChange = actions.onColorVisionModeChange,
            )
        }
    }
}

@Composable
private fun CdnPullingConfirmationDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismissRequest,
        title = "Enable CDN pulling",
        dismissAction =
            RipDpiDialogAction(
                label = "Cancel",
                onClick = onDismissRequest,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = "Enable",
                onClick = onConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = "This sends active test requests to selected public targets.",
            ),
    )
}

@Composable
private fun DetectionNetworkSettingsSection(
    state: DetectionSettingsUiState,
    onNetworkRequestsChange: (Boolean) -> Unit,
    onCdnPullingChange: (Boolean) -> Unit,
    onCdnPullingMeduzaChange: (Boolean) -> Unit,
    onCallTransportProbeChange: (Boolean) -> Unit,
    onRttTriangulationChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "Network probes") {
        SettingsRow(
            title = "Network requests",
            subtitle = "Master switch for active network checks",
            checked = state.networkRequestsEnabled,
            onCheckedChange = onNetworkRequestsChange,
        )
        DependentSettings(
            state = state,
        ) {
            SettingsRow(
                title = "CDN pulling",
                subtitle = "Trace CDN edge and TLS interception signals",
                checked = state.cdnPullingEnabled,
                enabled = state.networkDependentEnabled,
                onCheckedChange = onCdnPullingChange,
            )
            SettingsRow(
                title = "Meduza cohort",
                subtitle = "Include the Meduza target set",
                checked = state.cdnPullingMeduzaEnabled,
                enabled = state.networkDependentEnabled && state.cdnPullingEnabled,
                onCheckedChange = onCdnPullingMeduzaChange,
            )
            SettingsRow(
                title = "Call transport probe",
                subtitle = "Probe MTProto and STUN through a detected proxy",
                checked = state.callTransportProbeEnabled,
                enabled = state.networkDependentEnabled,
                onCheckedChange = onCallTransportProbeChange,
            )
            SettingsRow(
                title = "RTT triangulation",
                subtitle = "Compare latency from multiple endpoints",
                checked = state.rttTriangulationEnabled,
                enabled = state.networkDependentEnabled,
                onCheckedChange = onRttTriangulationChange,
            )
        }
    }
}

@Composable
private fun DetectionSplitTunnelSettingsSection(
    state: DetectionSettingsUiState,
    onProxyScanChange: (Boolean) -> Unit,
    onXrayApiScanChange: (Boolean) -> Unit,
    onTunProbeModeChange: (DetectionTunProbeMode) -> Unit,
    onPortRangeModeChange: (DetectionPortRangeMode) -> Unit,
    onCustomPortStartChange: (String) -> Unit,
    onCustomPortEndChange: (String) -> Unit,
) {
    SettingsSection(title = "Split tunnel") {
        SettingsRow(
            title = "Proxy scan",
            subtitle = "Search localhost proxy endpoints",
            checked = state.proxyScanEnabled,
            onCheckedChange = onProxyScanChange,
        )
        SettingsRow(
            title = "Xray API scan",
            subtitle = "Search localhost gRPC control ports",
            checked = state.xrayApiScanEnabled,
            onCheckedChange = onXrayApiScanChange,
        )
        ChipSelector(
            title = "TUN probe mode",
            entries = DetectionTunProbeMode.entries,
            selected = state.tunProbeMode,
            label = DetectionTunProbeMode::displayName,
            onSelect = onTunProbeModeChange,
        )
        ChipSelector(
            title = "Port range",
            entries = DetectionPortRangeMode.entries,
            selected = state.portRangeMode,
            label = DetectionPortRangeMode::displayName,
            onSelect = onPortRangeModeChange,
        )
        if (state.portRangeMode == DetectionPortRangeMode.CUSTOM) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                PortTextField(
                    label = "Start",
                    value = state.customPortStart.toString(),
                    onValueChange = onCustomPortStartChange,
                    modifier = Modifier.weight(1f),
                )
                PortTextField(
                    label = "End",
                    value = state.customPortEnd.toString(),
                    onValueChange = onCustomPortEndChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SettingsRow(
            title = "Port count",
            value = state.selectedPortCount?.toString() ?: "popular set",
        )
    }
}

@Composable
private fun DetectionDnsSettingsSection(
    state: DetectionSettingsUiState,
    onDnsResolverModeChange: (DetectionDnsResolverMode) -> Unit,
    onDnsPresetChange: (DetectionDnsPreset) -> Unit,
    onDnsDirectServersChange: (String) -> Unit,
    onDnsDohUrlChange: (String) -> Unit,
    onDnsDohBootstrapIpsChange: (String) -> Unit,
    onDnsRouteThroughProxyChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "DNS resolver") {
        ChipSelector(
            title = "Mode",
            entries = DetectionDnsResolverMode.entries,
            selected = state.dnsResolverMode,
            label = DetectionDnsResolverMode::displayName,
            onSelect = onDnsResolverModeChange,
        )
        DnsProviderPicker(
            selected = state.dnsPreset,
            onSelect = onDnsPresetChange,
        )
        RipDpiTextField(
            value = state.dnsDirectServers,
            onValueChange = onDnsDirectServersChange,
            decoration = RipDpiTextFieldDecoration(label = "Direct servers"),
            behavior = RipDpiTextFieldBehavior(enabled = state.dnsFieldsEditable),
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiTextField(
            value = state.dnsDohUrl,
            onValueChange = onDnsDohUrlChange,
            decoration = RipDpiTextFieldDecoration(label = "DoH URL"),
            behavior =
                RipDpiTextFieldBehavior(
                    enabled = state.dnsFieldsEditable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiTextField(
            value = state.dnsDohBootstrapIps,
            onValueChange = onDnsDohBootstrapIpsChange,
            decoration = RipDpiTextFieldDecoration(label = "DoH bootstrap IPs"),
            behavior = RipDpiTextFieldBehavior(enabled = state.dnsFieldsEditable),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsRow(
            title = stringResource(R.string.detection_dns_route_through_proxy_title),
            subtitle = stringResource(R.string.detection_dns_route_through_proxy_subtitle),
            checked = state.dnsRouteThroughProxy,
            onCheckedChange = onDnsRouteThroughProxyChange,
        )
    }
}

/**
 * Catalog-driven DNS provider picker: the full bundled provider set plus the user-defined custom
 * sentinel, surfaced as a single dropdown so the list scales past the legacy 3-option chip row.
 * Below the field it renders a flag caption (no-log / no-filter / DNSSEC) and a jurisdiction warning
 * for resolvers operated under RU jurisdiction (advisory app-config metadata only).
 */
@Composable
private fun DnsProviderPicker(
    selected: DetectionDnsPreset,
    onSelect: (DetectionDnsPreset) -> Unit,
) {
    val presets = DetectionDnsPreset.catalogPresets
    val options =
        presets
            .map { preset ->
                RipDpiDropdownOption(value = preset.wireValue, label = preset.displayName)
            }.toImmutableList()
    val caption = dnsProviderFlagsCaption(selected)
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        RipDpiDropdown(
            options = options,
            selectedValue = selected.wireValue,
            onValueSelected = { wireValue ->
                presets.firstOrNull { it.wireValue == wireValue }?.let(onSelect)
            },
            label = stringResource(R.string.detection_dns_provider_label),
            modifier = Modifier.fillMaxWidth(),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        if (selected.jurisdictionRu) {
            Text(
                text = stringResource(R.string.detection_dns_provider_jurisdiction_ru_warning),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.warning,
            )
        }
    }
}

@Composable
private fun dnsProviderFlagsCaption(preset: DetectionDnsPreset): String? {
    if (preset.isCustom) {
        return null
    }
    val flags =
        buildList {
            if (preset.noLog) add(stringResource(R.string.detection_dns_provider_flag_no_log))
            if (preset.noFilter) add(stringResource(R.string.detection_dns_provider_flag_no_filter))
            if (preset.dnssec) add(stringResource(R.string.detection_dns_provider_flag_dnssec))
        }
    return flags
        .takeIf { it.isNotEmpty() }
        ?.joinToString(stringResource(R.string.detection_dns_provider_flag_separator))
}

@Composable
private fun DetectionDiagnosticProbeSettingsSection(
    state: DetectionSettingsUiState,
    onDiagnosticRandomHostnamesChange: (Boolean) -> Unit,
    onTlsKeylogPathChange: (String) -> Unit,
) {
    SettingsSection(title = "Diagnostic probes") {
        SettingsRow(
            title = "Use random hostnames in diagnostic probes",
            subtitle = "Avoid fixed test-domain allowlists by sending fresh Host/SNI values.",
            checked = state.diagnosticRandomHostnamesEnabled,
            enabled = state.networkDependentEnabled,
            onCheckedChange = onDiagnosticRandomHostnamesChange,
        )
        if (state.tlsKeylogPathVisible) {
            RipDpiTextField(
                value = state.tlsKeylogPath,
                onValueChange = onTlsKeylogPathChange,
                decoration = RipDpiTextFieldDecoration(label = "TLS keylog path"),
                behavior =
                    RipDpiTextFieldBehavior(
                        enabled = !state.privacyModeEnabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.privacyModeEnabled) {
                Text(
                    text = "Privacy mode suppresses TLS key logging.",
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun DetectionPrivacyAppearanceSection(
    state: DetectionSettingsUiState,
    onPrivacyModeChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onColorVisionModeChange: (com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode) -> Unit,
) {
    SettingsSection(title = "Privacy and status") {
        SettingsRow(
            title = "Privacy mode",
            subtitle =
                "Masks exported addresses and disables PT reachability checks that contact brokers and obfs4 bridges.",
            checked = state.privacyModeEnabled,
            onCheckedChange = onPrivacyModeChange,
        )
        SettingsRow(
            title = "Debug diagnostics",
            checked = state.debugModeEnabled,
            onCheckedChange = onDebugModeChange,
        )
        ChipSelector(
            title = "Status visuals",
            entries = com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode.entries,
            selected = state.colorVisionMode,
            label = com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode::displayName,
            onSelect = onColorVisionModeChange,
        )
        StatusVisualPreviewRow(mode = state.colorVisionMode)
        if (state.protanopiaVariantUnlocked) {
            Text(
                text = "Protanopia variant unlocked",
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            content = { content() },
        )
    }
}

@Composable
private fun DependentSettings(
    state: DetectionSettingsUiState,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(state.networkDependentAlpha),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        content = { content() },
    )
}

@Composable
private fun <T> ChipSelector(
    title: String,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.smallLabel,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                RipDpiChip(
                    text = label(entry),
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun PortTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    RipDpiTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        decoration = RipDpiTextFieldDecoration(label = label),
        behavior = RipDpiTextFieldBehavior(keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)),
        modifier = modifier.padding(top = RipDpiThemeTokens.spacing.xs),
    )
}
