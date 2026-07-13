package com.poyka.ripdpi.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.BackupVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        exportUseCase: BackupExportUseCase,
        restoreUseCase: BackupRestoreUseCase,
        private val exportPolicy: BackupExportPolicy,
        private val shareReminderPreferences: BackupShareReminderPreferences,
        resetAllSettingsUseCase: ResetAllSettingsUseCase,
        @Named("appVersionName") appVersion: String,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                BackupRestoreUiState(exportDisabledByPolicy = exportPolicy.isExportDisabledByPolicy()),
            )
        val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

        private val exportEffectChannel = Channel<BackupExportEffect>(Channel.BUFFERED)
        val effects: Flow<BackupExportEffect> = exportEffectChannel.receiveAsFlow()

        private val restoreEffectChannel = Channel<BackupRestoreEffect>(Channel.BUFFERED)
        val restoreEffects: Flow<BackupRestoreEffect> = restoreEffectChannel.receiveAsFlow()

        private val shareEffectChannel = Channel<BackupShareEffect>(Channel.BUFFERED)
        val shareEffects: Flow<BackupShareEffect> = shareEffectChannel.receiveAsFlow()

        private val resetEffectChannel = Channel<BackupResetEffect>(Channel.BUFFERED)
        val resetEffects: Flow<BackupResetEffect> = resetEffectChannel.receiveAsFlow()

        private val exportCoordinator =
            BackupExportCoordinator(
                scope = viewModelScope,
                exportUseCase = exportUseCase,
                appVersion = appVersion,
                exportDisabledByPolicy = { _uiState.value.exportDisabledByPolicy },
                currentState = _uiState::value,
                updateState = _uiState::update,
                emitExportEffect = { exportEffectChannel.trySend(it) },
                emitShareEffect = { shareEffectChannel.trySend(it) },
            )

        private val importCoordinator =
            BackupImportCoordinator(
                scope = viewModelScope,
                restoreUseCase = restoreUseCase,
                currentState = _uiState::value,
                updateState = _uiState::update,
                emitRestoreEffect = { restoreEffectChannel.trySend(it) },
            )

        private val resetCoordinator =
            BackupResetCoordinator(
                scope = viewModelScope,
                resetAllSettingsUseCase = resetAllSettingsUseCase,
                currentState = _uiState::value,
                updateState = _uiState::update,
                emitResetEffect = { resetEffectChannel.trySend(it) },
            )

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
        ) = exportCoordinator.export(variant, openOutput, onWriteFailed)

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
        fun prepareShareBackup(openOutput: () -> OutputStream?) = exportCoordinator.prepareShareBackup(openOutput)

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
        fun openImport(openInput: () -> InputStream?) = importCoordinator.openImport(openInput)

        /** Toggles one restore category in the active preview. */
        fun setProfilesAndGroupsSelected(selected: Boolean) = importCoordinator.setProfilesAndGroupsSelected(selected)

        fun setRoutesSelected(selected: Boolean) = importCoordinator.setRoutesSelected(selected)

        fun setSettingsSelected(selected: Boolean) = importCoordinator.setSettingsSelected(selected)

        /** Dismisses the import-preview sheet without restoring. */
        fun cancelImport() = importCoordinator.cancelImport()

        /**
         * Applies the staged restore for the active preview's selection. On success
         * the [BackupRestoreEffect.Restored] effect is emitted; the screen restarts
         * the process. Malformed JSON or a newer schema aborts without touching live
         * data (the use case re-validates from the same bytes).
         */
        fun confirmRestore() = importCoordinator.confirmRestore()

        // -- Reset all settings ---------------------------------------------------

        /**
         * Shows or hides the typed-confirmation reset dialog, always resetting the
         * typed input. Hiding it has NO side effect on any store: cancellation up to
         * the confirm step is completely free of consequences. Ignored mid-wipe.
         */
        fun setResetDialogVisible(visible: Boolean) = resetCoordinator.setResetDialogVisible(visible)

        /** Updates the typed confirmation token as the user types. */
        fun onResetConfirmationInputChange(input: String) = resetCoordinator.onResetConfirmationInputChange(input)

        /**
         * Performs the wipe — but ONLY if the typed token matches exactly. Records
         * the one-shot reset telemetry event first (inside the use case), then wipes
         * every user store and emits [BackupResetEffect.Wiped] so the screen restarts
         * the process. A mismatched token is a no-op.
         */
        fun confirmReset() = resetCoordinator.confirmReset()
    }
