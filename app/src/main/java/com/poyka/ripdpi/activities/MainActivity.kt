package com.poyka.ripdpi.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionDialog
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var diagnosticsManager: DiagnosticsManager

    @Inject
    lateinit var logcatSnapshotCollector: LogcatSnapshotCollector

    private val viewModel: MainViewModel by viewModels()
    private val openHomeRequests = MutableStateFlow(false)
    private val showVpnPermissionDialog = MutableStateFlow(false)
    private val startConfiguredModeRequests = MutableStateFlow(false)
    private var pendingDiagnosticsArchive: PendingDiagnosticsArchive? = null

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

    private data class PendingDiagnosticsArchive(
        val filePath: String,
        val fileName: String,
    )

    private val vpnPermissionRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onPermissionResult(
                kind = PermissionKind.VpnConsent,
                result = if (it.resultCode == RESULT_OK) PermissionResult.Granted else PermissionResult.Denied,
            )
        }

    private val notificationPermissionRegister =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(
                kind = PermissionKind.Notifications,
                result =
                    mapNotificationPermissionResult(
                        granted = granted,
                        shouldShowRationale =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                true
                            },
                    ),
            )
        }

    private val batteryOptimizationRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onPermissionResult(
                kind = PermissionKind.BatteryOptimization,
                result = PermissionResult.ReturnedFromSettings,
            )
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logcatSnapshot = runCatching { logcatSnapshotCollector.capture() }.getOrNull()

                if (logcatSnapshot == null) {
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                this@MainActivity,
                                R.string.logs_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    return@launch
                }

                val uri =
                    it.data?.data ?: run {
                        logcat(LogPriority.ERROR) { "No data in result" }
                        return@launch
                    }
                contentResolver.openOutputStream(uri)?.use { stream ->
                    try {
                        stream.write(logcatSnapshot.content.toByteArray())
                    } catch (e: IOException) {
                        logcat(LogPriority.ERROR) { "Failed to save logs\n${e.asLog()}" }
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to open output stream" }
                }
            }
        }

    private val diagnosticsArchiveRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val archive = pendingDiagnosticsArchive ?: return@registerForActivityResult
            pendingDiagnosticsArchive = null
            lifecycleScope.launch(Dispatchers.IO) {
                val uri = it.data?.data ?: return@launch
                val source = java.io.File(archive.filePath)
                contentResolver.openOutputStream(uri)?.use { stream ->
                    source.inputStream().use { input -> input.copyTo(stream) }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.startupState.value.isReady }
        openHomeRequests.value = requestsHomeTab(intent)
        startConfiguredModeRequests.value = requestsConfiguredStart(intent)

        setContent {
            val startupState by viewModel.startupState.collectAsStateWithLifecycle()
            val openHomeRequested by openHomeRequests.collectAsStateWithLifecycle()
            val vpnPermissionDialogVisible by showVpnPermissionDialog.collectAsStateWithLifecycle()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val launchConfiguredStartRequested by startConfiguredModeRequests.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(viewModel, snackbarHostState) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.RequestPermission -> {
                            handlePermissionEffect(effect)
                        }

                        is MainEffect.OpenAppSettings -> {
                            startActivity(effect.intent)
                        }

                        MainEffect.ShowVpnPermissionDialog -> {
                            showVpnPermissionDialog.value = true
                        }

                        is MainEffect.ShowError -> {
                            snackbarHostState.showRipDpiSnackbar(
                                message = effect.message,
                                tone = RipDpiSnackbarTone.Error,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                }
            }

            LaunchedEffect(launchConfiguredStartRequested) {
                if (launchConfiguredStartRequested) {
                    startConfiguredModeRequests.value = false
                    viewModel.onPrimaryConnectionAction()
                }
            }

            RipDpiTheme(themePreference = startupState.theme) {
                if (startupState.isReady) {
                    val initialStartDestination = remember { startupState.startDestination }
                    RipDpiNavHost(
                        startDestination = initialStartDestination,
                        onSaveLogs = { saveLogs() },
                        onShareDebugBundle = { shareDebugBundle() },
                        onSaveDiagnosticsArchive = { filePath, fileName ->
                            saveDiagnosticsArchive(filePath = filePath, fileName = fileName)
                        },
                        onShareDiagnosticsArchive = { filePath, fileName ->
                            shareDiagnosticsArchive(filePath = filePath, fileName = fileName)
                        },
                        onShareDiagnosticsSummary = { title, body ->
                            shareDiagnosticsSummary(title = title, body = body)
                        },
                        mainViewModel = viewModel,
                        launchHomeRequested = openHomeRequested,
                        onLaunchHomeHandled = {
                            openHomeRequests.value = false
                        },
                        onStartConfiguredMode = viewModel::onPrimaryConnectionAction,
                        onRepairPermission = viewModel::onRepairPermissionRequested,
                        snackbarHostState = snackbarHostState,
                    )
                    if (vpnPermissionDialogVisible) {
                        LaunchedEffect(uiState.connectionState) {
                            if (uiState.connectionState == ConnectionState.Connecting ||
                                uiState.connectionState == ConnectionState.Connected
                            ) {
                                showVpnPermissionDialog.value = false
                            }
                        }
                        VpnPermissionDialog(
                            uiState = uiState,
                            onDismiss = { showVpnPermissionDialog.value = false },
                            onContinue = viewModel::onVpnPermissionContinueRequested,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionSnapshot()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (requestsHomeTab(intent)) {
            openHomeRequests.value = true
        }
        if (requestsConfiguredStart(intent)) {
            startConfiguredModeRequests.value = true
        }
    }

    private fun handlePermissionEffect(effect: MainEffect.RequestPermission) {
        when (effect.kind) {
            PermissionKind.Notifications -> {
                notificationPermissionRegister.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            PermissionKind.VpnConsent -> {
                effect.payload?.let(vpnPermissionRegister::launch)
            }

            PermissionKind.BatteryOptimization -> {
                effect.payload?.let(batteryOptimizationRegister::launch)
            }
        }
    }

    private fun saveLogs() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "ripdpi.log")
            }
        logsRegister.launch(intent)
    }

    private fun shareDebugBundle() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    diagnosticsManager.createArchive(null)
                }
            }.onSuccess { archive ->
                shareDiagnosticsArchive(filePath = archive.absolutePath, fileName = archive.fileName)
            }.onFailure { error ->
                logcat(LogPriority.ERROR) { "Failed to prepare support bundle\n${error.asLog()}" }
                Toast
                    .makeText(
                        this@MainActivity,
                        R.string.debug_bundle_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    private fun saveDiagnosticsArchive(
        filePath: String,
        fileName: String,
    ) {
        pendingDiagnosticsArchive =
            PendingDiagnosticsArchive(
                filePath = filePath,
                fileName = fileName,
            )
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
        diagnosticsArchiveRegister.launch(intent)
    }

    private fun shareDiagnosticsArchive(
        filePath: String,
        fileName: String,
    ) {
        val archiveUri =
            FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
                java.io.File(filePath),
            )
        val shareIntent = DiagnosticsShareIntents.createArchiveShareIntent(archiveUri = archiveUri, fileName = fileName)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.diagnostics_share_archive_chooser)))
    }

    private fun shareDiagnosticsSummary(
        title: String,
        body: String,
    ) {
        val shareIntent = DiagnosticsShareIntents.createSummaryShareIntent(title = title, body = body)
        startActivity(Intent.createChooser(shareIntent, title))
    }
}
