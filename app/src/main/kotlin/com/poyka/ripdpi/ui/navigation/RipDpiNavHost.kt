package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.PcapCaptureViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileRoute
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileRoute
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckRoute
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserRoute
import com.poyka.ripdpi.ui.screens.config.ConfigModeSection
import com.poyka.ripdpi.ui.screens.config.ConfigRoute
import com.poyka.ripdpi.ui.screens.config.ModeEditorRoute
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionCheckRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionSettingsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRouteCallbacks
import com.poyka.ripdpi.ui.screens.diagnostics.PcapCaptureListRoute
import com.poyka.ripdpi.ui.screens.diagnostics.PcapViewerRoute
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayHistoryRoute
import com.poyka.ripdpi.ui.screens.diagnostics.share.SharedResultRenderRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmRoute
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileShareRoute
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmRoute
import com.poyka.ripdpi.ui.screens.routes.RoutesRoute
import com.poyka.ripdpi.ui.screens.routes.RuleEditorRoute
import com.poyka.ripdpi.ui.screens.scanner.QrScannerRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.AssetProviderRoute
import com.poyka.ripdpi.ui.screens.settings.BackupRestoreRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListRoute
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksRoute
import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.screens.settings.SplitTunnelRoute
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigRoute
import com.poyka.ripdpi.ui.screens.ssh.SshProfileRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusRoute
import com.poyka.ripdpi.ui.screens.support.SupportSettingsRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerTopBarAction
import com.poyka.ripdpi.ui.screens.xray.XrayProfileImportRoute
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiNavHostActions(
    val onSaveLogs: () -> Unit = {},
    val onShareDebugBundle: () -> Unit = {},
    val onSaveDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsSummary: (String, String) -> Unit = { _, _ -> },
    val onRepairPermission: (PermissionKind) -> Unit = {},
)

data class RipDpiNavHostLaunchRequests(
    val launchHomeRequested: Boolean = false,
    val onLaunchHomeHandled: () -> Unit = {},
    val launchRouteRequested: String? = null,
    val onLaunchRouteHandled: () -> Unit = {},
    val sharedDiagnosticFragmentRequested: String? = null,
    val onSharedDiagnosticFragmentHandled: () -> Unit = {},
    val importRouteRequested: Route? = null,
    val onImportRouteHandled: () -> Unit = {},
    val relockRequested: Boolean = false,
    val onRelockHandled: () -> Unit = {},
)

@Suppress("detekt.LongMethod")
@Composable
fun RipDpiNavHost(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Home,
    isSessionAuthenticated: Boolean = false,
    actions: RipDpiNavHostActions = RipDpiNavHostActions(),
    launchRequests: RipDpiNavHostLaunchRequests = RipDpiNavHostLaunchRequests(),
    snackbarHostState: SnackbarHostState? = null,
) {
    val navigationState =
        rememberRipDpiNavigationState(
            startRoute = startDestination,
            initialGateActive = shouldStartNavigationGate(startDestination, isSessionAuthenticated),
        )
    val navigator = remember(navigationState) { RipDpiNavigator(navigationState) }
    val diagnosticsInitialSection = rememberSaveable { mutableStateOf<DiagnosticsSection?>(null) }
    val currentRoute = navigator.currentRoute
    val selectedTopLevel = currentRoute.selectedTopLevel()
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    HandleLaunchRequests(
        launchRequests = launchRequests,
        currentStableRoute = currentRoute.stableRoute,
        navigateHome = navigator::resetToHome,
        navigateToRoute = { destination ->
            if (destination in Route.topLevel) {
                navigator.navigateTopLevel(destination)
            } else {
                navigator.navigate(destination, singleTop = true)
            }
        },
        navigateToSharedDiagnostic = { fragment ->
            navigator.navigate(Route.SharedDiagnosticResult(fragment = fragment), singleTop = true)
        },
        navigateToImportRoute = { destination -> navigator.navigate(destination, singleTop = true) },
        relockToRoute = navigator::replaceAll,
    )

    val isWideScreen = rememberIsWideScreen()
    Scaffold(
        modifier = modifier,
        containerColor = RipDpiThemeTokens.colors.background,
        snackbarHost = {
            snackbarHostState?.let { hostState ->
                RipDpiSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                )
            }
        },
        bottomBar = {
            if (!isWideScreen) {
                TopLevelBottomBar(
                    selectedTopLevel = selectedTopLevel,
                    onNavigate = navigator::navigateTopLevel,
                )
            }
        },
    ) { innerPadding ->
        ResponsiveNavContent(
            isWideScreen = isWideScreen,
            selectedTopLevel = selectedTopLevel,
            innerPadding = innerPadding,
            navigationState = navigationState,
            navigator = navigator,
            motion = motion,
            actions = actions,
            mainViewModel = mainViewModel,
            permissionSummary = mainUiState.permissionSummary,
            diagnosticsInitialSection = diagnosticsInitialSection.value,
            onDiagnosticsInitialSectionChanged = { diagnosticsInitialSection.value = it },
        )
    }
}

@Composable
private fun ResponsiveNavContent(
    isWideScreen: Boolean,
    selectedTopLevel: Route?,
    innerPadding: PaddingValues,
    navigationState: RipDpiNavigationState,
    navigator: RipDpiNavigator,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    val content: @Composable (PaddingValues) -> Unit = { contentPadding ->
        RipDpiNavGraph(
            innerPadding = contentPadding,
            navigationState = navigationState,
            navigator = navigator,
            motion = motion,
            actions = actions,
            mainViewModel = mainViewModel,
            permissionSummary = permissionSummary,
            diagnosticsInitialSection = diagnosticsInitialSection,
            onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
        )
    }
    if (isWideScreen && selectedTopLevel != null) {
        Row(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
        ) {
            RipDpiNavRail(
                selectedRoute = selectedTopLevel,
                onNavigate = navigator::navigateTopLevel,
            )
            Box(modifier = Modifier.weight(1f)) {
                content(PaddingValues())
            }
        }
    } else {
        content(innerPadding)
    }
}

@Composable
private fun HandleLaunchRequests(
    launchRequests: RipDpiNavHostLaunchRequests,
    currentStableRoute: String?,
    navigateHome: () -> Unit,
    navigateToRoute: (Route) -> Unit,
    navigateToSharedDiagnostic: (String) -> Unit,
    navigateToImportRoute: (Route) -> Unit,
    relockToRoute: (Route) -> Unit,
) {
    LaunchedEffect(launchRequests.launchHomeRequested, currentStableRoute) {
        if (!launchRequests.launchHomeRequested || launchRequests.relockRequested || currentStableRoute == null) {
            return@LaunchedEffect
        }
        when {
            currentStableRoute == Route.Home.stableRoute -> {
                launchRequests.onLaunchHomeHandled()
            }

            shouldNavigateToHomeFromLaunchRequest(launchRequests.launchHomeRequested, currentStableRoute) -> {
                navigateHome()
                launchRequests.onLaunchHomeHandled()
            }
        }
    }

    LaunchedEffect(launchRequests.launchRouteRequested, currentStableRoute) {
        if (launchRequests.relockRequested) return@LaunchedEffect
        val requestedStableRoute = launchRequests.launchRouteRequested ?: return@LaunchedEffect
        val resolvedCurrentRoute = currentStableRoute ?: return@LaunchedEffect
        if (!canHandleExternalNavigation(resolvedCurrentRoute)) return@LaunchedEffect
        if (requestedStableRoute == resolvedCurrentRoute) {
            launchRequests.onLaunchRouteHandled()
            return@LaunchedEffect
        }
        val destination = Route.fromStableRoute(requestedStableRoute) ?: return@LaunchedEffect
        navigateToRoute(destination)
        launchRequests.onLaunchRouteHandled()
    }

    LaunchedEffect(launchRequests.sharedDiagnosticFragmentRequested, currentStableRoute) {
        if (launchRequests.relockRequested) return@LaunchedEffect
        val fragment = launchRequests.sharedDiagnosticFragmentRequested ?: return@LaunchedEffect
        if (!canHandleExternalNavigation(currentStableRoute)) {
            return@LaunchedEffect
        }
        navigateToSharedDiagnostic(fragment)
        launchRequests.onSharedDiagnosticFragmentHandled()
    }

    LaunchedEffect(launchRequests.importRouteRequested, currentStableRoute) {
        if (launchRequests.relockRequested) return@LaunchedEffect
        val destination = launchRequests.importRouteRequested ?: return@LaunchedEffect
        if (!canHandleExternalNavigation(currentStableRoute)) {
            return@LaunchedEffect
        }
        navigateToImportRoute(destination)
        launchRequests.onImportRouteHandled()
    }

    LaunchedEffect(launchRequests.relockRequested) {
        if (launchRequests.relockRequested) {
            relockToRoute(Route.BiometricPrompt)
            launchRequests.onRelockHandled()
        }
    }
}

@Composable
private fun TopLevelBottomBar(
    selectedTopLevel: Route?,
    onNavigate: (Route) -> Unit,
) {
    val destination = selectedTopLevel ?: return
    BottomNavBar(
        selectedRoute = destination,
        onNavigate = onNavigate,
    )
}

@Composable
private fun RipDpiNavGraph(
    innerPadding: PaddingValues,
    navigationState: RipDpiNavigationState,
    navigator: RipDpiNavigator,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    val entryProvider =
        entryProvider<NavKey> {
            addPrimaryRoutes(
                navigator = navigator,
                actions = actions,
                mainViewModel = mainViewModel,
                diagnosticsInitialSection = diagnosticsInitialSection,
                onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
            )
            addConfigRoutes(navigator = navigator)
            addSettingsRoutes(
                navigator = navigator,
                actions = actions,
                mainViewModel = mainViewModel,
                permissionSummary = permissionSummary,
            )
            entry<Route.About> {
                AboutRoute(onBack = navigator::goBack)
            }
        }
    NavDisplay(
        entries = navigationState.toEntries(entryProvider),
        modifier =
            Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        onBack = navigator::goBack,
        transitionSpec = {
            motion.routeEnterTransition() togetherWith motion.routeExitTransition()
        },
        popTransitionSpec = {
            motion.routeEnterTransition(initialScale = 0.992f) togetherWith motion.routePopExitTransition()
        },
        predictivePopTransitionSpec = {
            motion.routeEnterTransition(initialScale = 0.992f) togetherWith motion.routePopExitTransition()
        },
    )
}

private fun EntryProviderScope<NavKey>.addPrimaryRoutes(
    navigator: RipDpiNavigator,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    entry<Route.Onboarding> {
        OnboardingRoute(
            onComplete = navigator::resetToHome,
            onOpenAdvancedDns = { navigator.navigateFromGate(Route.DnsSettings) },
        )
    }
    addHomeRoute(navigator, mainViewModel, onDiagnosticsInitialSectionChanged)
    entry<Route.Diagnostics> { route ->
        val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
        val pcapCaptureViewModel: PcapCaptureViewModel = hiltViewModel()
        DiagnosticsRoute(
            callbacks =
                diagnosticsRouteCallbacks(
                    navigator = navigator,
                    actions = actions,
                    mainViewModel = mainViewModel,
                    onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
                ),
            initialSection = diagnosticsInitialSection ?: DiagnosticsSection.Scan.takeIf { route.autoStartScan },
            autoStartScan = route.autoStartScan,
            viewModel = diagnosticsViewModel,
            pcapCaptureViewModel = pcapCaptureViewModel,
            topBarExtraActions = {
                StrategyTunerTopBarAction(
                    onOpen = { navigator.navigate(Route.StrategyTuner, singleTop = true) },
                )
            },
        )
    }
    entry<Route.History> {
        HistoryRoute(onBack = { navigator.goBack() })
    }
    entry<Route.Logs> {
        LogsRoute(
            onSaveLogs = actions.onSaveLogs,
            onShareSupportBundle = actions.onShareDebugBundle,
        )
    }
    entry<Route.ConnectionHealth> {
        ConnectionHealthRoute(onBack = { navigator.goBack() })
    }
    addSubscriptionFailoverRoute(navigator)
    addSubscriptionStatusRoute(navigator)
    entry<Route.StrategyTuner> {
        StrategyTunerRoute(onBack = { navigator.goBack() })
    }
    entry<Route.BiometricPrompt> {
        BiometricPromptRoute(
            onAuthenticated = {
                mainViewModel.appLock.onAuthenticated()
                navigator.resetToHome()
            },
        )
    }
}

private fun EntryProviderScope<NavKey>.addHomeRoute(
    navigator: RipDpiNavigator,
    mainViewModel: MainViewModel,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    entry<Route.Home> {
        HomeRoute(
            onOpenDiagnostics = {
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Scan)
                navigator.navigate(Route.Diagnostics(), singleTop = true)
            },
            onOpenHistory = { navigator.navigate(Route.History, singleTop = true) },
            onOpenConnectionHealth = { navigator.navigate(Route.ConnectionHealth, singleTop = true) },
            onOpenSubscriptionStatus = { navigator.navigate(Route.SubscriptionStatus, singleTop = true) },
            onOpenAdvancedSettings = { navigator.navigate(Route.AdvancedSettings) },
            onOpenModeEditor = { navigator.navigate(Route.ModeEditor) },
            onOpenOwnedStackBrowser = { url -> navigator.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
            onOpenLocalBypassConfig = { navigator.navigateConfigSubRoute(Route.LocalBypassConfig) },
            onOpenVpnConfig = { navigator.navigateConfigSubRoute(Route.VpnConfig) },
            onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
            viewModel = mainViewModel,
        )
    }
}

private fun EntryProviderScope<NavKey>.addSubscriptionFailoverRoute(navigator: RipDpiNavigator) {
    entry<Route.SubscriptionFailover> {
        SubscriptionFailoverRoute(onBack = { navigator.goBack() })
    }
}

private fun EntryProviderScope<NavKey>.addSubscriptionStatusRoute(navigator: RipDpiNavigator) {
    entry<Route.SubscriptionStatus> {
        SubscriptionStatusRoute(onBack = { navigator.goBack() })
    }
}

private fun diagnosticsRouteCallbacks(
    navigator: RipDpiNavigator,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
): DiagnosticsRouteCallbacks =
    DiagnosticsRouteCallbacks(
        onShareArchive = actions.onShareDiagnosticsArchive,
        onSaveArchive = actions.onSaveDiagnosticsArchive,
        onShareSummary = actions.onShareDiagnosticsSummary,
        onSaveLogs = actions.onSaveLogs,
        onOpenLogs = { navigator.navigate(Route.Logs, singleTop = true) },
        onOpenConnectionHealth = { navigator.navigate(Route.ConnectionHealth, singleTop = true) },
        onOpenAdvancedSettings = { navigator.navigate(Route.AdvancedSettings) },
        onOpenDnsSettings = { navigator.navigate(Route.DnsSettings) },
        onOpenDetectionCheck = { navigator.navigate(Route.DetectionCheck) },
        onRequestVpnPermission = mainViewModel::onOpenVpnPermissionRequested,
        onOpenHistory = { navigator.navigate(Route.History, singleTop = true) },
        onOpenModeEditor = { navigator.navigate(Route.ModeEditor) },
        onOpenOwnedStackBrowser = { url -> navigator.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
        onOpenPcapCaptureList = { navigator.navigate(Route.PcapCaptureList) },
        onOpenPastReplays = { navigator.navigate(Route.ReplayHistory) },
        onInitialSectionHandled = { onDiagnosticsInitialSectionChanged(null) },
    )

private fun EntryProviderScope<NavKey>.addConfigRoutes(navigator: RipDpiNavigator) {
    entry<Route.Config>(clazzContentKey = { ConfigScopeContentKey }) {
        val configViewModel: ConfigViewModel = hiltViewModel()
        ConfigRoute(
            onOpenModeEditor = { navigator.navigate(Route.ModeEditor) },
            onOpenLocalBypassEditor = { navigator.navigate(Route.StrategyConfig) },
            onOpenDnsSettings = { navigator.navigate(Route.DnsSettings) },
            onRetestStrategies = { navigator.navigate(Route.Diagnostics(autoStartScan = true)) },
            onScanServer = { navigator.navigate(Route.QrScanner) },
            route = Route.Config,
            initialModeSection = ConfigModeSection.LocalBypass,
            viewModel = configViewModel,
            onProfileImport = { request -> navigator.navigateProfileImport(request) },
            onProfileShare = { profileId -> navigator.navigate(Route.ProfileShare(profileId = profileId)) },
        )
    }
    entry<Route.LocalBypassConfig>(metadata = ConfigScopeMetadata) {
        val configViewModel: ConfigViewModel = sharedHiltViewModel()
        ConfigRoute(
            onOpenModeEditor = { navigator.navigate(Route.ModeEditor) },
            onOpenLocalBypassEditor = { navigator.navigate(Route.StrategyConfig) },
            onOpenDnsSettings = { navigator.navigate(Route.DnsSettings) },
            onRetestStrategies = { navigator.navigate(Route.Diagnostics(autoStartScan = true)) },
            onScanServer = { navigator.navigate(Route.QrScanner) },
            route = Route.LocalBypassConfig,
            initialModeSection = ConfigModeSection.LocalBypass,
            viewModel = configViewModel,
            onProfileImport = { request -> navigator.navigateProfileImport(request) },
            onProfileShare = { profileId -> navigator.navigate(Route.ProfileShare(profileId = profileId)) },
        )
    }
    entry<Route.VpnConfig>(metadata = ConfigScopeMetadata) {
        val configViewModel: ConfigViewModel = sharedHiltViewModel()
        ConfigRoute(
            onOpenModeEditor = { navigator.navigate(Route.ModeEditor) },
            onOpenLocalBypassEditor = { navigator.navigate(Route.StrategyConfig) },
            onOpenDnsSettings = { navigator.navigate(Route.DnsSettings) },
            onRetestStrategies = { navigator.navigate(Route.Diagnostics(autoStartScan = true)) },
            onScanServer = { navigator.navigate(Route.QrScanner) },
            route = Route.VpnConfig,
            initialModeSection = ConfigModeSection.Vpn,
            viewModel = configViewModel,
            onProfileImport = { request -> navigator.navigateProfileImport(request) },
            onProfileShare = { profileId -> navigator.navigate(Route.ProfileShare(profileId = profileId)) },
        )
    }
    entry<Route.ModeEditor>(metadata = ConfigScopeMetadata) {
        val configViewModel: ConfigViewModel = sharedHiltViewModel()
        ModeEditorRoute(
            onBack = { navigator.goBack() },
            viewModel = configViewModel,
        )
    }
}

private fun EntryProviderScope<NavKey>.addSettingsRoutes(
    navigator: RipDpiNavigator,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    entry<Route.Settings>(clazzContentKey = { SettingsScopeContentKey }) {
        SettingsHomeRoute(
            navigator = navigator,
            actions = actions,
            mainViewModel = mainViewModel,
            permissionSummary = permissionSummary,
        )
    }
    addAdvancedSettingsRoutes(navigator, mainViewModel)
    addDetectionSettingsRoutes(navigator)
}

@Composable
private fun SettingsHomeRoute(
    navigator: RipDpiNavigator,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    SettingsRoute(
        onOpenDnsSettings = { navigator.navigate(Route.DnsSettings) },
        onOpenAdvancedSettings = { navigator.navigate(Route.AdvancedSettings) },
        onOpenCustomization = { navigator.navigate(Route.AppCustomization) },
        onOpenAbout = { navigator.navigate(Route.About) },
        onOpenDataTransparency = { navigator.navigate(Route.DataTransparency) },
        onOpenDetectionCheck = { navigator.navigate(Route.DetectionCheck) },
        onOpenSubscriptionFailover = { navigator.navigate(Route.SubscriptionFailover) },
        onOpenSubscriptionStatus = { navigator.navigate(Route.SubscriptionStatus) },
        onOpenDomainBypass = { navigator.navigate(Route.DomainBypassList) },
        onOpenRoutingRules = { navigator.navigate(Route.Routes) },
        onOpenSplitTunnel = { navigator.navigate(Route.SplitTunnel) },
        onOpenRootModeStrategies = { navigator.navigate(Route.RootModeStrategies) },
        onOpenBackupRestore = { navigator.navigate(Route.BackupRestore) },
        onOpenLogs = { navigator.navigate(Route.Logs, singleTop = true) },
        onShareDebugBundle = actions.onShareDebugBundle,
        permissionSummary = permissionSummary,
        onRepairPermission = actions.onRepairPermission,
        onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
        onDismissBackgroundGuidance = mainViewModel::onDismissBackgroundGuidance,
        viewModel = settingsViewModel,
    )
}

private fun EntryProviderScope<NavKey>.addAdvancedSettingsRoutes(
    navigator: RipDpiNavigator,
    mainViewModel: MainViewModel,
) {
    entry<Route.DnsSettings>(metadata = SettingsScopeMetadata) {
        val settingsViewModel: SettingsViewModel = sharedHiltViewModel()
        DnsSettingsRoute(onBack = { navigator.goBack() }, viewModel = settingsViewModel)
    }
    entry<Route.AdvancedSettings>(metadata = SettingsScopeMetadata) {
        val settingsViewModel: SettingsViewModel = sharedHiltViewModel()
        AdvancedSettingsRoute(
            onBack = { navigator.goBack() },
            onOpenStrategyConfig = { navigator.navigate(Route.StrategyConfig) },
            onOpenBlockcheck = { navigator.navigate(Route.Blockcheck) },
            onOpenAssetProvider = { navigator.navigate(Route.AssetProvider) },
            onOpenRememberedNetworks = { navigator.navigate(Route.RememberedNetworks) },
            viewModel = settingsViewModel,
        )
    }
    entry<Route.RememberedNetworks> {
        RememberedNetworksRoute(onBack = { navigator.goBack() })
    }
    entry<Route.RootModeStrategies> {
        RootModeStrategiesRoute(
            onBack = { navigator.goBack() },
            onOpenStrategyConfig = { navigator.navigate(Route.StrategyConfig) },
        )
    }
    entry<Route.StrategyConfig>(metadata = SettingsScopeMetadata) {
        val settingsViewModel: SettingsViewModel = sharedHiltViewModel()
        StrategyConfigRoute(
            onBack = { navigator.goBack() },
            viewModel = settingsViewModel,
            applySavedConfig = mainViewModel::applySavedStrategyConfig,
        )
    }
    entry<Route.Blockcheck> {
        BlockcheckRoute(onBack = { navigator.goBack() })
    }
    entry<Route.DomainBypassList> {
        DomainBypassListRoute(onBack = { navigator.goBack() })
    }
    entry<Route.AssetProvider> {
        AssetProviderRoute(onBack = { navigator.goBack() })
    }
    entry<Route.BackupRestore> {
        BackupRestoreRoute(onBack = { navigator.goBack() })
    }
    entry<Route.SplitTunnel> {
        SplitTunnelRoute(onBack = { navigator.goBack() })
    }
    entry<Route.Routes> {
        RoutesRoute(
            onBack = { navigator.goBack() },
            onAddRule = { navigator.navigate(Route.RuleEditor(0L)) },
            onEditRule = { id -> navigator.navigate(Route.RuleEditor(id)) },
        )
    }
    entry<Route.RuleEditor> { route ->
        RuleEditorRoute(ruleId = route.ruleId, onBack = { navigator.goBack() })
    }
    entry<Route.AppCustomization>(metadata = SettingsScopeMetadata) {
        val settingsViewModel: SettingsViewModel = sharedHiltViewModel()
        AppCustomizationRoute(onBack = { navigator.goBack() }, viewModel = settingsViewModel)
    }
}

private fun EntryProviderScope<NavKey>.addDetectionSettingsRoutes(navigator: RipDpiNavigator) {
    entry<Route.DataTransparency> {
        DataTransparencyRoute(onBack = { navigator.goBack() })
    }
    entry<Route.DetectionCheck> {
        DetectionCheckRoute(
            onBack = { navigator.goBack() },
            onOpenSettings = { navigator.navigate(Route.DetectionSettings) },
        )
    }
    entry<Route.DetectionSettings> {
        DetectionSettingsRoute(onBack = { navigator.goBack() })
    }
    entry<Route.PcapViewer> {
        PcapViewerRoute(onBack = { navigator.goBack() })
    }
    entry<Route.PcapCaptureList> {
        PcapCaptureListRoute(
            onCaptureSelected = { _ ->
                navigator.navigate(Route.PcapViewer, singleTop = true)
            },
            onBack = { navigator.goBack() },
        )
    }
    entry<Route.ReplayHistory> {
        ReplayHistoryRoute(
            onRunScan = { navigator.navigate(Route.Diagnostics(autoStartScan = true)) },
            onBack = { navigator.goBack() },
        )
    }
    entry<Route.OwnedStackBrowser> { route ->
        OwnedStackBrowserRoute(
            initialUrl = route.initialUrl,
            onBack = { navigator.goBack() },
        )
    }
    entry<Route.SharedDiagnosticResult> { route ->
        SharedResultRenderRoute(
            fragment = route.fragment,
            onBack = { navigator.goBack() },
        )
    }
    addImportRoutes(navigator)
}

private fun EntryProviderScope<NavKey>.addImportRoutes(navigator: RipDpiNavigator) {
    entry<Route.ProfileImportConfirm> { route ->
        ProfileImportConfirmRoute(
            importToken = route.importToken,
            onBack = { navigator.goBack() },
            onImported = navigator::resetToHome,
            onUnavailable = navigator::resetToHome,
        )
    }
    entry<Route.SubscriptionImportConfirm> { route ->
        SubscriptionImportConfirmRoute(
            importToken = route.importToken,
            onBack = { navigator.goBack() },
            onImported = navigator::resetToHome,
            onUnavailable = navigator::resetToHome,
        )
    }
    entry<Route.SupportSettings> { route ->
        SupportSettingsRoute(
            packageJson = route.packageJson,
            onBack = { navigator.goBack() },
            onApplied = navigator::resetToHome,
        )
    }
    entry<Route.ProfileShare> { route ->
        ProfileShareRoute(
            profileId = route.profileId,
            onBack = { navigator.goBack() },
        )
    }
    entry<Route.QrScanner> {
        QrScannerRoute(
            onBack = { navigator.goBack() },
            onProfileScanned = { request ->
                navigator.navigateProfileImport(request)
            },
        )
    }
    entry<Route.AmneziaWgProfile> {
        AmneziaWgProfileRoute(onBack = { navigator.goBack() })
    }
    entry<Route.XrayImport> {
        XrayProfileImportRoute(
            onBack = { navigator.goBack() },
            onFinished = { navigator.goBack() },
        )
    }
    entry<Route.AnyTlsProfile> {
        AnyTlsProfileRoute(onBack = { navigator.goBack() })
    }
    entry<Route.MieruProfile> {
        MieruProfileRoute(onBack = { navigator.goBack() })
    }
    entry<Route.SshProfile> {
        SshProfileRoute(onBack = { navigator.goBack() })
    }
}

internal fun nestedEnterTransition(motion: RipDpiMotion) = motion.nestedEnterTransition()

internal fun nestedPopExitTransition(motion: RipDpiMotion) = motion.nestedPopExitTransition()

internal fun shouldNavigateToHomeFromLaunchRequest(
    launchHomeRequested: Boolean,
    currentRoute: String?,
): Boolean {
    if (!launchHomeRequested || currentRoute == null) {
        return false
    }

    return currentRoute != Route.Home.stableRoute &&
        currentRoute !in
        setOf(
            Route.Onboarding.stableRoute,
            Route.BiometricPrompt.stableRoute,
        )
}

internal fun canHandleExternalNavigation(currentRoute: String?): Boolean =
    currentRoute != null && currentRoute !in gateStableRoutes

private const val ConfigScopeContentKey = "config-navigation-scope"
private const val SettingsScopeContentKey = "settings-navigation-scope"

private val ConfigScopeMetadata = RipDpiSharedViewModelStoreNavEntryDecorator.parent(ConfigScopeContentKey)
private val SettingsScopeMetadata = RipDpiSharedViewModelStoreNavEntryDecorator.parent(SettingsScopeContentKey)
private val gateStableRoutes = gateRoutes.mapTo(mutableSetOf(), Route::stableRoute)

@Composable
private inline fun <reified VM : androidx.lifecycle.ViewModel> sharedHiltViewModel(): VM =
    hiltViewModel(viewModelStoreOwner = LocalRipDpiSharedViewModelStoreOwner.current)
