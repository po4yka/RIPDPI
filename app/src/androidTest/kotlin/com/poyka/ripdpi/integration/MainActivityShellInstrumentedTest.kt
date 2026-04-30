package com.poyka.ripdpi.integration

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
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionStatusProviderModule
import com.poyka.ripdpi.platform.AppPlatformBindingsModule
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceControllerModule
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.testing.FakeInstrumentedAppSettingsRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
class MainActivityShellInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

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
    var diagnosticsBootstrapper: DiagnosticsBootstrapper = StubInstrumentedDiagnosticsBootstrapper()

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
    var permissionStatusProvider: PermissionStatusProvider = MutablePermissionStatusProvider()

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
    internal var mainActivityHost: MainActivityHost = RecordingMainActivityHost()

    private val recordingServiceController: RecordingInstrumentedServiceController
        get() = serviceController as RecordingInstrumentedServiceController

    private val mutablePermissionStatusProvider: MutablePermissionStatusProvider
        get() = permissionStatusProvider as MutablePermissionStatusProvider

    private val recordingMainActivityHost: RecordingMainActivityHost
        get() = mainActivityHost as RecordingMainActivityHost

    @Before
    fun setUp() {
        hiltRule.inject()
        recordingMainActivityHost.clear()
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
    }

    @Test
    fun launchIntentRequestStartsConfiguredModeOnce() {
        sendConfiguredStartIntent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordingServiceController.startedModes.size == 1
        }

        assertEquals(1, recordingServiceController.startedModes.size)
        assertEquals(com.poyka.ripdpi.data.Mode.VPN, recordingServiceController.startedModes.single())
    }

    @Test
    fun missingNotificationsEmitsHostCommandInsteadOfStartingService() {
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.RequiresSystemPrompt,
                batteryOptimization = PermissionStatus.Granted,
            )

        sendConfiguredStartIntent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordingMainActivityHost.commands.contains(MainActivityHostCommand.RequestNotificationsPermission)
        }

        assertTrue(recordingServiceController.startedModes.isEmpty())
    }

    @Test
    fun vpnDialogAppearsAndContinueEmitsVpnConsentCommand() {
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.RequiresSystemPrompt,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
        sendConfiguredStartIntent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(RipDpiTestTags.VpnPermissionDialog)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogContinue).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordingMainActivityHost.commands.any { command ->
                command is MainActivityHostCommand.RequestVpnConsent
            }
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

    private fun sendConfiguredStartIntent() {
        composeRule.runOnUiThread {
            composeRule.activity.startActivity(
                MainActivity.createLaunchIntent(
                    context = composeRule.activity,
                    requestStartConfiguredMode = true,
                ),
            )
        }
    }
}
