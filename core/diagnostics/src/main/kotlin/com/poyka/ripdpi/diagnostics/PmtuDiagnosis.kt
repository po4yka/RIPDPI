package com.poyka.ripdpi.diagnostics

enum class PmtuDiagnosisStatus { ConfirmedLowerBound, Partial, Historical, Unavailable }

data class PmtuDiagnosis(
    val status: PmtuDiagnosisStatus,
    val minimumIpPacketBytes: Int? = null,
)

/** Outbound packet lower bound only; never a tunnel setting or an exact path maximum. */
fun PmtuProbeEvidence?.diagnosePmtu(networkScopeUnverified: Boolean = false): PmtuDiagnosis {
    val evidence =
        this?.validatedPmtuProbeEvidence()
            ?: return PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable)
    if (networkScopeUnverified || evidence.reason == "network_scope_unverified") {
        return PmtuDiagnosis(PmtuDiagnosisStatus.Historical)
    }
    val payload =
        evidence.acknowledgedUdpPayloadLowerBoundBytes
            ?: return PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable)
    if (evidence.status != PmtuProbeStatus.OBSERVED || !evidence.observationWindowComplete) {
        return PmtuDiagnosis(PmtuDiagnosisStatus.Partial)
    }
    val headers =
        when (evidence.addressFamily) {
            PmtuAddressFamily.IPV4 -> Ipv4UdpHeaderBytes
            PmtuAddressFamily.IPV6 -> Ipv6UdpHeaderBytes
        }
    return PmtuDiagnosis(PmtuDiagnosisStatus.ConfirmedLowerBound, payload + headers)
}

private const val Ipv4UdpHeaderBytes = 28
private const val Ipv6UdpHeaderBytes = 48
