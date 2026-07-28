package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RemoteDeviceAcceptanceStatus(
    val wireValue: String,
) {
    Idle("idle"),
    Running("running"),
    Incomplete("incomplete"),
    Pass("pass"),
    Fail("fail"),
}

data class RemoteDeviceAcceptanceStep(
    val id: String,
    val status: RemoteDeviceAcceptanceStatus,
    val durationMs: Long? = null,
    val errorClass: String? = null,
    val pathKind: String? = null,
)

data class RemoteDeviceAcceptanceDevice(
    val model: String,
    val csc: String,
    val api: Int,
    val abi: String,
)

data class RemoteDeviceAcceptanceReport(
    val status: RemoteDeviceAcceptanceStatus = RemoteDeviceAcceptanceStatus.Idle,
    val device: RemoteDeviceAcceptanceDevice = captureRemoteDeviceAcceptanceDevice(),
    val transportKind: String = "unknown",
    val steps: List<RemoteDeviceAcceptanceStep> = acceptanceStepIds.map(::pendingStep),
    val pathHealth: RelayUdpPayloadHealthEvidence? = null,
    val underlay: RemoteDeviceAcceptanceUnderlay = RemoteDeviceAcceptanceUnderlay(),
)

data class RemoteDeviceAcceptanceUnderlay(
    val mtuBand: String = "unknown",
    val hasIpv4Address: Boolean = false,
    val hasIpv6Address: Boolean = false,
    val hasIpv4DefaultRoute: Boolean = false,
    val hasIpv6DefaultRoute: Boolean = false,
    val hasIpv4Dns: Boolean = false,
    val hasIpv6Dns: Boolean = false,
    val nat64Advertised: Boolean? = null,
    val nat64Reachability: String = "unknown",
)

interface RemoteDeviceAcceptanceGate {
    val report: StateFlow<RemoteDeviceAcceptanceReport>

    fun start(scope: CoroutineScope)

    fun renderRedactedReport(): String
}

internal data class AcceptanceBaselineEvidence(
    val serviceRunning: Boolean,
    val transportKind: String,
    val listenerAvailable: Boolean,
    val probe: RelayCapabilityProbeEvidence?,
    val ipv4Probe: RelayCapabilityProbeEvidence?,
    val ipv6Probe: RelayCapabilityProbeEvidence?,
    val payloadHealth: RelayUdpPayloadHealthEvidence?,
    val payloadHealthError: String? = null,
    val contextError: String? = null,
    val underlay: RemoteDeviceAcceptanceUnderlay,
    val directEgressObserved: Boolean,
    val durationMs: Long,
)

internal fun buildRemoteDeviceAcceptanceBaseline(
    device: RemoteDeviceAcceptanceDevice,
    evidence: AcceptanceBaselineEvidence,
): RemoteDeviceAcceptanceReport {
    val preflightError =
        when {
            !evidence.serviceRunning -> ErrorServiceNotRunning
            evidence.transportKind != com.poyka.ripdpi.data.RelayKindVlessReality -> ErrorTransportMismatch
            !evidence.listenerAvailable -> ErrorListenerUnavailable
            evidence.probe == null -> ErrorProbe
            else -> null
        }
    val probe = evidence.probe
    val udpAssociationSucceeded = probe?.udpAssociationOpened == true
    val steps =
        listOf(
            networkEvidenceStep(
                StepRealityTcp,
                preflightError = preflightError,
                contextError = evidence.contextError,
                succeeded = probe?.tcpSucceeded == true,
                failure = probe?.tcpFailure,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            networkEvidenceStep(
                StepUdpAssociate,
                preflightError = preflightError,
                contextError = evidence.contextError,
                succeeded = udpAssociationSucceeded,
                failure = probe?.udpFailure,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            networkEvidenceStep(
                StepDnsUdp,
                preflightError = preflightError,
                contextError = evidence.contextError,
                succeeded = probe?.udpSucceeded == true,
                failure = probe?.udpFailure,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            payloadHealthStep(
                StepRelayUdpPayload,
                preflightError = preflightError,
                payloadHealth = evidence.payloadHealth,
                payloadHealthError = evidence.contextError ?: evidence.payloadHealthError,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            networkEvidenceStep(
                StepIpv4,
                preflightError = preflightError,
                contextError = evidence.contextError,
                succeeded = evidence.ipv4Probe?.tcpSucceeded == true,
                failure = evidence.ipv4Probe?.tcpFailure ?: ErrorIpv4Egress,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            networkEvidenceStep(
                StepIpv6,
                preflightError = preflightError,
                contextError = evidence.contextError,
                succeeded = evidence.ipv6Probe?.tcpSucceeded == true,
                failure = evidence.ipv6Probe?.tcpFailure ?: ErrorIpv6Egress,
                evidence.durationMs,
                pathKind = RelayPathKind,
            ),
            pendingStep(StepReconnect),
            pendingStep(StepHandover),
            pendingStep(StepScreenOff),
            resultStep(
                StepNoDirectEgress,
                !evidence.directEgressObserved,
                ErrorDirectEgress,
                evidence.durationMs,
            ),
        )
    return RemoteDeviceAcceptanceReport(
        status = deriveAcceptanceStatus(steps),
        device = device,
        transportKind = evidence.transportKind,
        steps = steps,
        pathHealth = evidence.payloadHealth,
        underlay = evidence.underlay,
    )
}

internal fun renderRemoteDeviceAcceptanceReport(report: RemoteDeviceAcceptanceReport): String =
    RemoteDeviceAcceptanceReportJson.encodeToString(
        RedactedAcceptanceReport(
            device =
                RedactedAcceptanceDevice(
                    model = report.device.model,
                    csc = report.device.csc,
                    api = report.device.api,
                    abi = report.device.abi,
                ),
            transportKind = report.transportKind,
            result = report.status.wireValue,
            steps =
                report.steps.map { step ->
                    RedactedAcceptanceStep(
                        id = step.id,
                        status = step.status.wireValue,
                        durationMs = step.durationMs,
                        errorClass = step.errorClass,
                        pathKind = step.pathKind,
                    )
                },
            pathHealth = report.pathHealth?.toRedacted(),
            underlay = report.underlay.toRedacted(),
        ),
    )

@Serializable
private data class RedactedAcceptanceReport(
    val format: String = "ripdpi_remote_device_acceptance_v1",
    val device: RedactedAcceptanceDevice,
    val transportKind: String,
    val result: String,
    val steps: List<RedactedAcceptanceStep>,
    val underlay: RedactedAcceptanceUnderlay,
    val pathHealth: RedactedRelayUdpPayloadHealth? = null,
)

@Serializable
private data class RedactedAcceptanceDevice(
    val model: String,
    val csc: String,
    val api: Int,
    val abi: String,
)

@Serializable
private data class RedactedAcceptanceStep(
    val id: String,
    val status: String,
    val durationMs: Long? = null,
    val errorClass: String? = null,
    val pathKind: String? = null,
)

@Serializable
private data class RedactedRelayUdpPayloadHealth(
    val measurementKind: String,
    val ceilingLabel: String,
    val overheadAssessment: String,
    val effectivePathMtuBytes: Int? = null,
    val overallVerdict: String,
    val families: List<RedactedRelayUdpPayloadFamilyHealth>,
)

@Serializable
private data class RedactedRelayUdpPayloadFamilyHealth(
    val family: String,
    val controlBefore: String,
    val controlAfter: String,
    val maxAcknowledgedPayloadBytes: Int? = null,
    val firstRepeatedFailedPayloadBytes: Int? = null,
    val attemptCount: Int,
    val verdict: String,
    val ptbObservation: String,
    val fragmentationReassembly: String,
)

@Serializable
private data class RedactedAcceptanceUnderlay(
    val mtuBand: String,
    val hasIpv4Address: Boolean,
    val hasIpv6Address: Boolean,
    val hasIpv4DefaultRoute: Boolean,
    val hasIpv6DefaultRoute: Boolean,
    val hasIpv4Dns: Boolean,
    val hasIpv6Dns: Boolean,
    val nat64Advertised: Boolean? = null,
    val nat64Reachability: String,
)

internal fun RemoteDeviceAcceptanceReport.acceptanceDataPlaneStatus(): RemoteDeviceAcceptanceStatus {
    val dataPlaneSteps = steps.filter { it.id in acceptanceDataPlaneStepIds }
    return when {
        dataPlaneSteps.any { it.status == RemoteDeviceAcceptanceStatus.Fail } -> RemoteDeviceAcceptanceStatus.Fail
        dataPlaneSteps.all { it.status == RemoteDeviceAcceptanceStatus.Pass } -> RemoteDeviceAcceptanceStatus.Pass
        else -> RemoteDeviceAcceptanceStatus.Incomplete
    }
}

internal fun deriveAcceptanceStatus(steps: List<RemoteDeviceAcceptanceStep>): RemoteDeviceAcceptanceStatus =
    when {
        steps.any { it.status == RemoteDeviceAcceptanceStatus.Fail } -> RemoteDeviceAcceptanceStatus.Fail
        steps.all { it.status == RemoteDeviceAcceptanceStatus.Pass } -> RemoteDeviceAcceptanceStatus.Pass
        else -> RemoteDeviceAcceptanceStatus.Incomplete
    }

private fun pendingStep(id: String): RemoteDeviceAcceptanceStep =
    RemoteDeviceAcceptanceStep(id = id, status = RemoteDeviceAcceptanceStatus.Incomplete)

private fun resultStep(
    id: String,
    succeeded: Boolean,
    failure: String?,
    durationMs: Long,
    pathKind: String? = null,
): RemoteDeviceAcceptanceStep =
    RemoteDeviceAcceptanceStep(
        id = id,
        status = if (succeeded) RemoteDeviceAcceptanceStatus.Pass else RemoteDeviceAcceptanceStatus.Fail,
        durationMs = durationMs,
        errorClass = if (succeeded) null else failure,
        pathKind = pathKind,
    )

private fun networkEvidenceStep(
    id: String,
    preflightError: String?,
    contextError: String?,
    succeeded: Boolean,
    failure: String?,
    durationMs: Long,
    pathKind: String? = null,
): RemoteDeviceAcceptanceStep {
    val status =
        when {
            preflightError != null -> RemoteDeviceAcceptanceStatus.Fail
            contextError != null -> RemoteDeviceAcceptanceStatus.Incomplete
            succeeded -> RemoteDeviceAcceptanceStatus.Pass
            else -> RemoteDeviceAcceptanceStatus.Fail
        }
    return RemoteDeviceAcceptanceStep(
        id = id,
        status = status,
        durationMs = durationMs,
        errorClass =
            when (status) {
                RemoteDeviceAcceptanceStatus.Pass -> null
                RemoteDeviceAcceptanceStatus.Incomplete -> contextError
                else -> preflightError ?: failure
            },
        pathKind = pathKind,
    )
}

private fun payloadHealthStep(
    id: String,
    preflightError: String?,
    payloadHealth: RelayUdpPayloadHealthEvidence?,
    payloadHealthError: String?,
    durationMs: Long,
    pathKind: String? = null,
): RemoteDeviceAcceptanceStep {
    val status =
        when {
            preflightError != null -> RemoteDeviceAcceptanceStatus.Fail
            payloadHealth?.passed == true -> RemoteDeviceAcceptanceStatus.Pass
            else -> RemoteDeviceAcceptanceStatus.Incomplete
        }
    return RemoteDeviceAcceptanceStep(
        id = id,
        status = status,
        durationMs = durationMs,
        errorClass =
            when (status) {
                RemoteDeviceAcceptanceStatus.Pass -> null
                RemoteDeviceAcceptanceStatus.Fail -> preflightError
                else -> payloadHealthError ?: payloadHealth?.failureClass ?: ErrorPayloadHealthUnavailable
            },
        pathKind = pathKind,
    )
}

private fun RelayUdpPayloadHealthEvidence.toRedacted(): RedactedRelayUdpPayloadHealth =
    RedactedRelayUdpPayloadHealth(
        measurementKind = measurementKind,
        ceilingLabel = ceilingLabel,
        overheadAssessment = overheadAssessment,
        effectivePathMtuBytes = effectivePathMtuBytes,
        overallVerdict = overallVerdict,
        families =
            families.map { family ->
                RedactedRelayUdpPayloadFamilyHealth(
                    family = family.family,
                    controlBefore = family.controlBefore,
                    controlAfter = family.controlAfter,
                    maxAcknowledgedPayloadBytes = family.maxAcknowledgedPayloadBytes,
                    firstRepeatedFailedPayloadBytes = family.firstRepeatedFailedPayloadBytes,
                    attemptCount = family.attemptCount,
                    verdict = family.verdict,
                    ptbObservation = family.ptbObservation,
                    fragmentationReassembly = family.fragmentationReassembly,
                )
            },
    )

private fun RemoteDeviceAcceptanceUnderlay.toRedacted(): RedactedAcceptanceUnderlay =
    RedactedAcceptanceUnderlay(
        mtuBand = mtuBand,
        hasIpv4Address = hasIpv4Address,
        hasIpv6Address = hasIpv6Address,
        hasIpv4DefaultRoute = hasIpv4DefaultRoute,
        hasIpv6DefaultRoute = hasIpv6DefaultRoute,
        hasIpv4Dns = hasIpv4Dns,
        hasIpv6Dns = hasIpv6Dns,
        nat64Advertised = nat64Advertised,
        nat64Reachability = nat64Reachability,
    )

internal fun captureRemoteDeviceAcceptanceDevice(): RemoteDeviceAcceptanceDevice =
    RemoteDeviceAcceptanceDevice(
        model = sanitizeDeviceField(Build.MODEL),
        csc = readSalesCode(),
        api = Build.VERSION.SDK_INT,
        abi = sanitizeDeviceField(Build.SUPPORTED_ABIS.firstOrNull()),
    )

internal fun sanitizeTransportKind(value: String?): String =
    value?.takeIf { it.matches(Regex("[a-z0-9_]{1,32}")) } ?: "unknown"

private fun sanitizeDeviceField(value: String?): String =
    value
        ?.trim()
        ?.takeIf {
            it.length in 1..MaxDeviceFieldLength &&
                it.all { char -> char.isLetterOrDigit() || char in " -_." }
        }
        ?: "unknown"

private fun readSalesCode(): String {
    val getter =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
        }.getOrNull() ?: return "unknown"
    return SalesCodeProperties
        .firstNotNullOfOrNull { property ->
            runCatching { getter.invoke(null, property, "") as? String }
                .getOrNull()
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.matches(Regex("[A-Z0-9]{3}")) }
        } ?: "unknown"
}

private val RemoteDeviceAcceptanceReportJson =
    Json(RipDpiPrettyContractJson) {
        explicitNulls = true
    }

internal const val StepRealityTcp = "reality_tcp"
internal const val StepUdpAssociate = "socks_udp_associate"
internal const val StepDnsUdp = "dns_udp"
internal const val StepRelayUdpPayload = "relay_egress_udp_payload"
internal const val StepIpv4 = "ipv4_reality_egress"
internal const val StepIpv6 = "ipv6_reality_egress"
internal const val StepReconnect = "reconnect"
internal const val StepHandover = "wifi_mobile_handover"
internal const val StepScreenOff = "screen_off_survival"
internal const val ErrorServiceNotRunning = "service_not_running"
internal const val ErrorTransportMismatch = "transport_mismatch"
internal const val ErrorListenerUnavailable = "local_listener_unavailable"
internal const val ErrorProbe = "probe_error"
internal const val ErrorIpv4Egress = "ipv4_egress_failed"
internal const val ErrorIpv6Egress = "ipv6_egress_failed"
internal const val ErrorPayloadHealthUnavailable = "payload_health_unavailable"
internal const val ErrorPayloadHealthContextDrift = "payload_health_context_drift"
internal const val ErrorDirectEgress = "direct_egress_observed"
internal const val ErrorPostActionProbe = "post_action_probe_failed"
internal const val ErrorPostActionProbeInconclusive = "post_action_probe_inconclusive"
internal const val RelayPathKind = "relay_path"
private const val MaxDeviceFieldLength = 64
private val SalesCodeProperties = listOf("ro.boot.sales_code", "ril.sales_code", "ro.csc.sales_code")
private val acceptanceStepIds =
    listOf(
        StepRealityTcp,
        StepUdpAssociate,
        StepDnsUdp,
        StepRelayUdpPayload,
        StepIpv4,
        StepIpv6,
        StepReconnect,
        StepHandover,
        StepScreenOff,
        StepNoDirectEgress,
    )
private val acceptanceDataPlaneStepIds =
    setOf(
        StepRealityTcp,
        StepUdpAssociate,
        StepDnsUdp,
        StepRelayUdpPayload,
        StepIpv4,
        StepIpv6,
        StepNoDirectEgress,
    )
