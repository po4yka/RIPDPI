package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTab
import com.poyka.ripdpi.ui.components.inputs.RipDpiTabs
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

enum class ConnectionEditorTarget {
    Bypass,
    Resolver,
    SubscriptionStatus,
    SubscriptionFailover,
}

@androidx.compose.runtime.Stable
data class ConnectionProfileActions(
    val share: (String) -> Unit = {},
    val select: (String) -> Unit = {},
    val selectDirect: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val create: (Route) -> Unit = {},
    val scanServer: () -> Unit = {},
)

@Composable
fun ConnectionRoute(
    onOpenModeEditor: () -> Unit,
    onOpenLocalBypassEditor: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRetestStrategies: () -> Unit,
    onScanServer: () -> Unit,
    onOpenRoute: (Route) -> Unit,
    modifier: Modifier = Modifier,
    route: Route = Route.Config,
    initialModeSection: ConnectionModeSection = ConnectionModeSection.LocalBypass,
    viewModel: ConnectionViewModel = hiltViewModel(),
    clipboardImportViewModel: ClipboardImportViewModel = hiltViewModel(),
    onImport: (ImportPayload) -> Unit = {},
    onProfileShare: (String) -> Unit = {},
    runtimeIntegration: ConnectionRuntimeIntegration = ConnectionRuntimeIntegration(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val runtime = configRuntimePresentation(viewModel, uiState, runtimeIntegration)
    val clipboardImportState = clipboardImportViewModel.uiState.collectAsStateWithLifecycle().value
    val navigateTo = clipboardImportState.navigateToConfirm
    if (navigateTo != null) {
        androidx.compose.runtime.LaunchedEffect(navigateTo) {
            clipboardImportViewModel.consumeNavigation()
            onImport(navigateTo)
        }
    }
    val onImportFromClipboard =
        remember(clipboardImportViewModel) { clipboardImportViewModel::onImportFromClipboard }
    val onDismissClipboardError =
        remember(clipboardImportViewModel) { clipboardImportViewModel::dismissError }

    ConnectionScreen(
        uiState = runtime.uiState,
        runtimeCallbacks = runtime.callbacks,
        modifier = modifier,
        route = route,
        topBarActions = {
            ConnectionImportMenu(
                unknownContentScheme = clipboardImportState.unknownContentScheme,
                clipboardEmpty = clipboardImportState.clipboardEmpty,
                onImportFromClipboard = onImportFromClipboard,
                onDismissError = onDismissClipboardError,
            )
        },
        onModeSelected = remember(viewModel) { viewModel::selectMode },
        onPresetSelected = { preset ->
            when (preset.kind) {
                ConnectionPresetKind.Custom -> {
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
                ConnectionEditorTarget.Bypass -> onOpenLocalBypassEditor()
                ConnectionEditorTarget.Resolver -> onOpenDnsSettings()
                ConnectionEditorTarget.SubscriptionStatus -> onOpenRoute(Route.SubscriptionStatus)
                ConnectionEditorTarget.SubscriptionFailover -> onOpenRoute(Route.SubscriptionFailover)
            }
        },
        onRetestStrategies = onRetestStrategies,
        onPasteServerLink = clipboardImportViewModel::onImportFromClipboard,
        profileActions =
            ConnectionProfileActions(
                scanServer = onScanServer,
                share = onProfileShare,
                select = remember(viewModel) { viewModel::selectRelayProfile },
                selectDirect = remember(viewModel) { viewModel::selectDirectPath },
                create = onOpenRoute,
                edit = { profileId ->
                    when (uiState.vpnProfiles.firstOrNull { it.id == profileId }?.kind) {
                        com.poyka.ripdpi.data.RelayKindMieru -> {
                            onOpenRoute(Route.MieruProfileEdit(profileId))
                        }

                        com.poyka.ripdpi.data.RelayKindSsh -> {
                            onOpenRoute(Route.SshProfileEdit(profileId))
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
fun ConnectionScreen(
    uiState: ConnectionUiState,
    onModeSelected: (Mode) -> Unit,
    onPresetSelected: (ConnectionPreset) -> Unit,
    onEditCurrent: () -> Unit,
    onOpenConfigEditor: (ConnectionEditorTarget) -> Unit,
    onRetestStrategies: () -> Unit,
    onPasteServerLink: () -> Unit,
    profileActions: ConnectionProfileActions = ConnectionProfileActions(),
    modifier: Modifier = Modifier,
    route: Route = Route.Config,
    initialModeSection: ConnectionModeSection = ConnectionModeSection.LocalBypass,
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    runtimeCallbacks: ConnectionRuntimeCallbacks = ConnectionRuntimeCallbacks(),
) {
    val desyncSummary = uiState.draft.chainSummary
    // An active relay always shows Relay. Browsing covers the Relay section before a profile is active.
    var browsingRelay by rememberSaveable { mutableStateOf(initialModeSection == ConnectionModeSection.Vpn) }
    val selectedModeSection =
        if (uiState.activeRelayEnabled ||
            browsingRelay
        ) {
            ConnectionModeSection.Vpn
        } else {
            ConnectionModeSection.LocalBypass
        }
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
        ConnectionSavedConnectionCard(
            uiState = uiState,
            profilesExpanded = showProfileChoices,
            onChangeProfile = { showProfileChoices = !showProfileChoices },
        )
        ConnectionRuntimeFeedback(uiState, runtimeCallbacks.dismissError)
        ConnectionCliOverrideBanner(uiState)
        if (showProfileChoices) {
            Column(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigProfileChoices),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                ConnectionPresetList(uiState, onPresetSelected)
                ConnectionRelayProfileChoices(uiState, profileActions, onPasteServerLink)
            }
        }

        // One labeled control group: the chips carry their own label and the tabs name each section.
        RipDpiCard {
            ConnectionModeChips(
                selectedMode = uiState.activeMode,
                onModeSelected = onModeSelected,
                vpnLabel = stringResource(modeLabelRes(Mode.VPN)),
                proxyLabel = stringResource(modeLabelRes(Mode.Proxy)),
                label = stringResource(R.string.config_traffic_mode_title),
                groupTestTag = RipDpiTestTags.ConfigTrafficEndpointSelection,
            )
            ConnectionModeSectionSwitcher(
                selectedSection = selectedModeSection,
                onSectionSelected = { section ->
                    when (section) {
                        ConnectionModeSection.LocalBypass -> {
                            browsingRelay = false
                            if (uiState.activeRelayEnabled) profileActions.selectDirect()
                        }

                        ConnectionModeSection.Vpn -> {
                            browsingRelay = true
                            uiState.vpnProfiles
                                .firstOrNull { it.id == uiState.activeRelayProfileId }
                                ?.takeUnless { uiState.activeRelayEnabled }
                                ?.let { profileActions.select(it.id) }
                        }
                    }
                },
            )
        }

        ConnectionSelectedModeSection(
            section = selectedModeSection,
            uiState = uiState,
            desyncSummary = desyncSummary,
            onEditCurrent = onEditCurrent,
            onChooseRelay = { showProfileChoices = true },
            onOpenConfigEditor = onOpenConfigEditor,
            onRetestStrategies = onRetestStrategies,
        )
    }
}

@Composable
internal fun ConnectionModeSectionSwitcher(
    selectedSection: ConnectionModeSection,
    onSectionSelected: (ConnectionModeSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = ConnectionModeSection.entries

    Column(modifier = modifier.fillMaxWidth()) {
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
private fun ConnectionSelectedModeSection(
    section: ConnectionModeSection,
    uiState: ConnectionUiState,
    desyncSummary: String,
    onEditCurrent: () -> Unit,
    onChooseRelay: () -> Unit,
    onOpenConfigEditor: (ConnectionEditorTarget) -> Unit,
    onRetestStrategies: () -> Unit,
) {
    when (section) {
        ConnectionModeSection.LocalBypass -> {
            LocalBypassConfigScreen(
                uiState = uiState,
                desyncSummary = desyncSummary,
                onOpenDesyncSettings = { onOpenConfigEditor(ConnectionEditorTarget.Bypass) },
                onOpenDnsSettings = { onOpenConfigEditor(ConnectionEditorTarget.Resolver) },
                onRetestStrategies = onRetestStrategies,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassSummary),
            )
        }

        ConnectionModeSection.Vpn -> {
            VpnConfigScreen(
                uiState = uiState,
                onOpenRelaySettings = onEditCurrent,
                onChooseRelay = onChooseRelay,
                onOpenDnsSettings = { onOpenConfigEditor(ConnectionEditorTarget.Resolver) },
                onOpenSubscriptionStatus = { onOpenConfigEditor(ConnectionEditorTarget.SubscriptionStatus) },
                onOpenSubscriptionFailover = { onOpenConfigEditor(ConnectionEditorTarget.SubscriptionFailover) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigVpnSummary),
            )
        }
    }
}

@Composable
internal fun ConnectionModeChips(
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
        // The label names the group for accessibility only; the chips are self-explanatory.
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .semantics { label?.let { contentDescription = it } }
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

internal fun modeLabelRes(mode: Mode): Int =
    when (mode) {
        Mode.VPN -> R.string.status_mode_vpn
        Mode.Proxy -> R.string.status_mode_proxy
    }

/**
 * Config UI terminology is intentionally two-level:
 * Profile = a named saved configuration; Mode = the bypass path inside that profile.
 */
enum class ConnectionModeSection(
    val stableKey: String,
) {
    LocalBypass("local_bypass"),
    Vpn("vpn"),
    ;

    companion object {
        fun fromStableKey(key: String): ConnectionModeSection =
            entries.firstOrNull { it.stableKey == key } ?: LocalBypass
    }
}

private fun configModeSectionTitleRes(section: ConnectionModeSection): Int =
    when (section) {
        ConnectionModeSection.LocalBypass -> R.string.config_section_bypass_settings
        ConnectionModeSection.Vpn -> R.string.config_section_server_settings
    }

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    RipDpiTheme {
        ConnectionScreen(
            uiState =
                ConnectionUiState(
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
            initialModeSection = ConnectionModeSection.LocalBypass,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenDarkPreview() {
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
        ConnectionScreen(
            uiState =
                ConnectionUiState(
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
            initialModeSection = ConnectionModeSection.Vpn,
        )
    }
}

@Preview(showBackground = true, name = "ConfigScreen (loading)")
@Composable
private fun ConnectionScreenLoadingPreview() {
    RipDpiTheme {
        ConnectionScreen(
            uiState =
                ConnectionUiState(
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
            initialModeSection = ConnectionModeSection.LocalBypass,
        )
    }
}
