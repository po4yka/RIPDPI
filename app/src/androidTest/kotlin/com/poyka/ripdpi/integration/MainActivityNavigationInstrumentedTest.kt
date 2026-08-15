package com.poyka.ripdpi.integration

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.activities.MainActivityHost
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStateStoreModule
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsManagerModule
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsRuntimeEvidenceModule
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.exit.LastExitInspector
import com.poyka.ripdpi.diagnostics.memory.NativeMemoryProbe
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import com.poyka.ripdpi.diagnostics.profiling.MemoryProfilingRegistrar
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionStatusProviderModule
import com.poyka.ripdpi.platform.AppPlatformBindingsModule
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.EngineAppFacadeModule
import com.poyka.ripdpi.services.EnginePlatformCapabilities
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceControllerModule
import com.poyka.ripdpi.services.StartupFallbackController
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.testing.FakeInstrumentedAppSettingsRepository
import com.poyka.ripdpi.testing.FakeInstrumentedEnginePlatformCapabilities
import com.poyka.ripdpi.testing.FakeInstrumentedHostAutolearnStoreController
import com.poyka.ripdpi.testing.FakeInstrumentedLauncherIconController
import com.poyka.ripdpi.testing.FakeInstrumentedPermissionPlatformBridge
import com.poyka.ripdpi.testing.FakeInstrumentedServiceStateStore
import com.poyka.ripdpi.testing.FakeInstrumentedStringResolver
import com.poyka.ripdpi.testing.MutablePermissionStatusProvider
import com.poyka.ripdpi.testing.RecordingInstrumentedServiceController
import com.poyka.ripdpi.testing.RecordingMainActivityHost
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsBootstrapper
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsDetailLoader
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsHistorySource
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsHomeWorkflowService
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsRememberedPolicySource
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsResolverActions
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsScanController
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsShareService
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsTimelineSource
import com.poyka.ripdpi.testing.StubInstrumentedLastExitInspector
import com.poyka.ripdpi.testing.StubInstrumentedMemoryProfilingRegistrar
import com.poyka.ripdpi.testing.StubInstrumentedProxyPreferencesResolver
import com.poyka.ripdpi.testing.StubInstrumentedRipDpiProxyFactory
import com.poyka.ripdpi.testing.StubInstrumentedTun2SocksBridgeFactory
import com.poyka.ripdpi.testing.StubInstrumentedVpnTunnelSessionProvider
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import javax.inject.Named

private const val NavigationProfileId = "default"

private fun instrumentedNativeMemoryProbe(): NativeMemoryProbe =
    NativeMemoryProbe {
        NativeMemorySample(
            nativeHeapBytes = 0L,
            processRssBytes = 0L,
        )
    }

private fun navigationSettings(
    onboardingComplete: Boolean = true,
    biometricEnabled: Boolean = false,
): AppSettings =
    AppSettings
        .newBuilder()
        .setOnboardingComplete(onboardingComplete)
        .setBiometricEnabled(biometricEnabled)
        .setRipdpiMode("vpn")
        .setDiagnosticsActiveProfileId(NavigationProfileId)
        .build()

private fun navigationApproachSummary(): BypassApproachSummary =
    BypassApproachSummary(
        approachId = BypassApproachId(BypassApproachKind.Profile, NavigationProfileId),
        displayName = "Default profile",
        secondaryLabel = "Profile",
        verificationState = com.poyka.ripdpi.diagnostics.BypassApproachVerificationState.CONFIRMED_WORKING,
        validatedScanCount = 1,
        validatedSuccessCount = 1,
        validatedSuccessRate = 1.0f,
        lastValidatedResult = "ok",
        usageCount = 1,
        totalRuntimeDurationMs = 100L,
        recentRuntimeHealth = BypassRuntimeHealthSummary(),
        lastUsedAt = 100L,
    )

private fun navigationDiagnosticsTimelineSource(): StubInstrumentedDiagnosticsTimelineSource =
    StubInstrumentedDiagnosticsTimelineSource().apply {
        approachStats.value = listOf(navigationApproachSummary())
    }

private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(
    tag: String,
    timeoutMillis: Long = 5_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
}

private fun AndroidComposeTestRule<*, MainActivity>.assertScreenVisible(route: Route) {
    val tag = RipDpiTestTags.screen(route)
    waitForTag(tag)
    onNodeWithTag(tag).assertIsDisplayed()
}

private fun AndroidComposeTestRule<*, MainActivity>.tapBottomNav(route: Route) {
    val tag = RipDpiTestTags.bottomNav(route)
    waitForTag(tag)
    onNodeWithTag(tag).performClick()
}

private fun AndroidComposeTestRule<*, MainActivity>.sendLaunchHomeIntent(): Intent {
    val originalIntent = Intent(activity.intent)
    runOnUiThread {
        activity.startActivity(
            MainActivity.createLaunchIntent(
                context = activity,
                openHome = true,
            ),
        )
    }
    return originalIntent
}

private fun AndroidComposeTestRule<*, MainActivity>.restoreScenarioIntent(originalIntent: Intent) {
    runOnUiThread {
        activity.setIntent(originalIntent)
    }
}

private fun AndroidComposeTestRule<*, MainActivity>.pressBack() {
    runOnUiThread {
        activity.onBackPressedDispatcher.onBackPressed()
    }
}

@HiltAndroidTest
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    EngineAppFacadeModule::class,
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    DiagnosticsRuntimeEvidenceModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
class MainActivityNavigationInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val hiltInjectionRule = hiltRule.injectBeforeActivityRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    @Named("diagnosticsJson")
    var diagnosticsJson: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(navigationSettings())

    @BindValue
    @JvmField
    var proxyPreferencesResolver: ProxyPreferencesResolver = StubInstrumentedProxyPreferencesResolver()

    @BindValue
    @JvmField
    var proxyFactory: RipDpiProxyFactory = StubInstrumentedRipDpiProxyFactory()

    @BindValue
    @JvmField
    var tun2SocksBridgeFactory: Tun2SocksBridgeFactory = StubInstrumentedTun2SocksBridgeFactory()

    @BindValue
    @JvmField
    var serviceStateStore: ServiceStateStore = FakeInstrumentedServiceStateStore()

    @BindValue
    @JvmField
    var vpnTunnelSessionProvider: VpnTunnelSessionProvider = StubInstrumentedVpnTunnelSessionProvider()

    @BindValue
    @JvmField
    var serviceController: ServiceController = RecordingInstrumentedServiceController()

    @BindValue
    @JvmField
    var startupFallbackController: StartupFallbackController =
        serviceController as RecordingInstrumentedServiceController

    @BindValue
    @JvmField
    var diagnosticsBootstrapper: DiagnosticsBootstrapper = StubInstrumentedDiagnosticsBootstrapper()

    @BindValue
    @JvmField
    var lastExitInspector: LastExitInspector = StubInstrumentedLastExitInspector()

    @BindValue
    @JvmField
    var memoryProfilingRegistrar: MemoryProfilingRegistrar = StubInstrumentedMemoryProfilingRegistrar()

    @BindValue
    @JvmField
    var nativeMemoryProbe: NativeMemoryProbe = instrumentedNativeMemoryProbe()

    @BindValue
    @JvmField
    var diagnosticsTimelineSource: DiagnosticsTimelineSource = navigationDiagnosticsTimelineSource()

    @BindValue
    @JvmField
    var diagnosticsScanController: DiagnosticsScanController = StubInstrumentedDiagnosticsScanController()

    @BindValue
    @JvmField
    var diagnosticsDetailLoader: DiagnosticsDetailLoader = StubInstrumentedDiagnosticsDetailLoader()

    @BindValue
    @JvmField
    var diagnosticsShareService: DiagnosticsShareService = StubInstrumentedDiagnosticsShareService()

    @BindValue
    @JvmField
    var diagnosticsResolverActions: DiagnosticsResolverActions = StubInstrumentedDiagnosticsResolverActions()

    @BindValue
    @JvmField
    var diagnosticsHistorySource: DiagnosticsHistorySource = StubInstrumentedDiagnosticsHistorySource()

    @BindValue
    @JvmField
    var diagnosticsRememberedPolicySource: DiagnosticsRememberedPolicySource =
        StubInstrumentedDiagnosticsRememberedPolicySource()

    @BindValue
    @JvmField
    var diagnosticsActiveConnectionPolicySource: DiagnosticsActiveConnectionPolicySource =
        StubInstrumentedDiagnosticsActiveConnectionPolicySource()

    @BindValue
    @JvmField
    var diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService =
        StubInstrumentedDiagnosticsHomeWorkflowService()

    @BindValue
    @JvmField
    var diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService =
        StubInstrumentedDiagnosticsHomeCompositeRunService()

    @BindValue
    @JvmField
    var permissionStatusProvider: PermissionStatusProvider =
        MutablePermissionStatusProvider(grantedPermissionSnapshot())

    @BindValue
    @JvmField
    var permissionPlatformBridge: PermissionPlatformBridge = FakeInstrumentedPermissionPlatformBridge()

    @BindValue
    @JvmField
    var stringResolver: StringResolver = FakeInstrumentedStringResolver()

    @BindValue
    @JvmField
    var launcherIconController: LauncherIconController = FakeInstrumentedLauncherIconController()

    @BindValue
    @JvmField
    var hostAutolearnStoreController: HostAutolearnStoreController = FakeInstrumentedHostAutolearnStoreController()

    @BindValue
    @JvmField
    var enginePlatformCapabilities: EnginePlatformCapabilities = FakeInstrumentedEnginePlatformCapabilities()

    @BindValue
    @JvmField
    internal var mainActivityHost: MainActivityHost = RecordingMainActivityHost()

    @Test
    fun startupDestinationIsHomeWhenOnboardingCompleteAndBiometricDisabled() {
        composeRule.assertScreenVisible(Route.Home)
    }

    @Test
    fun bottomNavSwitchesBetweenTopLevelDestinations() {
        composeRule.assertScreenVisible(Route.Home)

        composeRule.tapBottomNav(Route.Config)
        composeRule.assertScreenVisible(Route.Config)

        composeRule.tapBottomNav(Route.Diagnostics())
        composeRule.assertScreenVisible(Route.Diagnostics())

        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        composeRule.tapBottomNav(Route.Home)
        composeRule.assertScreenVisible(Route.Home)
    }

    @Test
    fun nestedSettingsDestinationIsRestoredAfterReturningToSettings() {
        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDnsSettings).performClick()
        composeRule.assertScreenVisible(Route.DnsSettings)

        val originalIntent = composeRule.sendLaunchHomeIntent()
        try {
            composeRule.assertScreenVisible(Route.Home)
        } finally {
            composeRule.restoreScenarioIntent(originalIntent)
        }

        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)
    }

    @Test
    fun advancedSettingsScreenRetainsStableSelectorContract() {
        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.SettingsAdvancedConnectivity))
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsAdvancedConnectivity).performClick()
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.SettingsAdvancedSettings))
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsAdvancedSettings).performClick()
        composeRule.assertScreenVisible(Route.AdvancedSettings)
    }

    @Test
    fun openingHistoryFromDiagnosticsReturnsToDiagnosticsOnBack() {
        composeRule.tapBottomNav(Route.Diagnostics())
        composeRule.assertScreenVisible(Route.Diagnostics())

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsTopHistoryAction).performClick()
        composeRule.assertScreenVisible(Route.History)

        composeRule.pressBack()
        composeRule.assertScreenVisible(Route.Diagnostics())
    }

    @Test
    fun launchHomeRequestReturnsToHomeFromAnotherTopLevelDestination() {
        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        val originalIntent = composeRule.sendLaunchHomeIntent()
        try {
            composeRule.assertScreenVisible(Route.Home)
        } finally {
            composeRule.restoreScenarioIntent(originalIntent)
        }
    }

    @Test
    fun diagnosticsApproachesSectionIsReachableFromBottomNav() {
        composeRule.assertScreenVisible(Route.Home)

        composeRule.tapBottomNav(Route.Diagnostics())

        composeRule.assertScreenVisible(Route.Diagnostics())
        composeRule.waitForTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Tools))
        composeRule
            .onNodeWithTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Tools))
            .performClick()
        composeRule.waitForTag(
            RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles))
            .assertIsDisplayed()
    }

    @Test
    fun simpleHomeWiresDiagnosticStartCancelAndShareThroughMainViewModel() {
        assumeTrue("githubSimple only", BuildConfig.APP_EXPERIENCE == "simple")
        val runs = diagnosticsHomeCompositeRunService as StubInstrumentedDiagnosticsHomeCompositeRunService
        val shares = diagnosticsShareService as StubInstrumentedDiagnosticsShareService
        val host = mainActivityHost as RecordingMainActivityHost
        val runAction = RipDpiTestTags.HomeDiagnosticsRunAnalysis

        composeRule.waitForTag(runAction)
        composeRule.onNodeWithTag(runAction).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runs.startedRunIds.size == 1 }
        val cancelLabel = composeRule.activity.getString(R.string.diagnostics_action_cancel)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(runAction).and(hasText(cancelLabel)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val cancelledRunId = runs.startedRunIds.single()
        composeRule.onNodeWithTag(runAction).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runs.cancelledRunIds == listOf(cancelledRunId)
        }
        val runLabelId =
            composeRule.activity.resources.getIdentifier(
                "simple_run_report",
                "string",
                composeRule.activity.packageName,
            )
        assertTrue("simple_run_report resource is missing", runLabelId != 0)
        val runLabel = composeRule.activity.getString(runLabelId)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(runAction).and(hasText(runLabel)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(runAction).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runs.startedRunIds.size == 2 }
        val completedRunId = runs.startedRunIds.last()
        runs.completeRun(
            DiagnosticsHomeCompositeOutcome(
                runId = completedRunId,
                actionable = false,
                headline = "Network analysis complete",
                summary = "Report ready",
                bundleSessionIds = listOf("session-1"),
            ),
        )

        composeRule.waitForTag(RipDpiTestTags.HomeDiagnosticsShareAction)
        composeRule.onNodeWithText("Network analysis complete").assertIsDisplayed()
        composeRule.onNodeWithText("Report ready").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsShareAction).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { shares.archiveRequests.size == 1 }

        val request = shares.archiveRequests.single()
        assertEquals(completedRunId, request.homeRunId)
        assertEquals(listOf("session-1"), request.sessionIds)
        assertEquals(DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS, request.reason)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            host.commands.contains(
                MainActivityHostCommand.ShareDiagnosticsArchive(
                    filePath = "/tmp/home.zip",
                    fileName = "home.zip",
                ),
            )
        }
    }
}

@HiltAndroidTest
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    EngineAppFacadeModule::class,
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    DiagnosticsRuntimeEvidenceModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
class MainActivityOnboardingStartupInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val hiltInjectionRule = hiltRule.injectBeforeActivityRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    @Named("diagnosticsJson")
    var diagnosticsJson: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(
            navigationSettings(onboardingComplete = false),
        )

    @BindValue
    @JvmField
    var proxyPreferencesResolver: ProxyPreferencesResolver = StubInstrumentedProxyPreferencesResolver()

    @BindValue
    @JvmField
    var proxyFactory: RipDpiProxyFactory = StubInstrumentedRipDpiProxyFactory()

    @BindValue
    @JvmField
    var tun2SocksBridgeFactory: Tun2SocksBridgeFactory = StubInstrumentedTun2SocksBridgeFactory()

    @BindValue
    @JvmField
    var serviceStateStore: ServiceStateStore = FakeInstrumentedServiceStateStore()

    @BindValue
    @JvmField
    var vpnTunnelSessionProvider: VpnTunnelSessionProvider = StubInstrumentedVpnTunnelSessionProvider()

    @BindValue
    @JvmField
    var serviceController: ServiceController = RecordingInstrumentedServiceController()

    @BindValue
    @JvmField
    var startupFallbackController: StartupFallbackController =
        serviceController as RecordingInstrumentedServiceController

    @BindValue
    @JvmField
    var diagnosticsBootstrapper: DiagnosticsBootstrapper = StubInstrumentedDiagnosticsBootstrapper()

    @BindValue
    @JvmField
    var lastExitInspector: LastExitInspector = StubInstrumentedLastExitInspector()

    @BindValue
    @JvmField
    var memoryProfilingRegistrar: MemoryProfilingRegistrar = StubInstrumentedMemoryProfilingRegistrar()

    @BindValue
    @JvmField
    var nativeMemoryProbe: NativeMemoryProbe = instrumentedNativeMemoryProbe()

    @BindValue
    @JvmField
    var diagnosticsTimelineSource: DiagnosticsTimelineSource = StubInstrumentedDiagnosticsTimelineSource()

    @BindValue
    @JvmField
    var diagnosticsScanController: DiagnosticsScanController = StubInstrumentedDiagnosticsScanController()

    @BindValue
    @JvmField
    var diagnosticsDetailLoader: DiagnosticsDetailLoader = StubInstrumentedDiagnosticsDetailLoader()

    @BindValue
    @JvmField
    var diagnosticsShareService: DiagnosticsShareService = StubInstrumentedDiagnosticsShareService()

    @BindValue
    @JvmField
    var diagnosticsResolverActions: DiagnosticsResolverActions = StubInstrumentedDiagnosticsResolverActions()

    @BindValue
    @JvmField
    var diagnosticsHistorySource: DiagnosticsHistorySource = StubInstrumentedDiagnosticsHistorySource()

    @BindValue
    @JvmField
    var diagnosticsRememberedPolicySource: DiagnosticsRememberedPolicySource =
        StubInstrumentedDiagnosticsRememberedPolicySource()

    @BindValue
    @JvmField
    var diagnosticsActiveConnectionPolicySource: DiagnosticsActiveConnectionPolicySource =
        StubInstrumentedDiagnosticsActiveConnectionPolicySource()

    @BindValue
    @JvmField
    var diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService =
        StubInstrumentedDiagnosticsHomeWorkflowService()

    @BindValue
    @JvmField
    var diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService =
        StubInstrumentedDiagnosticsHomeCompositeRunService()

    @BindValue
    @JvmField
    var permissionStatusProvider: PermissionStatusProvider =
        MutablePermissionStatusProvider(grantedPermissionSnapshot())

    @BindValue
    @JvmField
    var permissionPlatformBridge: PermissionPlatformBridge = FakeInstrumentedPermissionPlatformBridge()

    @BindValue
    @JvmField
    var stringResolver: StringResolver = FakeInstrumentedStringResolver()

    @BindValue
    @JvmField
    var launcherIconController: LauncherIconController = FakeInstrumentedLauncherIconController()

    @BindValue
    @JvmField
    var hostAutolearnStoreController: HostAutolearnStoreController = FakeInstrumentedHostAutolearnStoreController()

    @BindValue
    @JvmField
    var enginePlatformCapabilities: EnginePlatformCapabilities = FakeInstrumentedEnginePlatformCapabilities()

    @BindValue
    @JvmField
    internal var mainActivityHost: MainActivityHost = RecordingMainActivityHost()

    @Test
    fun startupDestinationIsOnboardingWhenOnboardingIncomplete() {
        composeRule.assertScreenVisible(Route.Onboarding)
    }
}

@HiltAndroidTest
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    EngineAppFacadeModule::class,
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    DiagnosticsRuntimeEvidenceModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
@Ignore("Biometric startup routing is covered by MainViewModelTest; emulator keystore state is not stable here.")
class MainActivityBiometricStartupInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val hiltInjectionRule = hiltRule.injectBeforeActivityRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    @Named("diagnosticsJson")
    var diagnosticsJson: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(
            navigationSettings(biometricEnabled = true).toBuilder().setBackupPin("1234").build(),
        )

    @BindValue
    @JvmField
    var proxyPreferencesResolver: ProxyPreferencesResolver = StubInstrumentedProxyPreferencesResolver()

    @BindValue
    @JvmField
    var proxyFactory: RipDpiProxyFactory = StubInstrumentedRipDpiProxyFactory()

    @BindValue
    @JvmField
    var tun2SocksBridgeFactory: Tun2SocksBridgeFactory = StubInstrumentedTun2SocksBridgeFactory()

    @BindValue
    @JvmField
    var serviceStateStore: ServiceStateStore = FakeInstrumentedServiceStateStore()

    @BindValue
    @JvmField
    var vpnTunnelSessionProvider: VpnTunnelSessionProvider = StubInstrumentedVpnTunnelSessionProvider()

    @BindValue
    @JvmField
    var serviceController: ServiceController = RecordingInstrumentedServiceController()

    @BindValue
    @JvmField
    var startupFallbackController: StartupFallbackController =
        serviceController as RecordingInstrumentedServiceController

    @BindValue
    @JvmField
    var diagnosticsBootstrapper: DiagnosticsBootstrapper = StubInstrumentedDiagnosticsBootstrapper()

    @BindValue
    @JvmField
    var lastExitInspector: LastExitInspector = StubInstrumentedLastExitInspector()

    @BindValue
    @JvmField
    var memoryProfilingRegistrar: MemoryProfilingRegistrar = StubInstrumentedMemoryProfilingRegistrar()

    @BindValue
    @JvmField
    var nativeMemoryProbe: NativeMemoryProbe = instrumentedNativeMemoryProbe()

    @BindValue
    @JvmField
    var diagnosticsTimelineSource: DiagnosticsTimelineSource = StubInstrumentedDiagnosticsTimelineSource()

    @BindValue
    @JvmField
    var diagnosticsScanController: DiagnosticsScanController = StubInstrumentedDiagnosticsScanController()

    @BindValue
    @JvmField
    var diagnosticsDetailLoader: DiagnosticsDetailLoader = StubInstrumentedDiagnosticsDetailLoader()

    @BindValue
    @JvmField
    var diagnosticsShareService: DiagnosticsShareService = StubInstrumentedDiagnosticsShareService()

    @BindValue
    @JvmField
    var diagnosticsResolverActions: DiagnosticsResolverActions = StubInstrumentedDiagnosticsResolverActions()

    @BindValue
    @JvmField
    var diagnosticsHistorySource: DiagnosticsHistorySource = StubInstrumentedDiagnosticsHistorySource()

    @BindValue
    @JvmField
    var diagnosticsRememberedPolicySource: DiagnosticsRememberedPolicySource =
        StubInstrumentedDiagnosticsRememberedPolicySource()

    @BindValue
    @JvmField
    var diagnosticsActiveConnectionPolicySource: DiagnosticsActiveConnectionPolicySource =
        StubInstrumentedDiagnosticsActiveConnectionPolicySource()

    @BindValue
    @JvmField
    var diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService =
        StubInstrumentedDiagnosticsHomeWorkflowService()

    @BindValue
    @JvmField
    var diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService =
        StubInstrumentedDiagnosticsHomeCompositeRunService()

    @BindValue
    @JvmField
    var permissionStatusProvider: PermissionStatusProvider =
        MutablePermissionStatusProvider(grantedPermissionSnapshot())

    @BindValue
    @JvmField
    var permissionPlatformBridge: PermissionPlatformBridge = FakeInstrumentedPermissionPlatformBridge()

    @BindValue
    @JvmField
    var stringResolver: StringResolver = FakeInstrumentedStringResolver()

    @BindValue
    @JvmField
    var launcherIconController: LauncherIconController = FakeInstrumentedLauncherIconController()

    @BindValue
    @JvmField
    var hostAutolearnStoreController: HostAutolearnStoreController = FakeInstrumentedHostAutolearnStoreController()

    @BindValue
    @JvmField
    var enginePlatformCapabilities: EnginePlatformCapabilities = FakeInstrumentedEnginePlatformCapabilities()

    @BindValue
    @JvmField
    internal var mainActivityHost: MainActivityHost = RecordingMainActivityHost()

    @Test
    fun startupDestinationIsBiometricPromptWhenBiometricGateEnabled() {
        composeRule.assertScreenVisible(Route.BiometricPrompt)
    }
}
