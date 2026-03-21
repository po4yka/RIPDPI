package com.poyka.ripdpi.integration

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.activities.MainActivityHost
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.data.ServiceStateStoreModule
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.DiagnosticsManagerModule
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionStatusProviderModule
import com.poyka.ripdpi.platform.AppPlatformBindingsModule
import com.poyka.ripdpi.platform.HostAutolearnStoreController
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceControllerModule
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.testing.FakeInstrumentedAppSettingsRepository
import com.poyka.ripdpi.testing.FakeInstrumentedHostAutolearnStoreController
import com.poyka.ripdpi.testing.FakeInstrumentedLauncherIconController
import com.poyka.ripdpi.testing.FakeInstrumentedPermissionPlatformBridge
import com.poyka.ripdpi.testing.FakeInstrumentedStringResolver
import com.poyka.ripdpi.testing.MutablePermissionStatusProvider
import com.poyka.ripdpi.testing.RecordingInstrumentedServiceController
import com.poyka.ripdpi.testing.RecordingMainActivityHost
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsManager
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
    var serviceController: ServiceController = RecordingInstrumentedServiceController()

    @BindValue
    @JvmField
    var diagnosticsManager: DiagnosticsManager = StubInstrumentedDiagnosticsManager()

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
        val continueLabel = composeRule.activity.getString(R.string.permissions_vpn_continue)
        val dialogTitle = composeRule.activity.getString(R.string.permissions_vpn_title)

        sendConfiguredStartIntent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(dialogTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(continueLabel).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordingMainActivityHost.commands.any { command ->
                command is MainActivityHostCommand.RequestVpnConsent
            }
        }
    }

    @Test
    fun tappingShareSupportBundleRoutesToHost() {
        val settingsLabel = composeRule.activity.getString(R.string.settings)
        val shareSupportBundleLabel = composeRule.activity.getString(R.string.settings_share_debug_bundle_action)

        composeRule.onNodeWithText(settingsLabel).performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(shareSupportBundleLabel))
        composeRule.onNodeWithText(shareSupportBundleLabel).performClick()
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
