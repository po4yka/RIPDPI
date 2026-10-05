package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.AppStartupReadiness
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.ReadyAppStartupReadiness
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MainActivityContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `composition initializes the view model once`() {
        val permissionStatusProvider = FakePermissionStatusProvider()
        val controller = MainActivityShellController()
        val recomposeTrigger = mutableIntStateOf(0)
        val viewModel = createViewModel(permissionStatusProvider = permissionStatusProvider)

        composeRule.setContent {
            recomposeTrigger.intValue
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { permissionStatusProvider.currentSnapshotCalls > 0 }
        val snapshotCalls = permissionStatusProvider.currentSnapshotCalls

        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(snapshotCalls, permissionStatusProvider.currentSnapshotCalls)
    }

    @Test
    fun `pending recovery gates view model initialization until ready`() {
        val permissionStatusProvider = FakePermissionStatusProvider()
        val readiness = MutableAppStartupReadiness(AppStartupReadinessState.Pending)
        val viewModel =
            createViewModel(
                permissionStatusProvider = permissionStatusProvider,
                appStartupReadiness = readiness,
            )

        composeRule.setContent {
            MainActivityContent(
                exportPreview =
                    androidx.compose.runtime.remember {
                        createExportPreviewForTest()
                    },
                viewModel = viewModel,
                controller = MainActivityShellController(),
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, permissionStatusProvider.currentSnapshotCalls)
        composeRule.onNodeWithTag(RipDpiTestTags.StartupRecoveryPending).assertIsDisplayed()

        composeRule.runOnIdle { readiness.state.value = AppStartupReadinessState.Ready }
        composeRule.waitUntil(timeoutMillis = 5_000) { permissionStatusProvider.currentSnapshotCalls > 0 }
    }

    @Test
    fun `failed recovery renders retry without waiting for settings`() {
        val readiness = MutableAppStartupReadiness(AppStartupReadinessState.Failed)
        val permissionStatusProvider = FakePermissionStatusProvider()
        val viewModel =
            createViewModel(
                appSettingsRepository = DelayedAppSettingsRepository(),
                permissionStatusProvider = permissionStatusProvider,
                appStartupReadiness = readiness,
            )

        composeRule.setContent {
            MainActivityContent(
                exportPreview =
                    androidx.compose.runtime.remember {
                        createExportPreviewForTest()
                    },
                viewModel = viewModel,
                controller = MainActivityShellController(),
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.StartupRecoveryFailure).assertIsDisplayed()
        assertEquals(0, permissionStatusProvider.currentSnapshotCalls)
        composeRule.onNodeWithTag(RipDpiTestTags.StartupRecoveryRetry).performClick()
        composeRule.waitForIdle()

        assertEquals(1, readiness.retryCalls)
        composeRule.onNodeWithTag(RipDpiTestTags.StartupRecoveryPending).assertIsDisplayed()
    }

    @Test
    fun `start configured mode request invokes primary action once and clears shell request`() {
        val serviceController = FakeServiceController()
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStartConfiguredMode = true,
                ),
            )
        val viewModel =
            createViewModel(
                serviceController = serviceController,
                permissionStatusProvider = grantedStartupPermissionStatusProvider(),
            )

        composeRule.setContent {
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { serviceController.startedModes.size == 1 }

        assertEquals(listOf(Mode.VPN), serviceController.startedModes)
        assertFalse(controller.state.value.startConfiguredModeRequested)
    }

    @Test
    fun `start configured mode request waits for loaded settings before starting`() {
        val settingsRepository = DelayedAppSettingsRepository()
        val serviceController = FakeServiceController()
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStartConfiguredMode = true,
                ),
            )
        val viewModel =
            createViewModel(
                appSettingsRepository = settingsRepository,
                serviceController = serviceController,
                permissionStatusProvider = grantedStartupPermissionStatusProvider(),
            )

        composeRule.setContent {
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }
        composeRule.waitForIdle()

        assertTrue(serviceController.startedModes.isEmpty())
        assertTrue(controller.state.value.startConfiguredModeRequested)

        composeRule.runOnIdle {
            settingsRepository.emitSettings(
                AppSettings
                    .newBuilder()
                    .setOnboardingComplete(true)
                    .setRipdpiMode("proxy")
                    .build(),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { serviceController.startedModes.isNotEmpty() }

        assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        assertFalse(controller.state.value.startConfiguredModeRequested)
    }

    @Test
    fun `vpn dialog renders from shell state and continue emits vpn consent host command`() {
        val controller = MainActivityShellController().apply { showVpnPermissionDialog() }
        val commands = CopyOnWriteArrayList<MainActivityHostCommand>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectorJob =
            collectorScope.launch {
                controller.hostCommands.collect { command ->
                    commands += command
                }
            }
        val viewModel =
            createViewModel(
                permissionStatusProvider =
                    FakePermissionStatusProvider(
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.Granted,
                        ),
                    ),
            )
        composeRule.setContent {
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogContinue).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            commands.any { command -> command is MainActivityHostCommand.RequestVpnConsent }
        }

        collectorJob.cancel()
    }

    @Test
    fun `vpn dialog dismiss hides dialog`() {
        val controller = MainActivityShellController().apply { showVpnPermissionDialog() }
        val viewModel = createViewModel()
        composeRule.setContent {
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogDismiss).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(RipDpiTestTags.VpnPermissionDialog))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertDoesNotExist()
    }

    @Test
    fun `snackbar renders from shell ui event`() {
        val controller =
            MainActivityShellController().apply {
                onEffect(MainEffect.ShowError("boom"))
            }
        val viewModel = createViewModel()

        composeRule.setContent {
            MainActivityContent(
                exportPreview = androidx.compose.runtime.remember { createExportPreviewForTest() },
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(RipDpiTestTags.MainErrorSnackbar))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.MainErrorSnackbar).assertIsDisplayed()
        // Text lives in a child node of the Surface container; check it exists anywhere in the tree.
        composeRule.onNodeWithText("boom").assertExists()
    }

    @Test
    fun `actual host preview commands never launch before confirmation and cancel has no external action`() =
        runTest {
            val activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create()
            val activity = activityController.get()
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            val host = DefaultMainActivityHost(service, java.util.Optional.empty())
            host.register(activity, createViewModel(), owner)
            activityController.start().resume()
            val commands =
                listOf(
                    MainActivityHostCommand.SaveLogs,
                    MainActivityHostCommand.ShareDebugBundle,
                    MainActivityHostCommand.PrepareDiagnosticsExport(
                        exportPreparation(com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose.ShareSummary),
                    ),
                    MainActivityHostCommand.PrepareDiagnosticsExport(
                        exportPreparation(com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose.SaveArchive),
                    ),
                )
            commands.forEach { command ->
                host.handle(command)
                advanceUntilIdle()
                val ready = requireNotNull(owner.state.value)
                assertTrue(ready.presentation is com.poyka.ripdpi.ui.components.export.ExportPreviewPresentation.Ready)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertTrue(service.consumed.isEmpty())
                owner.cancel(ready.token)
                runCurrent()
                assertNull(owner.state.value)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            assertEquals(commands.size, service.prepareCount)
            activityController.pause().stop().destroy()
        }

    @Test
    fun `actual host launches exact final summary once after confirmed unlocked handoff`() =
        runTest {
            val activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create()
            val activity = activityController.get()
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            val host = DefaultMainActivityHost(service, java.util.Optional.empty())
            host.register(activity, createViewModel(), owner)
            activityController.start().resume()
            host.handle(
                MainActivityHostCommand.PrepareDiagnosticsExport(
                    exportPreparation(com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose.ShareSummary),
                ),
            )
            advanceUntilIdle()
            val token = owner.state.value!!.token
            owner.confirm(token)
            owner.confirm(token)
            advanceUntilIdle()
            assertNull(shadowOf(activity).nextStartedActivity)
            owner.setContentUnlocked(true)
            runCurrent()
            val chooser = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals("Final summary", shared.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("text/plain", shared.type)
            owner.confirm(token)
            runCurrent()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(1, service.consumed.size)
            assertTrue(token.leaseId in service.discarded)
            activityController.pause().stop().destroy()
        }

    @Test
    fun `actual host replacement handoff proceeds while canceled old validation remains suspended`() =
        runTest {
            val activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create()
            val activity = activityController.get()
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            val host = DefaultMainActivityHost(service, java.util.Optional.empty())
            host.register(activity, createViewModel(), owner)
            activityController.start().resume()
            val blocked = CompletableDeferred<Unit>()
            try {
                val preparation =
                    exportPreparation(com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose.ShareSummary)
                owner.prepare(preparation)
                advanceUntilIdle()
                val old = owner.state.value!!.token
                owner.confirm(old)
                advanceUntilIdle()
                service.validationBlocker = blocked
                owner.setContentUnlocked(true)
                runCurrent()
                assertEquals(2, service.validationCount)
                assertNull(shadowOf(activity).nextStartedActivity)
                owner.prepare(preparation)
                service.validationBlocker = null
                advanceUntilIdle()
                val replacement = owner.state.value!!.token
                owner.confirm(replacement)
                advanceUntilIdle()
                assertFalse(blocked.isCompleted)
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Canceled old validation blocked the replacement handoff", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
                assertEquals(listOf(old.leaseId, replacement.leaseId), service.consumed)
                assertTrue(old.leaseId in service.discarded)
                assertNull(owner.state.value)
                blocked.complete(Unit)
                runCurrent()
                assertNull(shadowOf(activity).nextStartedActivity)
            } finally {
                blocked.complete(Unit)
                activityController.pause().stop().destroy()
            }
        }

    private fun exportPreparation(purpose: com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose) =
        com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation.Archive(
            com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest(
                reason = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason.SHARE_ARCHIVE,
                requestedAt = 10,
            ),
            purpose,
        )

    private fun createViewModel(
        appSettingsRepository: AppSettingsRepository =
            FakeAppSettingsRepository(
                AppSettings
                    .newBuilder()
                    .setOnboardingComplete(true)
                    .setRipdpiMode("vpn")
                    .build(),
            ),
        serviceController: FakeServiceController = FakeServiceController(),
        permissionStatusProvider: FakePermissionStatusProvider = FakePermissionStatusProvider(),
        hostPackCatalogUiStateStore: com.poyka.ripdpi.hosts.HostPackCatalogUiStateStore =
            com.poyka.ripdpi.hosts
                .HostPackCatalogUiStateStore(),
        strategyPackStateStore: com.poyka.ripdpi.data.InMemoryStrategyPackStateStore =
            com.poyka.ripdpi.data
                .InMemoryStrategyPackStateStore(),
        appStartupReadiness: AppStartupReadiness = ReadyAppStartupReadiness,
    ): MainViewModel {
        val crashReportReader =
            com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
            )
        return MainViewModel(
            appSettingsRepository = appSettingsRepository,
            mainServiceDependencies =
                MainServiceDependencies(
                    appliedConfigurationSource = FakeAppliedRuntimeConfigurationSource(),
                    reconnectCoordinator = FakeRunningServiceReconnect(),
                    serviceStateStore = FakeServiceStateStore(),
                    serviceController = serviceController,
                    trafficStatsReader = FakeTrafficStatsReader(),
                    hardKillSwitchStateStore = FakeAndroidHardKillSwitchStateStore(),
                ),
            mainPermissionDependencies =
                MainPermissionDependencies(
                    permissionPlatformBridge =
                        FakePermissionPlatformBridge(
                            vpnPermissionIntent = Intent("fake.vpn.permission"),
                        ),
                    permissionStatusProvider = permissionStatusProvider,
                    permissionCoordinator = PermissionCoordinator(),
                ),
            mainDiagnosticsDependencies =
                MainDiagnosticsDependencies(
                    diagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
                    diagnosticsScanController = StubDiagnosticsScanController(),
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = StubDiagnosticsHomeWorkflowService(),
                            compositeRunService = StubDiagnosticsHomeCompositeRunService(),
                        ),
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                    networkPathValidationSource = FakeNetworkPathValidationSource(),
                ),
            mainControlPlaneDependencies =
                MainControlPlaneDependencies(
                    hostPackCatalogUiStateStore = hostPackCatalogUiStateStore,
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
                            stateStore = hostPackCatalogUiStateStore,
                        ),
                    strategyPackStateStore = strategyPackStateStore,
                    proxyGroupRepository = TestEmptyProxyGroupRepository,
                    subscriptionExpiryClock =
                        com.poyka.ripdpi.subscription
                            .SubscriptionExpiryClock { 0L },
                ),
            mainLifecycleDependencies =
                createLifecycleDependencies(
                    appSettingsRepository = appSettingsRepository,
                    crashReportReader = crashReportReader,
                    appStartupReadiness = appStartupReadiness,
                ),
            stringResolver = FakeStringResolver(),
            activeTransportProvider = java.util.Optional.empty(),
            pcapCaptureRuntimeController = null,
            savedStateHandle = SavedStateHandle(),
        )
    }

    private fun createLifecycleDependencies(
        appSettingsRepository: AppSettingsRepository,
        crashReportReader: com.poyka.ripdpi.diagnostics.crash.CrashReportReader,
        appStartupReadiness: AppStartupReadiness,
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
            settingsDismissCoordinator = MainSettingsDismissCoordinator(appSettingsRepository),
            crashReportCoordinator = MainCrashReportCoordinator(crashReportReader),
            appStartupReadiness = appStartupReadiness,
        )

    private fun grantedStartupPermissionStatusProvider(): FakePermissionStatusProvider =
        FakePermissionStatusProvider(
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            ),
        )

    private class MutableAppStartupReadiness(
        initial: AppStartupReadinessState,
    ) : AppStartupReadiness {
        val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        var retryCalls = 0
            private set

        override val readiness = state

        override fun retryRecovery() {
            retryCalls += 1
            state.value = AppStartupReadinessState.Pending
        }
    }

    private class DelayedAppSettingsRepository(
        initialSnapshot: AppSettings = AppSettingsSerializer.defaultValue,
    ) : AppSettingsRepository {
        private val emissions = MutableSharedFlow<AppSettings>(replay = 1)
        private var latest = initialSnapshot

        override val settings: Flow<AppSettings> = emissions

        override suspend fun snapshot(): AppSettings = latest

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            replace(
                latest
                    .toBuilder()
                    .apply(transform)
                    .build(),
            )
        }

        override suspend fun replace(settings: AppSettings) {
            latest = settings
            emissions.emit(settings)
        }

        fun emitSettings(settings: AppSettings) {
            latest = settings
            check(emissions.tryEmit(settings))
        }
    }
}
