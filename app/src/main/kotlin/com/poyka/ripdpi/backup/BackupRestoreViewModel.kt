package com.poyka.ripdpi.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.backup.BackupExportResult
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** Immutable render state for the Backup & Restore screen. */
data class BackupRestoreUiState(
    val exporting: Boolean = false,
    val exportDisabledByPolicy: Boolean = false,
)

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val exportUseCase: BackupExportUseCase,
        private val exportPolicy: BackupExportPolicy,
        @param:Named("appVersionName") private val appVersion: String,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                BackupRestoreUiState(exportDisabledByPolicy = exportPolicy.isExportDisabledByPolicy()),
            )
        val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

        private val _effects = MutableStateFlow<BackupExportEffect?>(null)
        val effects: StateFlow<BackupExportEffect?> = _effects.asStateFlow()

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

        /** Clears the last one-shot effect after the screen has consumed it. */
        fun consumeEffect() {
            _effects.value = null
        }
    }
