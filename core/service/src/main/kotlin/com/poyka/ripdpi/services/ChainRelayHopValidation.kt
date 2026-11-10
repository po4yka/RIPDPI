package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedChainRelayHopConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.ServiceStartupRejectedException

/** Applies the standalone per-kind checks to every resolved chain hop. */
internal fun validateChainRelayHopFeatures(chain: ResolvedChainRelayConfig) {
    chain.hops.forEach { hop -> validateChainRelayHopFeatures(hop.config) }
}

private fun validateChainRelayHopFeatures(hop: ResolvedChainRelayHopConfig) {
    if (hop.finalmask.type.isNotBlank() && hop.finalmask.type != RelayFinalmaskTypeOff) {
        rejectHop("Chain relay hop ${hop.profileId} cannot use finalmask; only single-hop relays support it")
    }
    when (hop.kind) {
        RelayKindTrojan -> {
            requireTlsHopEndpoint(hop, "Trojan")
        }

        RelayKindAnyTls -> {
            requireTlsHopEndpoint(hop, "AnyTLS")
        }

        RelayKindMasque -> {
            validateMasqueHopFeatures(hop)
        }
    }
}

private fun requireTlsHopEndpoint(
    hop: ResolvedChainRelayHopConfig,
    label: String,
) {
    if (hop.server.isBlank()) rejectHop("Chain relay hop ${hop.profileId}: $label requires a server hostname")
    if (hop.serverName.isBlank()) rejectHop("Chain relay hop ${hop.profileId}: $label requires a TLS server name")
}

private fun validateMasqueHopFeatures(hop: ResolvedChainRelayHopConfig) {
    // A hop carries no Privacy Pass provider, so the native handshake could never get a token.
    if (hop.masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        rejectHop("MASQUE privacy_pass auth is not supported on chain relay hop ${hop.profileId}")
    }
    validateMasqueRelayUrl(hop.masqueUrl)
}

private fun rejectHop(message: String): Nothing =
    throw ServiceStartupRejectedException(FailureReason.RelayConfigRejected(message))
