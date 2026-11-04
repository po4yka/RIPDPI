package com.poyka.ripdpi.ui.screens.connection

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.message
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

internal class ConnectionRelayProfileSelector(
    private val dependencies: ConnectionViewModelDependencies,
    private val scope: CoroutineScope,
    private val stringResolver: StringResolver,
    private val effects: MutableSharedFlow<ConnectionEffect>,
    private val log: Logger,
    private val activeSaveJob: () -> Job?,
    private val suppressSaveSuccess: () -> Unit,
    private val onProviderChanged: () -> Unit,
) {
    private val selectionLock = Mutex()
    private val generation = AtomicLong()

    // Both read saved settings inside the selection lock, so a tap made before the UI updated still lands.
    fun select(profileId: String) =
        changeActivePath {
            val settings = dependencies.appSettingsRepository.snapshot()
            val mode = settings.toConfigDraft().mode
            if (dependencies.externalProviders.current(mode) == null &&
                settings.relayEnabled &&
                settings.relayProfileId == profileId
            ) {
                PathChange.None
            } else {
                val restart = turnOffExternalProviders(mode)
                val draft = dependencies.relayArtifacts.selectProfile(profileId)
                if (restart) PathChange.Restart(draft.mode) else PathChange.Reconnect(draft)
            }
        }

    fun selectDirect() =
        changeActivePath {
            val settings = dependencies.appSettingsRepository.snapshot()
            val mode = settings.toConfigDraft().mode
            val hadExternal = dependencies.externalProviders.current(mode) != null
            val restart = turnOffExternalProviders(mode)
            val draft =
                when {
                    settings.relayEnabled -> dependencies.relayArtifacts.deactivateActiveProfile()
                    hadExternal -> dependencies.appSettingsRepository.snapshot().toConfigDraft()
                    else -> null
                }
            when {
                draft == null -> PathChange.None
                restart -> PathChange.Restart(draft.mode)
                else -> PathChange.Reconnect(draft)
            }
        }

    /**
     * Returns true when the provider stopped a running session, which then must start again on the new
     * path. A provider can refuse to turn off, so this checks that it did instead of showing a false path.
     */
    private suspend fun turnOffExternalProviders(mode: Mode): Boolean {
        val store = dependencies.serviceStateStore
        val wasRunning = store.status.value.first == AppStatus.Running
        val stopped = dependencies.externalProviders.deactivate(mode)
        check(dependencies.externalProviders.current(mode) == null) { "External provider stayed active" }
        if (!stopped || !wasRunning) return false
        withTimeout(ProviderStopTimeoutMillis) { store.status.first { it.first == AppStatus.Halted } }
        return true
    }

    /** A change made during a reconnect would stay saved while the service kept the old settings. */
    fun rejectWhileReconnecting(): Boolean {
        val pending =
            dependencies.reconnectCoordinator.reconnectState.value
                .isConfigPending()
        if (pending) {
            effects.tryEmit(ConnectionEffect.Message(stringResolver.getString(R.string.config_reconnect_in_progress)))
        }
        return pending
    }

    /** The settings are already saved, so a failure here keeps them and reports the stale service. */
    suspend fun applySavedSettings() {
        runCatching {
            applyToRunningService(dependencies.appSettingsRepository.snapshot().toConfigDraft())
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.e(error) { "Failed to apply saved settings" }
            effects.emit(ConnectionEffect.Message(stringResolver.getString(R.string.update_error_unknown)))
        }
    }

    private suspend fun applyToRunningService(draft: ConnectionDraft) {
        applySavedConfigDraftToRunningService(
            draft = draft,
            appSettingsRepository = dependencies.appSettingsRepository,
            serviceStateStore = dependencies.serviceStateStore,
            reconnectCoordinator = dependencies.reconnectCoordinator,
            onReconnectFailure = { result ->
                effects.tryEmit(ConnectionEffect.Message(result.reason.message(stringResolver)))
            },
            onUnsupportedVpnDns = {
                effects.tryEmit(
                    ConnectionEffect.Message(stringResolver.getString(R.string.dns_custom_doq_unavailable)),
                )
            },
        )
    }

    private fun changeActivePath(pathChange: suspend () -> PathChange) {
        if (rejectWhileReconnecting()) return
        val request = generation.incrementAndGet()
        val saveJob = activeSaveJob()
        if (saveJob != null) suppressSaveSuccess()
        scope.launch {
            saveJob?.join()
            selectionLock.withLock {
                if (generation.get() != request) return@withLock
                runCatching {
                    when (val change = pathChange()) {
                        PathChange.None -> Unit
                        is PathChange.Reconnect -> applyToRunningService(change.draft)
                        is PathChange.Restart -> dependencies.serviceController.start(change.mode)
                    }
                    onProviderChanged()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) { "Failed to change the active path" }
                    effects.emit(
                        ConnectionEffect.Message(stringResolver.getString(R.string.relay_editor_activation_failed)),
                    )
                }
            }
        }
    }
}

private sealed interface PathChange {
    data object None : PathChange

    data class Reconnect(
        val draft: ConnectionDraft,
    ) : PathChange

    data class Restart(
        val mode: Mode,
    ) : PathChange
}

private const val ProviderStopTimeoutMillis = 10_000L
