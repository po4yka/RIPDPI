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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.proxyimport.ImportHandlerActivity
import com.poyka.ripdpi.proxyimport.ImportLaunchRoute
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.navigation.decodeImportedProfile
import com.poyka.ripdpi.ui.screens.diagnostics.share.DiagnosticShareLinkDeepLink
import dagger.hilt.android.AndroidEntryPoint
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
        splashScreen.setKeepOnScreenCondition { !viewModel.startupState.value.isReady }

        setContent {
            MainActivityContent(viewModel = viewModel, controller = shellController)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionSnapshot()
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
        val groupId = selectorGroupIdFrom(intent) ?: return
        val profileId = selectorProfileIdFrom(intent) ?: return
        runCatching { selectorSelectionStore.select(groupId, profileId) }
            .onFailure { error ->
                Logger.w(error) { "Failed to apply selector selection from shortcut intent" }
            }
    }
}

private const val extraOpenHome = "com.poyka.ripdpi.extra.OPEN_HOME"
private const val extraStartConfiguredMode = "com.poyka.ripdpi.extra.START_CONFIGURED_MODE"
private const val extraStopConfiguredMode = "com.poyka.ripdpi.extra.STOP_CONFIGURED_MODE"

internal fun createMainActivityLaunchIntent(
    context: Context,
    openHome: Boolean = false,
    requestStartConfiguredMode: Boolean = false,
    requestStopConfiguredMode: Boolean = false,
): Intent =
    Intent(context, MainActivity::class.java).apply {
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
    intent?.getBooleanExtra(extraStartConfiguredMode, false) == true

internal fun requestsConfiguredStop(intent: Intent?): Boolean =
    intent?.getBooleanExtra(extraStopConfiguredMode, false) == true ||
        intent?.data?.toString() == "ripdpi://disconnect"

internal fun selectorGroupIdFrom(intent: Intent?): String? =
    intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectGroupId)

internal fun selectorProfileIdFrom(intent: Intent?): String? =
    intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectProfileId)

internal fun diagnosticShareFragment(intent: Intent?): String? = DiagnosticShareLinkDeepLink.fragmentFrom(intent)

@Suppress("ReturnCount")
internal fun importRouteFrom(intent: Intent?): Route? {
    val route = intent?.getStringExtra(ImportHandlerActivity.EXTRA_IMPORT_ROUTE) ?: return null
    return when (route) {
        ImportLaunchRoute.PROFILE_CONFIRM -> {
            val profileJson =
                intent.getStringExtra(ImportHandlerActivity.EXTRA_PROFILE_JSON) ?: return null
            // Validate the payload up front so navigation never lands on a broken screen.
            if (decodeImportedProfile(profileJson) == null) return null
            Route.ProfileImportConfirm(profileJson = profileJson)
        }

        ImportLaunchRoute.SUBSCRIPTION_CONFIRM -> {
            val url =
                intent.getStringExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_URL) ?: return null
            Route.SubscriptionImportConfirm(
                url = url,
                name = intent.getStringExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_NAME).orEmpty(),
                bootstrap =
                    intent.getBooleanExtra(
                        ImportHandlerActivity.EXTRA_SUBSCRIPTION_BOOTSTRAP,
                        false,
                    ),
            )
        }

        else -> {
            null
        }
    }
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
        is MainActivityHostCommand.ShareDiagnosticsArchive,
        is MainActivityHostCommand.ShareDiagnosticsSummary,
        -> {
            null
        }
    }
