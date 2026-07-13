package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val BackupMaxImportBytes: Int = 8 * 1024 * 1024

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
    private val maxImportBytes: Int = BackupMaxImportBytes,
) {
    init {
        require(maxImportBytes > 0) { "maxImportBytes must be positive" }
    }

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
            val outcome =
                try {
                    val json =
                        withContext(ioDispatcher) {
                            openInput()?.use { readBoundedUtf8Text(it, maxImportBytes) }
                        }
                    if (json == null) {
                        OpenImportOutcome.Cancelled
                    } else {
                        when (val result = withContext(defaultDispatcher) { restoreUseCase.preview(json) }) {
                            is BackupPreviewResult.Ready -> {
                                OpenImportOutcome.Ready(json, result)
                            }

                            is BackupPreviewResult.UnsupportedVersion -> {
                                OpenImportOutcome.UnsupportedVersion(result.found, result.supported)
                            }

                            is BackupPreviewResult.Malformed -> {
                                OpenImportOutcome.Malformed
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    updateState { it.copy(importing = false) }
                    throw cancelled
                } catch (_: Exception) {
                    OpenImportOutcome.Malformed
                }
            when (outcome) {
                OpenImportOutcome.Cancelled -> {
                    updateState { it.copy(importing = false) }
                }

                is OpenImportOutcome.Ready -> {
                    val preview = outcome.result.preview
                    updateState {
                        it.copy(
                            importing = false,
                            importPreview =
                                BackupImportPreview(
                                    json = outcome.json,
                                    preview = preview,
                                    // Default: restore only the categories that
                                    // actually carry content; never silently flip
                                    // an empty category on.
                                    selection =
                                        RestoreSelection(
                                            profilesAndGroups = preview.canRestoreProfilesAndGroups,
                                            routes = preview.ruleCount > 0,
                                            settings = preview.settingCount > 0,
                                        ),
                                ),
                        )
                    }
                }

                is OpenImportOutcome.UnsupportedVersion -> {
                    updateState { it.copy(importing = false) }
                    emitRestoreEffect(BackupRestoreEffect.UnsupportedVersion(outcome.found, outcome.supported))
                }

                OpenImportOutcome.Malformed -> {
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
                    val effect =
                        try {
                            withContext(defaultDispatcher) {
                                restoreUseCase.restore(preview.json, preview.selection)
                            }.toEffect()
                        } catch (cancelled: CancellationException) {
                            updateState { it.copy(restoring = false) }
                            throw cancelled
                        } catch (_: Exception) {
                            BackupRestoreEffect.Malformed
                        }
                    updateState { it.copy(restoring = false, importPreview = null) }
                    emitRestoreEffect(effect)
                }
            }
        }
    }
}

private sealed interface OpenImportOutcome {
    data object Cancelled : OpenImportOutcome

    data class Ready(
        val json: String,
        val result: BackupPreviewResult.Ready,
    ) : OpenImportOutcome

    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : OpenImportOutcome

    data object Malformed : OpenImportOutcome
}

private fun RestoreResult.toEffect(): BackupRestoreEffect =
    when (this) {
        is RestoreResult.Success -> BackupRestoreEffect.Restored
        is RestoreResult.UnsupportedVersion -> BackupRestoreEffect.UnsupportedVersion(found, supported)
        is RestoreResult.Aborted -> BackupRestoreEffect.Malformed
        is RestoreResult.IntegrityFailure -> BackupRestoreEffect.IntegrityFailure
        RestoreResult.NothingSelected -> BackupRestoreEffect.NothingSelected
    }

private fun readBoundedUtf8Text(
    input: InputStream,
    maxBytes: Int,
): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0

    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        if (read > maxBytes - total) throw BackupImportTooLargeException()
        output.write(buffer, 0, read)
        total += read
    }

    return Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}

private class BackupImportTooLargeException : Exception()
