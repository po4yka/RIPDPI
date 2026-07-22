package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jakewharton.processphoenix.ProcessPhoenix
import com.poyka.ripdpi.R
import com.poyka.ripdpi.backup.BackupExportEffect
import com.poyka.ripdpi.backup.BackupImportPreview
import com.poyka.ripdpi.backup.BackupRestoreEffect
import com.poyka.ripdpi.backup.BackupRestoreUiState
import com.poyka.ripdpi.backup.BackupRestoreViewModel
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BackupMimeType = "application/json"
private const val EncryptedBackupMimeType = "application/vnd.poyka.ripdpi.backup"

/** Builds the default export filename, e.g. `ripdpi-backup-2026-05-29T14-30.json`. */
internal fun defaultBackupFilename(now: Date = Date()): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm", Locale.US).format(now)
    return "ripdpi-backup-$stamp.json"
}

@Composable
fun BackupRestoreRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var importPassphrase by remember { mutableStateOf("") }

    // Export wiring (launcher + effect + variant sheet) lives in its own composable
    // so this route stays small; it returns the click handler for the export button.
    val onExportClick = rememberBackupExportController(viewModel, snackbarHostState)

    // Share wiring (one-time reminder + fresh cache-dir SHARE backup + ShareCompat
    // intent + post-share cleanup) likewise lives in its own composable.
    val onShareRedactedClick = rememberBackupShareController(viewModel, snackbarHostState)

    val openDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            viewModel.openImport { context.contentResolver.openInputStream(uri) }
        }

    BackupRestoreEffectHandler(
        flow = viewModel.restoreEffects,
        snackbarHostState = snackbarHostState,
        onRestart = {
            // Restart the process so every DataStore / Room observer reinitializes
            // against the freshly-restored state — the reference-implementation pattern.
            ProcessPhoenix.triggerRebirth(context.applicationContext)
        },
    )

    BackupResetEffectHandler(
        flow = viewModel.resetEffects,
        onRestart = {
            // Restart so the freshly-wiped stores reinitialize and the app lands on
            // onboarding (no profiles / groups / settings to resume into).
            ProcessPhoenix.triggerRebirth(context.applicationContext)
        },
    )

    BackupRestoreScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onExportClick = onExportClick,
        onShareRedactedClick = onShareRedactedClick,
        onImportClick = {
            openDocumentLauncher.launch(arrayOf(BackupMimeType, EncryptedBackupMimeType, "application/octet-stream"))
        },
        onResetClick = { viewModel.setResetDialogVisible(true) },
        modifier = modifier,
    )

    if (uiState.resetDialogVisible) {
        BackupResetConfirmationDialog(
            input = uiState.resetConfirmationInput,
            confirmEnabled = uiState.resetConfirmationMatches && !uiState.resetting,
            resetting = uiState.resetting,
            onInputChange = viewModel::onResetConfirmationInputChange,
            onConfirm = viewModel::confirmReset,
            onDismiss = { viewModel.setResetDialogVisible(false) },
        )
    }

    if (uiState.encryptedImportPending) {
        BackupPassphraseDialog(
            title = stringResource(R.string.backup_encryption_unlock_title),
            message = stringResource(R.string.backup_encryption_unlock_body),
            passphrase = importPassphrase,
            onPassphraseChange = { importPassphrase = it },
            confirmLabel = stringResource(R.string.backup_encryption_unlock_action),
            onConfirm = {
                viewModel.unlockEncryptedImport(importPassphrase.toCharArray())
                importPassphrase = ""
            },
            onDismiss = {
                importPassphrase = ""
                viewModel.cancelEncryptedImport()
            },
        )
    }

    uiState.importPreview?.let { preview ->
        BackupImportPreviewSheet(
            preview = preview,
            restoring = uiState.restoring,
            onDismiss = viewModel::cancelImport,
            onProfilesAndGroupsToggle = viewModel::setProfilesAndGroupsSelected,
            onRoutesToggle = viewModel::setRoutesSelected,
            onSettingsToggle = viewModel::setSettingsSelected,
            onConfirm = viewModel::confirmRestore,
        )
    }
}

/**
 * Wires the backup-export flow (SAF document creation, biometric gate for FULL,
 * success/failure snackbar, and the variant-picker sheet) and returns the click
 * handler the screen attaches to its export button. Kept separate so
 * [BackupRestoreRoute] stays focused on the restore/import path.
 */
@Composable
private fun rememberBackupExportController(
    viewModel: BackupRestoreViewModel,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val biometricAuthManager = remember { BiometricAuthManager() }

    var showVariantPicker by rememberSaveable { mutableStateOf(false) }
    var showPassphraseDialog by rememberSaveable { mutableStateOf(false) }
    // The Uri written by the last successful export, used for the SHARE follow-up.
    var lastWrittenUri by remember { mutableStateOf<Uri?>(null) }

    val onDocumentCreated: (Uri?) -> Unit = { uri ->
        val pending = viewModel.pendingExport.consume()
        if (uri == null) {
            pending?.close()
        } else if (pending == null) {
            // The picker result outlived its in-memory request (for example process death).
            // Fail closed and remove the empty SAF document instead of exporting without a secret.
            deletePartialDocument(context, uri)
        } else {
            lastWrittenUri = uri
            pending.use { request ->
                viewModel.export(
                    variant = request.variant,
                    openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                    // Delete the partial document so a half-written backup is never left behind.
                    onWriteFailed = { deletePartialDocument(context, uri) },
                    passphrase = request.passphrase,
                )
            }
        }
    }

    val createShareDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupMimeType), onDocumentCreated)
    val createEncryptedDocumentLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(EncryptedBackupMimeType),
            onDocumentCreated,
        )

    val launchPicker: (BackupVariant) -> Unit = { variant ->
        when (variant) {
            BackupVariant.SHARE -> {
                viewModel.pendingExport.stage(BackupVariant.SHARE)
                createShareDocumentLauncher.launch(defaultBackupFilename())
            }

            BackupVariant.FULL -> {
                createEncryptedDocumentLauncher.launch(defaultEncryptedBackupFilename())
            }
        }
    }

    val onVariantChosen =
        rememberBackupVariantChosen(
            context = context,
            coroutineScope = coroutineScope,
            biometricAuthManager = biometricAuthManager,
            snackbarHostState = snackbarHostState,
            launchPicker = launchPicker,
            onFullAuthenticated = { showPassphraseDialog = true },
            onPickerDismissed = { showVariantPicker = false },
        )

    BackupExportEffectHandler(
        flow = viewModel.effects,
        snackbarHostState = snackbarHostState,
        context = context,
        onShare = { lastWrittenUri?.let { uri -> shareBackup(context, uri) } },
    )

    BackupExportOverlays(
        showVariantPicker = showVariantPicker,
        showPassphraseDialog = showPassphraseDialog,
        onVariantPickerDismiss = { showVariantPicker = false },
        onVariantChosen = onVariantChosen,
        onPassphraseDismiss = {
            showPassphraseDialog = false
            viewModel.pendingExport.clear()
        },
        onPassphraseConfirmed = { confirmedPassphrase ->
            viewModel.pendingExport.stage(BackupVariant.FULL, confirmedPassphrase.toCharArray())
            showPassphraseDialog = false
            launchPicker(BackupVariant.FULL)
        },
    )

    return { showVariantPicker = true }
}

/**
 * Wires the "share redacted backup" flow and returns the click handler the screen
 * attaches to its share button.
 *
 * On click it shows a one-time reminder (SHARE is redacted but NOT zero-knowledge);
 * the first acknowledgement is persisted so subsequent shares skip straight to
 * generation. Generation writes a FRESH [BackupVariant.SHARE] backup into a private
 * cache subdirectory (`cacheDir/backup-share/`), then hands it to the share sheet via
 * the `${applicationId}.backup.fileprovider` FileProvider using [ShareCompat]. The
 * temp file is deleted when the share activity returns (completed or cancelled) and
 * on any generation failure, so a redacted backup is never left in the cache.
 */
@Composable
private fun rememberBackupShareController(
    viewModel: BackupRestoreViewModel,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    var showReminder by rememberSaveable { mutableStateOf(false) }

    val shareLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Regardless of result (shared, cancelled, or dismissed) the redacted temp
            // file has served its purpose — delete it.
            viewModel.shareTempFiles.clear()
        }

    val generateAndShare: () -> Unit = {
        val shareFile = newShareTempFile(context)
        viewModel.shareTempFiles.replace(shareFile)
        viewModel.prepareShareBackup {
            runCatching { java.io.FileOutputStream(shareFile) }.getOrNull()
        }
    }

    val subject = stringResource(R.string.backup_share_redacted_subject)
    val chooserTitle = stringResource(R.string.backup_share_redacted_action)

    BackupShareEffectHandler(
        flow = viewModel.shareEffects,
        snackbarHostState = snackbarHostState,
        onReady = {
            val file = viewModel.shareTempFiles.current()
            if (file == null) {
                return@BackupShareEffectHandler
            }
            val launched =
                launchShareIntent(
                    context = context,
                    file = file,
                    subject = subject,
                    chooserTitle = chooserTitle,
                    launcher = shareLauncher,
                )
            if (!launched) {
                // No share target / launch failure: clean up the temp file now.
                viewModel.shareTempFiles.clear()
            }
        },
        onFailed = {
            viewModel.shareTempFiles.clear()
        },
    )

    if (showReminder) {
        BackupShareReminderDialog(
            onDismiss = { showReminder = false },
            onAcknowledge = {
                showReminder = false
                viewModel.markShareReminderShown()
                generateAndShare()
            },
        )
    }

    return {
        if (viewModel.shouldShowShareReminder()) {
            showReminder = true
        } else {
            generateAndShare()
        }
    }
}

@Composable
internal fun <T> BackupEffectCollector(
    flow: Flow<T>,
    onEffect: suspend (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEffect by rememberUpdatedState(onEffect)
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { effect -> currentOnEffect(effect) }
        }
    }
}

@Composable
private fun BackupRestoreEffectHandler(
    flow: Flow<BackupRestoreEffect>,
    snackbarHostState: SnackbarHostState,
    onRestart: () -> Unit,
) {
    val restoredMessage = stringResource(R.string.backup_restore_success)
    val malformedMessage = stringResource(R.string.backup_restore_malformed)
    val integrityFailureMessage = stringResource(R.string.backup_restore_integrity_failure)
    val decryptionFailedMessage = stringResource(R.string.backup_encryption_unlock_failed)
    val nothingSelectedMessage = stringResource(R.string.backup_restore_nothing_selected)
    val unsupportedVersionTemplate = stringResource(R.string.backup_restore_unsupported_version)
    BackupEffectCollector(flow) { effect ->
        when (effect) {
            BackupRestoreEffect.Restored -> {
                // Surface the message, then restart. The snackbar is best-effort —
                // the rebirth may pre-empt it, which is acceptable.
                snackbarHostState.showRipDpiSnackbar(
                    message = restoredMessage,
                    tone = RipDpiSnackbarTone.Info,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onRestart()
            }

            is BackupRestoreEffect.UnsupportedVersion -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = String.format(unsupportedVersionTemplate, effect.found, effect.supported),
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }

            BackupRestoreEffect.Malformed -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = malformedMessage,
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }

            BackupRestoreEffect.IntegrityFailure -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = integrityFailureMessage,
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }

            BackupRestoreEffect.DecryptionFailed -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = decryptionFailedMessage,
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }

            BackupRestoreEffect.NothingSelected -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = nothingSelectedMessage,
                    tone = RipDpiSnackbarTone.Warning,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }
        }
    }
}

@Composable
private fun BackupResetEffectHandler(
    flow: Flow<com.poyka.ripdpi.backup.BackupResetEffect>,
    onRestart: () -> Unit,
) {
    BackupEffectCollector(flow) { effect ->
        when (effect) {
            com.poyka.ripdpi.backup.BackupResetEffect.Wiped -> {
                onRestart()
            }
        }
    }
}

/**
 * The destructive "type RESET to confirm" dialog. The surrounding copy is localized;
 * the typed token itself ([com.poyka.ripdpi.backup.ResetConfirmationToken]) is fixed
 * and shown verbatim in the prompt. The confirm action is only supplied once the
 * input matches, so it stays disabled (absent) until then.
 */
@Composable
private fun BackupResetConfirmationDialog(
    input: String,
    confirmEnabled: Boolean,
    resetting: Boolean,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.poyka.ripdpi.ui.components.feedback.RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_reset_dialog_title),
        dialogTestTag = RipDpiTestTags.BackupResetDialog,
        visuals =
            com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals(
                message =
                    stringResource(
                        R.string.backup_reset_dialog_body,
                        com.poyka.ripdpi.backup.ResetConfirmationToken,
                    ),
                tone = com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone.Destructive,
            ),
        confirmAction =
            if (confirmEnabled) {
                com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction(
                    label = stringResource(R.string.backup_reset_confirm_action),
                    onClick = onConfirm,
                    testTag = RipDpiTestTags.BackupResetConfirm,
                )
            } else {
                null
            },
        dismissAction =
            com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction(
                label = stringResource(R.string.backup_reset_cancel_action),
                onClick = onDismiss,
            ),
    ) {
        com.poyka.ripdpi.ui.components.inputs.RipDpiTextField(
            value = input,
            onValueChange = onInputChange,
            decoration =
                com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration(
                    placeholder = com.poyka.ripdpi.backup.ResetConfirmationToken,
                    testTag = RipDpiTestTags.BackupResetConfirmationField,
                ),
            behavior =
                com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior(
                    enabled = !resetting,
                    singleLine = true,
                ),
        )
    }
}

/**
 * Removes the partially-written SAF document on a failed export so no half-written
 * backup is left behind. SAF documents created via `CreateDocument` are deletable
 * through `DocumentsContract`.
 */
private fun deletePartialDocument(
    context: android.content.Context,
    uri: Uri,
) {
    runCatching {
        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    }
}

private fun shareBackup(
    context: android.content.Context,
    uri: Uri,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = BackupMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooser = Intent.createChooser(intent, context.getString(R.string.backup_export_share_action))
    runCatching { context.startActivity(chooser) }
}

/** Subdirectory of the app cache backing the redacted-share FileProvider grant. */
private const val ShareCacheDirName = "backup-share"

/** FileProvider authority for the redacted-share grant (mirrors the manifest entry). */
private fun backupFileProviderAuthority(context: android.content.Context): String =
    "${context.packageName}.backup.fileprovider"

/**
 * Creates a fresh, empty temp file under `cacheDir/backup-share/` for a redacted
 * share. Stale files from a previous interrupted share are swept first so the cache
 * never accumulates redacted backups.
 */
private fun newShareTempFile(context: android.content.Context): java.io.File {
    val dir = java.io.File(context.cacheDir, ShareCacheDirName)
    dir.mkdirs()
    runCatching { dir.listFiles()?.forEach { it.delete() } }
    return java.io.File(dir, defaultBackupFilename())
}

/**
 * Launches the share sheet for [file] via [ShareCompat] backed by the backup
 * FileProvider. Uses `StartActivityForResult` so the caller can clean up the temp
 * file on return. Returns `false` when no chooser could be launched (so the caller
 * can delete the temp file immediately).
 */
private fun launchShareIntent(
    context: android.content.Context,
    file: java.io.File,
    subject: String,
    chooserTitle: String,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
): Boolean {
    val uri =
        runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                backupFileProviderAuthority(context),
                file,
            )
        }.getOrNull() ?: return false
    val sendIntent =
        androidx.core.app.ShareCompat
            .IntentBuilder(context)
            .setType(BackupMimeType)
            .setStream(uri)
            .setSubject(subject)
            // EMPTY body: a non-empty text body invites autofill / clipboard managers
            // to surface the message, risking accidental leaks alongside the file.
            .setText("")
            .intent
            .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val chooser = Intent.createChooser(sendIntent, chooserTitle)
    return runCatching { launcher.launch(chooser) }.isSuccess
}

@Composable
private fun BackupShareEffectHandler(
    flow: Flow<com.poyka.ripdpi.backup.BackupShareEffect>,
    snackbarHostState: SnackbarHostState,
    onReady: () -> Unit,
    onFailed: () -> Unit,
) {
    val failedMessage = stringResource(R.string.backup_export_failed)
    BackupEffectCollector(flow) { effect ->
        when (effect) {
            com.poyka.ripdpi.backup.BackupShareEffect.Ready -> {
                onReady()
            }

            com.poyka.ripdpi.backup.BackupShareEffect.Failed -> {
                onFailed()
                snackbarHostState.showRipDpiSnackbar(
                    message = failedMessage,
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }
        }
    }
}

@Composable
private fun BackupShareReminderDialog(
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    com.poyka.ripdpi.ui.components.feedback.RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_share_reminder_title),
        dialogTestTag = RipDpiTestTags.BackupShareReminderDialog,
        visuals =
            com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals(
                message = stringResource(R.string.backup_share_reminder_body),
                tone = com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone.Info,
            ),
        confirmAction =
            com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction(
                label = stringResource(R.string.backup_share_reminder_confirm),
                onClick = onAcknowledge,
            ),
        dismissAction =
            com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction(
                label = stringResource(R.string.backup_share_reminder_cancel),
                onClick = onDismiss,
            ),
    )
}

@Composable
private fun BackupExportEffectHandler(
    flow: Flow<BackupExportEffect>,
    snackbarHostState: SnackbarHostState,
    context: Context,
    onShare: () -> Unit,
) {
    val exportSuccessTemplate = stringResource(R.string.backup_export_success)
    val shareActionLabel = stringResource(R.string.backup_export_share_action)
    val failedMessage = stringResource(R.string.backup_export_failed)
    BackupEffectCollector(flow) { effect ->
        when (effect) {
            is BackupExportEffect.Success -> {
                val message =
                    String.format(exportSuccessTemplate, Formatter.formatShortFileSize(context, effect.byteCount))
                if (effect.offerShare) {
                    val result =
                        snackbarHostState.showRipDpiSnackbar(
                            message = message,
                            tone = RipDpiSnackbarTone.Info,
                            actionLabel = shareActionLabel,
                            testTag = RipDpiTestTags.BackupExportSnackbar,
                        )
                    if (result == SnackbarResult.ActionPerformed) onShare()
                } else {
                    snackbarHostState.showRipDpiSnackbar(
                        message = message,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.BackupExportSnackbar,
                    )
                }
            }

            BackupExportEffect.WriteFailed -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = failedMessage,
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
            }

            BackupExportEffect.Cancelled -> {
                // No feedback for a cancelled picker; the snackbar stays silent.
            }
        }
    }
}

/**
 * Export section card: the SAF "Export backup" action plus the in-app
 * "Share redacted backup" shortcut. Extracted so [BackupRestoreScreen] stays
 * within the composable-length budget.
 */
@Composable
private fun BackupExportCard(
    state: BackupRestoreUiState,
    onExportClick: () -> Unit,
    onShareRedactedClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.backup_export_section))
        RipDpiCard {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                Text(
                    text = stringResource(R.string.backup_export_body),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                RipDpiButton(
                    text = stringResource(R.string.backup_export_action),
                    onClick = onExportClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.BackupExportButton),
                    loading = state.exporting,
                    enabled = !state.exporting && !state.exportDisabledByPolicy,
                    leadingIcon = RipDpiIcons.Share,
                )
                // Redacted share shortcut: generates a fresh SHARE backup
                // into the cache and hands it straight to the share sheet.
                RipDpiButton(
                    text = stringResource(R.string.backup_share_redacted_action),
                    onClick = onShareRedactedClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.BackupShareButton),
                    variant = RipDpiButtonVariant.Outline,
                    loading = state.sharing,
                    enabled = !state.sharing && !state.exportDisabledByPolicy,
                    leadingIcon = RipDpiIcons.Share,
                )
            }
        }
    }
}

/**
 * Danger-zone card hosting the destructive "Reset all settings" action. The button
 * only opens the typed-confirmation dialog; the wipe never runs from a single tap.
 * Extracted so [BackupRestoreScreen] stays within the composable-length budget.
 */
@Composable
private fun BackupResetCard(
    state: BackupRestoreUiState,
    onResetClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.backup_reset_section))
        RipDpiCard {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                WarningBanner(
                    title = stringResource(R.string.backup_reset_warning_title),
                    message = stringResource(R.string.backup_reset_warning_body),
                    tone = WarningBannerTone.Restricted,
                )
                RipDpiButton(
                    text = stringResource(R.string.backup_reset_action),
                    onClick = onResetClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.BackupResetButton),
                    variant = RipDpiButtonVariant.Destructive,
                    loading = state.resetting,
                    enabled = !state.resetting,
                    leadingIcon = RipDpiIcons.Warning,
                )
            }
        }
    }
}

@Composable
internal fun BackupRestoreScreen(
    state: BackupRestoreUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onExportClick: () -> Unit,
    onShareRedactedClick: () -> Unit,
    onImportClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        RipDpiSettingsScaffold(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.screen(Route.BackupRestore))
                    .fillMaxSize(),
            title = stringResource(R.string.title_backup_restore),
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = onBack,
        ) {
            item(key = "backup_intro") {
                WarningBanner(
                    title = stringResource(R.string.backup_export_intro_title),
                    message = stringResource(R.string.backup_export_intro_body),
                    tone = WarningBannerTone.Info,
                )
            }
            if (state.exportDisabledByPolicy) {
                item(key = "backup_policy_blocked") {
                    WarningBanner(
                        title = stringResource(R.string.backup_export_policy_blocked_title),
                        message = stringResource(R.string.backup_export_policy_blocked_body),
                        tone = WarningBannerTone.Restricted,
                    )
                }
            }
            item(key = "backup_export_action") {
                BackupExportCard(
                    state = state,
                    onExportClick = onExportClick,
                    onShareRedactedClick = onShareRedactedClick,
                )
            }
            item(key = "backup_import_action") {
                Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
                    SettingsCategoryHeader(title = stringResource(R.string.backup_restore_section))
                    RipDpiCard {
                        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                            Text(
                                text = stringResource(R.string.backup_restore_body),
                                style = RipDpiThemeTokens.type.body,
                                color = RipDpiThemeTokens.colors.mutedForeground,
                            )
                            RipDpiButton(
                                text = stringResource(R.string.backup_restore_action),
                                onClick = onImportClick,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .ripDpiTestTag(RipDpiTestTags.BackupImportButton),
                                variant = RipDpiButtonVariant.Outline,
                                loading = state.importing,
                                enabled = !state.importing && !state.restoring,
                                leadingIcon = RipDpiIcons.Logs,
                            )
                        }
                    }
                }
            }
            item(key = "backup_reset_action") {
                BackupResetCard(state = state, onResetClick = onResetClick)
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        ) {
            RipDpiSnackbarHost(hostState = snackbarHostState)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BackupImportPreviewSheet(
    preview: BackupImportPreview,
    restoring: Boolean,
    onDismiss: () -> Unit,
    onProfilesAndGroupsToggle: (Boolean) -> Unit,
    onRoutesToggle: (Boolean) -> Unit,
    onSettingsToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = preview.preview
    val selection = preview.selection
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_import_preview_title),
        message =
            stringResource(
                R.string.backup_import_preview_schema,
                summary.schemaVersion,
                summary.appVersion,
            ),
        icon = RipDpiIcons.Logs,
        testTag = RipDpiTestTags.BackupImportPreviewSheet,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            // SHARE backups cannot restore profiles: surface that honestly.
            if (summary.hasUndecodableProfiles) {
                WarningBanner(
                    title = stringResource(R.string.backup_import_shareable_title),
                    message =
                        stringResource(
                            R.string.backup_import_shareable_body,
                            summary.undecodableProfiles.size,
                        ),
                    tone = WarningBannerTone.Restricted,
                )
            }
            if (summary.containsCredentials && !preview.encrypted) {
                WarningBanner(
                    title = stringResource(R.string.backup_variant_full_warning_title),
                    message = stringResource(R.string.backup_import_legacy_full_warning),
                    tone = WarningBannerTone.Restricted,
                )
            }
            // Profiles + groups category.
            RipDpiSwitch(
                checked = selection.profilesAndGroups,
                onCheckedChange = onProfilesAndGroupsToggle,
                label =
                    stringResource(
                        R.string.backup_import_category_profiles,
                        summary.restorableProfileCount,
                        summary.groupCount,
                    ),
                helperText = stringResource(R.string.backup_import_category_unchecked_hint),
                enabled = !restoring && summary.canRestoreProfilesAndGroups,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.BackupImportToggleProfiles),
            )
            // Routes category.
            RipDpiSwitch(
                checked = selection.routes,
                onCheckedChange = onRoutesToggle,
                label = stringResource(R.string.backup_import_category_routes, summary.ruleCount),
                enabled = !restoring && summary.ruleCount > 0,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.BackupImportToggleRoutes),
            )
            // Settings category.
            RipDpiSwitch(
                checked = selection.settings,
                onCheckedChange = onSettingsToggle,
                label = stringResource(R.string.backup_import_category_settings, summary.settingCount),
                enabled = !restoring && summary.settingCount > 0,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.BackupImportToggleSettings),
            )
            RipDpiButton(
                text = stringResource(R.string.backup_import_confirm_action),
                onClick = onConfirm,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.BackupImportConfirm),
                variant = RipDpiButtonVariant.Destructive,
                loading = restoring,
                enabled = !restoring && selection.any,
                leadingIcon = RipDpiIcons.Logs,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BackupVariantPickerSheet(
    onDismiss: () -> Unit,
    onVariantChosen: (BackupVariant) -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_variant_picker_title),
        message = stringResource(R.string.backup_variant_picker_body),
        icon = RipDpiIcons.Share,
        testTag = RipDpiTestTags.BackupVariantSheet,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            // SHARE is the safe default: presented first, primary button.
            RipDpiButton(
                text = stringResource(R.string.backup_variant_share_action),
                onClick = { onVariantChosen(BackupVariant.SHARE) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.BackupVariantShare),
                variant = RipDpiButtonVariant.Primary,
                leadingIcon = RipDpiIcons.Share,
            )
            Text(
                text = stringResource(R.string.backup_variant_share_body),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            // FULL carries credentials: visually flagged as destructive, never the default.
            WarningBanner(
                title = stringResource(R.string.backup_variant_full_warning_title),
                message = stringResource(R.string.backup_variant_full_warning_body),
                tone = WarningBannerTone.Restricted,
            )
            RipDpiButton(
                text = stringResource(R.string.backup_variant_full_action),
                onClick = { onVariantChosen(BackupVariant.FULL) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.BackupVariantFull),
                variant = RipDpiButtonVariant.Destructive,
                leadingIcon = RipDpiIcons.Lock,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun previewBackupRestoreScreen() {
    RipDpiTheme(themePreference = "light") {
        BackupRestoreScreen(
            state = BackupRestoreUiState(),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onExportClick = {},
            onShareRedactedClick = {},
            onImportClick = {},
            onResetClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewBackupRestoreScreenBlocked() {
    RipDpiTheme(themePreference = "dark") {
        BackupRestoreScreen(
            state = BackupRestoreUiState(exportDisabledByPolicy = true),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onExportClick = {},
            onShareRedactedClick = {},
            onImportClick = {},
            onResetClick = {},
        )
    }
}
