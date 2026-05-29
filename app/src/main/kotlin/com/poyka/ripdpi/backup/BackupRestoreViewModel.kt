package com.poyka.ripdpi.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.backup.BackupExportResult
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupPreview
import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Named

/**
 * One-shot effect surfaced after an export attempt; the screen renders it as a
 * snackbar and (for SHARE only) an optional follow-up share action, then clears it.
 */
sealed interface BackupExportEffect {
    /**
     * Export succeeded. [byteCount] is the number of JSON bytes written; [offerShare]
     * is `true` only for [BackupVariant.SHARE] (FULL backups are never offered an
     * inline share, to avoid spraying credentials through a share sheet).
     */
    data class Success(
        val variant: BackupVariant,
        val byteCount: Long,
        val offerShare: Boolean,
    ) : BackupExportEffect

    /** The destination write failed. The screen also deletes any partial file. */
    data object WriteFailed : BackupExportEffect

    /** The user cancelled the SAF picker (or document creation returned no Uri). */
    data object Cancelled : BackupExportEffect
}

/**
 * One-shot effect surfaced after a "share redacted backup" attempt. The screen
 * launches the share sheet on [Ready] and cleans up the temp cache file on every
 * terminal outcome.
 */
sealed interface BackupShareEffect {
    /** A fresh SHARE backup was written to the cache file and is ready to share. */
    data object Ready : BackupShareEffect

    /** Writing the temp SHARE backup failed; nothing is shared. */
    data object Failed : BackupShareEffect
}

/**
 * One-shot effect surfaced after an import / restore attempt.
 */
sealed interface BackupRestoreEffect {
    /**
     * The restore committed. The screen restarts the process (ProcessPhoenix) so
     * all DataStore / Room observers reinitialize against the restored state.
     */
    data object Restored : BackupRestoreEffect

    /** The chosen file's schema version is newer than this app supports. */
    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : BackupRestoreEffect

    /** The file was malformed, unreadable, or failed an integrity check. */
    data object Malformed : BackupRestoreEffect

    /** The user picked a file but selected no categories to restore. */
    data object NothingSelected : BackupRestoreEffect
}

/**
 * Live preview of a chosen backup file, shown BEFORE any write. Carries the parsed
 * JSON so the subsequent restore re-uses the exact bytes the user previewed.
 */
data class BackupImportPreview(
    val json: String,
    val preview: BackupPreview,
    val selection: RestoreSelection,
)

/** Immutable render state for the Backup & Restore screen. */
data class BackupRestoreUiState(
    val exporting: Boolean = false,
    val exportDisabledByPolicy: Boolean = false,
    val importing: Boolean = false,
    val restoring: Boolean = false,
    /** True while a fresh SHARE backup is being written to the cache for sharing. */
    val sharing: Boolean = false,
    /** Non-null while the import-preview sheet is visible. */
    val importPreview: BackupImportPreview? = null,
)

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val exportUseCase: BackupExportUseCase,
        private val restoreUseCase: BackupRestoreUseCase,
        private val exportPolicy: BackupExportPolicy,
        private val shareReminderPreferences: BackupShareReminderPreferences,
        @param:Named("appVersionName") private val appVersion: String,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                BackupRestoreUiState(exportDisabledByPolicy = exportPolicy.isExportDisabledByPolicy()),
            )
        val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

        private val _effects = MutableStateFlow<BackupExportEffect?>(null)
        val effects: StateFlow<BackupExportEffect?> = _effects.asStateFlow()

        private val _restoreEffects = MutableStateFlow<BackupRestoreEffect?>(null)
        val restoreEffects: StateFlow<BackupRestoreEffect?> = _restoreEffects.asStateFlow()

        private val _shareEffects = MutableStateFlow<BackupShareEffect?>(null)
        val shareEffects: StateFlow<BackupShareEffect?> = _shareEffects.asStateFlow()

        /** Re-evaluates the MDM suppression knob (called on screen resume). */
        fun refreshPolicy() {
            _uiState.update { it.copy(exportDisabledByPolicy = exportPolicy.isExportDisabledByPolicy()) }
        }

        /**
         * Streams a backup of [variant] into the stream produced by [openOutput].
         *
         * [openOutput] returns the SAF destination stream (or `null` if the picker
         * was cancelled). On a write failure the screen-supplied [onWriteFailed] runs
         * so the partial document can be removed; on success it is left in place. The
         * stream is always closed here once writing finishes.
         */
        fun export(
            variant: BackupVariant,
            openOutput: () -> OutputStream?,
            onWriteFailed: () -> Unit,
        ) {
            if (_uiState.value.exporting || _uiState.value.exportDisabledByPolicy) return
            _uiState.update { it.copy(exporting = true) }
            viewModelScope.launch {
                val output = openOutput()
                if (output == null) {
                    _uiState.update { it.copy(exporting = false) }
                    _effects.value = BackupExportEffect.Cancelled
                    return@launch
                }
                val result =
                    output.use { stream ->
                        exportUseCase.export(
                            variant = variant,
                            output = stream,
                            appVersion = appVersion,
                        )
                    }
                _uiState.update { it.copy(exporting = false) }
                _effects.value =
                    when (result) {
                        is BackupExportResult.Success -> {
                            BackupExportEffect.Success(
                                variant = result.variant,
                                byteCount = result.byteCount,
                                offerShare = result.variant == BackupVariant.SHARE,
                            )
                        }

                        is BackupExportResult.WriteFailed -> {
                            onWriteFailed()
                            BackupExportEffect.WriteFailed
                        }
                    }
            }
        }

        /** Clears the last one-shot export effect after the screen has consumed it. */
        fun consumeEffect() {
            _effects.value = null
        }

        /**
         * Writes a FRESH [BackupVariant.SHARE] backup into the stream produced by
         * [openOutput] (a cache-dir file the screen then hands to the share sheet).
         *
         * SHARE is forced regardless of any export-variant choice: a redacted backup
         * is the only thing safe to spray through ACTION_SEND. On success
         * [BackupShareEffect.Ready] is emitted so the screen can launch the chooser;
         * on a write failure [BackupShareEffect.Failed] is emitted and the screen
         * deletes the (possibly partial) temp file. The stream is always closed here.
         */
        fun prepareShareBackup(openOutput: () -> OutputStream?) {
            if (_uiState.value.sharing || _uiState.value.exportDisabledByPolicy) return
            _uiState.update { it.copy(sharing = true) }
            viewModelScope.launch {
                val output = openOutput()
                if (output == null) {
                    _uiState.update { it.copy(sharing = false) }
                    _shareEffects.value = BackupShareEffect.Failed
                    return@launch
                }
                val result =
                    output.use { stream ->
                        exportUseCase.export(
                            variant = BackupVariant.SHARE,
                            output = stream,
                            appVersion = appVersion,
                        )
                    }
                _uiState.update { it.copy(sharing = false) }
                _shareEffects.value =
                    when (result) {
                        is BackupExportResult.Success -> BackupShareEffect.Ready
                        is BackupExportResult.WriteFailed -> BackupShareEffect.Failed
                    }
            }
        }

        /** Clears the last one-shot share effect after the screen has consumed it. */
        fun consumeShareEffect() {
            _shareEffects.value = null
        }

        /**
         * Returns `true` when the one-time "SHARE is redacted but not zero-knowledge"
         * reminder still needs to be shown before the first redacted-backup share.
         */
        fun shouldShowShareReminder(): Boolean = !shareReminderPreferences.wasReminderShown()

        /** Records that the share-redaction reminder has been acknowledged. */
        fun markShareReminderShown() {
            shareReminderPreferences.markReminderShown()
        }

        /**
         * Reads the picked backup file via [openInput] and computes a preview WITHOUT
         * touching any live store. On success the preview sheet is shown; a newer
         * schema or malformed JSON surfaces a one-shot effect and no preview.
         *
         * [openInput] returns the SAF source stream (or `null` if cancelled). The
         * stream is fully read and closed here.
         */
        fun openImport(openInput: () -> InputStream?) {
            if (_uiState.value.importing || _uiState.value.restoring) return
            _uiState.update { it.copy(importing = true) }
            viewModelScope.launch {
                val json =
                    withContext(Dispatchers.IO) {
                        runCatching { openInput()?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    }
                if (json == null) {
                    // Cancelled picker or unreadable stream: no preview, no effect noise.
                    _uiState.update { it.copy(importing = false) }
                    return@launch
                }
                when (val result = withContext(Dispatchers.Default) { restoreUseCase.preview(json) }) {
                    is BackupPreviewResult.Ready -> {
                        _uiState.update {
                            it.copy(
                                importing = false,
                                importPreview =
                                    BackupImportPreview(
                                        json = json,
                                        preview = result.preview,
                                        // Default: restore only the categories that
                                        // actually carry content; never silently flip
                                        // an empty category on.
                                        selection =
                                            RestoreSelection(
                                                profilesAndGroups = result.preview.canRestoreProfilesAndGroups,
                                                routes = result.preview.ruleCount > 0,
                                                settings = result.preview.settingCount > 0,
                                            ),
                                    ),
                            )
                        }
                    }

                    is BackupPreviewResult.UnsupportedVersion -> {
                        _uiState.update { it.copy(importing = false) }
                        _restoreEffects.value =
                            BackupRestoreEffect.UnsupportedVersion(result.found, result.supported)
                    }

                    is BackupPreviewResult.Malformed -> {
                        _uiState.update { it.copy(importing = false) }
                        _restoreEffects.value = BackupRestoreEffect.Malformed
                    }
                }
            }
        }

        /** Toggles one restore category in the active preview. */
        fun setProfilesAndGroupsSelected(selected: Boolean) = updateSelection { it.copy(profilesAndGroups = selected) }

        fun setRoutesSelected(selected: Boolean) = updateSelection { it.copy(routes = selected) }

        fun setSettingsSelected(selected: Boolean) = updateSelection { it.copy(settings = selected) }

        private fun updateSelection(transform: (RestoreSelection) -> RestoreSelection) {
            _uiState.update { state ->
                val preview = state.importPreview ?: return@update state
                state.copy(importPreview = preview.copy(selection = transform(preview.selection)))
            }
        }

        /** Dismisses the import-preview sheet without restoring. */
        fun cancelImport() {
            _uiState.update { it.copy(importPreview = null) }
        }

        /**
         * Applies the staged restore for the active preview's selection. On success
         * the [BackupRestoreEffect.Restored] effect is emitted; the screen restarts
         * the process. Malformed JSON or a newer schema aborts without touching live
         * data (the use case re-validates from the same bytes).
         */
        fun confirmRestore() {
            val preview = _uiState.value.importPreview ?: return
            if (_uiState.value.restoring) return
            if (!preview.selection.any) {
                _restoreEffects.value = BackupRestoreEffect.NothingSelected
                return
            }
            _uiState.update { it.copy(restoring = true) }
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.Default) {
                        restoreUseCase.restore(preview.json, preview.selection)
                    }
                _uiState.update { it.copy(restoring = false, importPreview = null) }
                _restoreEffects.value =
                    when (result) {
                        is RestoreResult.Success -> {
                            BackupRestoreEffect.Restored
                        }

                        is RestoreResult.UnsupportedVersion -> {
                            BackupRestoreEffect.UnsupportedVersion(result.found, result.supported)
                        }

                        is RestoreResult.Aborted -> {
                            BackupRestoreEffect.Malformed
                        }

                        RestoreResult.NothingSelected -> {
                            BackupRestoreEffect.NothingSelected
                        }
                    }
            }
        }

        /** Clears the last one-shot restore effect after the screen has consumed it. */
        fun consumeRestoreEffect() {
            _restoreEffects.value = null
        }
    }
