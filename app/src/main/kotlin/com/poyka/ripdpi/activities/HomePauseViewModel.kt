package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.PauseAuthorityState
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.TimedPauseController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class HomePauseViewModel
    @Inject
    constructor(
        private val controller: TimedPauseController,
        private val authority: PauseIntentAuthority,
        stateStore: ServiceStateStore,
    ) : ViewModel() {
        private val failed = MutableStateFlow(false)

        // Presentation only. AlarmManager and durable generation authority own scheduling.
        private val ticks =
            flow {
                while (true) {
                    emit(Unit)
                    delay(1_000)
                }
            }
        val uiState =
            combine(
                authority.states,
                controller.cleanupPending,
                stateStore.status,
                failed,
                ticks,
            ) { stored, cleanup, _, error, _ ->
                val pause = presentedPauseIntent(stored, cleanup)
                val remaining = pause?.let(authority::remainingMillis)
                val clockChanged = pause?.phase == PausePhase.Paused && remaining == null
                HomePauseUiState(
                    available = controller.canPause(),
                    phase = if (clockChanged) PausePhase.Deferred else pause?.phase,
                    mode =
                        pause?.let {
                            com.poyka.ripdpi.data.Mode
                                .fromString(it.mode)
                        },
                    deadlineWallMillis = pause?.deadlineWallMillis,
                    remainingMillis = remaining,
                    failure = if (clockChanged) com.poyka.ripdpi.data.PauseFailure.ClockChanged else pause?.failure,
                    requestFailed = error,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomePauseUiState())

        fun pause(durationMillis: Long) = runCommand { controller.pause(durationMillis) }

        fun resume() = runCommand { controller.resumeNow() }

        fun stop() = runCommand { controller.stop() }

        private fun runCommand(command: suspend () -> Unit) =
            viewModelScope.launch(Dispatchers.IO) {
                failed.value = false
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalStateException) {
                    failed.value = true
                } catch (_: IllegalArgumentException) {
                    failed.value = true
                } catch (_: ArithmeticException) {
                    failed.value = true
                } catch (_: IOException) {
                    failed.value = true
                }
            }
    }

/** Retained resource ownership overrides a phase only for the current durable generation. */
internal fun presentedPauseIntent(
    stored: PauseAuthorityState?,
    cleanup: PauseIntent?,
): PauseIntent? = cleanup?.takeIf { it.generation == stored?.generation } ?: stored?.pause
