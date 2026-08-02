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
    val applicability: String = AcceptanceProbeApplicability.Required.wireValue,
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
    val recoveryReceipt: RemoteDeviceRecoveryReceipt = RemoteDeviceRecoveryReceipt(),
    val uidPolicyQualification: RemoteDeviceUidPolicyQualification = RemoteDeviceUidPolicyQualification(),
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
    val appliedTunnelMtuBytes: Int? = null,
    val appliedEncapsulationBudgetBytes: Int? = null,
    val appliedTunnelMetered: Boolean? = null,
    val appliedTunnelEgress: String = "unavailable",
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
    val pathPolicyAssessment: AcceptancePathPolicyAssessment,
    val durationMs: Long,
    val probePlan: AcceptanceTransportProbePlan = acceptanceTransportProbePlan(transportKind),
    val awgRuntimeHealthy: Boolean? = null,
)

internal enum class AcceptancePathPolicyAssessment {
    Consistent,
    Inconsistent,
    Inconclusive,
    Unavailable,
}

internal fun renderRemoteDeviceAcceptanceReport(report: RemoteDeviceAcceptanceReport): String =
    RemoteDeviceAcceptanceReportJson.encodeToString(
        report
            .withRecoveryReceipt(report.recoveryReceipt)
            .withUidPolicyQualification(report.uidPolicyQualification)
            .toRedactedPayload(),
    )

private fun RemoteDeviceAcceptanceReport.toRedactedPayload(): RedactedAcceptanceReport =
    RedactedAcceptanceReport(
        device =
            RedactedAcceptanceDevice(
                modelFamily = device.model.redactedModelFamily(),
                cscStatus = device.csc.redactedAvailability(),
                api = device.api,
                abiFamily = device.abi.redactedAbiFamily(),
            ),
        transportKind = transportKind,
        result = status.wireValue,
        steps =
            steps.map { step ->
                RedactedAcceptanceStep(
                    id = step.id,
                    status = step.status.wireValue,
                    durationMs = step.durationMs,
                    errorClass = step.errorClass,
                    pathKind = step.pathKind,
                    applicability = step.applicability,
                )
            },
        pathHealth = pathHealth?.toRedacted(),
        underlay = underlay.toRedacted(),
        recoveryReceipt = recoveryReceipt.toRedacted(),
        uidPolicyQualification = uidPolicyQualification.toRedacted(),
    )

@Serializable
private data class RedactedAcceptanceReport(
    val format: String = "ripdpi_remote_device_acceptance_v2",
    val device: RedactedAcceptanceDevice,
    val transportKind: String,
    val result: String,
    val steps: List<RedactedAcceptanceStep>,
    val underlay: RedactedAcceptanceUnderlay,
    val recoveryReceipt: RedactedRecoveryReceipt,
    val uidPolicyQualification: RedactedUidPolicyQualification,
    val pathHealth: RedactedRelayUdpPayloadHealth? = null,
)

@Serializable
private data class RedactedRecoveryReceipt(
    val persistenceAvailability: String,
    val generation: String,
    val startOrigin: String,
    val userUnlocked: String,
    val alwaysOn: String,
    val lockdown: String,
    val serviceInstanceChanged: String,
    val timeToForegroundService: String,
    val timeToTun: String,
    val timeToFirstFlow: String,
    val postStartDataPlaneOutcome: String,
)

@Serializable
private data class RedactedUidPolicyQualification(
    val kernelMajorMinorBand: String,
    val unprivilegedBindToDevice: String,
    val uidPolicyEligible: Boolean,
    val uidPolicyArmed: Boolean,
    val uidResolvedCount: Long,
    val uidUnresolvedCount: Long,
    val policyDecisionDeniedTcpCount: Long,
    val policyDecisionDeniedUdpCount: Long,
    val policyDecisionDeniedOtherCount: Long,
)

@Serializable
private data class RedactedAcceptanceDevice(
    val modelFamily: String,
    val cscStatus: String,
    val api: Int,
    val abiFamily: String,
)

private fun String.redactedModelFamily(): String =
    when {
        isBlank() || equals("unknown", ignoreCase = true) -> "unknown"
        else -> "other_android_family"
    }

private fun String.redactedAvailability(): String =
    if (isBlank() || equals("unavailable", ignoreCase = true)) {
        "unavailable"
    } else {
        "available"
    }

private fun String.redactedAbiFamily(): String =
    when {
        contains("arm64", ignoreCase = true) -> "arm64"
        contains("armeabi", ignoreCase = true) -> "arm32"
        contains("x86_64", ignoreCase = true) -> "x86_64"
        contains("x86", ignoreCase = true) -> "x86"
        else -> "unknown"
    }

@Serializable
private data class RedactedAcceptanceStep(
    val id: String,
    val status: String,
    val durationMs: Long? = null,
    val errorClass: String? = null,
    val pathKind: String? = null,
    val applicability: String,
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
    val appliedTunnelMtuBytes: Int? = null,
    val appliedEncapsulationBudgetBytes: Int? = null,
    val appliedTunnelMetered: Boolean? = null,
    val appliedTunnelEgress: String,
)

private fun RemoteDeviceRecoveryReceipt.toRedacted(): RedactedRecoveryReceipt =
    privacySafe().let { safe ->
        RedactedRecoveryReceipt(
            persistenceAvailability = safe.persistenceAvailability,
            generation = safe.generation,
            startOrigin = safe.startOrigin,
            userUnlocked = safe.userUnlocked,
            alwaysOn = safe.alwaysOn,
            lockdown = safe.lockdown,
            serviceInstanceChanged = safe.serviceInstanceChanged,
            timeToForegroundService = safe.timeToForegroundService,
            timeToTun = safe.timeToTun,
            timeToFirstFlow = safe.timeToFirstFlow,
            postStartDataPlaneOutcome = safe.postStartDataPlaneOutcome,
        )
    }

private fun RemoteDeviceUidPolicyQualification.toRedacted(): RedactedUidPolicyQualification =
    privacySafe().let { safe ->
        RedactedUidPolicyQualification(
            kernelMajorMinorBand = safe.kernelMajorMinorBand,
            unprivilegedBindToDevice = safe.unprivilegedBindToDevice,
            uidPolicyEligible = safe.uidPolicyEligible,
            uidPolicyArmed = safe.uidPolicyArmed,
            uidResolvedCount = safe.uidResolvedCount,
            uidUnresolvedCount = safe.uidUnresolvedCount,
            policyDecisionDeniedTcpCount = safe.policyDecisionDeniedTcpCount,
            policyDecisionDeniedUdpCount = safe.policyDecisionDeniedUdpCount,
            policyDecisionDeniedOtherCount = safe.policyDecisionDeniedOtherCount,
        )
    }

internal fun RemoteDeviceAcceptanceReport.acceptanceDataPlaneStatus(): RemoteDeviceAcceptanceStatus {
    val dataPlaneSteps =
        steps.filter {
            it.id in acceptanceDataPlaneStepIds &&
                it.applicability != AcceptanceProbeApplicability.NotApplicable.wireValue
        }
    return when {
        dataPlaneSteps.any { it.status == RemoteDeviceAcceptanceStatus.Fail } -> RemoteDeviceAcceptanceStatus.Fail
        dataPlaneSteps.all { it.status == RemoteDeviceAcceptanceStatus.Pass } -> RemoteDeviceAcceptanceStatus.Pass
        else -> RemoteDeviceAcceptanceStatus.Incomplete
    }
}

internal fun RemoteDeviceAcceptanceReport.acceptanceDataPlanePassed(): Boolean =
    acceptanceDataPlaneStatus() == RemoteDeviceAcceptanceStatus.Pass

internal fun RemoteDeviceAcceptanceReport.withUidPolicyQualification(
    qualification: RemoteDeviceUidPolicyQualification,
): RemoteDeviceAcceptanceReport {
    val safe = qualification.privacySafe()
    val qualificationStep = safe.toAcceptanceStep()
    val nextSteps =
        if (steps.any { step -> step.id == StepUidPolicyQualification }) {
            steps.map { step ->
                if (step.id == StepUidPolicyQualification) qualificationStep else step
            }
        } else {
            steps + qualificationStep
        }
    return copy(
        status =
            when (status) {
                RemoteDeviceAcceptanceStatus.Idle,
                RemoteDeviceAcceptanceStatus.Running,
                -> status

                else -> deriveAcceptanceStatus(nextSteps)
            },
        steps = nextSteps,
        uidPolicyQualification = safe,
    )
}

internal fun RemoteDeviceAcceptanceReport.withRecoveryReceipt(
    receipt: RemoteDeviceRecoveryReceipt,
): RemoteDeviceAcceptanceReport {
    val safe = receipt.privacySafe()
    val nextSteps =
        when (safe.persistenceAvailability) {
            RemoteDeviceRecoveryReceiptPersistenceAvailability.Available.wireValue -> {
                steps.upsert(
                    RemoteDeviceAcceptanceStep(
                        id = StepRecoveryReceiptPersistence,
                        status = RemoteDeviceAcceptanceStatus.Pass,
                    ),
                )
            }

            RemoteDeviceRecoveryReceiptPersistenceAvailability.DeviceProtectedStorageUnavailable.wireValue -> {
                steps.upsert(
                    RemoteDeviceAcceptanceStep(
                        id = StepRecoveryReceiptPersistence,
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        errorClass = ErrorRecoveryReceiptPersistenceUnavailable,
                    ),
                )
            }

            RemoteDeviceRecoveryReceiptPersistenceAvailability.WriteFailed.wireValue -> {
                steps.upsert(
                    RemoteDeviceAcceptanceStep(
                        id = StepRecoveryReceiptPersistence,
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        errorClass = ErrorRecoveryReceiptPersistenceWriteFailed,
                    ),
                )
            }

            else -> {
                steps
            }
        }
    return copy(
        status =
            when (status) {
                RemoteDeviceAcceptanceStatus.Idle,
                RemoteDeviceAcceptanceStatus.Running,
                -> status

                else -> deriveAcceptanceStatus(nextSteps)
            },
        steps = nextSteps,
        recoveryReceipt = safe,
    )
}

private fun List<RemoteDeviceAcceptanceStep>.upsert(
    replacement: RemoteDeviceAcceptanceStep,
): List<RemoteDeviceAcceptanceStep> =
    if (any { step -> step.id == replacement.id }) {
        map { step -> if (step.id == replacement.id) replacement else step }
    } else {
        this + replacement
    }

private fun RemoteDeviceUidPolicyQualification.toAcceptanceStep(): RemoteDeviceAcceptanceStep {
    val errorClass =
        when {
            unprivilegedBindToDevice == BindToDeviceProbeOutcome.BridgeFailure.wireValue -> {
                ErrorUidPolicyBridgeFailure
            }

            !uidPolicyEligible -> {
                ErrorUidPolicyIneligible
            }

            !uidPolicyArmed -> {
                ErrorUidPolicyNotArmed
            }

            else -> {
                null
            }
        }
    return RemoteDeviceAcceptanceStep(
        id = StepUidPolicyQualification,
        status =
            if (errorClass == null) {
                RemoteDeviceAcceptanceStatus.Pass
            } else {
                RemoteDeviceAcceptanceStatus.Incomplete
            },
        errorClass = errorClass,
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
        appliedTunnelMtuBytes = appliedTunnelMtuBytes,
        appliedEncapsulationBudgetBytes = appliedEncapsulationBudgetBytes,
        appliedTunnelMetered = appliedTunnelMetered,
        appliedTunnelEgress = appliedTunnelEgress,
    )

internal fun captureRemoteDeviceAcceptanceDevice(): RemoteDeviceAcceptanceDevice =
    RemoteDeviceAcceptanceDevice(
        model = sanitizeDeviceField(Build.MODEL),
        csc = UnavailableVendorPolicyMembership,
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

private val RemoteDeviceAcceptanceReportJson =
    Json(RipDpiPrettyContractJson) {
        explicitNulls = true
    }

private const val MaxDeviceFieldLength = 64
private const val UnavailableVendorPolicyMembership = "unavailable"
