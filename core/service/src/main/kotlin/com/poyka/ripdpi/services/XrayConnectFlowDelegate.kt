package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.HandoffOutcome
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.awgConfigOrNull

/**
 * Thin connect-flow collaborator that keeps the embedded-Xray provider logic out
 * of [VpnRuntimeCompositionCoordinator]. It wraps the
 * [XrayProviderSessionController] and exposes ONE generic method per lifecycle op
 * — each returns whether the Xray provider handled the call. When it returns
 * `false` the coordinator runs its EXISTING native composition BYTE-IDENTICAL;
 * nothing about the native path is affected by this seam.
 *
 * The delegate owns the provider-active state, the per-start params derivation,
 * and the provider-failed signalling (via [XrayProviderStartException] /
 * [XrayProviderHandoverException]), so the coordinator only sees a small,
 * generic delegation surface.
 *
 * Lifecycle calls are serialized by the coordinator, matching the controller's
 * threading contract.
 */
internal class XrayConnectFlowDelegate(
    private val controller: XrayProviderSessionController,
    /**
     * Supplies the per-start tunnel params for the Xray path from the live
     * session + resolution, mirroring the native composition's derivation. Only
     * invoked when the Xray path is taken.
     */
    private val startParams: (
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) -> XrayTunnelStartParams = ::defaultXrayStartParams,
    /** Publishes the session's active-DNS bookkeeping after a successful bring-up. */
    private val publishActiveDnsState: (
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) -> Unit,
    /** Re-applies the active connection policy during a handover (native parity). */
    private val applyActiveConnectionPolicy: (
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) -> Unit,
) {
    /** True while the active session is bound to the Xray provider path. */
    private var active = false
    private var ownsProviderPath = false

    /** Whether the active session is currently bound to the Xray provider. */
    val isActive: Boolean
        get() = active

    /** True while lifecycle cleanup still belongs to the provider path. */
    val ownsActiveProviderPath: Boolean
        get() = ownsProviderPath

    /**
     * Attempt the Xray start. Returns `true` when the Xray provider is durably
     * selected and started successfully (the coordinator must then skip the
     * native path). Returns `false` when the provider is not selected (native
     * path runs). Throws [XrayProviderStartException] when the provider IS
     * selected but the start failed — the coordinator must NOT silently fall
     * through to the native composition in that case.
     */
    suspend fun tryStart(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ): Boolean {
        if (!controller.isXraySelected()) {
            return false
        }
        val outcome = controller.start(startParams(session, resolution))
        active = outcome is HandoffOutcome.Running
        if (outcome is HandoffOutcome.Running) {
            ownsProviderPath = true
            publishActiveDnsState(session, resolution)
            return true
        }
        // Provider start failed (no profile / config rejected / engine/tunnel
        // start failure). Surface it so the coordinator's startup-failure
        // classification runs (distinct from a tunnel fault).
        throw XrayProviderStartException(
            (outcome as? HandoffOutcome.Failed)?.reason ?: "xray provider start failed",
        )
    }

    /**
     * Tear the active Xray session down. Returns `true` when an Xray session was
     * active (and was stopped); `false` when no Xray session was active, leaving
     * the coordinator to stop the native stack.
     */
    suspend fun tryStop(): Boolean {
        if (!ownsProviderPath) {
            return false
        }
        try {
            controller.stop()
        } finally {
            active = false
            ownsProviderPath = false
        }
        return true
    }

    /**
     * Restart the active Xray session after a network handover. Returns `true`
     * when an Xray session was active (and was restarted); `false` when no Xray
     * session was active, leaving the coordinator to run the native handover.
     * Throws [XrayProviderHandoverException] when Xray owned the live session
     * but the handover failed, so the generic handover retry / failure path sees
     * the failure instead of marking the handover as successful.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun tryRestart(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
        restartReason: String = "network_handover",
    ): Boolean {
        if (!ownsProviderPath) {
            return false
        }
        requireTransactionalPolicyRefresh(restartReason)
        return if (resolution.proxyPreferences.awgConfigOrNull() != null && !controller.isXraySelected()) {
            controller.releaseForNativeReplacement()
            active = false
            ownsProviderPath = false
            false
        } else {
            val outcome =
                try {
                    controller.restart(startParams(session, resolution))
                } catch (error: Exception) {
                    active = controller.isActive
                    throw error
                }
            active = outcome is HandoffOutcome.Running
            when (outcome) {
                is HandoffOutcome.Running -> {
                    resetSessionDnsState(session)
                    applyActiveConnectionPolicy(session, resolution, restartReason, appliedAt)
                    publishActiveDnsState(session, resolution)
                }

                is HandoffOutcome.Failed -> {
                    throw XrayProviderHandoverException(outcome.reason)
                }

                HandoffOutcome.Stopped -> {
                    ownsProviderPath = false
                    // The provider selection changed while this handover was in
                    // flight. Xray has stopped cleanly and the coordinator should
                    // treat the active provider path as handled for this lifecycle
                    // turn, not fall through to a second native restart.
                }
            }
            true
        }
    }

    /** Clear the provider-active flag after a full stop. */
    fun reset() {
        active = false
        ownsProviderPath = false
    }

    private fun resetSessionDnsState(session: VpnRuntimeSession) {
        session.currentDns = null
        session.currentDnsSignature = null
        session.currentNetworkScopeKey = null
        session.encryptedDnsFailoverState.resetAll()
    }

    private fun requireTransactionalPolicyRefresh(restartReason: String) {
        if (restartReason == RoutingPolicyRefreshReason) {
            throw XrayProviderPolicyRefreshUnsupportedException()
        }
    }

    private companion object {
        const val RoutingPolicyRefreshReason = "routing_policy_refresh"
    }
}

/**
 * Thrown when the Xray provider start fails (no profile / config rejected /
 * engine or tunnel start failure). Lets the coordinator's startup-failure path
 * classify a PROVIDER failure distinctly from a tunnel-establishment fault,
 * rather than silently falling through to the native composition.
 */
internal class XrayProviderStartException(
    message: String,
) : IllegalStateException(message)

/**
 * Thrown when an already-active Xray provider session fails to restart during a
 * network handover. This must flow into `NetworkHandoverProcessor` so normal
 * handover retry and eventual failed-state handling applies.
 */
internal class XrayProviderHandoverException(
    message: String,
) : IllegalStateException(message)

internal class XrayProviderPolicyRefreshUnsupportedException :
    IllegalStateException("Active Xray route-policy refresh requires a transactional provider handoff")

/**
 * Default Xray tunnel start-params derivation, mirroring the native composition
 * inputs. DNS for the Xray path is owned by the RIPDPI tunnel (see
 * `XrayTunnelHandoff` DNS-loop ownership), so `forceTunnelDns` is false — the
 * tunnel resolves and forwards resolved targets to the Xray SOCKS inbound.
 */
internal fun defaultXrayStartParams(
    session: VpnRuntimeSession,
    resolution: ConnectionPolicyResolution,
): XrayTunnelStartParams =
    XrayTunnelStartParams(
        activeDns = resolution.activeDns,
        overrideReason = resolution.resolverFallbackReason,
        logContext =
            RipDpiLogContext(
                runtimeId = session.runtimeId,
                mode = session.mode.preferenceValue,
                policySignature = resolution.policySignature,
                fingerprintHash = resolution.fingerprintHash,
            ),
        forceTunnelDns = false,
        splitStrictDnsPolicy = resolution.splitStrictDnsPolicy,
    )
