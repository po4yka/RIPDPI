package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.proxyimport.ClipboardImportViewModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import kotlinx.collections.immutable.persistentListOf
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
    initialPath: ConnectionPath = ConnectionPath.Direct,
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
            val selectedPresetId = uiState.presets.firstOrNull { it.isSelected }?.id ?: CustomPresetId
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
                    openRelayProfileEditor(
                        profileId,
                        uiState.vpnProfiles.firstOrNull { it.id == profileId }?.kind,
                        viewModel,
                        onOpenRoute,
                        onOpenModeEditor,
                    )
                },
            ),
        initialPath = initialPath,
    )
}

private fun openRelayProfileEditor(
    profileId: String,
    kind: String?,
    viewModel: ConnectionViewModel,
    onOpenRoute: (Route) -> Unit,
    onOpenModeEditor: () -> Unit,
) {
    when (kind) {
        RelayKindMieru -> {
            onOpenRoute(Route.MieruProfileEdit(profileId))
        }

        RelayKindSsh -> {
            onOpenRoute(Route.SshProfileEdit(profileId))
        }

        else -> {
            viewModel.startEditingPreset(relayProfilePresetId(profileId))
            onOpenModeEditor()
        }
    }
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
    initialPath: ConnectionPath = ConnectionPath.Direct,
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    runtimeCallbacks: ConnectionRuntimeCallbacks = ConnectionRuntimeCallbacks(),
) {
    val desyncSummary = uiState.draft.chainSummary
    // An active relay always shows Relay. Browsing covers the Relay section before a profile is active.
    var browsingRelay by rememberSaveable { mutableStateOf(initialPath == ConnectionPath.Relay) }
    // Browsing ends once a relay is active, so turning that relay off later returns to Direct.
    LaunchedEffect(uiState.activeRelayEnabled) { if (uiState.activeRelayEnabled) browsingRelay = false }
    val selectedPath = if (uiState.activeRelayEnabled || browsingRelay) ConnectionPath.Relay else ConnectionPath.Direct
    var showProfileChoices by rememberSaveable { mutableStateOf(false) }

    RipDpiContentScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(route))
                .fillMaxSize()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.connection_title),
        contentWidth = RipDpiScaffoldWidth.Content,
        actions = topBarActions,
    ) {
        ConnectionSavedConnectionCard(uiState = uiState, onChangeProfile = { showProfileChoices = true })
        ConnectionRuntimeFeedback(uiState, runtimeCallbacks.dismissError)
        ConnectionCliOverrideBanner(uiState)
        if (showProfileChoices) {
            ConnectionProfileChoicesSheet(
                uiState = uiState,
                onPresetSelected = { preset ->
                    if (preset.kind == ConnectionPresetKind.Recommended) browsingRelay = false
                    onPresetSelected(preset)
                },
                profileActions = profileActions,
                onPasteServerLink = onPasteServerLink,
                onDismiss = { showProfileChoices = false },
            )
        }

        ConnectionScopeSelector(selectedMode = uiState.activeMode, onModeSelected = onModeSelected)
        ConnectionPathSelector(
            selectedPath = selectedPath,
            onPathSelected = { path ->
                when (path) {
                    ConnectionPath.Direct -> {
                        browsingRelay = false
                        if (uiState.activeRelayEnabled) profileActions.selectDirect()
                    }

                    ConnectionPath.Relay -> {
                        if (!uiState.activeRelayEnabled) {
                            val lastProfile = uiState.vpnProfiles.firstOrNull { it.id == uiState.activeRelayProfileId }
                            if (lastProfile != null) profileActions.select(lastProfile.id) else browsingRelay = true
                        }
                    }
                }
            },
        )

        ConnectionSelectedPathSection(
            path = selectedPath,
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
private fun ConnectionScopeSelector(
    selectedMode: Mode,
    onModeSelected: (Mode) -> Unit,
) {
    val modes = persistentListOf(Mode.VPN, Mode.Proxy)
    val groupLabel = stringResource(R.string.config_scope_title)
    RipDpiSegmentedButton(
        options =
            persistentListOf(
                stringResource(modeLabelRes(Mode.VPN)),
                stringResource(modeLabelRes(Mode.Proxy)),
            ),
        selectedIndex = modes.indexOf(selectedMode),
        onSelect = { onModeSelected(modes[it]) },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = groupLabel }
                .ripDpiTestTag(RipDpiTestTags.ConfigScopeSelection),
        role = Role.RadioButton,
        optionTestTags = modes.map { RipDpiTestTags.configMode(it.name) }.toImmutableList(),
    )
}

@Composable
private fun ConnectionPathSelector(
    selectedPath: ConnectionPath,
    onPathSelected: (ConnectionPath) -> Unit,
) {
    val paths = ConnectionPath.entries
    RipDpiSegmentedButton(
        options = paths.map { stringResource(pathTitleRes(it)) }.toImmutableList(),
        selectedIndex = paths.indexOf(selectedPath),
        onSelect = { onPathSelected(paths[it]) },
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ConfigSectionNavigation),
        optionTestTags = paths.map { RipDpiTestTags.configPath(it.stableKey) }.toImmutableList(),
    )
}

@Composable
private fun ConnectionSelectedPathSection(
    path: ConnectionPath,
    uiState: ConnectionUiState,
    desyncSummary: String,
    onEditCurrent: () -> Unit,
    onChooseRelay: () -> Unit,
    onOpenConfigEditor: (ConnectionEditorTarget) -> Unit,
    onRetestStrategies: () -> Unit,
) {
    when (path) {
        ConnectionPath.Direct -> {
            ConnectionDirectSection(
                uiState = uiState,
                desyncSummary = desyncSummary,
                onOpenDesyncSettings = { onOpenConfigEditor(ConnectionEditorTarget.Bypass) },
                onOpenDnsSettings = { onOpenConfigEditor(ConnectionEditorTarget.Resolver) },
                onRetestStrategies = onRetestStrategies,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassSummary),
            )
        }

        ConnectionPath.Relay -> {
            ConnectionRelaySection(
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
internal fun ConnectionScopeChips(
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

/** How traffic leaves the device. Scope, the traffic the app captures, is [Mode]. */
enum class ConnectionPath(
    val stableKey: String,
) {
    Direct("direct"),
    Relay("relay"),
}

private fun pathTitleRes(path: ConnectionPath): Int =
    when (path) {
        ConnectionPath.Direct -> R.string.config_path_direct
        ConnectionPath.Relay -> R.string.config_path_relay
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
            initialPath = ConnectionPath.Direct,
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
            initialPath = ConnectionPath.Relay,
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
            initialPath = ConnectionPath.Direct,
        )
    }
}
