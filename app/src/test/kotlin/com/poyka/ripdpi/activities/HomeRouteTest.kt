package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            viewModel.uiState.value.homeDiagnostics.pcapToggleVisible
        }
        assertFalse(viewModel.uiState.value.homeDiagnostics.pcapRecordingRequested)

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.homeDiagnostics.pcapRecordingRequested
        }

        assertTrue(viewModel.uiState.value.homeDiagnostics.pcapRecordingRequested)
    }

    private fun createViewModel(appSettingsRepository: AppSettingsRepository): MainViewModel {
        val crashReportReader =
            com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
            )
        return MainViewModel(
            appSettingsRepository = appSettingsRepository,
            mainServiceDependencies =
                MainServiceDependencies(
                    serviceStateStore = FakeServiceStateStore(),
                    serviceController = FakeServiceController(),
                    trafficStatsReader = FakeTrafficStatsReader(),
                    hardKillSwitchStateStore = FakeAndroidHardKillSwitchStateStore(),
                ),
            mainPermissionDependencies =
                MainPermissionDependencies(
                    permissionPlatformBridge =
                        FakePermissionPlatformBridge(
                            vpnPermissionIntent = Intent("fake.vpn.permission"),
                        ),
                    permissionStatusProvider = FakePermissionStatusProvider(),
                    permissionCoordinator = PermissionCoordinator(),
                ),
            mainDiagnosticsDependencies =
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
                ),
            mainControlPlaneDependencies =
                MainControlPlaneDependencies(
                    hostPackCatalogUiStateStore =
                        com.poyka.ripdpi.hosts
                            .HostPackCatalogUiStateStore(),
                    hostPackCatalogUiStateCoordinator =
                        com.poyka.ripdpi.hosts.HostPackCatalogUiStateCoordinator(
                            repository =
                                object : com.poyka.ripdpi.hosts.HostPackCatalogRepository {
                                    override suspend fun loadSnapshot(): com.poyka.ripdpi.hosts
                                        .HostPackCatalogLoadResult =
                                        com.poyka.ripdpi.hosts.HostPackCatalogLoadResult(
                                            snapshot =
                                                com.poyka.ripdpi.data
                                                    .HostPackCatalogSnapshot(),
                                        )

                                    override suspend fun refreshSnapshot(): com.poyka.ripdpi.data
                                        .HostPackCatalogSnapshot =
                                        com.poyka.ripdpi.data
                                            .HostPackCatalogSnapshot()
                                },
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
                ),
            mainLifecycleDependencies =
                MainLifecycleDependencies(
                    appLockLifecycleCoordinator =
                        MainAppLockLifecycleCoordinator(
                            com.poyka.ripdpi.security.AppLockLifecycleObserver(
                                RuntimeEnvironment.getApplication(),
                            ),
                        ),
                    startupSideEffectsCoordinator =
                        MainStartupSideEffectsCoordinator(
                            appSettingsRepository = appSettingsRepository,
                            crashReportReader = crashReportReader,
                        ),
                    settingsDismissCoordinator =
                        MainSettingsDismissCoordinator(
                            appSettingsRepository = appSettingsRepository,
                        ),
                    crashReportCoordinator =
                        MainCrashReportCoordinator(
                            crashReportReader = crashReportReader,
                        ),
                ),
            stringResolver = FakeStringResolver(),
        ).also(MainViewModel::initialize)
    }
}
