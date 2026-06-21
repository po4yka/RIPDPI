package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    @Inject
    constructor(
        private val serviceController: ServiceController,
    ) : StandaloneAmneziaWgActivator,
        AwgEgressSelectionProvider {
        private val lifecycleLock = Mutex()
        private var selectedRequest: AwgActivationRequest? = null

        override suspend fun activate(request: AwgActivationRequest) {
            lifecycleLock.withLock {
                selectedRequest = request
                when (val result = serviceController.start(Mode.VPN)) {
                    is ServiceStartResult.Accepted -> {
                        Unit
                    }

                    is ServiceStartResult.Rejected -> {
                        selectedRequest = null
                        error("Cannot start standalone AWG VPN: ${result.reason}")
                    }
                }
            }
        }

        override suspend fun deactivate() {
            lifecycleLock.withLock {
                val hadSelectedRequest = selectedRequest != null
                selectedRequest = null
                if (hadSelectedRequest) {
                    serviceController.stop()
                }
            }
        }

        override suspend fun selectedAwgEgress(): AwgActivationRequest? =
            lifecycleLock.withLock {
                selectedRequest
            }
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
    @Singleton
    abstract fun bindAwgEgressSelectionProvider(
        activator: DefaultStandaloneAmneziaWgActivator,
    ): AwgEgressSelectionProvider
}
