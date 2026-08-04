package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    ) : StandaloneAmneziaWgActivator,
        AwgEgressSelectionSource {
        @Inject
        constructor(
            serviceController: ServiceController,
            bootSessionStateStore: BootSessionStateStore,
            profileRepository: AwgProfileRepository,
        ) : this(
            serviceController = serviceController,
            bootSessionStateStore = bootSessionStateStore,
            profileLoader = AwgProfileLoader { profileId -> profileRepository.load(profileId)?.request },
        )

        internal constructor(
            serviceController: ServiceController,
            bootSessionStateStore: BootSessionStateStore,
            loadProfile: suspend (String) -> AwgActivationRequest?,
        ) : this(serviceController, bootSessionStateStore, AwgProfileLoader(loadProfile))

        // A session-selected fallback must override this persisted standalone selection.
        override val selectionPriority: Int = 10

        private val lifecycleLock = Mutex()
        private var selectedRequest: AwgActivationRequest? = null

        override suspend fun activate(request: AwgActivationRequest) {
            lifecycleLock.withLock {
                selectedRequest = request
                bootSessionStateStore.setActiveAwgProfileId(request.profileId)
                when (val result = serviceController.start(Mode.VPN)) {
                    is ServiceStartResult.Accepted -> {
                        Unit
                    }

                    is ServiceStartResult.Rejected -> {
                        selectedRequest = null
                        bootSessionStateStore.setActiveAwgProfileId(null)
                        error("Cannot start standalone AWG VPN: ${result.reason}")
                    }
                }
            }
        }

        override suspend fun deactivate() {
            lifecycleLock.withLock {
                val hadSelectedRequest = selectedRequest != null || bootSessionStateStore.activeAwgProfileId() != null
                selectedRequest = null
                bootSessionStateStore.setActiveAwgProfileId(null)
                if (hadSelectedRequest) {
                    serviceController.stop()
                }
            }
        }

        override suspend fun selectedAwgEgress(): AwgActivationRequest? =
            lifecycleLock.withLock {
                selectedRequest?.let { return@withLock it }
                val profileId = bootSessionStateStore.activeAwgProfileId() ?: return@withLock null
                profileLoader
                    .load(profileId)
                    ?.also { selectedRequest = it }
                    ?: error("Selected standalone AWG profile is unavailable")
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
