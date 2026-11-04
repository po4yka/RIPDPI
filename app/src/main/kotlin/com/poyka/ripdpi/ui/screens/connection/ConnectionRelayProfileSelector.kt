package com.poyka.ripdpi.ui.screens.connection

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.message
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            val external = dependencies.externalProviders.current(mode)
            if (external == null && settings.relayEnabled && settings.relayProfileId == profileId) {
                null
            } else {
                dependencies.externalProviders.deactivate(mode)
                dependencies.relayArtifacts.selectProfile(profileId)
            }
        }

    fun selectDirect() =
        changeActivePath {
            val settings = dependencies.appSettingsRepository.snapshot()
            val mode = settings.toConfigDraft().mode
            val external = dependencies.externalProviders.current(mode)
            val reconnect = dependencies.externalProviders.deactivate(mode)
            when {
                settings.relayEnabled -> dependencies.relayArtifacts.deactivateActiveProfile()
                external != null && reconnect -> dependencies.appSettingsRepository.snapshot().toConfigDraft()
                else -> null
            }
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

    private fun changeActivePath(pathChange: suspend () -> ConnectionDraft?) {
        if (rejectWhileReconnecting()) return
        val request = generation.incrementAndGet()
        val saveJob = activeSaveJob()
        if (saveJob != null) suppressSaveSuccess()
        scope.launch {
            saveJob?.join()
            selectionLock.withLock {
                if (generation.get() != request) return@withLock
                runCatching {
                    pathChange()?.let { applyToRunningService(it) }
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
