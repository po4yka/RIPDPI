package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.data.support.SupportSettingsDeepLinkParseResult
import com.poyka.ripdpi.data.support.SupportSettingsDeepLinkParser
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.platform.ProvideDeviceHourCycle
import com.poyka.ripdpi.platform.userMessageRes
import com.poyka.ripdpi.proxyimport.ImportHandlerActivity
import com.poyka.ripdpi.proxyimport.ImportLaunchRoute
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.services.ProcessDeathResumeCoordinator
import com.poyka.ripdpi.shortcuts.SelectorShortcutCapability
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.diagnostics.share.DiagnosticShareLinkDeepLink
import com.poyka.ripdpi.ui.screens.onboarding.LocalOnboardingBadgeAnchor
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingBadgeAnchor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    internal lateinit var mainActivityHost: MainActivityHost

    @Inject
    internal lateinit var automationController: Optional<AutomationController>

    @Inject
    internal lateinit var selectorSelectionStore: SelectorSelectionStore

    @Inject
    internal lateinit var selectorShortcutCapability: SelectorShortcutCapability

    @Inject
    internal lateinit var processDeathResumeCoordinator: ProcessDeathResumeCoordinator

    private val viewModel: MainViewModel by viewModels()
    private val exportPreview: ExportPreviewViewModel by viewModels()
    private lateinit var shellController: MainActivityShellController
    private val pendingAutomationLaunches = ArrayDeque<Bundle>()
    private val automationPrepared = MutableStateFlow(true)

    companion object {
        fun createLaunchIntent(
            context: Context,
            openHome: Boolean = false,
            requestStartConfiguredMode: Boolean = false,
            requestStopConfiguredMode: Boolean = false,
        ): Intent =
            createMainActivityLaunchIntent(
                context = context,
                openHome = openHome,
                requestStartConfiguredMode = requestStartConfiguredMode,
                requestStopConfiguredMode = requestStopConfiguredMode,
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        shellController =
            MainActivityShellController(
                initialIntent = intent,
                savedPendingLaunchRequests = savedInstanceState?.getBundle(PendingLaunchRequestsStateKey),
            )
        applySelectorSelection(intent)
        val restoredAutomationLaunches =
            savedInstanceState?.let {
                BundleCompat.getParcelableArrayList(it, PendingAutomationLaunchesStateKey, Bundle::class.java)
            }
        if (restoredAutomationLaunches.isNullOrEmpty()) {
            prepareLaunchRoute(intent, preserveRestoredRoute = savedInstanceState != null)
        } else {
            pendingAutomationLaunches.addAll(restoredAutomationLaunches)
            preparePendingAutomationLaunches()
        }
        // NavController handles Activity.intent when its graph is created. Keep inbound
        // links in the shell so the onboarding and app-lock gates can defer them.
        setIntent(safeMainActivityIntent(this, intent))
        mainActivityHost.register(this, viewModel, exportPreview)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { resumeAfterProcessDeath() }
                shellController.hostCommands.collect { command ->
                    runCatching { mainActivityHost.handle(command) }
                        .onFailure { error ->
                            Logger.e(error, tag = LogTags.UI) { "Host command failed: $command" }
                            hostCommandFailurePermissionResult(command)?.let { (kind, result) ->
                                viewModel.onPermissionResult(kind = kind, result = result)
                            }
                            shellController.onEffect(MainEffect.ShowError(getString(error.userMessageRes())))
                        }
                }
            }
        }
        val badgeAnchor = OnboardingBadgeAnchor()
        val startsOnOnboarding = {
            viewModel.startupState.value.run { isReady && startDestination == Route.Onboarding }
        }
        val holdForBadge = splashHoldForBadge(badgeAnchor)
        var startupShellComposed = false
        splashScreen.setKeepOnScreenCondition {
            val readiness = viewModel.startupState.value.readiness
            // Let the splash exit without waiting for a Compose frame it can block.
            !startupShellComposed ||
                readiness == AppStartupReadinessState.Pending ||
                (readiness == AppStartupReadinessState.Ready && !automationPrepared.value) ||
                holdForBadge(startsOnOnboarding())
        }
        splashScreen.setRipDpiExit(badgeAnchor, startsOnOnboarding)

        setContent {
            val startupState by viewModel.startupState.collectAsStateWithLifecycle()
            var contentStarted by rememberSaveable { mutableStateOf(false) }
            var readyContentStarted by remember { mutableStateOf(false) }
            val prepared by automationPrepared.collectAsStateWithLifecycle()
            val waitingForAutomation = !readyContentStarted && !prepared && startupState.isReady
            val showContent = contentStarted || startupState.readiness != AppStartupReadinessState.Pending
            SideEffect { startupShellComposed = true }
            if (showContent && !waitingForAutomation) {
                // After a failure, keep the retry screen visible when recovery starts again.
                SideEffect {
                    contentStarted = true
                    if (startupState.isReady) readyContentStarted = true
                }
                CompositionLocalProvider(LocalOnboardingBadgeAnchor provides badgeAnchor) {
                    ProvideDeviceHourCycle {
                        MainActivityContent(
                            viewModel = viewModel,
                            controller = shellController,
                            startupState = startupState,
                            exportPreview = exportPreview,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(PendingLaunchRequestsStateKey, shellController.savePendingLaunchRequests())
        if (pendingAutomationLaunches.isNotEmpty()) {
            outState.putParcelableArrayList(PendingAutomationLaunchesStateKey, ArrayList(pendingAutomationLaunches))
        }
        super.onSaveInstanceState(outState)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resumeAfterProcessDeath() {
        viewModel.startupState.first { state ->
            state.readiness == AppStartupReadinessState.Ready
        }
        automationPrepared.first { it }
        try {
            processDeathResumeCoordinator.resumeIfNeeded()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(error, tag = LogTags.UI) { "Process-death VPN recovery failed" }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(safeMainActivityIntent(this))
        applySelectorSelection(intent)
        prepareLaunchRoute(intent, preserveRestoredRoute = false, isNewIntent = true)
    }

    private fun prepareLaunchRoute(
        intent: Intent?,
        preserveRestoredRoute: Boolean,
        isNewIntent: Boolean = false,
    ) {
        if (!automationController.isPresent) {
            applyLaunchRoute(intent, null, preserveRestoredRoute, isNewIntent)
            return
        }
        pendingAutomationLaunches.addLast(
            Bundle().apply {
                putParcelable(AutomationIntentKey, intent?.let(::Intent))
                putBoolean(PreserveRestoredRouteKey, preserveRestoredRoute)
                putBoolean(AutomationNewIntentKey, isNewIntent)
            },
        )
        preparePendingAutomationLaunches()
    }

    private fun preparePendingAutomationLaunches() {
        val controller = automationController.orElse(null) ?: return
        if (!automationPrepared.compareAndSet(expect = true, update = false)) return
        lifecycleScope.launch {
            while (pendingAutomationLaunches.isNotEmpty()) {
                val request = pendingAutomationLaunches.first()
                val intent = BundleCompat.getParcelable(request, AutomationIntentKey, Intent::class.java)
                val config = controller.prepareLaunch(intent)
                applyLaunchRoute(
                    intent,
                    config.startRoute,
                    request.getBoolean(PreserveRestoredRouteKey),
                    request.getBoolean(AutomationNewIntentKey),
                )
                pendingAutomationLaunches.removeFirst()
            }
            automationPrepared.value = true
        }
    }

    private fun applyLaunchRoute(
        intent: Intent?,
        automationRoute: String?,
        preserveRestoredRoute: Boolean,
        isNewIntent: Boolean,
    ) {
        if (isNewIntent) shellController.onNewIntent(intent)
        val route = automationRoute ?: navigationRouteFrom(intent)?.stableRoute
        if (route != null || !preserveRestoredRoute) {
            shellController.setLaunchRouteRequest(route)
        }
    }

    private fun applySelectorSelection(intent: Intent?) {
        val groupId = selectorGroupIdFrom(intent)
        val profileId = selectorProfileIdFrom(intent)
        if (groupId != null || profileId != null) {
            if (!applySelectorSelectionIntent(
                    intent = intent,
                    verifies = selectorShortcutCapability::verifies,
                    select = {
                        groupId,
                        profileId,
                        ->
                        lifecycleScope.launch { selectorSelectionStore.select(groupId, profileId) }
                    },
                )
            ) {
                Logger.w(tag = LogTags.UI) { "Rejected selector selection without a valid shortcut capability" }
            }
        }
    }
}

internal fun applySelectorSelectionIntent(
    intent: Intent?,
    verifies: (Intent?) -> Boolean,
    select: (String, String) -> Unit,
): Boolean {
    val groupId = selectorGroupIdFrom(intent)
    val profileId = selectorProfileIdFrom(intent)
    return if (groupId == null || profileId == null || !verifies(intent)) {
        false
    } else {
        runCatching { select(groupId, profileId) }
            .onFailure { error ->
                Logger.w(error, tag = LogTags.UI) { "Failed to apply selector selection from shortcut intent" }
            }.isSuccess
    }
}

private const val extraOpenHome = "com.poyka.ripdpi.extra.OPEN_HOME"
private const val PendingLaunchRequestsStateKey = "pending-launch-requests"
private const val PendingAutomationLaunchesStateKey = "pending-automation-launches"
private const val AutomationIntentKey = "intent"
private const val PreserveRestoredRouteKey = "preserve-restored-route"
private const val AutomationNewIntentKey = "new-intent"
private const val extraStartConfiguredMode = "com.poyka.ripdpi.extra.START_CONFIGURED_MODE"
private const val extraStopConfiguredMode = "com.poyka.ripdpi.extra.STOP_CONFIGURED_MODE"
internal const val internalVpnControlActivityClassName =
    "com.poyka.ripdpi.activities.InternalVpnControlActivity"

internal fun createMainActivityLaunchIntent(
    context: Context,
    openHome: Boolean = false,
    requestStartConfiguredMode: Boolean = false,
    requestStopConfiguredMode: Boolean = false,
): Intent =
    Intent().setClassName(context.packageName, internalVpnControlActivityClassName).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (openHome) {
            putExtra(extraOpenHome, true)
        }
        if (requestStartConfiguredMode) {
            putExtra(extraStartConfiguredMode, true)
        }
        if (requestStopConfiguredMode) {
            putExtra(extraStopConfiguredMode, true)
        }
    }

internal fun safeMainActivityIntent(
    context: Context,
    launchIntent: Intent? = null,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (launchIntent?.action == Intent.ACTION_MAIN && launchIntent.data == null && launchIntent.type == null) {
            action = Intent.ACTION_MAIN
            launchIntent.categories?.forEach(::addCategory)
        }
    }

internal fun navigationRouteFrom(intent: Intent?): Route? =
    intent
        ?.takeIf { it.action == Intent.ACTION_VIEW }
        ?.data
        ?.takeIf { it.scheme == "ripdpi" && it.path.isNullOrEmpty() }
        ?.let { uri ->
            when (uri.authority) {
                "connect" -> Route.Status
                "config" -> Route.Connection
                "diagnostics" -> Route.Diagnostics()
                "settings" -> Route.Settings
                "subscription-failover" -> Route.SubscriptionFailover
                "detection-check" -> Route.DetectionCheck
                else -> null
            }
        }

internal fun requestsHomeTab(intent: Intent?): Boolean = intent?.getBooleanExtra(extraOpenHome, false) == true

internal fun requestsConfiguredStart(intent: Intent?): Boolean =
    isInternalVpnControlIntent(intent) && intent?.getBooleanExtra(extraStartConfiguredMode, false) == true

internal fun requestsConfiguredStop(intent: Intent?): Boolean =
    isInternalVpnControlIntent(intent) && intent?.getBooleanExtra(extraStopConfiguredMode, false) == true

private fun isInternalVpnControlIntent(intent: Intent?): Boolean =
    intent?.component?.className == internalVpnControlActivityClassName

internal fun selectorGroupIdFrom(intent: Intent?): String? =
    intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectGroupId)

internal fun selectorProfileIdFrom(intent: Intent?): String? =
    intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectProfileId)

internal fun diagnosticShareFragment(intent: Intent?): String? = DiagnosticShareLinkDeepLink.fragmentFrom(intent)

@Suppress("ReturnCount")
internal fun importRouteFrom(intent: Intent?): Route? {
    when (val result = supportSettingsRouteFrom(intent)) {
        is SupportSettingsDeepLinkParseResult.Success -> return Route.SupportSettings(packageJson = result.packageJson)

        is SupportSettingsDeepLinkParseResult.Error,
        null,
        -> Unit
    }
    val route = intent?.getStringExtra(ImportHandlerActivity.EXTRA_IMPORT_ROUTE) ?: return null
    return when (route) {
        ImportLaunchRoute.PROFILE_CONFIRM -> {
            val importToken =
                intent.getStringExtra(ImportHandlerActivity.EXTRA_PROFILE_IMPORT_TOKEN) ?: return null
            if (!PendingProxyImportStore.process.contains(importToken)) return null
            Route.ProfileImportConfirm(importToken = importToken)
        }

        ImportLaunchRoute.SUBSCRIPTION_CONFIRM -> {
            val importToken =
                intent.getStringExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_IMPORT_TOKEN) ?: return null
            if (!PendingProxyImportStore.process.contains(importToken)) return null
            Route.SubscriptionImportConfirm(importToken = importToken)
        }

        ImportLaunchRoute.AMNEZIAWG_EDITOR -> {
            val importToken =
                intent.getStringExtra(ImportHandlerActivity.EXTRA_AMNEZIAWG_IMPORT_TOKEN) ?: return null
            if (!PendingProxyImportStore.process.contains(importToken)) return null
            Route.AmneziaWgProfile(importToken = importToken)
        }

        else -> {
            null
        }
    }
}

internal fun supportSettingsRouteFrom(intent: Intent?): SupportSettingsDeepLinkParseResult? {
    val data = intent?.data?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return SupportSettingsDeepLinkParser.parse(data)
}

internal fun mapVpnPermissionResult(context: Context): PermissionResult =
    if (VpnService.prepare(context) == null) {
        PermissionResult.Granted
    } else {
        PermissionResult.Denied
    }

internal fun hostCommandFailurePermissionResult(
    command: MainActivityHostCommand,
): Pair<PermissionKind, PermissionResult>? =
    when (command) {
        MainActivityHostCommand.RequestLocalNetworkPermission -> {
            PermissionKind.LocalNetwork to PermissionResult.Denied
        }

        MainActivityHostCommand.RequestNotificationsPermission -> {
            PermissionKind.Notifications to PermissionResult.Denied
        }

        is MainActivityHostCommand.RequestVpnConsent -> {
            PermissionKind.VpnConsent to PermissionResult.Denied
        }

        is MainActivityHostCommand.RequestBatteryOptimization -> {
            PermissionKind.BatteryOptimization to PermissionResult.ReturnedFromSettings
        }

        is MainActivityHostCommand.OpenIntent,
        MainActivityHostCommand.SaveLogs,
        MainActivityHostCommand.ShareDebugBundle,
        is MainActivityHostCommand.PrepareDiagnosticsExport,
        is MainActivityHostCommand.ShareText,
        -> {
            null
        }
    }
