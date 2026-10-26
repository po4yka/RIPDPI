package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches a settings read or write in [viewModelScope]. A storage failure (disk full, I/O error, a Room
 * error) calls [onFailure] instead of reaching the uncaught-exception handler, which kills the app. The
 * settings flow keeps showing the stored value, so the user sees that the change did not apply. The log
 * names only the exception class: its message can carry stored values.
 */
internal fun ViewModel.launchSettingsWrite(
    onFailure: () -> Unit = {},
    block: suspend () -> Unit,
): Job =
    viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            Logger.w { "Settings storage operation failed: ${ignored.javaClass.simpleName}" }
            onFailure()
        }
    }
