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
     * when no Xray session is bound or its registration changes during the probe.
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
        // Each binding has a distinct identity, even when the same callback is
        // registered again. A stopped or replaced session cannot publish a stale probe.
        private val registration = AtomicReference<Registration?>(null)

        override fun runProbes(): XrayProviderProbeReport? {
            val active = registration.get() ?: return null
            val report = active.runner()
            return report.takeIf { registration.get() === active }
        }

        override fun register(runner: () -> XrayProviderProbeReport) {
            registration.set(Registration(runner))
        }

        override fun clear() {
            registration.set(null)
        }

        private class Registration(
            val runner: () -> XrayProviderProbeReport,
        )
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
