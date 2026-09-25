package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal class ConfigRelayProfileSelector(
    private val dependencies: ConfigViewModelDependencies,
    private val scope: CoroutineScope,
    private val stringResolver: StringResolver,
    private val effects: MutableSharedFlow<ConfigEffect>,
    private val log: Logger,
    private val activeSaveJob: () -> Job?,
    private val suppressSaveSuccess: () -> Unit,
) {
    private val selectionLock = Mutex()
    private val generation = AtomicLong()

    fun select(profileId: String) {
        val request = generation.incrementAndGet()
        val saveJob = activeSaveJob()
        if (saveJob != null) suppressSaveSuccess()
        scope.launch {
            saveJob?.join()
            selectionLock.withLock {
                if (generation.get() != request) return@withLock
                runCatching {
                    val draft = dependencies.relayArtifacts.selectProfile(profileId)
                    applySavedConfigDraftToRunningService(
                        draft = draft,
                        appSettingsRepository = dependencies.appSettingsRepository,
                        serviceStateStore = dependencies.serviceStateStore,
                        serviceController = dependencies.serviceController,
                        startRuntimeMode = { mode ->
                            startConfigRuntimeMode(mode, dependencies.serviceController, stringResolver, effects)
                        },
                        onUnsupportedVpnDns = {
                            effects.tryEmit(
                                ConfigEffect.Message(stringResolver.getString(R.string.dns_custom_doq_unavailable)),
                            )
                        },
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) { "Failed to select relay profile" }
                    effects.emit(
                        ConfigEffect.Message(stringResolver.getString(R.string.relay_editor_activation_failed)),
                    )
                }
            }
        }
    }
}
