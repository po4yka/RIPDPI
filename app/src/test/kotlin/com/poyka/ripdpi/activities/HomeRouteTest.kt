package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `pcap toggle routes through main view model`() {
        val viewModel =
            createViewModel(
                appSettingsRepository =
                    FakeAppSettingsRepository(
                        AppSettings
                            .newBuilder()
                            .setOnboardingComplete(true)
                            .setRootModeEnabled(true)
                            .build(),
                    ),
            )

        composeRule.setContent {
            RipDpiTheme {
                HomeRoute(
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onOpenConnectionHealth = {},
                    onOpenAdvancedSettings = {},
                    onOpenModeEditor = {},
                    onOpenOwnedStackBrowser = {},
                    onOpenLocalBypassConfig = {},
                    onOpenVpnConfig = {},
                    onOpenVpnPermissionDialog = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.homeDiagnosticsUiState.value.pcapToggleVisible
        }
        assertFalse(viewModel.homeDiagnosticsUiState.value.pcapRecordingRequested)

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsHeader)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertFalse(viewModel.homeDiagnosticsUiState.value.pcapRecordingRequested)
        }
    }

    private fun createViewModel(appSettingsRepository: AppSettingsRepository): MainViewModel {
        val crashReportReader =
            com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
            )
        return MainViewModel(
            appSettingsRepository = appSettingsRepository,
            mainServiceDependencies = homeRouteServiceDependencies(),
            mainPermissionDependencies = homeRoutePermissionDependencies(),
            mainDiagnosticsDependencies = homeRouteDiagnosticsDependencies(),
            mainControlPlaneDependencies = homeRouteControlPlaneDependencies(),
            mainLifecycleDependencies = homeRouteLifecycleDependencies(appSettingsRepository, crashReportReader),
            stringResolver = FakeStringResolver(),
            activeTransportProvider = java.util.Optional.empty(),
            pcapCaptureRuntimeController = null,
            savedStateHandle = SavedStateHandle(),
        ).also(MainViewModel::initialize)
    }
}

private fun homeRouteServiceDependencies(): MainServiceDependencies =
    MainServiceDependencies(
        serviceStateStore = FakeServiceStateStore(),
        serviceController = FakeServiceController(),
        trafficStatsReader = FakeTrafficStatsReader(),
        hardKillSwitchStateStore = FakeAndroidHardKillSwitchStateStore(),
    )

private fun homeRoutePermissionDependencies(): MainPermissionDependencies =
    MainPermissionDependencies(
        permissionPlatformBridge =
            FakePermissionPlatformBridge(
                vpnPermissionIntent = Intent("fake.vpn.permission"),
            ),
        permissionStatusProvider = FakePermissionStatusProvider(),
        permissionCoordinator = PermissionCoordinator(),
    )

private fun homeRouteDiagnosticsDependencies(): MainDiagnosticsDependencies =
    MainDiagnosticsDependencies(
        diagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
        diagnosticsScanController = StubDiagnosticsScanController(),
        diagnosticsShareService = StubDiagnosticsShareService(),
        homeDiagnosticsServices =
            HomeDiagnosticsServices(
                workflowService = StubDiagnosticsHomeWorkflowService(),
                compositeRunService = StubDiagnosticsHomeCompositeRunService(),
            ),
        latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
        networkPathValidationSource = FakeNetworkPathValidationSource(),
    )

private fun homeRouteControlPlaneDependencies(): MainControlPlaneDependencies =
    MainControlPlaneDependencies(
        hostPackCatalogUiStateStore =
            com.poyka.ripdpi.hosts
                .HostPackCatalogUiStateStore(),
        hostPackCatalogUiStateCoordinator =
            com.poyka.ripdpi.hosts.HostPackCatalogUiStateCoordinator(
                repository = EmptyHostPackCatalogRepository,
                clock =
                    com.poyka.ripdpi.hosts
                        .HostPackCatalogClock { 0L },
                stateStore =
                    com.poyka.ripdpi.hosts
                        .HostPackCatalogUiStateStore(),
            ),
        strategyPackStateStore =
            com.poyka.ripdpi.data
                .InMemoryStrategyPackStateStore(),
        proxyGroupRepository = TestEmptyProxyGroupRepository,
        subscriptionExpiryClock =
            com.poyka.ripdpi.subscription
                .SubscriptionExpiryClock { 0L },
    )

private fun homeRouteLifecycleDependencies(
    appSettingsRepository: AppSettingsRepository,
    crashReportReader: com.poyka.ripdpi.diagnostics.crash.CrashReportReader,
): MainLifecycleDependencies =
    MainLifecycleDependencies(
        appLockLifecycleCoordinator =
            MainAppLockLifecycleCoordinator(
                com.poyka.ripdpi.security
                    .AppLockLifecycleObserver(RuntimeEnvironment.getApplication()),
            ),
        startupSideEffectsCoordinator =
            MainStartupSideEffectsCoordinator(
                appSettingsRepository = appSettingsRepository,
                crashReportReader = crashReportReader,
            ),
        settingsDismissCoordinator = MainSettingsDismissCoordinator(appSettingsRepository = appSettingsRepository),
        crashReportCoordinator = MainCrashReportCoordinator(crashReportReader = crashReportReader),
    )

private object EmptyHostPackCatalogRepository : com.poyka.ripdpi.hosts.HostPackCatalogRepository {
    override suspend fun loadSnapshot(): com.poyka.ripdpi.hosts.HostPackCatalogLoadResult =
        com.poyka.ripdpi.hosts
            .HostPackCatalogLoadResult(
                snapshot =
                    com.poyka.ripdpi.data
                        .HostPackCatalogSnapshot(),
            )

    override suspend fun refreshSnapshot(): com.poyka.ripdpi.data.HostPackCatalogSnapshot =
        com.poyka.ripdpi.data
            .HostPackCatalogSnapshot()
}
