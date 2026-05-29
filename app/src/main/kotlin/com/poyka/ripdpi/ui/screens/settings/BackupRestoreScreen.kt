package com.poyka.ripdpi.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakewharton.processphoenix.ProcessPhoenix
import com.poyka.ripdpi.R
import com.poyka.ripdpi.backup.BackupExportEffect
import com.poyka.ripdpi.backup.BackupImportPreview
import com.poyka.ripdpi.backup.BackupRestoreEffect
import com.poyka.ripdpi.backup.BackupRestoreUiState
import com.poyka.ripdpi.backup.BackupRestoreViewModel
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.security.BiometricAuthResult
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BackupMimeType = "application/json"

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
    val restoreEffect by viewModel.restoreEffects.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Export wiring (launcher + effect + variant sheet) lives in its own composable
    // so this route stays small; it returns the click handler for the export button.
    val onExportClick = rememberBackupExportController(viewModel, snackbarHostState)

    val openDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            viewModel.openImport { context.contentResolver.openInputStream(uri) }
        }

    BackupRestoreEffectHandler(
        effect = restoreEffect,
        snackbarHostState = snackbarHostState,
        onConsume = viewModel::consumeRestoreEffect,
        onRestart = {
            // Restart the process so every DataStore / Room observer reinitializes
            // against the freshly-restored state — the reference-implementation pattern.
            ProcessPhoenix.triggerRebirth(context.applicationContext)
        },
    )

    BackupRestoreScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onExportClick = onExportClick,
        // SAF picker restricted to JSON documents.
        onImportClick = { openDocumentLauncher.launch(arrayOf(BackupMimeType)) },
        modifier = modifier,
    )

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
    val effect by viewModel.effects.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val biometricAuthManager = remember { BiometricAuthManager() }

    var showVariantPicker by rememberSaveable { mutableStateOf(false) }
    // The variant chosen in the picker, carried across the SAF document-create round-trip.
    var pendingVariant by rememberSaveable { mutableStateOf<BackupVariant?>(null) }
    // The Uri written by the last successful export, used for the SHARE follow-up.
    var lastWrittenUri by remember { mutableStateOf<Uri?>(null) }

    val biometricTitle = stringResource(R.string.backup_export_biometric_title)
    val biometricSubtitle = stringResource(R.string.backup_export_biometric_subtitle)
    val biometricCancel = stringResource(R.string.backup_export_biometric_cancel)

    val createDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupMimeType)) { uri ->
            val variant = pendingVariant
            if (uri == null || variant == null) {
                viewModel.consumeEffect()
                pendingVariant = null
                return@rememberLauncherForActivityResult
            }
            lastWrittenUri = uri
            viewModel.export(
                variant = variant,
                openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                // Delete the partial document so a half-written backup is never left behind.
                onWriteFailed = { deletePartialDocument(context, uri) },
            )
            pendingVariant = null
        }

    val launchPicker: (BackupVariant) -> Unit = { variant ->
        pendingVariant = variant
        createDocumentLauncher.launch(defaultBackupFilename())
    }

    val onVariantChosen: (BackupVariant) -> Unit = { variant ->
        showVariantPicker = false
        if (variant == BackupVariant.FULL) {
            coroutineScope.launch {
                val result =
                    biometricAuthManager.authenticate(
                        context as FragmentActivity,
                        biometricTitle,
                        biometricSubtitle,
                        biometricCancel,
                    )
                if (result is BiometricAuthResult.Success) launchPicker(BackupVariant.FULL)
            }
        } else {
            launchPicker(variant)
        }
    }

    BackupExportEffectHandler(
        effect = effect,
        snackbarHostState = snackbarHostState,
        onConsume = viewModel::consumeEffect,
        onShare = { lastWrittenUri?.let { uri -> shareBackup(context, uri) } },
    )

    if (showVariantPicker) {
        BackupVariantPickerSheet(
            onDismiss = { showVariantPicker = false },
            onVariantChosen = onVariantChosen,
        )
    }

    return { showVariantPicker = true }
}

@Composable
private fun BackupRestoreEffectHandler(
    effect: BackupRestoreEffect?,
    snackbarHostState: SnackbarHostState,
    onConsume: () -> Unit,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(effect) {
        when (val current = effect) {
            BackupRestoreEffect.Restored -> {
                // Surface the message, then restart. The snackbar is best-effort —
                // the rebirth may pre-empt it, which is acceptable.
                snackbarHostState.showRipDpiSnackbar(
                    message = context.getString(R.string.backup_restore_success),
                    tone = RipDpiSnackbarTone.Info,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onConsume()
                onRestart()
            }

            is BackupRestoreEffect.UnsupportedVersion -> {
                snackbarHostState.showRipDpiSnackbar(
                    message =
                        context.getString(
                            R.string.backup_restore_unsupported_version,
                            current.found,
                            current.supported,
                        ),
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onConsume()
            }

            BackupRestoreEffect.Malformed -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = context.getString(R.string.backup_restore_malformed),
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onConsume()
            }

            BackupRestoreEffect.NothingSelected -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = context.getString(R.string.backup_restore_nothing_selected),
                    tone = RipDpiSnackbarTone.Warning,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onConsume()
            }

            null -> {
                Unit
            }
        }
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

@Composable
private fun BackupExportEffectHandler(
    effect: BackupExportEffect?,
    snackbarHostState: SnackbarHostState,
    onConsume: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(effect) {
        when (val current = effect) {
            is BackupExportEffect.Success -> {
                val message =
                    context.getString(R.string.backup_export_success, formatBytes(current.byteCount))
                if (current.offerShare) {
                    val result =
                        snackbarHostState.showRipDpiSnackbar(
                            message = message,
                            tone = RipDpiSnackbarTone.Info,
                            actionLabel = context.getString(R.string.backup_export_share_action),
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
                onConsume()
            }

            BackupExportEffect.WriteFailed -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = context.getString(R.string.backup_export_failed),
                    tone = RipDpiSnackbarTone.Error,
                    testTag = RipDpiTestTags.BackupExportSnackbar,
                )
                onConsume()
            }

            BackupExportEffect.Cancelled -> {
                onConsume()
            }

            null -> {
                Unit
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

@Composable
internal fun BackupRestoreScreen(
    state: BackupRestoreUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
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
                        }
                    }
                }
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
private fun BackupVariantPickerSheet(
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
            onImportClick = {},
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
            onImportClick = {},
        )
    }
}
