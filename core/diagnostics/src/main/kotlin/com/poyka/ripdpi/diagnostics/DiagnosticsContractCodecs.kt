package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ProbeFamily
import com.poyka.ripdpi.diagnostics.domain.ProbeTask
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsExecutionPolicyProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.json.Json

internal fun Json.decodeProfileSpecWire(payload: String): ProfileSpecWire =
    decodeFromString(ProfileSpecWire.serializer(), payload).backfillLegacyProbePersistencePolicy()

internal fun Json.decodeEngineScanReportWire(payload: String): EngineScanReportWire =
    decodeFromString(EngineScanReportWire.serializer(), payload)

internal fun Json.decodeEngineProgressWire(payload: String): EngineProgressWire =
    decodeFromString(EngineProgressWire.serializer(), payload)

internal fun ProfileSpecWire.normalizedExecutionPolicy(): ProfileExecutionPolicyWire =
    requireNotNull(executionPolicy) {
        "Diagnostics profile '$profileId' is missing executionPolicy"
    }.also { policy ->
        requireNotNull(policy.probePersistencePolicy) {
            "Diagnostics profile '$profileId' is missing executionPolicy.probePersistencePolicy"
        }
    }

internal fun ProbePersistencePolicyWire.toDomainPolicy(): ProbePersistencePolicy =
    when (this) {
        ProbePersistencePolicyWire.MANUAL_ONLY -> ProbePersistencePolicy.MANUAL_ONLY
        ProbePersistencePolicyWire.BACKGROUND_ONLY -> ProbePersistencePolicy.BACKGROUND_ONLY
        ProbePersistencePolicyWire.ALWAYS -> ProbePersistencePolicy.ALWAYS
    }

internal fun ProfileExecutionPolicyWire.normalizedProbePersistencePolicy(): ProbePersistencePolicyWire =
    requireNotNull(probePersistencePolicy) {
        "Probe persistence policy must be normalized before use"
    }

private fun ProfileSpecWire.backfillLegacyProbePersistencePolicy(): ProfileSpecWire {
    val policy = executionPolicy ?: return this
    if (policy.probePersistencePolicy != null) {
        return this
    }
    return copy(
        executionPolicy =
            policy.copy(
                probePersistencePolicy = policy.legacyProbePersistencePolicy(),
            ),
    )
}

private fun ProfileExecutionPolicyWire.legacyProbePersistencePolicy(): ProbePersistencePolicyWire =
    if (allowBackground) {
        ProbePersistencePolicyWire.BACKGROUND_ONLY
    } else {
        ProbePersistencePolicyWire.MANUAL_ONLY
    }

internal fun ProbeTask.toEngineProbeTaskWire(): EngineProbeTaskWire =
    EngineProbeTaskWire(
        family =
            when (family) {
                ProbeFamily.DNS -> EngineProbeTaskFamily.DNS
                ProbeFamily.WEB -> EngineProbeTaskFamily.WEB
                ProbeFamily.QUIC -> EngineProbeTaskFamily.QUIC
                ProbeFamily.TCP -> EngineProbeTaskFamily.TCP
                ProbeFamily.SERVICE -> EngineProbeTaskFamily.SERVICE
                ProbeFamily.CIRCUMVENTION -> EngineProbeTaskFamily.CIRCUMVENTION
                ProbeFamily.TELEGRAM -> EngineProbeTaskFamily.TELEGRAM
                ProbeFamily.THROUGHPUT -> EngineProbeTaskFamily.THROUGHPUT
            },
        targetId = targetId,
        label = label,
    )

internal fun DiagnosticsIntent.toProfileProjection(): DiagnosticsProfileProjection =
    DiagnosticsProfileProjection(
        kind = kind,
        family = family,
        intentBucket = intentBucket,
        legalSafety = legalSafety,
        regionTag = regionTag,
        executionPolicy =
            DiagnosticsExecutionPolicyProjection(
                manualOnly = executionPolicy.manualOnly,
                allowBackground = executionPolicy.allowBackground,
                requiresRawPath = executionPolicy.requiresRawPath,
                probePersistencePolicy = executionPolicy.probePersistencePolicy,
            ),
        manualOnly = executionPolicy.manualOnly,
        packRefs = packRefs,
        strategyProbeSuiteId = strategyProbe?.suiteId,
    )

internal fun ProfileSpecWire.toProfileProjection(): DiagnosticsProfileProjection =
    run {
        val executionPolicy = normalizedExecutionPolicy()
        DiagnosticsProfileProjection(
            kind = kind,
            family = family,
            intentBucket = intentBucket,
            legalSafety = legalSafety,
            regionTag = regionTag,
            executionPolicy =
                DiagnosticsExecutionPolicyProjection(
                    manualOnly = executionPolicy.manualOnly,
                    allowBackground = executionPolicy.allowBackground,
                    requiresRawPath = executionPolicy.requiresRawPath,
                    probePersistencePolicy = executionPolicy.normalizedProbePersistencePolicy().toDomainPolicy(),
                ),
            manualOnly = executionPolicy.manualOnly,
            packRefs = packRefs,
            strategyProbeSuiteId = strategyProbe?.suiteId,
        )
    }

internal fun ScanPlan.toEngineScanRequestWire(): EngineScanRequestWire =
    EngineScanRequestWire(
        profileId = intent.profileId,
        displayName = intent.displayName,
        pathMode = context.pathMode,
        kind = intent.kind,
        family = intent.family,
        regionTag = intent.regionTag,
        packRefs = intent.packRefs,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        probeTasks = probeTasks.map(ProbeTask::toEngineProbeTaskWire),
        domainTargets = domainTargets,
        dnsTargets = dnsTargets,
        tcpTargets = intent.tcpTargets,
        quicTargets = quicTargets,
        serviceTargets = intent.serviceTargets,
        circumventionTargets = intent.circumventionTargets,
        throughputTargets = throughputTargets,
        whitelistSni = intent.whitelistSni,
        telegramTarget = intent.telegramTarget,
        strategyProbe = intent.strategyProbe,
        networkSnapshot = context.networkSnapshot,
        routeProbe = routeProbe,
        nativeLogLevel = diagnosticsNativeLogLevel(context.pathMode),
    )

private fun diagnosticsNativeLogLevel(pathMode: ScanPathMode): String =
    when (pathMode) {
        ScanPathMode.RAW_PATH -> "debug"
        ScanPathMode.IN_PATH -> "info"
    }

internal fun EngineProbeResultWire.toProbeResult(): ProbeResult =
    ProbeResult(
        probeType = probeType,
        target = target,
        outcome = outcome,
        details = details,
        probeRetryCount = probeRetryCount,
    )

internal fun ProbeResult.toEngineProbeResultWire(): EngineProbeResultWire =
    EngineProbeResultWire(
        probeType = probeType,
        target = target,
        outcome = outcome,
        details = details,
        probeRetryCount = probeRetryCount,
    )

internal fun EngineScanReportWire.toSessionProjection(): DiagnosticsSessionProjection =
    DiagnosticsSessionProjection(
        results = results.map(EngineProbeResultWire::toProbeResult),
        resolverRecommendation = resolverRecommendation,
        strategyRecommendation = strategyRecommendation,
        directModeVerdict = directModeVerdict,
        strategyProbeReport = strategyProbeReport,
        observations = observations,
        engineAnalysisVersion = engineAnalysisVersion,
        diagnoses = diagnoses,
        classifierVersion = classifierVersion,
        packVersions = packVersions,
    )

internal fun EngineScanReportWire.toScanReport(): ScanReport =
    ScanReport(
        sessionId = sessionId,
        profileId = profileId,
        pathMode = pathMode,
        startedAt = startedAt,
        finishedAt = finishedAt,
        summary = summary,
        results = results.map(EngineProbeResultWire::toProbeResult),
        resolverRecommendation = resolverRecommendation,
        strategyRecommendation = strategyRecommendation,
        directModeVerdict = directModeVerdict,
        strategyProbeReport = strategyProbeReport,
        observations = observations,
        engineAnalysisVersion = engineAnalysisVersion,
        diagnoses = diagnoses,
        classifierVersion = classifierVersion,
        packVersions = packVersions,
    )

internal fun ScanReport.toEngineScanReportWire(): EngineScanReportWire =
    EngineScanReportWire(
        sessionId = sessionId,
        profileId = profileId,
        pathMode = pathMode,
        startedAt = startedAt,
        finishedAt = finishedAt,
        summary = summary,
        results = results.map(ProbeResult::toEngineProbeResultWire),
        resolverRecommendation = resolverRecommendation,
        strategyRecommendation = strategyRecommendation,
        directModeVerdict = directModeVerdict,
        strategyProbeReport = strategyProbeReport,
        observations = observations,
        engineAnalysisVersion = engineAnalysisVersion,
        diagnoses = diagnoses,
        classifierVersion = classifierVersion,
        packVersions = packVersions,
    )

internal fun ScanProgress.toEngineProgressWire(): EngineProgressWire =
    EngineProgressWire(
        sessionId = sessionId,
        phase = phase,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        message = message,
        isFinished = isFinished,
        latestProbeTarget = latestProbeTarget,
        latestProbeOutcome = latestProbeOutcome,
        strategyProbeProgress = strategyProbeProgress,
    )

internal fun EngineProgressWire.toScanProgress(): ScanProgress =
    ScanProgress(
        sessionId = sessionId,
        phase = phase,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        message = message,
        isFinished = isFinished,
        latestProbeTarget = latestProbeTarget,
        latestProbeOutcome = latestProbeOutcome,
        strategyProbeProgress = strategyProbeProgress,
    )
