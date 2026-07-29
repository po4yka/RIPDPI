package com.poyka.ripdpi.services

internal fun buildRemoteDeviceAcceptanceBaseline(
    device: RemoteDeviceAcceptanceDevice = captureRemoteDeviceAcceptanceDevice(),
    evidence: AcceptanceBaselineEvidence,
): RemoteDeviceAcceptanceReport {
    val preflightError =
        when {
            !evidence.serviceRunning -> {
                ErrorServiceNotRunning
            }

            evidence.probePlan.requiresRelayListener && !evidence.listenerAvailable -> {
                ErrorListenerUnavailable
            }

            evidence.probePlan.requiresRelayListener && evidence.probe == null && evidence.contextError == null -> {
                ErrorProbe
            }

            else -> {
                null
            }
        }
    val steps = evidence.toAcceptanceSteps(preflightError)
    return RemoteDeviceAcceptanceReport(
        status = deriveAcceptanceStatus(steps),
        device = device,
        transportKind = evidence.transportKind,
        steps = steps,
        pathHealth = evidence.payloadHealth,
        underlay = evidence.underlay,
    )
}

private fun AcceptanceBaselineEvidence.toAcceptanceSteps(preflightError: String?): List<RemoteDeviceAcceptanceStep> {
    val probe = probe
    return relayProbeSteps(probe, preflightError) + deferredRuntimeSteps()
}

private fun AcceptanceBaselineEvidence.relayProbeSteps(
    probe: RelayCapabilityProbeEvidence?,
    preflightError: String?,
): List<RemoteDeviceAcceptanceStep> =
    listOf(
        networkEvidenceStep(
            StepRealityTcp,
            applicability = probePlan.relayTcp,
            preflightError = preflightError,
            contextError = contextError,
            succeeded = probe?.tcpSucceeded == true,
            failure = probe?.tcpFailure,
            durationMs,
            pathKind = RelayPathKind,
        ),
        networkEvidenceStep(
            StepUdpAssociate,
            applicability = probePlan.relayUdp,
            preflightError = preflightError,
            contextError = contextError,
            succeeded = probe?.udpAssociationOpened == true,
            failure = probe?.udpFailure,
            durationMs,
            pathKind = RelayPathKind,
        ),
        networkEvidenceStep(
            StepDnsUdp,
            applicability = probePlan.relayUdp,
            preflightError = preflightError,
            contextError = contextError,
            succeeded = probe?.udpSucceeded == true,
            failure = probe?.udpFailure,
            durationMs,
            pathKind = RelayPathKind,
        ),
        payloadHealthStep(
            StepRelayUdpPayload,
            applicability = probePlan.relayUdpPayload,
            preflightError = preflightError,
            payloadHealth = payloadHealth,
            payloadHealthError = contextError ?: payloadHealthError,
            durationMs,
            pathKind = RelayPathKind,
        ),
        networkEvidenceStep(
            StepIpv4,
            applicability = probePlan.relayTcp,
            preflightError = preflightError,
            contextError = contextError,
            succeeded = ipv4Probe?.tcpSucceeded == true,
            failure = ipv4Probe?.tcpFailure ?: ErrorIpv4Egress,
            durationMs,
            pathKind = RelayPathKind,
        ),
        networkEvidenceStep(
            StepIpv6,
            applicability = probePlan.relayTcp,
            preflightError = preflightError,
            contextError = contextError,
            succeeded = ipv6Probe?.tcpSucceeded == true,
            failure = ipv6Probe?.tcpFailure ?: ErrorIpv6Egress,
            durationMs,
            pathKind = RelayPathKind,
        ),
    )

private fun AcceptanceBaselineEvidence.deferredRuntimeSteps(): List<RemoteDeviceAcceptanceStep> =
    listOf(
        pendingStep(StepReconnect),
        pendingStep(StepHandover),
        pendingStep(StepScreenOff),
        pendingStep(StepUidPolicyQualification),
        applicabilityStep(
            id = StepAmneziaWgRuntime,
            applicability = probePlan.awgRuntime,
            succeeded = awgRuntimeHealthy,
            durationMs = durationMs,
        ),
        pathPolicyConsistencyStep(pathPolicyAssessment, durationMs),
    )

private fun pathPolicyConsistencyStep(
    assessment: AcceptancePathPolicyAssessment,
    durationMs: Long,
): RemoteDeviceAcceptanceStep =
    when (assessment) {
        AcceptancePathPolicyAssessment.Consistent -> {
            resultStep(StepPathPolicyConsistency, succeeded = true, failure = null, durationMs)
        }

        AcceptancePathPolicyAssessment.Inconsistent -> {
            resultStep(StepPathPolicyConsistency, succeeded = false, ErrorPathPolicyInconsistent, durationMs)
        }

        AcceptancePathPolicyAssessment.Inconclusive -> {
            incompleteStep(StepPathPolicyConsistency, ErrorPathPolicyInconclusive, durationMs)
        }

        AcceptancePathPolicyAssessment.Unavailable -> {
            incompleteStep(StepPathPolicyConsistency, ErrorPathPolicyContextUnavailable, durationMs)
        }
    }

internal fun deriveAcceptanceStatus(steps: List<RemoteDeviceAcceptanceStep>): RemoteDeviceAcceptanceStatus =
    steps.filterNot { it.applicability == AcceptanceProbeApplicability.NotApplicable.wireValue }.let { applicable ->
        when {
            applicable.any { it.status == RemoteDeviceAcceptanceStatus.Fail } -> {
                RemoteDeviceAcceptanceStatus.Fail
            }

            applicable.isNotEmpty() && applicable.all { it.status == RemoteDeviceAcceptanceStatus.Pass } -> {
                RemoteDeviceAcceptanceStatus.Pass
            }

            else -> {
                RemoteDeviceAcceptanceStatus.Incomplete
            }
        }
    }

internal fun pendingStep(id: String): RemoteDeviceAcceptanceStep =
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

private fun incompleteStep(
    id: String,
    errorClass: String,
    durationMs: Long,
): RemoteDeviceAcceptanceStep =
    RemoteDeviceAcceptanceStep(
        id = id,
        status = RemoteDeviceAcceptanceStatus.Incomplete,
        durationMs = durationMs,
        errorClass = errorClass,
    )

private fun networkEvidenceStep(
    id: String,
    applicability: AcceptanceProbeApplicability,
    preflightError: String?,
    contextError: String?,
    succeeded: Boolean,
    failure: String?,
    durationMs: Long,
    pathKind: String? = null,
): RemoteDeviceAcceptanceStep {
    if (applicability != AcceptanceProbeApplicability.Required) {
        return nonRequiredProbeStep(id, applicability, durationMs, pathKind)
    }
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
        applicability = applicability.wireValue,
    )
}

private fun payloadHealthStep(
    id: String,
    applicability: AcceptanceProbeApplicability,
    preflightError: String?,
    payloadHealth: RelayUdpPayloadHealthEvidence?,
    payloadHealthError: String?,
    durationMs: Long,
    pathKind: String? = null,
): RemoteDeviceAcceptanceStep {
    if (applicability != AcceptanceProbeApplicability.Required) {
        return nonRequiredProbeStep(id, applicability, durationMs, pathKind)
    }
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
        applicability = applicability.wireValue,
    )
}

private fun nonRequiredProbeStep(
    id: String,
    applicability: AcceptanceProbeApplicability,
    durationMs: Long,
    pathKind: String?,
): RemoteDeviceAcceptanceStep =
    RemoteDeviceAcceptanceStep(
        id = id,
        status = RemoteDeviceAcceptanceStatus.Incomplete,
        durationMs = durationMs,
        errorClass =
            when (applicability) {
                AcceptanceProbeApplicability.NotApplicable -> ErrorProbeNotApplicable
                AcceptanceProbeApplicability.Unavailable -> ErrorRuntimeContextUnavailable
                AcceptanceProbeApplicability.Required -> null
            },
        pathKind = pathKind,
        applicability = applicability.wireValue,
    )

private fun applicabilityStep(
    id: String,
    applicability: AcceptanceProbeApplicability,
    succeeded: Boolean?,
    durationMs: Long,
): RemoteDeviceAcceptanceStep =
    when (applicability) {
        AcceptanceProbeApplicability.Required -> {
            RemoteDeviceAcceptanceStep(
                id = id,
                status =
                    when (succeeded) {
                        true -> RemoteDeviceAcceptanceStatus.Pass
                        false -> RemoteDeviceAcceptanceStatus.Fail
                        null -> RemoteDeviceAcceptanceStatus.Incomplete
                    },
                durationMs = durationMs,
                errorClass =
                    when (succeeded) {
                        true -> null
                        false -> ErrorAmneziaWgRuntimeUnhealthy
                        null -> ErrorRuntimeContextUnavailable
                    },
                applicability = applicability.wireValue,
            )
        }

        else -> {
            nonRequiredProbeStep(id, applicability, durationMs, pathKind = null)
        }
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
internal const val StepRecoveryReceiptPersistence = "recovery_receipt_persistence"
internal const val StepUidPolicyQualification = "uid_policy_qualification"
internal const val StepAmneziaWgRuntime = "amneziawg_runtime"
internal const val ErrorServiceNotRunning = "service_not_running"
internal const val ErrorTransportMismatch = "transport_mismatch"
internal const val ErrorListenerUnavailable = "local_listener_unavailable"
internal const val ErrorProbe = "probe_error"
internal const val ErrorIpv4Egress = "ipv4_egress_failed"
internal const val ErrorIpv6Egress = "ipv6_egress_failed"
internal const val ErrorPayloadHealthUnavailable = "payload_health_unavailable"
internal const val ErrorPayloadHealthContextDrift = "payload_health_context_drift"
internal const val ErrorPathPolicyInconsistent = "path_policy_inconsistent"
internal const val ErrorPathPolicyInconclusive = "path_policy_inconclusive"
internal const val ErrorPathPolicyContextUnavailable = "path_policy_context_unavailable"
internal const val ErrorPostActionProbe = "post_action_probe_failed"
internal const val ErrorPostActionProbeInconclusive = "post_action_probe_inconclusive"
internal const val ErrorRecoveryReceiptPersistenceUnavailable = "recovery_receipt_persistence_unavailable"
internal const val ErrorRecoveryReceiptPersistenceWriteFailed = "recovery_receipt_persistence_write_failed"
internal const val ErrorUidPolicyBridgeFailure = "uid_policy_bridge_failure"
internal const val ErrorUidPolicyIneligible = "uid_policy_ineligible"
internal const val ErrorUidPolicyNotArmed = "uid_policy_not_armed"
internal const val ErrorProbeNotApplicable = "probe_not_applicable"
internal const val ErrorRuntimeContextUnavailable = "runtime_context_unavailable"
internal const val ErrorRemoteAcceptanceProbeTargetMissing = "missing_probe_target"
internal const val ErrorAmneziaWgRuntimeUnhealthy = "amneziawg_runtime_unhealthy"
internal const val RelayPathKind = "relay_path"
internal val acceptanceStepIds =
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
        StepRecoveryReceiptPersistence,
        StepUidPolicyQualification,
        StepAmneziaWgRuntime,
        StepPathPolicyConsistency,
    )
internal val acceptanceDataPlaneStepIds =
    setOf(
        StepRealityTcp,
        StepUdpAssociate,
        StepDnsUdp,
        StepRelayUdpPayload,
        StepIpv4,
        StepIpv6,
        StepAmneziaWgRuntime,
        StepPathPolicyConsistency,
    )
