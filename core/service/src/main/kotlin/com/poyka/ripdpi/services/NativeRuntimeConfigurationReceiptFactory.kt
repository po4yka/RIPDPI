package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationSelection

internal class NativeRuntimeConfigurationReceiptFactory(
    private val identities: RuntimeConfigurationIdentityFactory,
) {
    fun build(
        session: ServiceRuntimeSession,
        resolution: ConnectionPolicyResolution,
        evidence: RuntimeStartEvidence.ProxySnapshot,
    ): RuntimeConfigurationSelection {
        val requested = resolution.requestedConfiguration
        val tunnel =
            if (session.mode == Mode.VPN) {
                checkNotNull(
                    (evidence as? RuntimeStartEvidence.VpnSnapshot)?.tunnel,
                ) { "VPN acknowledgment requires TUN readiness" }
            } else {
                null
            }
        val preferences = evidence.effectivePreferences
        session.effectiveConfigurationIdentity =
            identities.capture(
                connectionPolicyTransportMaterial(
                    session.mode,
                    preferences,
                    resolution.destinationRoutingDigest,
                ) +
                    evidence.consumedUpstreams.map { it.identityMaterial() } +
                    (
                        tunnel?.let {
                            vpnConfigurationMaterial(session.mode, it.configurationInput.settings) +
                                listOf(it.interfacePolicySignature)
                        }
                            ?: emptyList()
                    ) +
                    tunnel?.configurationInput?.packageRoutingRules.orEmpty().map {
                        com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson.encodeToString(
                            com.poyka.ripdpi.data.routing.PackageRoutingRule
                                .serializer(),
                            it,
                        )
                    },
                tunnel?.dnsMaterial() ?: connectionPolicyDnsMaterial(resolution.activeDns),
            )
        val actual =
            evidence.consumedUpstreams.firstOrNull()?.selection ?: RuntimeConfigurationSelection("native")
        return actual.copy(
            selectorGroupId =
                requested.selection.selectorGroupId.takeIf {
                    requested.selection.profileId ==
                        actual.profileId
                },
            selectorMemberId =
                requested.selection.selectorMemberId.takeIf {
                    requested.selection.profileId ==
                        actual.profileId
                },
        )
    }
}
