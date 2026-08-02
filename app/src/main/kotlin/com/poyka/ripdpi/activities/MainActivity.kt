package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.data.support.SupportSettingsDeepLinkParseResult
import com.poyka.ripdpi.data.support.SupportSettingsDeepLinkParser
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.proxyimport.ImportHandlerActivity
import com.poyka.ripdpi.proxyimport.ImportLaunchRoute
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.services.ProcessDeathResumeCoordinator
import com.poyka.ripdpi.shortcuts.SelectorShortcutCapability
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.diagnostics.share.DiagnosticShareLinkDeepLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
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
    private val shellController by lazy(LazyThreadSafetyMode.NONE) { MainActivityShellController(intent) }

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
        applySelectorSelection(intent)
        val automationConfig =
            automationController
                .map { controller -> controller.prepareLaunch(intent) }
                .orElse(null)
        shellController.setLaunchRouteRequest(automationConfig?.startRoute)
        mainActivityHost.register(this, viewModel)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { resumeAfterProcessDeath() }
                shellController.hostCommands.collect { command ->
                    runCatching { mainActivityHost.handle(command) }
                        .onFailure { error ->
                            Logger.e(error) { "Host command failed: $command" }
                            hostCommandFailurePermissionResult(command)?.let { (kind, result) ->
                                viewModel.onPermissionResult(kind = kind, result = result)
                            }
                            val message =
                                error.message
                                    ?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.onboarding_validation_failed_generic)
                            shellController.onEffect(MainEffect.ShowError(message))
                        }
                }
            }
        }
        splashScreen.setKeepOnScreenCondition {
            viewModel.startupState.value.readiness == AppStartupReadinessState.Pending
        }

        setContent {
            MainActivityContent(viewModel = viewModel, controller = shellController)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resumeAfterProcessDeath() {
        viewModel.startupState.first { state ->
            state.readiness == AppStartupReadinessState.Ready
        }
        try {
            processDeathResumeCoordinator.resumeIfNeeded()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(error) { "Process-death VPN recovery failed" }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySelectorSelection(intent)
        val automationConfig =
            automationController
                .map { controller -> controller.prepareLaunch(intent) }
                .orElse(null)
        shellController.onNewIntent(intent)
        shellController.setLaunchRouteRequest(automationConfig?.startRoute)
    }

    private fun applySelectorSelection(intent: Intent?) {
        val groupId = selectorGroupIdFrom(intent)
        val profileId = selectorProfileIdFrom(intent)
        if (groupId != null || profileId != null) {
            if (!applySelectorSelectionIntent(
                    intent = intent,
                    verifies = selectorShortcutCapability::verifies,
                    select = selectorSelectionStore::select,
                )
            ) {
                Logger.w { "Rejected selector selection without a valid shortcut capability" }
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
                Logger.w(error) { "Failed to apply selector selection from shortcut intent" }
            }.isSuccess
    }
}

private const val extraOpenHome = "com.poyka.ripdpi.extra.OPEN_HOME"
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

        else -> {
            null
        }
    }
}

internal fun supportSettingsRouteFrom(intent: Intent?): SupportSettingsDeepLinkParseResult? {
    val data = intent?.data?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return SupportSettingsDeepLinkParser.parse(data)
}

internal fun mapNotificationPermissionResult(
    granted: Boolean,
    shouldShowRationale: Boolean,
): PermissionResult =
    when {
        granted -> PermissionResult.Granted
        shouldShowRationale -> PermissionResult.Denied
        else -> PermissionResult.DeniedPermanently
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
        is MainActivityHostCommand.SaveDiagnosticsArchive,
        is MainActivityHostCommand.SaveDiagnosticsArchiveRequest,
        is MainActivityHostCommand.ShareDiagnosticsArchive,
        is MainActivityHostCommand.ShareDiagnosticsSummary,
        -> {
            null
        }
    }
