package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.RuntimeComponentSummary
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import java.security.MessageDigest

private const val SuccessRatePercentScale = 100

internal fun textEntry(
    name: String,
    content: String,
): DiagnosticsArchiveEntry = DiagnosticsArchiveEntry(name = name, bytes = content.toByteArray())

internal fun buildArchiveProvenance(
    target: DiagnosticsArchiveTarget,
    selection: DiagnosticsArchiveSelection,
): DiagnosticsArchiveProvenancePayload {
    val allEvents = selection.primaryEvents + selection.globalEvents
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val runtimeProvenance =
        DiagnosticsArchiveRuntimeProvenance(
            runtimeId = allEvents.latestCorrelation { it.runtimeId },
            mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode },
            policySignature = allEvents.latestCorrelation { it.policySignature },
            fingerprintHash =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            networkScope =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            androidVersion = context?.device?.androidVersion,
            apiLevel = context?.device?.apiLevel,
            primaryAbi = context?.device?.primaryAbi,
            locale = context?.device?.locale,
            timezone = context?.device?.timezone,
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
    )
}

internal fun buildRuntimeConfig(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveRuntimeConfigPayload {
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val snapshot = selection.latestSnapshotModel
    val telemetry = selection.payload.telemetry.firstOrNull()
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
                ?.let(::sha256Hex),
        effectiveStrategySignature = selection.effectiveStrategySignature,
        proxyRuntime = context?.service?.proxy,
        tunnelRuntime = context?.service?.tunnel,
        relayRuntime = context?.service?.relay,
        warpRuntime = context?.service?.warp,
        connectivityAssessment = selection.homeCompositeOutcome?.connectivityAssessment,
    )
}

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
            resolverEndpoint = telemetry.resolverEndpoint ?: "unavailable",
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
            privateDnsMode = snapshot.privateDnsMode,
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
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

internal fun List<NativeSessionEventEntity>.recentWarningPreview(limit: Int = 5): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

internal fun BypassApproachSummary.successRateLabel(): String =
    validatedSuccessRate?.let { rate ->
        "${(rate * SuccessRatePercentScale).toInt()}%"
    } ?: "unverified"

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
