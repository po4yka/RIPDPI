package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTab
import com.poyka.ripdpi.ui.components.inputs.RipDpiTabs
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.proxyimport.ClipboardImportViewModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import kotlinx.collections.immutable.toImmutableList

enum class ConfigEditorTarget {
    Bypass,
    Resolver,
}

@androidx.compose.runtime.Stable
data class ConfigProfileActions(
    val share: (String) -> Unit = {},
    val select: (String) -> Unit = {},
    val edit: (String) -> Unit = {},
    val create: (Route) -> Unit = {},
    val scanServer: () -> Unit = {},
)

@Composable
fun ConfigRoute(
    onOpenModeEditor: () -> Unit,
    onOpenLocalBypassEditor: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRetestStrategies: () -> Unit,
    onScanServer: () -> Unit,
    onOpenProfileEditor: (Route) -> Unit,
    modifier: Modifier = Modifier,
    route: Route = Route.Config,
    initialModeSection: ConfigModeSection = ConfigModeSection.LocalBypass,
    viewModel: ConfigViewModel = hiltViewModel(),
    clipboardImportViewModel: ClipboardImportViewModel = hiltViewModel(),
    onProfileImport: (ProxyImportRequest.Profile) -> Unit = {},
    onProfileShare: (String) -> Unit = {},
    runtimeIntegration: ConfigRuntimeIntegration = ConfigRuntimeIntegration(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val runtime = configRuntimePresentation(viewModel, uiState, runtimeIntegration)
    val clipboardImportState = clipboardImportViewModel.uiState.collectAsStateWithLifecycle().value
    val navigateTo = clipboardImportState.navigateToConfirm
    if (navigateTo is ProxyImportRequest.Profile) {
        androidx.compose.runtime.LaunchedEffect(navigateTo) {
            clipboardImportViewModel.consumeNavigation()
            onProfileImport(navigateTo)
        }
    }
    val onImportFromClipboard =
        remember(clipboardImportViewModel) { clipboardImportViewModel::onImportFromClipboard }
    val onDismissClipboardError =
        remember(clipboardImportViewModel) { clipboardImportViewModel::dismissError }

    ConfigScreen(
        uiState = runtime.uiState,
        runtimeCallbacks = runtime.callbacks,
        modifier = modifier,
        route = route,
        topBarActions = {
            ConfigImportMenu(
                unknownContentScheme = clipboardImportState.unknownContentScheme,
                clipboardEmpty = clipboardImportState.clipboardEmpty,
                onImportFromClipboard = onImportFromClipboard,
                onDismissError = onDismissClipboardError,
            )
        },
        onModeSelected = remember(viewModel) { viewModel::selectMode },
        onRuntimeModeToggle = remember(viewModel) { viewModel::toggleRuntimeMode },
        onPresetSelected = { preset ->
            when (preset.kind) {
                ConfigPresetKind.Custom -> {
                    viewModel.startEditingPreset(preset.id)
                    onOpenModeEditor()
                }

                else -> {
                    viewModel.selectPreset(preset.id)
                }
            }
        },
        onEditCurrent = {
            val selectedPresetId = uiState.presets.firstOrNull { it.isSelected }?.id ?: "custom"
            viewModel.startEditingPreset(selectedPresetId)
            onOpenModeEditor()
        },
        onOpenConfigEditor = { path ->
            when (path) {
                ConfigEditorTarget.Bypass -> onOpenLocalBypassEditor()
                ConfigEditorTarget.Resolver -> onOpenDnsSettings()
            }
        },
        onRetestStrategies = onRetestStrategies,
        onPasteServerLink = clipboardImportViewModel::onImportFromClipboard,
        profileActions =
            ConfigProfileActions(
                scanServer = onScanServer,
                share = onProfileShare,
                select = remember(viewModel) { viewModel::selectRelayProfile },
                create = onOpenProfileEditor,
                edit = { profileId ->
                    when (uiState.vpnProfiles.firstOrNull { it.id == profileId }?.kind) {
                        com.poyka.ripdpi.data.RelayKindMieru -> {
                            onOpenProfileEditor(Route.MieruProfileEdit(profileId))
                        }

                        com.poyka.ripdpi.data.RelayKindSsh -> {
                            onOpenProfileEditor(Route.SshProfileEdit(profileId))
                        }

                        else -> {
                            viewModel.startEditingPreset("relay-profile:$profileId")
                            onOpenModeEditor()
                        }
                    }
                },
            ),
        initialModeSection = initialModeSection,
    )
}

@Suppress("LongMethod")
@Composable
fun ConfigScreen(
    uiState: ConfigUiState,
    onModeSelected: (Mode) -> Unit,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit = { _, _ -> },
    onPresetSelected: (ConfigPreset) -> Unit,
    onEditCurrent: () -> Unit,
    onOpenConfigEditor: (ConfigEditorTarget) -> Unit,
    onRetestStrategies: () -> Unit,
    onPasteServerLink: () -> Unit,
    profileActions: ConfigProfileActions = ConfigProfileActions(),
    modifier: Modifier = Modifier,
    route: Route = Route.Config,
    initialModeSection: ConfigModeSection = ConfigModeSection.LocalBypass,
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    runtimeCallbacks: ConfigRuntimeCallbacks = ConfigRuntimeCallbacks(),
) {
    val colors = RipDpiThemeTokens.colors
    val desyncSummary = uiState.draft.chainSummary
    var selectedModeSectionKey by rememberSaveable { mutableStateOf(initialModeSection.stableKey) }
    val selectedModeSection = ConfigModeSection.fromStableKey(selectedModeSectionKey)
    var showProfileChoices by rememberSaveable { mutableStateOf(false) }

    RipDpiContentScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(route))
                .fillMaxSize()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.config),
        contentWidth = RipDpiScaffoldWidth.Content,
        actions = topBarActions,
    ) {
        ConfigSavedConnectionCard(
            uiState = uiState,
            profilesExpanded = showProfileChoices,
            onChangeProfile = { showProfileChoices = !showProfileChoices },
        )
        ConfigPrimaryRuntimeAction(
            uiState = uiState,
            onRuntimeModeToggle = onRuntimeModeToggle,
            callbacks = runtimeCallbacks,
        )
        if (showProfileChoices) {
            Column(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigProfileChoices),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                ConfigPresetList(uiState, onPresetSelected)
                ConfigRelayProfileChoices(uiState, profileActions, onPasteServerLink)
            }
        }

        RipDpiCard {
            Text(
                text = stringResource(R.string.config_connection_settings),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = colors.foreground,
            )
            ConfigModeChips(
                selectedMode = uiState.activeMode,
                onModeSelected = onModeSelected,
                vpnLabel = stringResource(R.string.config_scope_device),
                proxyLabel = stringResource(R.string.config_scope_proxy),
                label = stringResource(R.string.config_traffic_mode_title),
                groupTestTag = RipDpiTestTags.ConfigTrafficEndpointSelection,
            )
            ConfigModeSectionSwitcher(
                selectedSection = selectedModeSection,
                onSectionSelected = { section -> selectedModeSectionKey = section.stableKey },
            )
        }

        ConfigSelectedModeSection(
            onRequestVpnPermission = runtimeCallbacks.requestVpnPermission,
            onDismissRuntimeError = runtimeCallbacks.dismissError,
            section = selectedModeSection,
            uiState = uiState,
            desyncSummary = desyncSummary,
            onRuntimeModeToggle = onRuntimeModeToggle,
            onEditCurrent = onEditCurrent,
            onOpenConfigEditor = onOpenConfigEditor,
            onRetestStrategies = onRetestStrategies,
            onPasteServerLink = onPasteServerLink,
            onScanServer = profileActions.scanServer,
            profileActions = profileActions,
        )

        ConfigConnectionGuidance(uiState, selectedModeSection)
    }
}

@Composable
private fun ConfigPresetList(
    uiState: ConfigUiState,
    onPresetSelected: (ConfigPreset) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_presets_section))
        if (uiState.isLoading) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.ConfigPresetsLoading),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                RipDpiSpinner()
                RipDpiEmptyStateCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.config_presets_loading_title),
                    body = stringResource(R.string.config_presets_loading_body),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                uiState.presets.forEach { preset ->
                    PresetCard(
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.configPreset(preset.id)),
                        title = stringResource(titleResForPreset(preset.kind)),
                        description = stringResource(descriptionResForPreset(preset.kind)),
                        badgeText =
                            if (preset.isSelected) {
                                stringResource(R.string.config_badge_active)
                            } else {
                                null
                            },
                        selected = preset.isSelected,
                        onClick = { onPresetSelected(preset) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfigModeSectionSwitcher(
    selectedSection: ConfigModeSection,
    onSectionSelected: (ConfigModeSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val groupLabel = stringResource(R.string.config_mode_sections_title)
    val sections = ConfigModeSection.entries

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = groupLabel,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiTabs(
            tabs =
                sections
                    .map { section ->
                        RipDpiTab(
                            key = section.stableKey,
                            label = stringResource(configModeSectionTitleRes(section)),
                            testTag = RipDpiTestTags.configModeSection(section.stableKey),
                        )
                    }.toImmutableList(),
            selectedIndex = sections.indexOf(selectedSection),
            onSelect = { index -> sections.getOrNull(index)?.let(onSectionSelected) },
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigSectionNavigation),
        )
    }
}

@Composable
private fun ConfigSelectedModeSection(
    onRequestVpnPermission: () -> Unit,
    onDismissRuntimeError: () -> Unit,
    section: ConfigModeSection,
    uiState: ConfigUiState,
    desyncSummary: String,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit,
    onEditCurrent: () -> Unit,
    onOpenConfigEditor: (ConfigEditorTarget) -> Unit,
    onRetestStrategies: () -> Unit,
    onPasteServerLink: () -> Unit,
    onScanServer: () -> Unit,
    profileActions: ConfigProfileActions,
) {
    when (section) {
        ConfigModeSection.LocalBypass -> {
            LocalBypassConfigScreen(
                uiState = uiState,
                settingsOnly = true,
                onRequestVpnPermission = onRequestVpnPermission,
                onDismissRuntimeError = onDismissRuntimeError,
                desyncSummary = desyncSummary,
                onRuntimeModeToggle = onRuntimeModeToggle,
                onOpenDesyncSettings = { onOpenConfigEditor(ConfigEditorTarget.Bypass) },
                onOpenDnsSettings = { onOpenConfigEditor(ConfigEditorTarget.Resolver) },
                onRetestStrategies = onRetestStrategies,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassSummary),
            )
        }

        ConfigModeSection.Vpn -> {
            VpnConfigScreen(
                uiState = uiState,
                settingsOnly = true,
                onRequestVpnPermission = onRequestVpnPermission,
                onDismissRuntimeError = onDismissRuntimeError,
                onRuntimeModeToggle = onRuntimeModeToggle,
                onOpenRelaySettings = onEditCurrent,
                onOpenDnsSettings = { onOpenConfigEditor(ConfigEditorTarget.Resolver) },
                onPasteServerLink = onPasteServerLink,
                onScanServer = onScanServer,
                onProfileShare = profileActions.share,
                onProfileSelect = profileActions.select,
                onProfileEdit = profileActions.edit,
                onCreateProfile = profileActions.create,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigVpnSummary),
            )
        }
    }
}

@Composable
internal fun ConfigModeChips(
    selectedMode: Mode,
    onModeSelected: (Mode) -> Unit,
    vpnLabel: String,
    proxyLabel: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    groupTestTag: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        label?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .ripDpiTestTag(groupTestTag),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiChip(
                text = vpnLabel,
                selected = selectedMode == Mode.VPN,
                onClick = { onModeSelected(Mode.VPN) },
                role = Role.RadioButton,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.configMode(Mode.VPN.name)),
            )
            RipDpiChip(
                text = proxyLabel,
                selected = selectedMode == Mode.Proxy,
                onClick = { onModeSelected(Mode.Proxy) },
                role = Role.RadioButton,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.configMode(Mode.Proxy.name)),
            )
        }
    }
}

internal fun titleResForPreset(kind: ConfigPresetKind): Int =
    when (kind) {
        ConfigPresetKind.Recommended -> R.string.config_preset_recommended_title
        ConfigPresetKind.Proxy -> R.string.config_preset_proxy_title
        ConfigPresetKind.Custom -> R.string.config_preset_custom_title
    }

private fun descriptionResForPreset(kind: ConfigPresetKind): Int =
    when (kind) {
        ConfigPresetKind.Recommended -> R.string.config_preset_recommended_body
        ConfigPresetKind.Proxy -> R.string.config_preset_proxy_body
        ConfigPresetKind.Custom -> R.string.config_preset_custom_body
    }

internal fun modeLabelRes(mode: Mode): Int =
    when (mode) {
        Mode.VPN -> R.string.home_mode_vpn
        Mode.Proxy -> R.string.home_mode_proxy
    }

/**
 * Config UI terminology is intentionally two-level:
 * Profile = a named saved configuration; Mode = the bypass path inside that profile.
 */
enum class ConfigModeSection(
    val stableKey: String,
) {
    LocalBypass("local_bypass"),
    Vpn("vpn"),
    ;

    companion object {
        fun fromStableKey(key: String): ConfigModeSection = entries.firstOrNull { it.stableKey == key } ?: LocalBypass
    }
}

private fun configModeSectionTitleRes(section: ConfigModeSection): Int =
    when (section) {
        ConfigModeSection.LocalBypass -> R.string.config_section_bypass_settings
        ConfigModeSection.Vpn -> R.string.config_section_server_settings
    }

@Preview(showBackground = true)
@Composable
private fun ConfigScreenPreview() {
    RipDpiTheme {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = Mode.VPN,
                    presets = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
                    draft = AppSettingsSerializer.defaultValue.toConfigDraft(),
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenConfigEditor = {},
            onRetestStrategies = {},
            onPasteServerLink = {},
            initialModeSection = ConfigModeSection.LocalBypass,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigScreenDarkPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            proxyIp = "192.168.0.4",
            proxyPort = "1086",
            useCommandLineSettings = true,
            commandLineArgs = "--fake --split 2",
            defaultTtl = "12",
        )
    RipDpiTheme(themePreference = "dark") {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenConfigEditor = {},
            onRetestStrategies = {},
            onPasteServerLink = {},
            initialModeSection = ConfigModeSection.Vpn,
        )
    }
}

@Preview(showBackground = true, name = "ConfigScreen (loading)")
@Composable
private fun ConfigScreenLoadingPreview() {
    RipDpiTheme {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = Mode.VPN,
                    presets = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
                    draft = AppSettingsSerializer.defaultValue.toConfigDraft(),
                    isLoading = true,
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenConfigEditor = {},
            onRetestStrategies = {},
            onPasteServerLink = {},
            initialModeSection = ConfigModeSection.LocalBypass,
        )
    }
}
