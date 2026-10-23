package com.poyka.ripdpi.diagnostics

enum class PmtuDiagnosisStatus { ConfirmedLowerBound, Partial, Historical, Unavailable }

data class PmtuDiagnosis(
    val status: PmtuDiagnosisStatus,
    val minimumIpPacketBytes: Int? = null,
)

/** Outbound packet lower bound only; never a tunnel setting or an exact path maximum. */
fun PmtuProbeEvidence?.diagnosePmtu(networkScopeUnverified: Boolean = false): PmtuDiagnosis {
    val evidence = this?.validatedPmtuProbeEvidence()
    val payload = evidence?.acknowledgedUdpPayloadLowerBoundBytes
    return when {
        evidence == null -> {
            PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable)
        }

        networkScopeUnverified || evidence.reason == "network_scope_unverified" -> {
            PmtuDiagnosis(PmtuDiagnosisStatus.Historical)
        }

        payload == null -> {
            PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable)
        }

        evidence.status != PmtuProbeStatus.OBSERVED || !evidence.observationWindowComplete -> {
            PmtuDiagnosis(PmtuDiagnosisStatus.Partial)
        }

        else -> {
            val headers =
                when (evidence.addressFamily) {
                    PmtuAddressFamily.IPV4 -> Ipv4UdpHeaderBytes
                    PmtuAddressFamily.IPV6 -> Ipv6UdpHeaderBytes
                }
            PmtuDiagnosis(PmtuDiagnosisStatus.ConfirmedLowerBound, payload + headers)
        }
    }
}

private const val Ipv4UdpHeaderBytes = 28
private const val Ipv6UdpHeaderBytes = 48
