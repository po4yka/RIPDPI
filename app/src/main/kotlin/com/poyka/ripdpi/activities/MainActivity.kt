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
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
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
        private const val EXTRA_OPEN_HOME = "com.poyka.ripdpi.extra.OPEN_HOME"
        private const val EXTRA_START_CONFIGURED_MODE = "com.poyka.ripdpi.extra.START_CONFIGURED_MODE"
        private const val EXTRA_STOP_CONFIGURED_MODE = "com.poyka.ripdpi.extra.STOP_CONFIGURED_MODE"

        fun createLaunchIntent(
            context: Context,
            openHome: Boolean = false,
            requestStartConfiguredMode: Boolean = false,
            requestStopConfiguredMode: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (openHome) {
                    putExtra(EXTRA_OPEN_HOME, true)
                }
                if (requestStartConfiguredMode) {
                    putExtra(EXTRA_START_CONFIGURED_MODE, true)
                }
                if (requestStopConfiguredMode) {
                    putExtra(EXTRA_STOP_CONFIGURED_MODE, true)
                }
            }

        internal fun requestsHomeTab(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_HOME, false) == true

        internal fun requestsConfiguredStart(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_START_CONFIGURED_MODE, false) == true

        internal fun requestsConfiguredStop(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_STOP_CONFIGURED_MODE, false) == true ||
                intent?.data?.toString() == "ripdpi://disconnect"

        internal fun selectorGroupIdFrom(intent: Intent?): String? =
            intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectGroupId)

        internal fun selectorProfileIdFrom(intent: Intent?): String? =
            intent?.getStringExtra(com.poyka.ripdpi.shortcuts.ExtraSelectProfileId)

        internal fun diagnosticShareFragment(intent: Intent?): String? =
            DiagnosticShareLinkDeepLink.fragmentFrom(intent)

        /**
         * Resolves the import-confirmation [Route] an inbound intent forwarded from
         * [ImportHandlerActivity] should open, or `null` when the intent carries no
         * import payload. Never throws — a malformed payload resolves to `null`.
         */
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
            shellController.hostCommands.collect { command ->
                runCatching { mainActivityHost.handle(command) }
                    .onFailure { error ->
                        Logger.e(error) { "Host command failed: $command" }
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
