package com.poyka.ripdpi.integration

import android.content.Intent
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
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
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Named

private fun shellNativeMemoryProbe(): NativeMemoryProbe =
    NativeMemoryProbe {
        NativeMemorySample(
            nativeHeapBytes = 0L,
            processRssBytes = 0L,
        )
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
class MainActivityShellInstrumentedTest {
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
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .setRipdpiMode("vpn")
                .build(),
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
    var nativeMemoryProbe: NativeMemoryProbe = shellNativeMemoryProbe()

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

    private val recordingServiceController: RecordingInstrumentedServiceController
        get() = serviceController as RecordingInstrumentedServiceController

    private val mutablePermissionStatusProvider: MutablePermissionStatusProvider
        get() = permissionStatusProvider as MutablePermissionStatusProvider

    private val recordingMainActivityHost: RecordingMainActivityHost
        get() = mainActivityHost as RecordingMainActivityHost

    @Before
    fun setUp() {
        recordingMainActivityHost.clear()
        mutablePermissionStatusProvider.snapshot = grantedPermissionSnapshot()
    }

    @Test
    fun launchIntentRequestStartsConfiguredModeOnce() {
        val originalIntent = sendConfiguredStartIntent()

        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                recordingServiceController.startedModes.size == 1
            }

            assertEquals(1, recordingServiceController.startedModes.size)
            assertEquals(com.poyka.ripdpi.data.Mode.VPN, recordingServiceController.startedModes.single())
        } finally {
            restoreScenarioIntent(originalIntent)
        }
    }

    @Test
    fun missingNotificationsStartConfiguredModeWithoutHostCommand() {
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.RequiresSystemPrompt,
                batteryOptimization = PermissionStatus.Granted,
            )

        val originalIntent = sendConfiguredStartIntent()

        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                recordingServiceController.startedModes.size == 1
            }

            assertEquals(com.poyka.ripdpi.data.Mode.VPN, recordingServiceController.startedModes.single())
            assertFalse(
                recordingMainActivityHost.commands.contains(MainActivityHostCommand.RequestNotificationsPermission),
            )
        } finally {
            restoreScenarioIntent(originalIntent)
        }
    }

    @Test
    fun vpnDialogAppearsAndContinueEmitsVpnConsentCommand() {
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.RequiresSystemPrompt,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
        val originalIntent = sendConfiguredStartIntent()

        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule
                    .onAllNodes(
                        hasTestTag(RipDpiTestTags.VpnPermissionDialog),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogContinue).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                recordingMainActivityHost.commands.any { command ->
                    command is MainActivityHostCommand.RequestVpnConsent
                }
            }
        } finally {
            restoreScenarioIntent(originalIntent)
        }
    }

    @Test
    fun tappingShareSupportBundleRoutesToHost() {
        composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Settings)).performClick()
        composeRule
            .onNode(
                hasScrollToNodeAction(),
            ).performScrollToNode(hasTestTag(RipDpiTestTags.SettingsSupportBundle))
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSupportBundle).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordingMainActivityHost.commands.contains(MainActivityHostCommand.ShareDebugBundle)
        }
    }

    private fun sendConfiguredStartIntent(): Intent {
        waitForMainShellReady()
        val originalIntent = Intent(composeRule.activity.intent)
        composeRule.runOnUiThread {
            composeRule.activity.startActivity(
                MainActivity
                    .createLaunchIntent(
                        context = composeRule.activity,
                        requestStartConfiguredMode = true,
                    ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        return originalIntent
    }

    private fun waitForMainShellReady() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule
                .onAllNodes(hasTestTag(RipDpiTestTags.bottomNav(Route.Home)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun restoreScenarioIntent(originalIntent: Intent) {
        composeRule.runOnUiThread {
            composeRule.activity.setIntent(originalIntent)
        }
    }
}
