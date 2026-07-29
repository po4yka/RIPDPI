package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.activeDnsSettings
import org.junit.Assert.fail

internal fun FakeNetworkFingerprintProvider.transportSwitchHandoverEvent() =
    PolicyHandoverEvent(
        deliveryId = "delivery-transport-switch",
        mode = com.poyka.ripdpi.data.Mode.VPN,
        currentFingerprintHash = capture().scopeKey(),
        classification = "transport_switch",
        currentNetworkValidated = true,
        currentCaptivePortalDetected = false,
        usedRememberedPolicy = false,
        occurredAt = 10L,
    )

internal fun completeHiddenScan(
    bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
) {
    bridgeFactory.bridge.enqueueProgress(
        ScanProgress(
            sessionId = sessionId,
            phase = "complete",
            completedSteps = 1,
            totalSteps = 1,
            message = "complete",
            isFinished = true,
        ),
    )
    bridgeFactory.bridge.enqueueReport(
        controllerStrategyProbeReport(sessionId = sessionId, settings = settings),
    )
}

internal suspend inline fun <reified T : Throwable> assertControllerSuspendFailsWith(
    noinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw error
    }
    fail("Expected ${T::class.java.simpleName} to be thrown")
    throw AssertionError("Unreachable")
}

internal fun DiagnosticsManualScanStartResult.startedSessionId(): String =
    when (this) {
        is DiagnosticsManualScanStartResult.Started -> {
            sessionId
        }

        is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
            fail("Expected started result but got hidden probe conflict")
            throw AssertionError("Unreachable")
        }
    }

internal fun DiagnosticsManualScanResolution.startedSessionId(): String =
    when (this) {
        is DiagnosticsManualScanResolution.Started -> {
            sessionId
        }

        is DiagnosticsManualScanResolution.Failed -> {
            fail("Expected started resolution but got failure: $reason")
            throw AssertionError("Unreachable")
        }
    }

internal fun FakeDiagnosticsHistoryStores.addAutomaticAuditProfile(json: kotlinx.serialization.json.Json) {
    profilesState.value =
        profilesState.value +
        com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity(
            id = "automatic-audit",
            name = "Automatic audit",
            source = "bundled",
            version = 1,
            requestJson =
                diagnosticsProfileRequestJson(
                    json = json,
                    profileId = "automatic-audit",
                    displayName = "Automatic audit",
                    kind = ScanKind.STRATEGY_PROBE,
                    family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                    targets =
                        DiagnosticsProfileTargets(
                            domainTargets = listOf(DomainTarget(host = "example.org")),
                            quicTargets = listOf(QuicTarget(host = "example.org")),
                            strategyProbe = StrategyProbeRequest(suiteId = "full_matrix_v1"),
                        ),
                    requiresRawPath = true,
                    manualOnly = true,
                ),
            updatedAt = 1L,
        )
}

internal fun controllerStrategyProbeReport(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
) = ScanReport(
    sessionId = sessionId,
    profileId = "automatic-probing",
    pathMode = ScanPathMode.RAW_PATH,
    startedAt = 10L,
    finishedAt = 20L,
    summary = "strategy probe",
    results =
        listOf(
            ProbeResult(
                probeType = "http",
                target = "example.org",
                outcome = "success",
            ),
        ),
    strategyProbeReport =
        StrategyProbeReport(
            suiteId = "quick_v1",
            tcpCandidates =
                listOf(
                    StrategyProbeCandidateSummary(
                        id = "tcp-1",
                        label = "TCP candidate",
                        family = "split",
                        outcome = "success",
                        rationale = "best",
                        succeededTargets = 1,
                        totalTargets = 1,
                        weightedSuccessScore = 10,
                        totalWeight = 10,
                        qualityScore = 10,
                    ),
                ),
            quicCandidates =
                listOf(
                    StrategyProbeCandidateSummary(
                        id = "quic-1",
                        label = "QUIC candidate",
                        family = "quic_burst",
                        outcome = "success",
                        rationale = "best",
                        succeededTargets = 1,
                        totalTargets = 1,
                        weightedSuccessScore = 10,
                        totalWeight = 10,
                        qualityScore = 10,
                    ),
                ),
            recommendation =
                StrategyProbeRecommendation(
                    tcpCandidateId = "tcp-1",
                    tcpCandidateLabel = "TCP candidate",
                    quicCandidateId = "quic-1",
                    quicCandidateLabel = "QUIC candidate",
                    rationale = "best path",
                    recommendedProxyConfigJson =
                        RipDpiProxyUIPreferences
                            .fromSettings(
                                settings,
                                null,
                                null,
                                settings.activeDnsSettings().toRipDpiRuntimeContext(),
                            ).toNativeConfigJson(),
                ),
        ),
)
