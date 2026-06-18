package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `:app`-callable entry point for activating a **standalone** AmneziaWG profile.
 *
 * `:app` cannot see the engine-api `ResolvedRipDpiAmneziaWgConfig` (because
 * `:core:engine-api` is `implementation`-only on `:core:service`), so the editor
 * hands this interface the runtime-state [AwgActivationRequest] it already builds.
 * The implementation -- living inside `:core:service` -- maps it into the resolved
 * engine config and drives the native [RipDpiAmneziaWgRuntime] via an
 * [AmneziaWgRuntimeSupervisor], mirroring the WARP activation supervisor shape.
 *
 * This is the standalone-profile path; it does not replace the settings-driven
 * WARP/relay composition in [SharedProxyRuntimeStack].
 */
interface StandaloneAmneziaWgActivator {
    /**
     * Resolves [request] into the native AmneziaWG config and starts the tunnel.
     * Any already-running standalone tunnel is stopped first so re-activating the
     * editor never leaks a session. Throws [SupervisorStartupFailureException] when
     * the runtime fails to reach readiness; the previous tunnel stays stopped.
     */
    suspend fun activate(request: AwgActivationRequest)

    /** Stops the standalone AmneziaWG tunnel if one is running; otherwise a no-op. */
    suspend fun deactivate()
}

@Singleton
internal class DefaultStandaloneAmneziaWgActivator
    @Inject
    constructor(
        @param:ApplicationScope private val applicationScope: CoroutineScope,
        private val dispatchers: AppCoroutineDispatchers,
        private val supervisorFactory: AmneziaWgRuntimeSupervisorFactory,
    ) : StandaloneAmneziaWgActivator {
        // Serializes activate/deactivate so a rapid re-tap cannot interleave two
        // supervisor lifecycles. The lock is never held across a suspending native
        // call that could itself re-enter (start/stop only), so no await-holding-lock.
        private val lifecycleLock = Mutex()
        private var supervisor: AmneziaWgRuntimeSupervisor? = null

        override suspend fun activate(request: AwgActivationRequest) {
            lifecycleLock.withLock {
                supervisor?.let { running ->
                    running.stop()
                    supervisor = null
                }
                val next = supervisorFactory.create(scope = applicationScope, dispatcher = dispatchers.io)
                supervisor = next
                try {
                    next.start(request) { stopOnUnexpectedExit() }
                } catch (failure: SupervisorStartupFailureException) {
                    supervisor = null
                    throw failure
                }
            }
        }

        override suspend fun deactivate() {
            lifecycleLock.withLock {
                supervisor?.stop()
                supervisor = null
            }
        }

        // An unexpected runtime exit detaches the supervisor reference so the next
        // activate() starts a clean session; the actual exit cause is surfaced via
        // the runtime telemetry channel, not here.
        private suspend fun stopOnUnexpectedExit() {
            lifecycleLock.withLock {
                supervisor?.detach()
                supervisor = null
            }
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
}
