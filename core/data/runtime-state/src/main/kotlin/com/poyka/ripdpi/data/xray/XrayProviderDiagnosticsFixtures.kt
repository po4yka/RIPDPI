package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator

/**
 * Canonical regression fixtures for Xray provider diagnostics.
 *
 * Each fixture is a fully-typed [XrayProviderProbeReport] representing one of
 * the contract-relevant provider conditions the Home / Diagnostics surfaces
 * must render distinctly. They are exposed (not test-only) so the same fixtures
 * back unit tests here AND can seed previews / instrumentation in
 * `:core:service` / `:app` without duplicating the shapes.
 *
 * Every fixture is privacy-clean: no secret, no live endpoint. The failure
 * fixtures carry an ALREADY-REDACTED detail string to prove the export path
 * stays clean even when an upstream error message would have leaked one.
 */
object XrayProviderDiagnosticsFixtures {
    private const val LoopbackListen = "127.0.0.1"
    private const val LoopbackPort = 10808

    private fun baseSnapshot(
        readiness: XrayProviderReadiness,
        listener: XrayListenerState,
        outbound: XrayOutboundHealth,
        failure: XrayProviderFailureClass = XrayProviderFailureClass.None,
    ): XrayProviderSnapshot =
        XrayProviderSnapshot(
            xrayVersion = "25.1.0",
            readiness = readiness,
            listenerState = listener,
            outboundHealth = outbound,
            failureClass = failure,
            tunnelTopology = XrayTunnelTopology.TunToLocalInbound::class.simpleName,
            localInboundListen = LoopbackListen,
            localInboundPort = LoopbackPort,
            profileName = "Example profile",
            outboundProtocol = "vless",
            outboundSecurity = "reality",
            capturedAt = 1_700_000_000_000L,
        )

    /** Provider fully healthy: engine up, listener bound, outbound reachable. */
    val healthy: XrayProviderProbeReport =
        XrayProviderProbeReport(
            snapshot =
                baseSnapshot(
                    readiness = XrayProviderReadiness.OutboundHealthy,
                    listener = XrayListenerState.Bound,
                    outbound = XrayOutboundHealth.Reachable,
                ).copy(lastPingRttMs = 42),
            probes =
                listOf(
                    XrayProviderProbeResult(XrayProviderProbeKind.Version, ok = true),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.ListenerReadiness,
                        ok = true,
                        latencyMs = 1,
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.WrapperPing,
                        ok = true,
                        latencyMs = 42,
                    ),
                ),
        )

    /** Rendered config rejected by the validator before the engine started. */
    val configInvalid: XrayProviderProbeReport =
        XrayProviderProbeReport(
            snapshot =
                baseSnapshot(
                    readiness = XrayProviderReadiness.Failed,
                    listener = XrayListenerState.Unknown,
                    outbound = XrayOutboundHealth.Unknown,
                    failure = XrayProviderFailureClass.ConfigInvalid,
                ).copy(
                    configFindings =
                        listOf(
                            XrayConfigValidationFinding(
                                code = XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING,
                                path = "outbounds[0].settings.vnext[0].users[0].flow",
                                message = "VLESS user is missing required 'flow'.",
                            ),
                        ),
                ),
            probes = emptyList(),
        )

    /**
     * Outbound socket was not protected via VpnService.protect — the socket's
     * traffic is captured by the TUN route, so the handshake never completes.
     * The redacted detail proves a leaked endpoint would be scrubbed.
     */
    val protectFailure: XrayProviderProbeReport =
        XrayProviderProbeReport(
            snapshot =
                baseSnapshot(
                    readiness = XrayProviderReadiness.Failed,
                    listener = XrayListenerState.Bound,
                    outbound = XrayOutboundHealth.Unreachable,
                    failure = XrayProviderFailureClass.ProtectFailure,
                ).copy(
                    lastFailureDetailRedacted =
                        XrayProfileRedactor.redactText(
                            "protect() returned false for outbound to " +
                                "{\"address\":\"vpn.example.com\"}:443",
                        ),
                ),
            probes =
                listOf(
                    XrayProviderProbeResult(XrayProviderProbeKind.Version, ok = true),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.ListenerReadiness,
                        ok = true,
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.WrapperPing,
                        ok = false,
                        detailRedacted = "handshake timeout",
                    ),
                ),
        )

    /**
     * Xray DNS egress re-entered the tunnel (LibXraySetTunFd topology without a
     * DNS bypass rule). Suspected from the symptom heuristic.
     */
    val dnsLoopSuspected: XrayProviderProbeReport =
        XrayProviderProbeReport(
            snapshot =
                baseSnapshot(
                    readiness = XrayProviderReadiness.Degraded,
                    listener = XrayListenerState.Bound,
                    outbound = XrayOutboundHealth.Unreachable,
                    failure = XrayProviderFailureClass.DnsLoopSuspected,
                ).copy(
                    tunnelTopology = XrayTunnelTopology.LibXraySetTunFd::class.simpleName,
                    lastFailureDetailRedacted =
                        "DNS query latency spike with no upstream response; loop suspected",
                ),
            probes =
                listOf(
                    XrayProviderProbeResult(XrayProviderProbeKind.Version, ok = true),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.WrapperPing,
                        ok = false,
                        detailRedacted = "no DNS resolution",
                    ),
                ),
        )

    /** Engine and listener up, but the upstream server never answered. */
    val outboundUnreachable: XrayProviderProbeReport =
        XrayProviderProbeReport(
            snapshot =
                baseSnapshot(
                    readiness = XrayProviderReadiness.Degraded,
                    listener = XrayListenerState.Bound,
                    outbound = XrayOutboundHealth.Unreachable,
                    failure = XrayProviderFailureClass.OutboundUnreachable,
                ).copy(
                    lastFailureDetailRedacted = "connect timeout after 5000ms",
                ),
            probes =
                listOf(
                    XrayProviderProbeResult(XrayProviderProbeKind.Version, ok = true),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.ListenerReadiness,
                        ok = true,
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.WrapperPing,
                        ok = false,
                        latencyMs = 5_000,
                        detailRedacted = "connect timeout",
                    ),
                ),
        )

    /** Every fixture, for exhaustive table-driven tests. */
    val all: List<Pair<String, XrayProviderProbeReport>> =
        listOf(
            "healthy" to healthy,
            "configInvalid" to configInvalid,
            "protectFailure" to protectFailure,
            "dnsLoopSuspected" to dnsLoopSuspected,
            "outboundUnreachable" to outboundUnreachable,
        )
}
