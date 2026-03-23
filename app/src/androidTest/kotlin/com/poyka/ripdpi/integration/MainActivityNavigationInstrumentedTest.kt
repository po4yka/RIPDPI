package com.poyka.ripdpi.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.activities.MainActivityHost
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.data.ServiceStateStoreModule
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsManagerModule
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
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
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsBootstrapper
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsDetailLoader
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsResolverActions
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsScanController
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsShareService
import com.poyka.ripdpi.testing.StubInstrumentedDiagnosticsTimelineSource
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private fun navigationSettings(
    onboardingComplete: Boolean = true,
    biometricEnabled: Boolean = false,
): AppSettings =
    AppSettings
        .newBuilder()
        .setOnboardingComplete(onboardingComplete)
        .setBiometricEnabled(biometricEnabled)
        .setRipdpiMode("vpn")
        .build()

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

private fun AndroidComposeTestRule<*, MainActivity>.sendLaunchHomeIntent() {
    runOnUiThread {
        activity.startActivity(
            MainActivity.createLaunchIntent(
                context = activity,
                openHome = true,
            ),
        )
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
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
class MainActivityNavigationInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(navigationSettings())

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

    private val mutablePermissionStatusProvider: MutablePermissionStatusProvider
        get() = permissionStatusProvider as MutablePermissionStatusProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
    }

    @Test
    fun startupDestinationIsHomeWhenOnboardingCompleteAndBiometricDisabled() {
        composeRule.assertScreenVisible(Route.Home)
    }

    @Test
    fun bottomNavSwitchesBetweenTopLevelDestinations() {
        composeRule.assertScreenVisible(Route.Home)

        composeRule.tapBottomNav(Route.Config)
        composeRule.assertScreenVisible(Route.Config)

        composeRule.tapBottomNav(Route.Diagnostics)
        composeRule.assertScreenVisible(Route.Diagnostics)

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

        composeRule.sendLaunchHomeIntent()
        composeRule.assertScreenVisible(Route.Home)

        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.DnsSettings)
    }

    @Test
    fun advancedSettingsScreenRetainsStableSelectorContract() {
        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsAdvancedSettings).performClick()
        composeRule.assertScreenVisible(Route.AdvancedSettings)
    }

    @Test
    fun openingHistoryFromDiagnosticsReturnsToDiagnosticsOnBack() {
        composeRule.tapBottomNav(Route.Diagnostics)
        composeRule.assertScreenVisible(Route.Diagnostics)

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsTopHistoryAction).performClick()
        composeRule.assertScreenVisible(Route.History)

        composeRule.pressBack()
        composeRule.assertScreenVisible(Route.Diagnostics)
    }

    @Test
    fun launchHomeRequestReturnsToHomeFromAnotherTopLevelDestination() {
        composeRule.tapBottomNav(Route.Settings)
        composeRule.assertScreenVisible(Route.Settings)

        composeRule.sendLaunchHomeIntent()
        composeRule.assertScreenVisible(Route.Home)
    }

    @Test
    fun homeApproachCardOpensDiagnosticsApproachesSection() {
        composeRule.assertScreenVisible(Route.Home)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeApproachCard).performClick()

        composeRule.assertScreenVisible(Route.Diagnostics)
        composeRule.waitForTag(
            RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles))
            .assertIsDisplayed()
    }
}

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
class MainActivityOnboardingStartupInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(
            navigationSettings(onboardingComplete = false),
        )

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

    private val mutablePermissionStatusProvider: MutablePermissionStatusProvider
        get() = permissionStatusProvider as MutablePermissionStatusProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
    }

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
    VpnTunnelSessionProviderModule::class,
    ServiceControllerModule::class,
    DiagnosticsManagerModule::class,
    PermissionStatusProviderModule::class,
    AppPlatformBindingsModule::class,
    com.poyka.ripdpi.activities.MainActivityHostModule::class,
)
class MainActivityBiometricStartupInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository =
        FakeInstrumentedAppSettingsRepository(
            navigationSettings(biometricEnabled = true),
        )

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

    private val mutablePermissionStatusProvider: MutablePermissionStatusProvider
        get() = permissionStatusProvider as MutablePermissionStatusProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        mutablePermissionStatusProvider.snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            )
    }

    @Test
    fun startupDestinationIsBiometricPromptWhenBiometricGateEnabled() {
        composeRule.assertScreenVisible(Route.BiometricPrompt)
    }
}
