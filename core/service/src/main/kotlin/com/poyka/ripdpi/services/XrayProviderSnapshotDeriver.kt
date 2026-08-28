package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayListenerState
import com.poyka.ripdpi.data.xray.XrayOutboundHealth
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderReadiness
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.data.xray.XrayTunnelTopology

/**
 * Pure derivation of a privacy-safe [XrayProviderSnapshot] from the live Xray
 * provider observables (decision 4).
 *
 * Inputs are the orchestrator's [VpnProviderState], the bridge's
 * `version()` / `listenerReady()` / `isAlive()` reads, the last render's
 * config findings, and the last protect-failure detail (from the
 * [VpnProtectFailureMonitor]). Outputs ONLY non-secret fields; the only
 * free-form string ([XrayProviderSnapshot.lastFailureDetailRedacted]) is passed
 * through [XrayProfileRedactor.redactText] so a leaked endpoint / UUID / key is
 * scrubbed before it reaches telemetry. Profile name / protocol / security are
 * accepted as already-non-secret labels (the caller reads them from the metadata
 * half, never the secret half).
 *
 * Failure precedence (highest first): protect failure -> config invalid ->
 * engine crash -> readiness/listener state. This keeps the Android-topology
 * hazards (protect loop, DNS loop) visible above ordinary readiness churn.
 */
internal class XrayProviderSnapshotDeriver(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param providerState orchestrator's current [VpnProviderState].
     * @param config active provider config (topology + loopback inbound port).
     * @param xrayVersion bridge `version()`; null until first read.
     * @param listenerReady bridge `listenerReady()`.
     * @param isAlive bridge `isAlive()`.
     * @param configFindings findings from the last render (empty when valid).
     * @param protectFailureDetail raw last-protect-failure detail, or null. MUST
     *   be the raw monitor detail; this deriver redacts it.
     * @param dnsLoopSuspected heuristic flag (e.g. listener bound + outbound
     *   never completes on the SetTunFd topology). Defaults false.
     * @param profileName already-non-secret label of the active profile.
     * @param outboundProtocol already-non-secret protocol kind (e.g. `vless`).
     * @param outboundSecurity already-non-secret stream security (e.g. `reality`).
     */
    @Suppress("LongParameterList")
    fun derive(
        providerState: VpnProviderState,
        config: XrayProviderConfig,
        xrayVersion: String?,
        listenerReady: Boolean,
        isAlive: Boolean,
        configFindings: List<XrayConfigValidationFinding> = emptyList(),
        protectFailureDetail: String? = null,
        dnsLoopSuspected: Boolean = false,
        profileName: String? = null,
        outboundProtocol: String? = null,
        outboundSecurity: String? = null,
    ): XrayProviderSnapshot {
        val failureClass =
            resolveFailureClass(
                providerState = providerState,
                isAlive = isAlive,
                configFindings = configFindings,
                protectFailureDetail = protectFailureDetail,
                dnsLoopSuspected = dnsLoopSuspected,
            )
        val listenerState = resolveListenerState(providerState, listenerReady, failureClass)
        val readiness = resolveReadiness(providerState, listenerReady, failureClass)
        val outboundHealth = resolveOutboundHealth(readiness, failureClass)

        val failureDetail =
            protectFailureDetail
                ?.takeIf { failureClass == XrayProviderFailureClass.ProtectFailure }
                ?.let(XrayProfileRedactor::redactText)

        return XrayProviderSnapshot(
            xrayVersion = xrayVersion,
            readiness = readiness,
            listenerState = listenerState,
            outboundHealth = outboundHealth,
            failureClass = failureClass,
            tunnelTopology = config.tunnelTopology.label(),
            localInboundListen = "127.0.0.1",
            localInboundPort = config.localInboundPort,
            profileName = profileName,
            outboundProtocol = outboundProtocol,
            outboundSecurity = outboundSecurity,
            configFindings = configFindings,
            lastFailureDetailRedacted = failureDetail,
            capturedAt = clock(),
        )
    }

    private fun resolveFailureClass(
        providerState: VpnProviderState,
        isAlive: Boolean,
        configFindings: List<XrayConfigValidationFinding>,
        protectFailureDetail: String?,
        dnsLoopSuspected: Boolean,
    ): XrayProviderFailureClass =
        when {
            protectFailureDetail != null -> XrayProviderFailureClass.ProtectFailure

            configFindings.isNotEmpty() -> XrayProviderFailureClass.ConfigInvalid

            dnsLoopSuspected -> XrayProviderFailureClass.DnsLoopSuspected

            // Process died while it should have been coming up / running.
            !isAlive && providerState == VpnProviderState.Starting -> XrayProviderFailureClass.EngineStartFailure

            !isAlive && providerState == VpnProviderState.Running -> XrayProviderFailureClass.EngineStartFailure

            else -> XrayProviderFailureClass.None
        }

    private fun resolveListenerState(
        providerState: VpnProviderState,
        listenerReady: Boolean,
        failureClass: XrayProviderFailureClass,
    ): XrayListenerState =
        when {
            failureClass == XrayProviderFailureClass.ListenerBindFailure -> XrayListenerState.BindFailed
            providerState == VpnProviderState.Stopped -> XrayListenerState.Unknown
            listenerReady -> XrayListenerState.Bound
            providerState == VpnProviderState.Starting -> XrayListenerState.Binding
            else -> XrayListenerState.Unknown
        }

    private fun resolveReadiness(
        providerState: VpnProviderState,
        listenerReady: Boolean,
        failureClass: XrayProviderFailureClass,
    ): XrayProviderReadiness =
        when {
            failureClass != XrayProviderFailureClass.None -> XrayProviderReadiness.Failed
            providerState == VpnProviderState.Stopped -> XrayProviderReadiness.Unknown
            providerState == VpnProviderState.Running && listenerReady -> XrayProviderReadiness.ListenerReady
            providerState == VpnProviderState.Running -> XrayProviderReadiness.Degraded
            listenerReady -> XrayProviderReadiness.ListenerReady
            else -> XrayProviderReadiness.Starting
        }

    private fun resolveOutboundHealth(
        readiness: XrayProviderReadiness,
        failureClass: XrayProviderFailureClass,
    ): XrayOutboundHealth =
        when {
            failureClass == XrayProviderFailureClass.OutboundUnreachable -> XrayOutboundHealth.Unreachable
            failureClass == XrayProviderFailureClass.OutboundDegraded -> XrayOutboundHealth.Unreachable
            readiness == XrayProviderReadiness.OutboundHealthy -> XrayOutboundHealth.Reachable
            readiness == XrayProviderReadiness.ListenerReady -> XrayOutboundHealth.Unknown
            else -> XrayOutboundHealth.Unknown
        }

    private fun XrayTunnelTopology.label(): String =
        when (this) {
            XrayTunnelTopology.TunToLocalInbound -> "TunToLocalInbound"
            XrayTunnelTopology.LibXraySetTunFd -> "LibXraySetTunFd"
        }
}
