package com.poyka.ripdpi.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the typed-confirmation "erase all app data" workflow: dialog visibility, the
 * confirmation-token input, and the gated wipe.
 *
 * The wipe runs on [defaultDispatcher] (defaulting to the dispatcher the ViewModel
 * used historically), then flips `resetting` off and hides the dialog in the SAME
 * update BEFORE emitting [BackupResetEffect.Wiped] — state-then-effect ordering, so
 * the screen never restarts the process before the state settles. The typed-token
 * gate is read from the live [BackupRestoreUiState] via [currentState] (the
 * `resetConfirmationMatches` computed property stays on the state object).
 */
internal class BackupResetCoordinator(
    private val scope: CoroutineScope,
    private val resetAllSettingsUseCase: ResetAllSettingsUseCase,
    private val currentState: () -> BackupRestoreUiState,
    private val updateState: ((BackupRestoreUiState) -> BackupRestoreUiState) -> Unit,
    private val emitResetEffect: (BackupResetEffect) -> Unit,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Shows or hides the typed-confirmation reset dialog, always resetting the typed
     * input. Hiding it has NO side effect on any store: cancellation up to the confirm
     * step is completely free of consequences. Ignored mid-wipe.
     */
    fun setResetDialogVisible(visible: Boolean) {
        if (currentState().resetting) return
        updateState { it.copy(resetDialogVisible = visible, resetConfirmationInput = "") }
    }

    /** Updates the typed confirmation token as the user types. */
    fun onResetConfirmationInputChange(input: String) {
        updateState { it.copy(resetConfirmationInput = input) }
    }

    /**
     * Performs the wipe — but ONLY if the typed token matches exactly. Records the
     * one-shot reset telemetry event first (inside the use case), then wipes every
     * user store and emits [BackupResetEffect.Wiped] so the screen restarts the
     * process. A mismatched token is a no-op.
     */
    fun confirmReset() {
        val state = currentState()
        if (state.resetting || !state.resetConfirmationMatches) return
        updateState { it.copy(resetting = true) }
        scope.launch {
            try {
                withContext(defaultDispatcher) { resetAllSettingsUseCase.reset() }
            } catch (cancelled: CancellationException) {
                updateState { it.copy(resetting = false) }
                throw cancelled
            } catch (ignored: Exception) {
                // A failed store wipe must not crash the app or leave the dialog stuck. Each step only
                // clears a store, so the still-open dialog lets the user run the reset again.
                Logger.w { "Reset failed: ${ignored.javaClass.simpleName}" }
                updateState { it.copy(resetting = false) }
                return@launch
            }
            updateState {
                it.copy(resetting = false, resetDialogVisible = false, resetConfirmationInput = "")
            }
            emitResetEffect(BackupResetEffect.Wiped)
        }
    }
}
