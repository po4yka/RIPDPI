package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.permissions.PermissionResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    internal lateinit var mainActivityHost: MainActivityHost

    @Inject
    internal lateinit var automationController: Optional<AutomationController>

    private val viewModel: MainViewModel by viewModels()
    private val shellController by lazy(LazyThreadSafetyMode.NONE) { MainActivityShellController(intent) }

    companion object {
        private const val EXTRA_OPEN_HOME = "com.poyka.ripdpi.extra.OPEN_HOME"
        private const val EXTRA_START_CONFIGURED_MODE = "com.poyka.ripdpi.extra.START_CONFIGURED_MODE"

        fun createLaunchIntent(
            context: Context,
            openHome: Boolean = false,
            requestStartConfiguredMode: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (openHome) {
                    putExtra(EXTRA_OPEN_HOME, true)
                }
                if (requestStartConfiguredMode) {
                    putExtra(EXTRA_START_CONFIGURED_MODE, true)
                }
            }

        internal fun requestsHomeTab(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_HOME, false) == true

        internal fun requestsConfiguredStart(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_START_CONFIGURED_MODE, false) == true

        internal fun mapNotificationPermissionResult(
            granted: Boolean,
            shouldShowRationale: Boolean,
        ): PermissionResult =
            when {
                granted -> PermissionResult.Granted
                shouldShowRationale -> PermissionResult.Denied
                else -> PermissionResult.DeniedPermanently
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val automationConfig =
            automationController
                .map { controller -> controller.prepareLaunch(intent) }
                .orElse(null)
        shellController.setLaunchRouteRequest(automationConfig?.startRoute)
        mainActivityHost.register(this, viewModel)
        lifecycleScope.launch {
            shellController.hostCommands.collect { command ->
                mainActivityHost.handle(command)
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
        val automationConfig =
            automationController
                .map { controller -> controller.prepareLaunch(intent) }
                .orElse(null)
        shellController.onNewIntent(intent)
        shellController.setLaunchRouteRequest(automationConfig?.startRoute)
    }
}
