package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The `:app`-callable entry point for activating a standalone AmneziaWG profile. */
interface StandaloneAmneziaWgActivator {
    /**
     * Selects [request] as the VPN-mode AmneziaWG egress and starts the VPN service.
     * Throws [IllegalStateException] when Android rejects the service start.
     */
    suspend fun activate(request: AwgActivationRequest)

    /** Clears the selected standalone AWG egress and stops the owned VPN session. */
    suspend fun deactivate()
}

@Singleton
internal class DefaultStandaloneAmneziaWgActivator
    private constructor(
        private val serviceController: ServiceController,
        private val bootSessionStateStore: BootSessionStateStore,
        private val profileLoader: AwgProfileLoader,
        private val activationController: VpnTransportActivationController,
        private val applyTracker: TransportFailoverApplyTracker,
        private val providerSelectionStore: XrayProviderSelectionStore,
        private val serviceIntentArbiter: ServiceIntentArbiter,
    ) : StandaloneAmneziaWgActivator,
        AwgEgressSelectionSource {
        @Inject
        constructor(
            serviceController: ServiceController,
            bootSessionStateStore: BootSessionStateStore,
            profileRepository: AwgProfileRepository,
            activationController: VpnTransportActivationController,
            applyTracker: TransportFailoverApplyTracker,
            providerSelectionStore: XrayProviderSelectionStore,
            serviceIntentArbiter: ServiceIntentArbiter,
        ) : this(
            serviceController = serviceController,
            bootSessionStateStore = bootSessionStateStore,
            profileLoader = AwgProfileLoader { profileId -> profileRepository.load(profileId)?.request },
            activationController = activationController,
            applyTracker = applyTracker,
            providerSelectionStore = providerSelectionStore,
            serviceIntentArbiter = serviceIntentArbiter,
        )

        internal constructor(
            serviceController: ServiceController,
            bootSessionStateStore: BootSessionStateStore,
            loadProfile: suspend (String) -> AwgActivationRequest?,
            activationController: VpnTransportActivationController,
            applyTracker: TransportFailoverApplyTracker,
            providerSelectionStore: XrayProviderSelectionStore,
            serviceIntentArbiter: ServiceIntentArbiter,
        ) : this(
            serviceController,
            bootSessionStateStore,
            AwgProfileLoader(loadProfile),
            activationController,
            applyTracker,
            providerSelectionStore,
            serviceIntentArbiter,
        )

        // Explicit standalone selection wins until a normal/simple start clears its pointer.
        override val selectionPriority: Int = -10

        private val lifecycleLock = Mutex()
        private val selectionLock = Mutex()
        private var selectedRequest: AwgActivationRequest? = null
        private var selectedGeneration: Long? = null

        private data class PreviousSelection(
            val profileId: String?,
            val provider: XrayProviderSelectionRecord,
            val generation: Long,
        )

        @Suppress("TooGenericExceptionCaught")
        override suspend fun activate(request: AwgActivationRequest) {
            request.requireRuntimeReady()
            lifecycleLock.withLock {
                var previous: PreviousSelection? = null
                val native = XrayProviderSelectionRecord.of(VpnProviderKind.Native, null)
                val requestId = applyTracker.begin()
                try {
                    previous = selectionLock.withLock { publishActivation(request, requestId, native) }
                    when (applyTracker.awaitOutcome(requestId, ApplyTimeoutMillis)) {
                        TransportFailoverApplyOutcome.Applied -> {
                            Unit
                        }

                        TransportFailoverApplyOutcome.RollbackSafeFailure -> {
                            error("AmneziaWG activation failed")
                        }

                        TransportFailoverApplyOutcome.TimedOutInFlight -> {
                            error(
                                "AmneziaWG activation is still in flight",
                            )
                        }
                    }
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        val outcome = applyTracker.settleCancellation(requestId, ApplyTimeoutMillis)
                        if (outcome == TransportFailoverApplyOutcome.RollbackSafeFailure) {
                            previous?.let { rollbackSelection(request.profileId, it, native) }
                        }
                    }
                    throw failure
                }
            }
        }

        private fun publishActivation(
            request: AwgActivationRequest,
            requestId: Long,
            native: XrayProviderSelectionRecord,
        ): PreviousSelection? =
            serviceIntentArbiter.serialize {
                val previous =
                    PreviousSelection(
                        bootSessionStateStore.activeAwgProfileId(),
                        providerSelectionStore.current(),
                        serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                    )
                val dispatch =
                    runCatching {
                        selectedRequest = request
                        bootSessionStateStore.setActiveAwgProfileId(request.profileId)
                        providerSelectionStore.update(native)
                        activationController.startVpnTransport(
                            requestId,
                            TransportFailoverTarget(TransportKindAmneziaWg, request.profileId),
                        )
                    }
                if (dispatch.isFailure || dispatch.getOrNull() is ServiceStartResult.Rejected) {
                    restoreSelection(previous, native)
                    applyTracker.recordRollbackSafeFailure(requestId)
                    dispatch.getOrThrow()
                    null
                } else {
                    selectedGeneration = serviceIntentArbiter.captureExplicitUserIntentGeneration()
                    previous.copy(generation = checkNotNull(selectedGeneration))
                }
            }

        private fun restoreSelection(
            previous: PreviousSelection,
            native: XrayProviderSelectionRecord,
        ) {
            selectedRequest = null
            selectedGeneration = null
            bootSessionStateStore.setActiveAwgProfileId(previous.profileId)
            if (providerSelectionStore.current() == native) providerSelectionStore.update(previous.provider)
        }

        private suspend fun rollbackSelection(
            profileId: String,
            previous: PreviousSelection,
            native: XrayProviderSelectionRecord,
        ) {
            selectionLock.withLock {
                serviceIntentArbiter.runIfExplicitUserIntentCurrent(previous.generation) {
                    if (bootSessionStateStore.activeAwgProfileId() == profileId) {
                        restoreSelection(previous, native)
                    }
                }
            }
        }

        override suspend fun deactivate() {
            lifecycleLock.withLock {
                selectionLock.withLock {
                    serviceIntentArbiter.serialize {
                        val currentId = bootSessionStateStore.activeAwgProfileId()
                        val ownsSelection =
                            currentId != null &&
                                providerSelectionStore.current().kind == VpnProviderKind.Native &&
                                (selectedRequest == null || selectedRequest?.profileId == currentId) &&
                                (
                                    selectedGeneration == null ||
                                        selectedGeneration == serviceIntentArbiter.captureExplicitUserIntentGeneration()
                                )
                        if (ownsSelection) {
                            selectedRequest = null
                            selectedGeneration = null
                            bootSessionStateStore.setActiveAwgProfileId(null)
                            serviceController.stop()
                        }
                    }
                }
            }
        }

        override suspend fun selectedAwgEgress(): AwgActivationRequest? =
            selectionLock.withLock {
                if (providerSelectionStore.current().kind != VpnProviderKind.Native) return@withLock null
                val profileId = bootSessionStateStore.activeAwgProfileId() ?: return@withLock null
                selectedRequest?.takeIf { it.profileId == profileId }?.let { return@withLock it }
                profileLoader
                    .load(profileId)
                    ?.also { selectedRequest = it }
                    ?: error("Selected standalone AWG profile is unavailable")
            }

        private companion object {
            const val ApplyTimeoutMillis = 30_000L
        }
    }

private fun interface AwgProfileLoader {
    suspend fun load(profileId: String): AwgActivationRequest?
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StandaloneAmneziaWgActivatorModule {
    @Binds
    @Singleton
    abstract fun bindStandaloneAmneziaWgActivator(
        activator: DefaultStandaloneAmneziaWgActivator,
    ): StandaloneAmneziaWgActivator

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAwgEgressSelectionSource(activator: DefaultStandaloneAmneziaWgActivator): AwgEgressSelectionSource
}
