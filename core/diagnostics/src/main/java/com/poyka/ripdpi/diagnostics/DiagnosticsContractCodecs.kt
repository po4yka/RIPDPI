package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ProbeFamily
import com.poyka.ripdpi.diagnostics.domain.ProbeTask
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.json.Json

internal fun Json.decodeProfileSpecWireCompat(payload: String): ProfileSpecWire =
    runCatching {
        decodeFromString(ProfileSpecWire.serializer(), payload)
    }.getOrElse {
        decodeFromString(ScanRequest.serializer(), payload).toProfileSpecWire()
    }

internal fun Json.decodeEngineScanReportWireCompat(payload: String): EngineScanReportWire =
    runCatching {
        decodeFromString(EngineScanReportWire.serializer(), payload)
    }.getOrElse {
        decodeFromString(ScanReport.serializer(), payload).toEngineScanReportWire()
    }

internal fun Json.decodeEngineProgressWireCompat(payload: String): EngineProgressWire =
    runCatching {
        decodeFromString(EngineProgressWire.serializer(), payload)
    }.getOrElse {
        decodeFromString(ScanProgress.serializer(), payload).toEngineProgressWire()
    }

internal fun ScanRequest.toProfileSpecWire(): ProfileSpecWire =
    ProfileSpecWire(
        profileId = profileId,
        displayName = displayName,
        kind = kind,
        family = family,
        regionTag = regionTag,
        manualOnly = manualOnly,
        packRefs = packRefs,
        domainTargets = domainTargets,
        dnsTargets = dnsTargets,
        tcpTargets = tcpTargets,
        quicTargets = quicTargets,
        serviceTargets = serviceTargets,
        circumventionTargets = circumventionTargets,
        throughputTargets = throughputTargets,
        whitelistSni = whitelistSni,
        telegramTarget = telegramTarget,
        strategyProbe = strategyProbe,
    )

internal fun ProfileSpecWire.toCompatibilityScanRequest(
    pathMode: ScanPathMode,
    proxyHost: String? = null,
    proxyPort: Int? = null,
    networkSnapshot: com.poyka.ripdpi.data.NativeNetworkSnapshot? = null,
): ScanRequest =
    ScanRequest(
        profileId = profileId,
        displayName = displayName,
        pathMode = pathMode,
        kind = kind,
        family = family,
        regionTag = regionTag,
        manualOnly = manualOnly,
        packRefs = packRefs,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        domainTargets = domainTargets,
        dnsTargets = dnsTargets,
        tcpTargets = tcpTargets,
        quicTargets = quicTargets,
        serviceTargets = serviceTargets,
        circumventionTargets = circumventionTargets,
        throughputTargets = throughputTargets,
        whitelistSni = whitelistSni,
        telegramTarget = telegramTarget,
        strategyProbe = strategyProbe,
        networkSnapshot = networkSnapshot,
    )

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
        regionTag = regionTag,
        manualOnly = executionPolicy.manualOnly,
        packRefs = packRefs,
        strategyProbeSuiteId = strategyProbe?.suiteId,
    )

internal fun ProfileSpecWire.toProfileProjection(): DiagnosticsProfileProjection =
    DiagnosticsProfileProjection(
        kind = kind,
        family = family,
        regionTag = regionTag,
        manualOnly = manualOnly,
        packRefs = packRefs,
        strategyProbeSuiteId = strategyProbe?.suiteId,
    )

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
        domainTargets = intent.domainTargets,
        dnsTargets = dnsTargets,
        tcpTargets = intent.tcpTargets,
        quicTargets = intent.quicTargets,
        serviceTargets = intent.serviceTargets,
        circumventionTargets = intent.circumventionTargets,
        throughputTargets = intent.throughputTargets,
        whitelistSni = intent.whitelistSni,
        telegramTarget = intent.telegramTarget,
        strategyProbe = intent.strategyProbe,
        networkSnapshot = context.networkSnapshot,
    )

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
        strategyProbeReport = strategyProbeReport,
        diagnoses = diagnoses,
        classifierVersion = classifierVersion,
        packVersions = packVersions,
    )

internal fun EngineScanReportWire.toLegacyScanReportCompat(): ScanReport =
    ScanReport(
        sessionId = sessionId,
        profileId = profileId,
        pathMode = pathMode,
        startedAt = startedAt,
        finishedAt = finishedAt,
        summary = summary,
        results = results.map(EngineProbeResultWire::toProbeResult),
        resolverRecommendation = resolverRecommendation,
        strategyProbeReport = strategyProbeReport,
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
        strategyProbeReport = strategyProbeReport,
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
    )

internal fun EngineProgressWire.toLegacyScanProgressCompat(): ScanProgress =
    ScanProgress(
        sessionId = sessionId,
        phase = phase,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        message = message,
        isFinished = isFinished,
        latestProbeTarget = latestProbeTarget,
        latestProbeOutcome = latestProbeOutcome,
    )
