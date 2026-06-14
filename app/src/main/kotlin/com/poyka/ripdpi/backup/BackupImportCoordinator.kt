package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Owns the import-preview, selection editing, and confirmed-restore workflows.
 *
 * Reads the picked stream on [ioDispatcher], parses/restores on [defaultDispatcher]
 * (both default to the exact dispatchers the ViewModel used historically, so
 * production behavior is unchanged; tests inject test dispatchers). The coordinator
 * mutates the single ViewModel-owned [BackupRestoreUiState] via [updateState] and
 * reads it via [currentState], flipping the in-flight flag BEFORE emitting any
 * terminal effect.
 */
internal class BackupImportCoordinator(
    private val scope: CoroutineScope,
    private val restoreUseCase: BackupRestoreUseCase,
    private val currentState: () -> BackupRestoreUiState,
    private val updateState: ((BackupRestoreUiState) -> BackupRestoreUiState) -> Unit,
    private val emitRestoreEffect: (BackupRestoreEffect) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Reads the picked backup file via [openInput] and computes a preview WITHOUT
     * touching any live store. On success the preview sheet is shown; a newer schema
     * or malformed JSON surfaces a one-shot effect and no preview.
     *
     * [openInput] returns the SAF source stream (or `null` if cancelled). The stream
     * is fully read and closed here.
     */
    fun openImport(openInput: () -> InputStream?) {
        if (currentState().importing || currentState().restoring) return
        updateState { it.copy(importing = true) }
        scope.launch {
            val json =
                withContext(ioDispatcher) {
                    runCatching { openInput()?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                }
            if (json == null) {
                // Cancelled picker or unreadable stream: no preview, no effect noise.
                updateState { it.copy(importing = false) }
                return@launch
            }
            when (val result = withContext(defaultDispatcher) { restoreUseCase.preview(json) }) {
                is BackupPreviewResult.Ready -> {
                    updateState {
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
                    updateState { it.copy(importing = false) }
                    emitRestoreEffect(
                        BackupRestoreEffect.UnsupportedVersion(result.found, result.supported),
                    )
                }

                is BackupPreviewResult.Malformed -> {
                    updateState { it.copy(importing = false) }
                    emitRestoreEffect(BackupRestoreEffect.Malformed)
                }
            }
        }
    }

    /** Toggles one restore category in the active preview. */
    fun setProfilesAndGroupsSelected(selected: Boolean) = updateSelection { it.copy(profilesAndGroups = selected) }

    fun setRoutesSelected(selected: Boolean) = updateSelection { it.copy(routes = selected) }

    fun setSettingsSelected(selected: Boolean) = updateSelection { it.copy(settings = selected) }

    private fun updateSelection(transform: (RestoreSelection) -> RestoreSelection) {
        updateState { state ->
            val preview = state.importPreview ?: return@updateState state
            state.copy(importPreview = preview.copy(selection = transform(preview.selection)))
        }
    }

    /** Dismisses the import-preview sheet without restoring. */
    fun cancelImport() {
        updateState { it.copy(importPreview = null) }
    }

    /**
     * Applies the staged restore for the active preview's selection. On success the
     * [BackupRestoreEffect.Restored] effect is emitted; the screen restarts the
     * process. Malformed JSON or a newer schema aborts without touching live data
     * (the use case re-validates from the same bytes).
     */
    fun confirmRestore() {
        val state = currentState()
        val preview = state.importPreview
        when {
            preview == null || state.restoring -> {
            }

            !preview.selection.any -> {
                emitRestoreEffect(BackupRestoreEffect.NothingSelected)
            }

            else -> {
                updateState { it.copy(restoring = true) }
                scope.launch {
                    val result =
                        withContext(defaultDispatcher) {
                            restoreUseCase.restore(preview.json, preview.selection)
                        }
                    updateState { it.copy(restoring = false, importPreview = null) }
                    emitRestoreEffect(
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
                        },
                    )
                }
            }
        }
    }
}
