package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.diagnostics.export.CheckedDiagnosticsExportHandoff
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.PreparedDiagnosticsExportService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
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

    data class PrepareDiagnosticsExport(
        val preparation: DiagnosticsExportPreparation,
    ) : MainActivityHostCommand

    data class ShareText(
        val title: String,
        val body: String,
    ) : MainActivityHostCommand
}

internal interface MainActivityHost {
    fun register(
        activity: AppCompatActivity,
        viewModel: MainViewModel,
        exportPreview: ExportPreviewViewModel,
    )

    fun handle(command: MainActivityHostCommand)
}

@ActivityScoped
internal class DefaultMainActivityHost
    @Inject
    constructor(
        private val preparedExports: PreparedDiagnosticsExportService,
        private val automationController: Optional<AutomationController>,
    ) : MainActivityHost {
        private lateinit var activity: AppCompatActivity
        private lateinit var viewModel: MainViewModel
        private lateinit var exportPreview: ExportPreviewViewModel
        private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
        private lateinit var localNetworkPermissionLauncher: ActivityResultLauncher<String>
        private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
        private lateinit var batteryOptimizationLauncher: ActivityResultLauncher<Intent>
        private lateinit var diagnosticsArchiveLauncher: ActivityResultLauncher<Intent>
        private val pendingDiagnosticsArchive = PendingDiagnosticsArchiveState()
        private var registered = false

        override fun register(
            activity: AppCompatActivity,
            viewModel: MainViewModel,
            exportPreview: ExportPreviewViewModel,
        ) {
            if (registered) {
                return
            }

            this.activity = activity
            this.viewModel = viewModel
            this.exportPreview = exportPreview
            registerPendingArchiveState(activity)
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
            diagnosticsArchiveLauncher =
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    handleDiagnosticsArchiveResult(result.data?.data)
                }
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        exportPreview.cleanupFailures.filter { it }.collect {
                            viewModel.reportSupportError(
                                activity.getString(R.string.export_preview_cleanup_failed),
                                DiagnosticsExportSupportCodes.ArchiveIo,
                                null,
                            )
                            exportPreview.acknowledgeCleanupFailure()
                        }
                    }
                    exportPreview.handoffs.collectLatest { token ->
                        if (token != null) exportPreview.handoff(token, ::launchPreparedExport)
                    }
                }
            }
            registered = true
        }

        private fun registerPendingArchiveState(activity: AppCompatActivity) {
            pendingDiagnosticsArchive.restore(
                activity.savedStateRegistry.consumeRestoredStateForKey(PendingDiagnosticsArchiveStateKey),
            )
            activity.savedStateRegistry.registerSavedStateProvider(PendingDiagnosticsArchiveStateKey) {
                pendingDiagnosticsArchive.save()
            }
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
                    exportPreview.prepare(DiagnosticsExportPreparation.Logs)
                }

                MainActivityHostCommand.ShareDebugBundle -> {
                    exportPreview.prepare(
                        DiagnosticsExportPreparation.Archive(
                            DiagnosticsArchiveRequest(
                                reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE,
                                requestedAt = System.currentTimeMillis(),
                            ),
                            DiagnosticsExportPurpose.ShareArchive,
                        ),
                    )
                }

                is MainActivityHostCommand.PrepareDiagnosticsExport -> {
                    exportPreview.prepare(command.preparation)
                }

                is MainActivityHostCommand.ShareText -> {
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

        private fun launchPreparedExport(checked: CheckedDiagnosticsExportHandoff) {
            check(!activity.isFinishing && !activity.isDestroyed) { "Export Activity is unavailable" }
            val preview = checked.preview
            when (preview.purpose) {
                DiagnosticsExportPurpose.ShareSummary -> {
                    val intent =
                        DiagnosticsShareIntents.createSummaryShareIntent(
                            activity.getString(R.string.app_name),
                            preview.summary,
                        )
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.app_name)))
                }

                DiagnosticsExportPurpose.ShareArchive -> {
                    val uri =
                        FileProvider.getUriForFile(
                            activity,
                            "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
                            File(checked.absolutePath),
                        )
                    val intent = DiagnosticsShareIntents.createArchiveShareIntent(uri, preview.fileName)
                    activity.startActivity(
                        Intent.createChooser(
                            intent,
                            activity.getString(R.string.diagnostics_share_archive_chooser),
                        ),
                    )
                }

                DiagnosticsExportPurpose.SaveArchive, DiagnosticsExportPurpose.SaveLogs -> {
                    pendingDiagnosticsArchive.begin(preview.leaseId)
                    runCatching {
                        diagnosticsArchiveLauncher.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = preview.mimeType
                                putExtra(Intent.EXTRA_TITLE, preview.fileName)
                            },
                        )
                    }.onFailure {
                        pendingDiagnosticsArchive.onPickerResult(null)
                    }.getOrThrow()
                }
            }
        }

        private fun handleDiagnosticsArchiveResult(uri: Uri?) {
            val result = pendingDiagnosticsArchive.onPickerResult(uri) ?: return
            activity.lifecycleScope.launch {
                var verifiedPurpose: DiagnosticsExportPurpose? = null
                val operation =
                    runCatching {
                        if (result.uri != null) {
                            withContext(Dispatchers.IO) {
                                val checked = preparedExports.validateHandoff(result.leaseId)
                                check(
                                    checked.preview.purpose == DiagnosticsExportPurpose.SaveArchive ||
                                        checked.preview.purpose == DiagnosticsExportPurpose.SaveLogs,
                                )
                                verifiedPurpose = checked.preview.purpose
                                writeDiagnosticsArchiveDocument(
                                    destination = result.uri,
                                    openDestinationStream = { activity.contentResolver.openOutputStream(result.uri) },
                                    writeArchive = { stream -> preparedExports.copyPrepared(result.leaseId, stream) },
                                    deleteDocument = ::deletePartialDiagnosticsArchiveDocument,
                                )
                            }
                        }
                    }
                val cleanup = withContext(NonCancellable) { runCatching { preparedExports.discard(result.leaseId) } }
                val primary = operation.exceptionOrNull()
                cleanup.exceptionOrNull()?.let { error ->
                    primary?.addSuppressed(error)
                    exportPreview.reportCleanupFailure(result.leaseId)
                }
                operation.getOrElse { error ->
                    when (error) {
                        is CancellationException, is Error -> {
                            throw error
                        }

                        else -> {
                            val feedback = diagnosticsArchiveSaveFeedback(error)
                            val message =
                                if (verifiedPurpose == DiagnosticsExportPurpose.SaveArchive) {
                                    feedback.messageRes
                                } else {
                                    R.string.export_preview_save_failed
                                }
                            viewModel.reportSupportError(activity.getString(message), feedback.supportCode, null)
                        }
                    }
                }
                if (primary == null) {
                    cleanup.getOrElse { error ->
                        when (error) {
                            is CancellationException, is Error -> throw error
                            else -> Unit // The neutral cleanup error was already published above.
                        }
                    }
                }
            }
        }

        private fun reportExportLaunchFailure(
            code: String?,
            message: String,
        ) {
            code?.let { viewModel.reportSupportError(message, it, null) }
        }

        private fun deletePartialDiagnosticsArchiveDocument(uri: Uri): Boolean =
            runCatching {
                android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri)
            }.getOrDefault(false)
    }

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
