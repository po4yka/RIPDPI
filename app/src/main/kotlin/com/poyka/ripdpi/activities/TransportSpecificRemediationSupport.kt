package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import java.util.Locale

internal enum class TransportRemediationKind {
    OWNED_STACK_ACTION,
    BROWSER_FALLBACK,
    QUIC_FALLBACK,
    NO_RELIABLE_RELAY_HINT,
}

data class TransportRemediationEvidence(
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val shadowTlsCamouflageAccepted: Boolean? = null,
    val naiveHttpsProxyAccepted: Boolean? = null,
)

internal fun recommendTransportRemediation(
    result: DirectModeVerdictResult?,
    reasonCode: DirectModeReasonCode?,
    transportClass: DirectTransportClass?,
    evidence: TransportRemediationEvidence = TransportRemediationEvidence(),
): TransportRemediationKind? =
    when (result) {
        DirectModeVerdictResult.TRANSPARENT_WORKS, null -> {
            null
        }

        DirectModeVerdictResult.OWNED_STACK_ONLY -> {
            TransportRemediationKind.OWNED_STACK_ACTION
        }

        DirectModeVerdictResult.NO_DIRECT_SOLUTION -> {
            when {
                evidence.prefersBrowserFallback() -> TransportRemediationKind.BROWSER_FALLBACK

                evidence.supportsQuicFallback() -> TransportRemediationKind.QUIC_FALLBACK

                transportClass == DirectTransportClass.SNI_TLS_SUSPECT ||
                    transportClass == DirectTransportClass.IP_BLOCK_SUSPECT ||
                    reasonCode == DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE ||
                    reasonCode == DirectModeReasonCode.IP_BLOCKED
                -> TransportRemediationKind.BROWSER_FALLBACK

                else -> TransportRemediationKind.NO_RELIABLE_RELAY_HINT
            }
        }
    }

internal fun List<DiagnosticsCapabilityEvidence>.toTransportRemediationEvidence(): TransportRemediationEvidence {
    val values =
        buildMap {
            for (evidence in this@toTransportRemediationEvidence) {
                for (detail in evidence.details) {
                    put(detail.label.trim().lowercase(Locale.US), detail.value.trim().lowercase(Locale.US))
                }
            }
        }
    return TransportRemediationEvidence(
        quicUsable = values["quic"].toCapabilityFlag(),
        udpUsable = values["udp"].toCapabilityFlag(),
        shadowTlsCamouflageAccepted = values["shadowtls"].toCapabilityFlag(),
        naiveHttpsProxyAccepted = values["https proxy"].toCapabilityFlag(),
    )
}

private fun TransportRemediationEvidence.prefersBrowserFallback(): Boolean =
    naiveHttpsProxyAccepted == true ||
        shadowTlsCamouflageAccepted == true ||
        quicUsable == false ||
        udpUsable == false

private fun TransportRemediationEvidence.supportsQuicFallback(): Boolean = quicUsable == true && udpUsable != false

private fun String?.toCapabilityFlag(): Boolean? =
    when (this) {
        "available", "usable", "accepted" -> true
        "blocked", "rejected" -> false
        else -> null
    }
