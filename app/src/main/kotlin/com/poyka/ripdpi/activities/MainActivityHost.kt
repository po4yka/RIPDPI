package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsLogRedactor
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Optional
import javax.inject.Inject

private const val PostNotificationsPermission = "android.permission.POST_NOTIFICATIONS"

internal sealed interface MainActivityHostCommand {
    data object RequestLocalNetworkPermission : MainActivityHostCommand

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

    data class SaveDiagnosticsArchiveRequest(
        val request: DiagnosticsArchiveRequest,
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
        activity: AppCompatActivity,
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
        private val diagnosticsLogRedactor: DiagnosticsLogRedactor,
        private val automationController: Optional<AutomationController>,
    ) : MainActivityHost {
        private lateinit var activity: AppCompatActivity
        private lateinit var viewModel: MainViewModel
        private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
        private lateinit var localNetworkPermissionLauncher: ActivityResultLauncher<String>
        private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
        private lateinit var batteryOptimizationLauncher: ActivityResultLauncher<Intent>
        private lateinit var logsLauncher: ActivityResultLauncher<Intent>
        private lateinit var diagnosticsArchiveLauncher: ActivityResultLauncher<Intent>
        private var pendingDiagnosticsArchive: PendingDiagnosticsArchive? = null
        private var pendingDiagnosticsArchiveRequest: DiagnosticsArchiveRequest? = null
        private var registered = false

        override fun register(
            activity: AppCompatActivity,
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
                        result = mapVpnPermissionResult(activity),
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
                        result = mapNotificationPermissionResult(granted, shouldShowRationale),
                    )
                }
            localNetworkPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    viewModel.onPermissionResult(
                        com.poyka.ripdpi.permissions.PermissionKind.LocalNetwork,
                        mapNotificationPermissionResult(
                            granted,
                            activity.shouldShowRequestPermissionRationale(com.poyka.ripdpi.data.LocalNetworkPermission),
                        ),
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
            if (automationIntercepts(command)) {
                return
            }
            when (command) {
                MainActivityHostCommand.RequestLocalNetworkPermission -> {
                    if (Build.VERSION.SDK_INT >= com.poyka.ripdpi.data.LocalNetworkPermissionApi) {
                        localNetworkPermissionLauncher.launch(com.poyka.ripdpi.data.LocalNetworkPermission)
                    }
                }

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
                    saveLogs()
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
                    launchSaveDocument(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, command.fileName)
                        },
                        launch = diagnosticsArchiveLauncher::launch,
                        failureMessage = activity.getString(R.string.diagnostics_archive_save_failed),
                    )
                }

                is MainActivityHostCommand.SaveDiagnosticsArchiveRequest -> {
                    pendingDiagnosticsArchiveRequest = command.request
                    launchSaveDocument(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "ripdpi-analysis.zip")
                        },
                        launch = diagnosticsArchiveLauncher::launch,
                        failureMessage = activity.getString(R.string.diagnostics_archive_save_failed),
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
                    reportExportLaunchFailure(
                        code =
                            launchDiagnosticsExport(Intent.createChooser(shareIntent, command.title)) {
                                activity.startActivity(it)
                            },
                        message = activity.getString(R.string.home_diagnostics_share_failed),
                    )
                }
            }
        }

        private fun automationIntercepts(command: MainActivityHostCommand): Boolean =
            automationController
                .map { controller -> controller.interceptHostCommand(command, viewModel) }
                .orElse(false)

        private fun saveLogs() {
            launchSaveDocument(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "ripdpi.log")
                },
                launch = logsLauncher::launch,
                failureMessage = activity.getString(R.string.logs_failed),
            )
        }

        private fun handleLogsResult(uri: Uri?) {
            // A null URI is the picker cancellation signal. Do not collect logs before
            // checking it: logcat collection can be expensive and must not happen for a cancelled save.
            if (uri != null) {
                activity.launchIoOperation(
                    operation = {
                        val logcatSnapshot =
                            logcatSnapshotCollector.capture()
                                ?: throw IOException("Failed to capture logs")
                        activity.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(diagnosticsLogRedactor.redactLogcat(logcatSnapshot.content).toByteArray())
                        } ?: throw IOException("Failed to open log destination")
                    },
                    onFailure = { error ->
                        Logger.e(error) { "Failed to save logs" }
                        viewModel.reportSupportError(
                            activity.getString(R.string.logs_failed),
                            DiagnosticsExportSupportCodes.ArchiveIo,
                            null,
                        )
                    },
                )
            }
        }

        private fun handleDiagnosticsArchiveResult(uri: Uri?) {
            val request = pendingDiagnosticsArchiveRequest
            val archive = pendingDiagnosticsArchive.takeIf { request == null }
            if (request == null) {
                pendingDiagnosticsArchive = null
            } else {
                pendingDiagnosticsArchiveRequest = null
            }
            val onFailure: (Throwable) -> Unit = { error ->
                Logger.e(error) { "Failed to save diagnostics archive" }
                val feedback = diagnosticsArchiveSaveFeedback(error)
                viewModel.reportSupportError(
                    activity.getString(feedback.messageRes),
                    feedback.supportCode,
                    feedback.supportPayload,
                )
            }

            if (uri != null) {
                when {
                    request != null -> {
                        activity.launchIoOperation(
                            operation = {
                                writeDiagnosticsArchiveDocument(
                                    destination = uri,
                                    openDestinationStream = { activity.contentResolver.openOutputStream(uri) },
                                    writeArchive = { stream -> diagnosticsShareService.writeArchive(request, stream) },
                                    deleteDocument = { document -> deletePartialDiagnosticsArchiveDocument(document) },
                                )
                            },
                            onFailure = onFailure,
                        )
                    }

                    archive != null -> {
                        activity.launchIoOperation(
                            operation = {
                                writeDiagnosticsArchiveDocument(
                                    destination = uri,
                                    openDestinationStream = { activity.contentResolver.openOutputStream(uri) },
                                    writeArchive = { stream ->
                                        copyDiagnosticsArchive(source = File(archive.filePath), destination = stream)
                                    },
                                    deleteDocument = { document -> deletePartialDiagnosticsArchiveDocument(document) },
                                )
                            },
                            onFailure = onFailure,
                        )
                    }
                }
            }
        }

        private fun shareDebugBundle() {
            activity.lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        diagnosticsShareService.createArchive(
                            DiagnosticsArchiveRequest(
                                requestedSessionId = null,
                                reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE,
                                requestedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }.onSuccess { archive ->
                    shareDiagnosticsArchive(archive.absolutePath, archive.fileName)
                }.onFailure { error ->
                    Logger.e(error) { "Failed to prepare support bundle" }
                    viewModel.reportSupportError(
                        activity.getString(R.string.debug_bundle_failed),
                        DiagnosticsExportSupportCodes.ArchiveIo,
                        null,
                    )
                }
            }
        }

        private fun shareDiagnosticsArchive(
            filePath: String,
            fileName: String,
        ) {
            runCatching {
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
                reportExportLaunchFailure(
                    code =
                        launchDiagnosticsExport(
                            Intent.createChooser(
                                shareIntent,
                                activity.getString(R.string.diagnostics_share_archive_chooser),
                            ),
                        ) { activity.startActivity(it) },
                    message = activity.getString(R.string.home_diagnostics_share_failed),
                )
            }.onFailure { error ->
                Logger.e(error) { "Failed to share diagnostics archive" }
                viewModel.reportSupportError(
                    activity.getString(R.string.home_diagnostics_share_failed),
                    DiagnosticsExportSupportCodes.ArchiveIo,
                    null,
                )
            }
        }

        private fun launchSaveDocument(
            intent: Intent,
            launch: (Intent) -> Unit,
            failureMessage: String,
        ) {
            val failureCode = launchDiagnosticsExport(intent, launch)
            if (failureCode != null) {
                pendingDiagnosticsArchive = null
                pendingDiagnosticsArchiveRequest = null
                reportExportLaunchFailure(failureCode, failureMessage)
            }
        }

        private fun reportExportLaunchFailure(
            code: String?,
            message: String,
        ) {
            code?.let { viewModel.reportSupportError(message, it, null) }
        }

        private data class PendingDiagnosticsArchive(
            val filePath: String,
            val fileName: String,
        )

        private fun deletePartialDiagnosticsArchiveDocument(uri: Uri): Boolean =
            runCatching {
                android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri)
            }.getOrDefault(false)
    }

private fun AppCompatActivity.launchIoOperation(
    operation: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    lifecycleScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { operation() }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            onFailure(error)
        }
    }
}

internal fun copyDiagnosticsArchive(
    source: File,
    destination: OutputStream,
) {
    source.inputStream().use { input ->
        input.copyTo(destination)
    }
}

/**
 * Writes an archive to the SAF document created by the picker.
 *
 * A document exists before its output stream is opened, so a failed or cancelled write must make a
 * best-effort deletion. Providers may reject deletion; that limitation must not replace the real
 * write failure or turn a cancelled export into a reported error.
 */
internal suspend fun writeDiagnosticsArchiveDocument(
    destination: Uri,
    openDestinationStream: () -> OutputStream?,
    writeArchive: suspend (OutputStream) -> Unit,
    deleteDocument: (Uri) -> Boolean,
) {
    val failure =
        runCatching {
            val stream =
                runCatching {
                    openDestinationStream()
                        ?: throw IOException("Failed to open diagnostics archive destination")
                }.forDocumentStage(DiagnosticsDocumentExportStage.DESTINATION_OPEN)
                    .getOrThrow()
            runCatching {
                stream.use { output -> writeArchive(output) }
            }.forDocumentStage(DiagnosticsDocumentExportStage.ARCHIVE_WRITE)
                .getOrThrow()
        }.exceptionOrNull() ?: return

    val partialDocumentDeleted = runCatching { deleteDocument(destination) }.getOrDefault(false)
    throw when (failure) {
        is DiagnosticsDocumentStageException -> {
            DiagnosticsDocumentExportException(
                stage = failure.stage,
                partialDocumentDeleted = partialDocumentDeleted,
                cause = failure.cause ?: failure,
            )
        }

        else -> {
            failure
        }
    }
}

private fun <T> Result<T>.forDocumentStage(stage: DiagnosticsDocumentExportStage): Result<T> =
    fold(
        onSuccess = { value -> Result.success(value) },
        onFailure = { error -> Result.failure(error.forDocumentStage(stage)) },
    )

private fun Throwable.forDocumentStage(stage: DiagnosticsDocumentExportStage): Throwable =
    when (this) {
        is CancellationException, is Error -> this
        else -> DiagnosticsDocumentStageException(stage, this)
    }

private class DiagnosticsDocumentStageException(
    val stage: DiagnosticsDocumentExportStage,
    cause: Throwable,
) : IOException("Diagnostics document stage failed at ${stage.supportValue}", cause)

@Module
@InstallIn(ActivityComponent::class)
internal abstract class MainActivityHostModule {
    @Binds
    @ActivityScoped
    abstract fun bindMainActivityHost(host: DefaultMainActivityHost): MainActivityHost
}
