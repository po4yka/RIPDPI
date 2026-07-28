package com.poyka.ripdpi.services

import android.os.Build
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
    val ipv4Route: Boolean,
    val ipv6Route: Boolean,
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
    val udpAssociationSucceeded =
        probe?.udpSucceeded == true ||
            probe?.udpFailure in
            setOf(
                RelayProbeFailure.UdpWrite.wireValue,
                RelayProbeFailure.UdpReadTimeout.wireValue,
                RelayProbeFailure.DnsResponse.wireValue,
            )
    val steps =
        listOf(
            resultStep(
                StepRealityTcp,
                preflightError == null && probe?.tcpSucceeded == true,
                preflightError ?: probe?.tcpFailure,
                evidence.durationMs,
            ),
            resultStep(
                StepUdpAssociate,
                preflightError == null && udpAssociationSucceeded,
                preflightError ?: probe?.udpFailure,
                evidence.durationMs,
            ),
            resultStep(
                StepDnsUdp,
                preflightError == null && probe?.udpSucceeded == true,
                preflightError ?: probe?.udpFailure,
                evidence.durationMs,
            ),
            resultStep(StepIpv4, evidence.ipv4Route, ErrorIpv4Route, evidence.durationMs),
            resultStep(StepIpv6, evidence.ipv6Route, ErrorIpv6Route, evidence.durationMs),
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
    )
}

internal fun renderRemoteDeviceAcceptanceReport(report: RemoteDeviceAcceptanceReport): String =
    AcceptanceReportJson.encodeToString(
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
                    )
                },
        ),
    )

@Serializable
private data class RedactedAcceptanceReport(
    val format: String = "ripdpi_remote_device_acceptance_v1",
    val device: RedactedAcceptanceDevice,
    val transportKind: String,
    val result: String,
    val steps: List<RedactedAcceptanceStep>,
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
)

internal fun RemoteDeviceAcceptanceReport.acceptanceDataPlanePassed(): Boolean =
    steps
        .filter { it.id in acceptanceDataPlaneStepIds }
        .all { it.status == RemoteDeviceAcceptanceStatus.Pass }

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
): RemoteDeviceAcceptanceStep =
    RemoteDeviceAcceptanceStep(
        id = id,
        status = if (succeeded) RemoteDeviceAcceptanceStatus.Pass else RemoteDeviceAcceptanceStatus.Fail,
        durationMs = durationMs,
        errorClass = if (succeeded) null else failure,
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

internal const val StepRealityTcp = "reality_tcp"
internal const val StepUdpAssociate = "socks_udp_associate"
internal const val StepDnsUdp = "dns_udp"
internal const val StepIpv4 = "ipv4_vpn_route"
internal const val StepIpv6 = "ipv6_vpn_route"
internal const val StepReconnect = "reconnect"
internal const val StepHandover = "wifi_mobile_handover"
internal const val StepScreenOff = "screen_off_survival"
internal const val ErrorServiceNotRunning = "service_not_running"
internal const val ErrorTransportMismatch = "transport_mismatch"
internal const val ErrorListenerUnavailable = "local_listener_unavailable"
internal const val ErrorProbe = "probe_error"
internal const val ErrorIpv4Route = "ipv4_route_missing"
internal const val ErrorIpv6Route = "ipv6_route_missing"
internal const val ErrorDirectEgress = "direct_egress_observed"
internal const val ErrorPostActionProbe = "post_action_probe_failed"
private const val MaxDeviceFieldLength = 64
private val SalesCodeProperties = listOf("ro.boot.sales_code", "ril.sales_code", "ro.csc.sales_code")
private val acceptanceStepIds =
    listOf(
        StepRealityTcp,
        StepUdpAssociate,
        StepDnsUdp,
        StepIpv4,
        StepIpv6,
        StepReconnect,
        StepHandover,
        StepScreenOff,
        StepNoDirectEgress,
    )
private val acceptanceDataPlaneStepIds =
    setOf(StepRealityTcp, StepUdpAssociate, StepDnsUdp, StepIpv4, StepIpv6, StepNoDirectEgress)
private val AcceptanceReportJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
