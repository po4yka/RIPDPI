package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationOutcome
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        private val pauseAuthority: PauseIntentAuthority,
        private val profileMutations: ProfileMutationCoordinator,
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
            pauseAuthority: PauseIntentAuthority,
            profileMutations: ProfileMutationCoordinator,
        ) : this(
            serviceController = serviceController,
            bootSessionStateStore = bootSessionStateStore,
            profileLoader = AwgProfileLoader { profileId -> profileRepository.load(profileId)?.request },
            activationController = activationController,
            applyTracker = applyTracker,
            providerSelectionStore = providerSelectionStore,
            serviceIntentArbiter = serviceIntentArbiter,
            pauseAuthority = pauseAuthority,
            profileMutations = profileMutations,
        )

        internal constructor(
            serviceController: ServiceController,
            bootSessionStateStore: BootSessionStateStore,
            loadProfile: suspend (String) -> AwgActivationRequest?,
            activationController: VpnTransportActivationController,
            applyTracker: TransportFailoverApplyTracker,
            providerSelectionStore: XrayProviderSelectionStore,
            serviceIntentArbiter: ServiceIntentArbiter,
            pauseAuthority: PauseIntentAuthority,
            profileMutations: ProfileMutationCoordinator,
        ) : this(
            serviceController,
            bootSessionStateStore,
            AwgProfileLoader(loadProfile),
            activationController,
            applyTracker,
            providerSelectionStore,
            serviceIntentArbiter,
            pauseAuthority,
            profileMutations,
        )

        // Explicit standalone selection wins until a normal/simple start clears its pointer.
        override val selectionPriority: Int = -10

        private val lifecycleLock = Mutex()
        private val selectionLock = Mutex()
        private var selectedRequest: AwgActivationRequest? = null
        private var selectedGeneration: Long? = null

        @Suppress("TooGenericExceptionCaught")
        override suspend fun activate(request: AwgActivationRequest) {
            request.requireRuntimeReady()
            val preparation = profileMutations.captureMutation(ProfileMutationOrigin.ExplicitActivation)
            lifecycleLock.withLock {
                val requestId = applyTracker.begin()
                var activationReceipt: ProfileActivationReceipt? = null
                try {
                    withContext(NonCancellable) {
                        val outcome = profileMutations.activateStandaloneAwg(preparation, request.profileId)
                        val reserved =
                            outcome as? ProfileMutationOutcome.Reserved
                                ?: error("AmneziaWG activation was superseded")
                        activationReceipt =
                            reserved.receipt as? ProfileActivationReceipt
                                ?: error("AmneziaWG activation requires a profile activation receipt")
                    }
                    currentCoroutineContext().ensureActive()
                    val receipt = checkNotNull(activationReceipt)
                    val bound =
                        pauseAuthority.bindProfileActivation(receipt, Mode.VPN)
                            ?: error("AmneziaWG activation was superseded")
                    selectionLock.withLock { publishActivation(request, requestId, bound) }
                    when (applyTracker.awaitOutcome(requestId, ApplyTimeoutMillis)) {
                        TransportFailoverApplyOutcome.Applied -> {
                            Unit
                        }

                        TransportFailoverApplyOutcome.RollbackSafeFailure -> {
                            error("AmneziaWG activation failed")
                        }

                        TransportFailoverApplyOutcome.TimedOutInFlight -> {
                            error("AmneziaWG activation is still in flight")
                        }
                    }
                } catch (failure: Exception) {
                    try {
                        withContext(NonCancellable) {
                            val outcome = applyTracker.settleCancellation(requestId, ApplyTimeoutMillis)
                            if (outcome == TransportFailoverApplyOutcome.RollbackSafeFailure) {
                                activationReceipt?.let { rollbackSelection(request.profileId, it) }
                            }
                        }
                    } catch (cleanupFailure: Exception) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }

        private fun publishActivation(
            request: AwgActivationRequest,
            requestId: Long,
            receipt: RuntimeActivationReceipt,
        ) {
            serviceIntentArbiter.serialize {
                check(pauseAuthority.isCurrent(receipt)) { "AmneziaWG activation was superseded" }
                selectedRequest = request
                selectedGeneration = serviceIntentArbiter.captureExplicitUserIntentGeneration()
                when (
                    val dispatch =
                        activationController.startVpnTransport(
                            requestId,
                            TransportFailoverTarget(TransportKindAmneziaWg, request.profileId),
                            receipt,
                        )
                ) {
                    is ServiceStartResult.Accepted -> {
                        selectedGeneration = serviceIntentArbiter.captureExplicitUserIntentGeneration()
                    }

                    is ServiceStartResult.MaintenanceAccepted -> {
                        error("AmneziaWG activation requires a runtime activation receipt")
                    }

                    is ServiceStartResult.Rejected -> {
                        applyTracker.recordRollbackSafeFailure(requestId)
                        error("AmneziaWG VPN start rejected: ${dispatch.reason}")
                    }
                }
            }
        }

        private suspend fun rollbackSelection(
            profileId: String,
            receipt: ProfileActivationReceipt,
        ) {
            selectionLock.withLock {
                if (profileMutations.compensateStandaloneAwg(receipt, profileId)) {
                    selectedRequest = null
                    selectedGeneration = null
                }
            }
        }

        override suspend fun deactivate() {
            val selection =
                selectionLock.withLock {
                    serviceIntentArbiter.serialize {
                        val currentId = bootSessionStateStore.activeAwgProfileId()
                        currentId
                            ?.takeIf {
                                providerSelectionStore.current().kind == VpnProviderKind.Native &&
                                    (selectedRequest == null || selectedRequest?.profileId == currentId) &&
                                    (
                                        selectedGeneration == null ||
                                            selectedGeneration ==
                                            serviceIntentArbiter.captureExplicitUserIntentGeneration()
                                    )
                            }?.let { it to pauseAuthority.snapshotAuthority() }
                    }
                } ?: return
            withContext(NonCancellable) {
                val receipt = serviceController.prepareStopIfCurrent(selection.second) ?: return@withContext
                val clearFailure =
                    runCatching {
                        lifecycleLock.withLock {
                            selectionLock.withLock {
                                if (profileMutations.clearStandaloneAwg(receipt, selection.first)) {
                                    selectedRequest = null
                                    selectedGeneration = null
                                }
                            }
                        }
                    }.exceptionOrNull()
                val dispatchFailure = runCatching { serviceController.stopPrepared(receipt) }.exceptionOrNull()
                if (clearFailure != null) {
                    if (dispatchFailure != null && dispatchFailure !== clearFailure) {
                        clearFailure.addSuppressed(dispatchFailure)
                    }
                    throw clearFailure
                }
                if (dispatchFailure != null) throw dispatchFailure
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
