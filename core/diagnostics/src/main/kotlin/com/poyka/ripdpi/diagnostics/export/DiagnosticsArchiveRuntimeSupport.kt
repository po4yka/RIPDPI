package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.RuntimeComponentSummary
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import java.security.MessageDigest

private const val SuccessRatePercentScale = 100
private const val ArchiveNetworkFingerprint = "redacted"

internal fun archiveFingerprintProjection(value: String?): String? = value?.let { ArchiveNetworkFingerprint }

internal fun textEntry(
    name: String,
    content: String,
): DiagnosticsArchiveEntry = DiagnosticsArchiveEntry(name = name, bytes = content.toByteArray())

internal fun buildArchiveProvenance(
    target: DiagnosticsArchiveTarget,
    selection: DiagnosticsArchiveSelection,
): DiagnosticsArchiveProvenancePayload {
    val allEvents = selection.primaryEvents + selection.globalEvents
    val context = selectArchiveRuntimeContext(selection).context
    val runtimeProvenance =
        DiagnosticsArchiveRuntimeProvenance(
            runtimeId = allEvents.latestCorrelation { it.runtimeId }?.let(::redactDiagnosticsArchiveText),
            mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode },
            policySignature = archiveStableCorrelatorProjection(allEvents.latestCorrelation { it.policySignature }),
            fingerprintHash =
                archiveFingerprintProjection(
                    selection.payload.telemetry
                        .firstOrNull()
                        ?.telemetryNetworkFingerprintHash
                        ?: allEvents.latestCorrelation { it.fingerprintHash },
                ),
            networkScope =
                archiveFingerprintProjection(
                    selection.payload.telemetry
                        .firstOrNull()
                        ?.telemetryNetworkFingerprintHash
                        ?: allEvents.latestCorrelation { it.fingerprintHash },
                ),
            androidVersion = context?.device?.androidVersion,
            apiLevel = context?.device?.apiLevel,
            primaryAbi = context?.device?.primaryAbi,
        )
    return DiagnosticsArchiveProvenancePayload(
        runType = selection.runType,
        homeRunId = selection.homeRunId,
        archiveReason = selection.request.reason,
        requestedAt = selection.request.requestedAt,
        createdAt = target.createdAt,
        requestedSessionId =
            if (selection.runType == DiagnosticsArchiveRunType.SINGLE_SESSION) {
                selection.request.requestedSessionId
            } else {
                null
            },
        selectedSessionId = selection.primarySession?.id,
        bundleSessionIds = selection.homeCompositeOutcome?.bundleSessionIds.orEmpty(),
        sessionSelectionStatus = selection.sessionSelectionStatus,
        triggerMetadata =
            selection.primarySession?.let {
                DiagnosticsArchiveTriggerMetadata(
                    launchOrigin = it.launchOrigin,
                    triggerType = it.triggerType,
                    triggerClassification = it.triggerClassification,
                    triggerOccurredAt = it.triggerOccurredAt,
                )
            },
        buildProvenance = selection.buildProvenance,
        runtimeProvenance = runtimeProvenance,
        installedArtifact = selection.installedArtifact,
    )
}

internal fun buildRuntimeConfig(
    selection: DiagnosticsArchiveSelection,
    redactor: DiagnosticsArchiveRedactor,
): DiagnosticsArchiveRuntimeConfigPayload {
    val runtimeContextSelection = selectArchiveRuntimeContext(selection)
    val context = runtimeContextSelection.context?.let(redactor::redact)
    val snapshot = selection.latestSnapshotModel?.let(redactor::redact)
    val telemetry =
        selection.payload.telemetry
            .firstOrNull()
            ?.redactForArchive()
    val serviceConfig = resolveServiceConfig(context?.service, selection.primarySession?.profileId)
    val resolverConfig = resolveResolverConfig(telemetry)
    val networkConfig = resolveNetworkConfig(snapshot)
    val envConfig = resolveEnvironmentConfig(context?.environment, context?.permissions)
    return DiagnosticsArchiveRuntimeConfigPayload(
        configuredMode = serviceConfig.configuredMode,
        activeMode = serviceConfig.activeMode,
        serviceStatus = serviceConfig.serviceStatus,
        selectedProfileId = serviceConfig.selectedProfileId,
        selectedProfileName = serviceConfig.selectedProfileName,
        configSource = serviceConfig.configSource,
        desyncMethod = serviceConfig.desyncMethod,
        chainSummary = serviceConfig.chainSummary,
        routeGroup = serviceConfig.routeGroup,
        restartCount = serviceConfig.restartCount,
        sessionUptimeMs = serviceConfig.sessionUptimeMs,
        hostAutolearnEnabled = serviceConfig.hostAutolearnEnabled,
        learnedHostCount = serviceConfig.learnedHostCount,
        penalizedHostCount = serviceConfig.penalizedHostCount,
        blockedHostCount = serviceConfig.blockedHostCount,
        lastBlockSignal = serviceConfig.lastBlockSignal,
        lastBlockProvider = serviceConfig.lastBlockProvider,
        lastAutolearnHost = serviceConfig.lastAutolearnHost,
        lastAutolearnGroup = serviceConfig.lastAutolearnGroup,
        lastAutolearnAction = serviceConfig.lastAutolearnAction,
        lastNativeErrorHeadline = serviceConfig.lastNativeErrorHeadline,
        resolverId = resolverConfig.resolverId,
        resolverProtocol = resolverConfig.resolverProtocol,
        resolverEndpoint = resolverConfig.resolverEndpoint,
        resolverLatencyMs = resolverConfig.resolverLatencyMs,
        resolverFallbackActive = resolverConfig.resolverFallbackActive,
        resolverFallbackReason = resolverConfig.resolverFallbackReason,
        networkHandoverClass = resolverConfig.networkHandoverClass,
        transport = networkConfig.transport,
        privateDnsMode = networkConfig.privateDnsMode,
        mtu = networkConfig.mtu,
        networkValidated = networkConfig.networkValidated,
        captivePortalDetected = networkConfig.captivePortalDetected,
        batterySaverState = envConfig.batterySaverState,
        powerSaveModeState = envConfig.powerSaveModeState,
        dataSaverState = envConfig.dataSaverState,
        batteryOptimizationState = envConfig.batteryOptimizationState,
        vpnPermissionState = envConfig.vpnPermissionState,
        notificationPermissionState = envConfig.notificationPermissionState,
        networkMeteredState = envConfig.networkMeteredState,
        roamingState = envConfig.roamingState,
        commandLineSettingsEnabled = selection.appSettings.enableCmdSettings,
        commandLineArgsHash =
            selection.appSettings
                .takeIf { it.enableCmdSettings }
                ?.cmdArgs
                ?.takeIf { it.isNotBlank() }
                ?.let { "redacted" },
        effectiveStrategySignature = null,
        proxyRuntime = context?.service?.proxy?.redactedRuntimeAddresses(),
        tunnelRuntime = context?.service?.tunnel?.redactedRuntimeAddresses(),
        relayRuntime = context?.service?.relay?.redactedRuntimeAddresses(),
        warpRuntime = context?.service?.warp?.redactedRuntimeAddresses(),
        connectivityAssessment = redactor.redact(selection.homeCompositeOutcome?.connectivityAssessment),
        runtimeContextSource = runtimeContextSelection.mixedVantageValue(runtimeContextSelection.source),
        runtimeContextCapturedAt = runtimeContextSelection.mixedVantageValue(runtimeContextSelection.capturedAt),
        networkSnapshotSource = runtimeContextSelection.mixedVantageValue(selection.archiveSnapshotSource()),
        networkSnapshotCapturedAt = runtimeContextSelection.mixedVantageValue(snapshot?.capturedAt),
        terminalServiceStatus =
            runtimeContextSelection.mixedVantageValue(runtimeContextSelection.terminalContext?.service?.serviceStatus),
        terminalContextSource =
            runtimeContextSelection.mixedVantageValue(runtimeContextSelection.terminalSource),
        terminalContextCapturedAt =
            runtimeContextSelection.mixedVantageValue(runtimeContextSelection.terminalCapturedAt),
    )
}

internal data class SelectedArchiveRuntimeContext(
    val context: DiagnosticContextModel?,
    val source: String?,
    val capturedAt: Long?,
    val terminalContext: DiagnosticContextModel?,
    val terminalSource: String?,
    val terminalCapturedAt: Long?,
) {
    val usesHistoricalContext: Boolean
        get() = context != null && terminalContext != null && source != terminalSource

    fun <T> mixedVantageValue(value: T?): T? = value.takeIf { usesHistoricalContext }
}

/**
 * Selects the context that best explains the runtime failure without losing the
 * chronologically terminal state. Raw-path scans intentionally stop the VPN,
 * so their later `Halted`/idle snapshot must not hide a still-relevant passive
 * `Running` snapshot with degraded or failed native components.
 */
internal fun selectArchiveRuntimeContext(selection: DiagnosticsArchiveSelection): SelectedArchiveRuntimeContext {
    val candidates =
        listOfNotNull(
            selection.sessionContextModel?.let { context ->
                ArchiveRuntimeContextCandidate(
                    context = context,
                    source = ArchiveSessionContextSource,
                    capturedAt = selection.primaryContexts.maxOfOrNull { it.capturedAt },
                    sourcePriority = ArchiveSessionContextPriority,
                )
            },
            selection.latestContextModel?.let { context ->
                ArchiveRuntimeContextCandidate(
                    context = context,
                    source = ArchivePassiveContextSource,
                    capturedAt = selection.latestPassiveContext?.capturedAt,
                    sourcePriority = ArchivePassiveContextPriority,
                )
            },
        )
    val terminal =
        candidates.maxWithOrNull(
            compareBy(
                ArchiveRuntimeContextCandidate::capturedAtOrMinimum,
                ArchiveRuntimeContextCandidate::sourcePriority,
            ),
        )
    val selected =
        candidates.maxWithOrNull(
            compareBy(
                ArchiveRuntimeContextCandidate::diagnosticRank,
                ArchiveRuntimeContextCandidate::capturedAtOrMinimum,
                ArchiveRuntimeContextCandidate::sourcePriority,
            ),
        )
    return SelectedArchiveRuntimeContext(
        context = selected?.context,
        source = selected?.source,
        capturedAt = selected?.capturedAt,
        terminalContext = terminal?.context,
        terminalSource = terminal?.source,
        terminalCapturedAt = terminal?.capturedAt,
    )
}

private data class ArchiveRuntimeContextCandidate(
    val context: DiagnosticContextModel,
    val source: String,
    val capturedAt: Long?,
    val sourcePriority: Int,
) {
    val capturedAtOrMinimum: Long
        get() = capturedAt ?: Long.MIN_VALUE

    val diagnosticRank: Int
        get() = context.diagnosticRank()
}

private fun DiagnosticContextModel.diagnosticRank(): Int {
    val components = listOfNotNull(service.proxy, service.tunnel, service.relay, service.warp)
    val hasFailure =
        components.any(RuntimeComponentSummary::hasDiagnosticFailure) ||
            service.lastNativeErrorHeadline.hasDiagnosticValue()
    val serviceState = service.serviceStatus.lowercase()
    val isActive =
        serviceState in ArchiveActiveServiceStates ||
            components.any { component ->
                component.activeSessions > 0 || component.state.lowercase() in ArchiveActiveComponentStates
            }
    val hasNonIdleComponent =
        components.any { component ->
            component.state.lowercase() !in ArchiveIdleComponentStates ||
                component.health.lowercase() !in ArchiveIdleComponentStates
        }
    return when {
        hasFailure -> ArchiveFailedContextRank
        isActive -> ArchiveActiveContextRank
        hasNonIdleComponent -> ArchiveNonIdleContextRank
        else -> ArchiveIdleContextRank
    }
}

private fun RuntimeComponentSummary.hasDiagnosticFailure(): Boolean =
    state.lowercase().containsDiagnosticFailureToken() ||
        health.lowercase().containsDiagnosticFailureToken() ||
        lastError.hasDiagnosticValue() ||
        lastFailureClass.hasDiagnosticValue()

private fun String.containsDiagnosticFailureToken(): Boolean = ArchiveFailureTokens.any(::contains)

private fun String.hasDiagnosticValue(): Boolean = isNotBlank() && lowercase() !in ArchiveEmptyDiagnosticValues

private fun DiagnosticsArchiveSelection.archiveSnapshotSource(): String? {
    return when (latestSnapshotSource) {
        DiagnosticsArchiveSnapshotSource.SESSION -> {
            ArchiveSessionSnapshotSource
        }

        DiagnosticsArchiveSnapshotSource.PASSIVE -> {
            ArchivePassiveSnapshotSource
        }

        null -> {
            val snapshot = latestSnapshotModel ?: return null
            if (latestPassiveSnapshot?.capturedAt == snapshot.capturedAt) {
                ArchivePassiveSnapshotSource
            } else {
                ArchiveSessionSnapshotSource
            }
        }
    }
}

private const val ArchiveSessionContextSource = "session_context"
private const val ArchivePassiveContextSource = "latest_passive_context"
private const val ArchiveSessionSnapshotSource = "session_snapshot"
private const val ArchivePassiveSnapshotSource = "latest_passive_snapshot"
private const val ArchiveSessionContextPriority = 1
private const val ArchivePassiveContextPriority = 0
private const val ArchiveIdleContextRank = 0
private const val ArchiveNonIdleContextRank = 1
private const val ArchiveActiveContextRank = 2
private const val ArchiveFailedContextRank = 3
private val ArchiveActiveServiceStates = setOf("running", "connected", "active")
private val ArchiveActiveComponentStates = setOf("running", "connected", "active", "listening")
private val ArchiveIdleComponentStates = setOf("idle", "stopped", "unavailable", "unknown", "none", "")
private val ArchiveEmptyDiagnosticValues = setOf("none", "unavailable", "unknown", "")
private val ArchiveFailureTokens = setOf("degraded", "failed", "error", "broken", "unhealthy")

private data class ResolvedServiceConfig(
    val configuredMode: String = "unavailable",
    val activeMode: String = "unavailable",
    val serviceStatus: String = "unavailable",
    val selectedProfileId: String = "unavailable",
    val selectedProfileName: String = "unavailable",
    val configSource: String = "unavailable",
    val desyncMethod: String = "unavailable",
    val chainSummary: String = "unavailable",
    val routeGroup: String = "unavailable",
    val restartCount: Int = 0,
    val sessionUptimeMs: Long? = null,
    val hostAutolearnEnabled: String = "unavailable",
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String = "unavailable",
    val lastBlockProvider: String = "unavailable",
    val lastAutolearnHost: String = "unavailable",
    val lastAutolearnGroup: String = "unavailable",
    val lastAutolearnAction: String = "unavailable",
    val lastNativeErrorHeadline: String = "unavailable",
    val proxyRuntime: RuntimeComponentSummary? = null,
    val tunnelRuntime: RuntimeComponentSummary? = null,
    val relayRuntime: RuntimeComponentSummary? = null,
    val warpRuntime: RuntimeComponentSummary? = null,
)

private fun resolveServiceConfig(
    service: ServiceContextModel?,
    fallbackProfileId: String?,
): ResolvedServiceConfig =
    if (service == null) {
        ResolvedServiceConfig(selectedProfileId = fallbackProfileId ?: "unavailable")
    } else {
        ResolvedServiceConfig(
            configuredMode = service.configuredMode,
            activeMode = service.activeMode,
            serviceStatus = service.serviceStatus,
            selectedProfileId = service.selectedProfileId,
            selectedProfileName = service.selectedProfileName,
            configSource = service.configSource,
            desyncMethod = service.desyncMethod,
            chainSummary = service.chainSummary,
            routeGroup = service.routeGroup,
            restartCount = service.restartCount,
            sessionUptimeMs = service.sessionUptimeMs,
            hostAutolearnEnabled = service.hostAutolearnEnabled,
            learnedHostCount = service.learnedHostCount,
            penalizedHostCount = service.penalizedHostCount,
            blockedHostCount = service.blockedHostCount,
            lastBlockSignal = service.lastBlockSignal,
            lastBlockProvider = service.lastBlockProvider,
            lastAutolearnHost = service.lastAutolearnHost,
            lastAutolearnGroup = service.lastAutolearnGroup,
            lastAutolearnAction = service.lastAutolearnAction,
            lastNativeErrorHeadline = service.lastNativeErrorHeadline,
            proxyRuntime = service.proxy,
            tunnelRuntime = service.tunnel,
            relayRuntime = service.relay,
            warpRuntime = service.warp,
        )
    }

private data class ResolvedResolverConfig(
    val resolverId: String = "unavailable",
    val resolverProtocol: String = "unavailable",
    val resolverEndpoint: String = "unavailable",
    val resolverLatencyMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String = "unavailable",
    val networkHandoverClass: String = "unavailable",
)

private fun resolveResolverConfig(telemetry: TelemetrySampleEntity?): ResolvedResolverConfig =
    if (telemetry == null) {
        ResolvedResolverConfig()
    } else {
        ResolvedResolverConfig(
            resolverId = telemetry.resolverId ?: "unavailable",
            resolverProtocol = telemetry.resolverProtocol ?: "unavailable",
            resolverEndpoint =
                if (telemetry.resolverEndpoint.isNullOrBlank()) {
                    "unavailable"
                } else {
                    "redacted"
                },
            resolverLatencyMs = telemetry.resolverLatencyMs,
            resolverFallbackActive = telemetry.resolverFallbackActive,
            resolverFallbackReason = telemetry.resolverFallbackReason ?: "unavailable",
            networkHandoverClass = telemetry.networkHandoverClass ?: "unavailable",
        )
    }

private data class ResolvedNetworkConfig(
    val transport: String = "unavailable",
    val privateDnsMode: String = "unavailable",
    val mtu: Int? = null,
    val networkValidated: Boolean? = null,
    val captivePortalDetected: Boolean? = null,
)

private fun resolveNetworkConfig(snapshot: NetworkSnapshotModel?): ResolvedNetworkConfig =
    if (snapshot == null) {
        ResolvedNetworkConfig()
    } else {
        ResolvedNetworkConfig(
            transport = snapshot.transport,
            privateDnsMode = redactPrivateDnsMode(snapshot.privateDnsMode),
            mtu = snapshot.mtu,
            networkValidated = snapshot.networkValidated,
            captivePortalDetected = snapshot.captivePortalDetected,
        )
    }

private data class ResolvedEnvironmentConfig(
    val batterySaverState: String = "unavailable",
    val powerSaveModeState: String = "unavailable",
    val dataSaverState: String = "unavailable",
    val batteryOptimizationState: String = "unavailable",
    val vpnPermissionState: String = "unavailable",
    val notificationPermissionState: String = "unavailable",
    val networkMeteredState: String = "unavailable",
    val roamingState: String = "unavailable",
)

private fun resolveEnvironmentConfig(
    environment: EnvironmentContextModel?,
    permissions: PermissionContextModel?,
): ResolvedEnvironmentConfig =
    ResolvedEnvironmentConfig(
        batterySaverState = environment?.batterySaverState ?: "unavailable",
        powerSaveModeState = environment?.powerSaveModeState ?: "unavailable",
        dataSaverState = permissions?.dataSaverState ?: "unavailable",
        batteryOptimizationState = permissions?.batteryOptimizationState ?: "unavailable",
        vpnPermissionState = permissions?.vpnPermissionState ?: "unavailable",
        notificationPermissionState = permissions?.notificationPermissionState ?: "unavailable",
        networkMeteredState = environment?.networkMeteredState ?: "unavailable",
        roamingState = environment?.roamingState ?: "unavailable",
    )

// Redacts the network *addresses* on a runtime summary. `protocolKind` is deliberately
// NOT redacted: it is a privacy-safe transport-kind enum (e.g. "vless_reality",
// "hysteria2", "amneziawg"), not an address or identifier, and is the active-protocol
// signal the simple flavor exists to surface in the diagnostic report (see M5 /
// network-fingerprint-privacy.md, which classes relay-kind strings as safe to record).
private fun RuntimeComponentSummary.redactedRuntimeAddresses(): RuntimeComponentSummary =
    copy(
        listenerAddress = listenerAddress?.let { "redacted" },
        upstreamAddress = upstreamAddress?.let { "redacted" },
    )

internal fun List<NativeSessionEventEntity>.latestCorrelation(
    selector: (NativeSessionEventEntity) -> String?,
): String? =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .mapNotNull(selector)
        .firstOrNull()

internal fun List<NativeSessionEventEntity>.lifecycleMilestones(limit: Int = 6): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            val subsystem = (event.subsystem ?: event.source).lowercase()
            val message = event.message.lowercase()
            subsystem in setOf("service", "proxy", "tunnel", "diagnostics") &&
                (
                    message.contains("started") ||
                        message.contains("stopped") ||
                        message.contains("stop requested") ||
                        message.contains("listener started") ||
                        message.contains("listener stopped")
                )
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${redactDiagnosticsArchiveText(event.message)}" }
        .toList()

internal fun List<NativeSessionEventEntity>.recentWarningPreview(limit: Int = 5): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${redactDiagnosticsArchiveText(event.message)}" }
        .toList()

internal fun BypassApproachSummary.successRateLabel(): String =
    validatedSuccessRate?.let { rate ->
        "${(rate * SuccessRatePercentScale).toInt()}%"
    } ?: "unverified"

internal fun BypassApproachSummary.projectForArchive(index: Int? = null): BypassApproachSummary {
    val kindLabel = approachId.kind.name.lowercase()
    val categoricalId = "$kindLabel-${index?.plus(1) ?: "unknown"}"
    return copy(
        approachId = BypassApproachId(kind = approachId.kind, value = categoricalId),
        displayName = "redacted",
        secondaryLabel = kindLabel,
        verificationState = verificationState.takeIf { it in setOf("validated", "unverified") } ?: "unknown",
        lastValidatedResult = lastValidatedResult?.let { "redacted" },
        recentRuntimeHealth =
            recentRuntimeHealth.copy(
                lastEndedReason = recentRuntimeHealth.lastEndedReason?.let { "redacted" },
            ),
        topFailureOutcomes = topFailureOutcomes.mapIndexed { outcomeIndex, _ -> "failure-${outcomeIndex + 1}" },
        outcomeBreakdown =
            outcomeBreakdown.mapIndexed { outcomeIndex, outcome ->
                outcome.copy(
                    probeType = "probe-${outcomeIndex + 1}",
                    dominantFailureOutcome = outcome.dominantFailureOutcome?.let { "redacted" },
                )
            },
    )
}

internal fun DiagnosticsArchiveSelection.selectedApproachProjection(): BypassApproachSummary? =
    selectedApproachSummary?.let { selected ->
        val index = payload.approachSummaries.indexOf(selected).takeIf { it >= 0 }
        selected.projectForArchive(index)
    }

internal fun DiagnosticsArchiveBuildProvenance.toSummary(): DiagnosticsArchiveBuildProvenanceSummary =
    DiagnosticsArchiveBuildProvenanceSummary(
        applicationId = applicationId,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        buildType = buildType,
        gitCommit = gitCommit,
        nativeLibraries = nativeLibraries.map { "${it.name}:${it.version}" },
    )

internal fun sha256Hex(value: String): String = sha256Hex(value.toByteArray())

internal fun sha256Hex(value: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
