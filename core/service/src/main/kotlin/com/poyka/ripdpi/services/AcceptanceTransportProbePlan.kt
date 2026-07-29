package com.poyka.ripdpi.services

internal enum class AcceptanceProbeApplicability(
    val wireValue: String,
) {
    Required("required"),
    NotApplicable("not_applicable"),
    Unavailable("unavailable"),
}

internal data class AcceptanceTransportProbePlan(
    val transportKind: String,
    val relayTcp: AcceptanceProbeApplicability,
    val relayUdp: AcceptanceProbeApplicability,
    val relayUdpPayload: AcceptanceProbeApplicability,
    val awgRuntime: AcceptanceProbeApplicability,
) {
    val requiresRelayListener: Boolean
        get() =
            relayTcp == AcceptanceProbeApplicability.Required ||
                relayUdp == AcceptanceProbeApplicability.Required ||
                relayUdpPayload == AcceptanceProbeApplicability.Required
}

internal fun acceptanceTransportProbePlan(
    relayKind: String,
    awgRuntimePublished: Boolean = false,
): AcceptanceTransportProbePlan =
    relayKindDescriptor(relayKind).let { descriptor ->
        when {
            awgRuntimePublished -> amneziaWgTransportProbePlan()
            descriptor == null || (!descriptor.tcp && !descriptor.udp) -> unavailableTransportProbePlan(relayKind)
            else -> descriptor.toAcceptanceTransportProbePlan()
        }
    }

private const val AmneziaWgTransportKind = "amneziawg"

private fun Boolean.toApplicability(): AcceptanceProbeApplicability =
    if (this) AcceptanceProbeApplicability.Required else AcceptanceProbeApplicability.NotApplicable

private fun RelayKindDescriptor.toAcceptanceTransportProbePlan(): AcceptanceTransportProbePlan =
    AcceptanceTransportProbePlan(
        transportKind = kindId,
        relayTcp = tcp.toApplicability(),
        relayUdp = udp.toApplicability(),
        relayUdpPayload = udp.toApplicability(),
        awgRuntime = AcceptanceProbeApplicability.NotApplicable,
    )

private fun amneziaWgTransportProbePlan(): AcceptanceTransportProbePlan =
    AcceptanceTransportProbePlan(
        transportKind = AmneziaWgTransportKind,
        relayTcp = AcceptanceProbeApplicability.NotApplicable,
        relayUdp = AcceptanceProbeApplicability.NotApplicable,
        relayUdpPayload = AcceptanceProbeApplicability.NotApplicable,
        awgRuntime = AcceptanceProbeApplicability.Required,
    )

private fun unavailableTransportProbePlan(relayKind: String): AcceptanceTransportProbePlan =
    AcceptanceTransportProbePlan(
        transportKind = sanitizeTransportKind(relayKind),
        relayTcp = AcceptanceProbeApplicability.Unavailable,
        relayUdp = AcceptanceProbeApplicability.Unavailable,
        relayUdpPayload = AcceptanceProbeApplicability.Unavailable,
        awgRuntime = AcceptanceProbeApplicability.Unavailable,
    )
