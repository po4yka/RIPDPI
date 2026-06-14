package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupExportResult
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Owns the two write-to-[OutputStream] export workflows: a normal SAF export and a
 * fresh redacted-SHARE export. Both flip the matching in-flight flag on [updateState]
 * BEFORE emitting their terminal one-shot effect, guard against reentrancy by reading
 * the live state via [currentState], and always close the destination stream.
 *
 * The coordinator owns no state holder of its own: it mutates the single
 * [BackupRestoreUiState] the ViewModel owns through the injected [updateState] lambda,
 * and reads it through [currentState] (== `_uiState.value`) so all coordinators share
 * one source of truth.
 */
internal class BackupExportCoordinator(
    private val scope: CoroutineScope,
    private val exportUseCase: BackupExportUseCase,
    private val appVersion: String,
    private val exportDisabledByPolicy: () -> Boolean,
    private val currentState: () -> BackupRestoreUiState,
    private val updateState: ((BackupRestoreUiState) -> BackupRestoreUiState) -> Unit,
    private val emitExportEffect: (BackupExportEffect) -> Unit,
    private val emitShareEffect: (BackupShareEffect) -> Unit,
) {
    /**
     * Streams a backup of [variant] into the stream produced by [openOutput].
     *
     * [openOutput] returns the SAF destination stream (or `null` if the picker was
     * cancelled). On a write failure the screen-supplied [onWriteFailed] runs so the
     * partial document can be removed; on success it is left in place. The stream is
     * always closed here once writing finishes.
     */
    fun export(
        variant: BackupVariant,
        openOutput: () -> OutputStream?,
        onWriteFailed: () -> Unit,
    ) {
        if (currentState().exporting || exportDisabledByPolicy()) return
        updateState { it.copy(exporting = true) }
        scope.launch {
            val output = openOutput()
            if (output == null) {
                updateState { it.copy(exporting = false) }
                emitExportEffect(BackupExportEffect.Cancelled)
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
            updateState { it.copy(exporting = false) }
            emitExportEffect(
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
                },
            )
        }
    }

    /**
     * Writes a FRESH [BackupVariant.SHARE] backup into the stream produced by
     * [openOutput] (a cache-dir file the screen then hands to the share sheet).
     *
     * SHARE is forced regardless of any export-variant choice: a redacted backup is
     * the only thing safe to spray through ACTION_SEND. On success
     * [BackupShareEffect.Ready] is emitted so the screen can launch the chooser; on a
     * write failure [BackupShareEffect.Failed] is emitted and the screen deletes the
     * (possibly partial) temp file. The stream is always closed here.
     */
    fun prepareShareBackup(openOutput: () -> OutputStream?) {
        if (currentState().sharing || exportDisabledByPolicy()) return
        updateState { it.copy(sharing = true) }
        scope.launch {
            val output = openOutput()
            if (output == null) {
                updateState { it.copy(sharing = false) }
                emitShareEffect(BackupShareEffect.Failed)
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
            updateState { it.copy(sharing = false) }
            emitShareEffect(
                when (result) {
                    is BackupExportResult.Success -> BackupShareEffect.Ready
                    is BackupExportResult.WriteFailed -> BackupShareEffect.Failed
                },
            )
        }
    }
}
