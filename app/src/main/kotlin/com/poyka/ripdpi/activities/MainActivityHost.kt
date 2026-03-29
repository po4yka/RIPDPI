package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.io.IOException
import java.util.Optional
import javax.inject.Inject

private const val PostNotificationsPermission = "android.permission.POST_NOTIFICATIONS"

internal sealed interface MainActivityHostCommand {
    data object RequestNotificationsPermission : MainActivityHostCommand

    data class RequestVpnConsent(
        val intent: Intent,
    ) : MainActivityHostCommand

    data class RequestBatteryOptimization(
        val intent: Intent,
    ) : MainActivityHostCommand

    data class OpenIntent(
        val intent: Intent,
    ) : MainActivityHostCommand

    data object SaveLogs : MainActivityHostCommand

    data object ShareDebugBundle : MainActivityHostCommand

    data class SaveDiagnosticsArchive(
        val filePath: String,
        val fileName: String,
    ) : MainActivityHostCommand

    data class ShareDiagnosticsArchive(
        val filePath: String,
        val fileName: String,
    ) : MainActivityHostCommand

    data class ShareDiagnosticsSummary(
        val title: String,
        val body: String,
    ) : MainActivityHostCommand
}

internal interface MainActivityHost {
    fun register(
        activity: ComponentActivity,
        viewModel: MainViewModel,
    )

    fun handle(command: MainActivityHostCommand)
}

@ActivityScoped
internal class DefaultMainActivityHost
    @Inject
    constructor(
        private val diagnosticsShareService: DiagnosticsShareService,
        private val logcatSnapshotCollector: LogcatSnapshotCollector,
        private val automationController: Optional<AutomationController>,
    ) : MainActivityHost {
        private lateinit var activity: ComponentActivity
        private lateinit var viewModel: MainViewModel
        private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
        private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
        private lateinit var batteryOptimizationLauncher: ActivityResultLauncher<Intent>
        private lateinit var logsLauncher: ActivityResultLauncher<Intent>
        private lateinit var diagnosticsArchiveLauncher: ActivityResultLauncher<Intent>
        private var pendingDiagnosticsArchive: PendingDiagnosticsArchive? = null
        private var registered = false

        override fun register(
            activity: ComponentActivity,
            viewModel: MainViewModel,
        ) {
            if (registered) {
                return
            }

            this.activity = activity
            this.viewModel = viewModel
            vpnPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    viewModel.onPermissionResult(
                        kind = com.poyka.ripdpi.permissions.PermissionKind.VpnConsent,
                        result = MainActivity.mapVpnPermissionResult(activity),
                    )
                }
            notificationPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val shouldShowRationale =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            activity.shouldShowRequestPermissionRationale(PostNotificationsPermission)
                        } else {
                            true
                        }
                    viewModel.onPermissionResult(
                        kind = com.poyka.ripdpi.permissions.PermissionKind.Notifications,
                        result = MainActivity.mapNotificationPermissionResult(granted, shouldShowRationale),
                    )
                }
            batteryOptimizationLauncher =
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    viewModel.onPermissionResult(
                        kind = com.poyka.ripdpi.permissions.PermissionKind.BatteryOptimization,
                        result = com.poyka.ripdpi.permissions.PermissionResult.ReturnedFromSettings,
                    )
                }
            logsLauncher =
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    handleLogsResult(result.data?.data)
                }
            diagnosticsArchiveLauncher =
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    handleDiagnosticsArchiveResult(result.data?.data)
                }
            registered = true
        }

        override fun handle(command: MainActivityHostCommand) {
            check(registered) { "MainActivityHost must be registered before use." }
            if (
                automationController
                    .map { controller ->
                        controller.interceptHostCommand(command, viewModel)
                    }.orElse(false)
            ) {
                return
            }
            when (command) {
                MainActivityHostCommand.RequestNotificationsPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(PostNotificationsPermission)
                    }
                }

                is MainActivityHostCommand.RequestVpnConsent -> {
                    vpnPermissionLauncher.launch(command.intent)
                }

                is MainActivityHostCommand.RequestBatteryOptimization -> {
                    batteryOptimizationLauncher.launch(command.intent)
                }

                is MainActivityHostCommand.OpenIntent -> {
                    activity.startActivity(command.intent)
                }

                MainActivityHostCommand.SaveLogs -> {
                    logsLauncher.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "ripdpi.log")
                        },
                    )
                }

                MainActivityHostCommand.ShareDebugBundle -> {
                    shareDebugBundle()
                }

                is MainActivityHostCommand.SaveDiagnosticsArchive -> {
                    pendingDiagnosticsArchive =
                        PendingDiagnosticsArchive(
                            filePath = command.filePath,
                            fileName = command.fileName,
                        )
                    diagnosticsArchiveLauncher.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, command.fileName)
                        },
                    )
                }

                is MainActivityHostCommand.ShareDiagnosticsArchive -> {
                    shareDiagnosticsArchive(command.filePath, command.fileName)
                }

                is MainActivityHostCommand.ShareDiagnosticsSummary -> {
                    val shareIntent =
                        DiagnosticsShareIntents.createSummaryShareIntent(
                            title = command.title,
                            body = command.body,
                        )
                    activity.startActivity(Intent.createChooser(shareIntent, command.title))
                }
            }
        }

        private fun handleLogsResult(uri: Uri?) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val logcatSnapshot = runCatching { logcatSnapshotCollector.capture() }.getOrNull()
                if (logcatSnapshot == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val destination =
                    uri ?: run {
                        logcat(LogPriority.ERROR) { "No data in result" }
                        return@launch
                    }
                activity.contentResolver.openOutputStream(destination)?.use { stream ->
                    try {
                        stream.write(logcatSnapshot.content.toByteArray())
                    } catch (error: IOException) {
                        logcat(LogPriority.ERROR) { "Failed to save logs\n${error.asLog()}" }
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to open output stream" }
                }
            }
        }

        private fun handleDiagnosticsArchiveResult(uri: Uri?) {
            val archive = pendingDiagnosticsArchive ?: return
            pendingDiagnosticsArchive = null
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val destination = uri ?: return@launch
                val source = File(archive.filePath)
                activity.contentResolver.openOutputStream(destination)?.use { stream ->
                    source.inputStream().use { input -> input.copyTo(stream) }
                }
            }
        }

        private fun shareDebugBundle() {
            activity.lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        diagnosticsShareService.createArchive(null)
                    }
                }.onSuccess { archive ->
                    shareDiagnosticsArchive(archive.absolutePath, archive.fileName)
                }.onFailure { error ->
                    logcat(LogPriority.ERROR) { "Failed to prepare support bundle\n${error.asLog()}" }
                    Toast.makeText(activity, R.string.debug_bundle_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun shareDiagnosticsArchive(
            filePath: String,
            fileName: String,
        ) {
            val archiveUri =
                FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
                    File(filePath),
                )
            val shareIntent =
                DiagnosticsShareIntents.createArchiveShareIntent(
                    archiveUri = archiveUri,
                    fileName = fileName,
                )
            activity.startActivity(
                Intent.createChooser(
                    shareIntent,
                    activity.getString(R.string.diagnostics_share_archive_chooser),
                ),
            )
        }

        private data class PendingDiagnosticsArchive(
            val filePath: String,
            val fileName: String,
        )
    }

@Module
@InstallIn(ActivityComponent::class)
internal abstract class MainActivityHostModule {
    @Binds
    @ActivityScoped
    abstract fun bindMainActivityHost(host: DefaultMainActivityHost): MainActivityHost
}
