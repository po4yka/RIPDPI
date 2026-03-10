package com.poyka.ripdpi.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val openHomeRequests = MutableStateFlow(false)
    private val openVpnPermissionRequests = MutableStateFlow(false)
    private var pendingNotificationAction: PendingStartAction? = null
    private var pendingDiagnosticsExport: PendingDiagnosticsExport? = null

    companion object {
        private const val EXTRA_OPEN_HOME = "com.poyka.ripdpi.extra.OPEN_HOME"

        private fun collectLogs(): String? =
            try {
                Runtime
                    .getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to collect logs\n${e.asLog()}" }
                null
            }

        fun createLaunchIntent(
            context: Context,
            openHome: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (openHome) {
                    putExtra(EXTRA_OPEN_HOME, true)
                }
            }

        internal fun requestsHomeTab(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_HOME, false) == true
    }

    private enum class PendingStartAction {
        StartConfiguredMode,
        OpenVpnPermission,
        RequestVpnPermission,
    }

    private data class PendingDiagnosticsExport(
        val filePath: String,
        val fileName: String,
    )

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onVpnPermissionResult(it.resultCode == RESULT_OK)
        }

    private val notificationPermissionRegister =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingNotificationAction
            pendingNotificationAction = null

            if (granted) {
                action?.let(::executePendingStartAction)
            } else {
                viewModel.onNotificationPermissionDenied()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
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
                        stream.write(logs.toByteArray())
                    } catch (e: IOException) {
                        logcat(LogPriority.ERROR) { "Failed to save logs\n${e.asLog()}" }
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to open output stream" }
                }
            }
        }

    private val diagnosticsExportRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val export = pendingDiagnosticsExport ?: return@registerForActivityResult
            pendingDiagnosticsExport = null
            lifecycleScope.launch(Dispatchers.IO) {
                val uri = it.data?.data ?: return@launch
                val source = java.io.File(export.filePath)
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

        setContent {
            val startupState by viewModel.startupState.collectAsStateWithLifecycle()
            val openHomeRequested by openHomeRequests.collectAsStateWithLifecycle()
            val openVpnPermissionRequested by openVpnPermissionRequests.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            var notificationPermissionDialogAction by remember { mutableStateOf<PendingStartAction?>(null) }

            LaunchedEffect(viewModel, snackbarHostState) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.RequestVpnPermission -> {
                            vpnRegister.launch(effect.prepareIntent)
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

            RipDpiTheme(themePreference = startupState.theme) {
                if (startupState.isReady) {
                    notificationPermissionDialogAction?.let { action ->
                        RipDpiDialog(
                            onDismissRequest = { notificationPermissionDialogAction = null },
                            title = getString(R.string.permissions_notifications_title),
                            message = getString(R.string.permissions_notifications_body),
                            dismissLabel = getString(R.string.permissions_notifications_not_now),
                            onDismiss = { notificationPermissionDialogAction = null },
                            confirmLabel = getString(R.string.permissions_notifications_continue),
                            onConfirm = {
                                notificationPermissionDialogAction = null
                                pendingNotificationAction = action
                                notificationPermissionRegister.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            tone = RipDpiDialogTone.Info,
                            icon = RipDpiIcons.Info,
                        )
                    }

                    val initialStartDestination = remember { startupState.startDestination }
                    RipDpiNavHost(
                        startDestination = initialStartDestination,
                        onSaveLogs = { saveLogs() },
                        onExportDiagnostics = { filePath ->
                            if (filePath != null) {
                                saveDiagnosticsExport(filePath)
                            }
                        },
                        mainViewModel = viewModel,
                        openVpnPermissionRequested = openVpnPermissionRequested,
                        onOpenVpnPermissionHandled = {
                            openVpnPermissionRequests.value = false
                        },
                        launchHomeRequested = openHomeRequested,
                        onLaunchHomeHandled = {
                            openHomeRequests.value = false
                        },
                        onStartConfiguredMode = {
                            requestStartAction(
                                action = PendingStartAction.StartConfiguredMode,
                                onNeedsPermissionDialog = { notificationPermissionDialogAction = it },
                            )
                        },
                        onOpenVpnPermission = {
                            requestStartAction(
                                action = PendingStartAction.OpenVpnPermission,
                                onNeedsPermissionDialog = { notificationPermissionDialogAction = it },
                            )
                        },
                        onRequestVpnPermission = {
                            requestStartAction(
                                action = PendingStartAction.RequestVpnPermission,
                                onNeedsPermissionDialog = { notificationPermissionDialogAction = it },
                            )
                        },
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (requestsHomeTab(intent)) {
            openHomeRequests.value = true
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

    private fun saveDiagnosticsExport(filePath: String) {
        val source = java.io.File(filePath)
        pendingDiagnosticsExport =
            PendingDiagnosticsExport(
                filePath = filePath,
                fileName = source.name,
            )
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, source.name)
            }
        diagnosticsExportRegister.launch(intent)
    }

    private fun requestStartAction(
        action: PendingStartAction,
        onNeedsPermissionDialog: (PendingStartAction) -> Unit,
    ) {
        if (needsNotificationPermissionForStart()) {
            onNeedsPermissionDialog(action)
        } else {
            executePendingStartAction(action)
        }
    }

    private fun executePendingStartAction(action: PendingStartAction) {
        when (action) {
            PendingStartAction.StartConfiguredMode -> viewModel.toggleService(this)
            PendingStartAction.OpenVpnPermission -> openVpnPermissionRequests.value = true
            PendingStartAction.RequestVpnPermission -> viewModel.requestVpnPermission(this)
        }
    }

    private fun needsNotificationPermissionForStart(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
}
