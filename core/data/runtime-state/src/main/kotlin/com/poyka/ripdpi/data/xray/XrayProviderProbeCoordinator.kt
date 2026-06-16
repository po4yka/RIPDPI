package com.poyka.ripdpi.data.xray

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide seam the `:app` Diagnostics surface uses to trigger the
 * user-triggered Xray provider-path probe (decision 5) without referencing the
 * session-scoped `XrayProviderSessionController` (which is `internal` to
 * `:core:service`).
 *
 * The active VPN session registers its probe runner here on Xray-provider start
 * and clears it on stop, so a probe issued from the UI reaches the live
 * orchestrator state ONLY while an Xray session is actually running. When no
 * session is bound (native path, or VPN stopped), [runProbes] returns null so
 * the UI renders "no active provider to probe" rather than a false failure —
 * mirroring the in-process probe runner's "skip, don't fail" contract.
 *
 * The registered runner is a plain function reference; it never carries a
 * secret-bearing config (the produced [XrayProviderProbeReport] is already
 * secret-free per [XrayProviderSnapshot] / [XrayProviderProbeResult]).
 */
interface XrayProviderProbeCoordinator {
    /**
     * Run the provider-path probes against the active Xray session. Returns null
     * when no Xray session is currently bound (nothing to probe).
     */
    fun runProbes(): XrayProviderProbeReport?

    /** Register the active session's probe runner. Replaces any previous one. */
    fun register(runner: () -> XrayProviderProbeReport)

    /** Clear the registered runner (called on session stop). Idempotent. */
    fun clear()
}

@Singleton
class DefaultXrayProviderProbeCoordinator
    @Inject
    constructor() : XrayProviderProbeCoordinator {
        // AtomicReference: register/clear happen on the service session thread while
        // runProbes is issued from the UI/viewModel thread. No lock needed for a
        // single-slot publish/clear.
        private val runner = AtomicReference<(() -> XrayProviderProbeReport)?>(null)

        override fun runProbes(): XrayProviderProbeReport? = runner.get()?.invoke()

        override fun register(runner: () -> XrayProviderProbeReport) {
            this.runner.set(runner)
        }

        override fun clear() {
            runner.set(null)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class XrayProviderProbeCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindXrayProviderProbeCoordinator(
        impl: DefaultXrayProviderProbeCoordinator,
    ): XrayProviderProbeCoordinator
}
