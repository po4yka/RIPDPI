package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class PreparedDiagnosticsScan(
    val sessionId: String,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val requestJson: String,
    val exposeProgress: Boolean,
    val registerActiveBridge: Boolean,
    val networkFingerprint: NetworkFingerprint?,
    val preferredDnsPath: EncryptedDnsPathCandidate?,
    val initialSession: ScanSessionEntity,
    val preScanSnapshot: NetworkSnapshotEntity,
    val preScanContext: DiagnosticContextEntity,
)

@Singleton
class DiagnosticsScanRequestFactory
    @Inject
    constructor(
        @param:ApplicationContext
        private val context: Context,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val nativeNetworkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val serviceStateStore: ServiceStateStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val DefaultProxyPort = 1080
        }

        suspend fun prepareScan(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            exposeProgress: Boolean,
            registerActiveBridge: Boolean,
        ): PreparedDiagnosticsScan {
            val request = json.decodeFromString(ScanRequest.serializer(), profile.requestJson)
            val networkFingerprint = networkFingerprintProvider.capture()
            val nativeNetworkSnapshot = nativeNetworkSnapshotProvider.capture()
            val preferredDnsPath =
                networkFingerprint
                    ?.scopeKey()
                    ?.let { fingerprintHash -> networkDnsPathPreferenceStore.getPreferredPath(fingerprintHash) }
            val requestForPath =
                buildScanRequest(
                    request = request,
                    settings = settings,
                    pathMode = pathMode,
                    preferredDnsPath = preferredDnsPath,
                    nativeNetworkSnapshot = nativeNetworkSnapshot,
                )

            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val contextSnapshot = diagnosticsContextProvider.captureContext()
            val approachSnapshot =
                createStoredApproachSnapshot(json, settings, profile, contextSnapshot)
            val serviceMode = serviceStateStore.status.value.second.name

            return buildPreparedScan(
                sessionId = sessionId,
                settings = settings,
                pathMode = pathMode,
                requestForPath = requestForPath,
                exposeProgress = exposeProgress,
                registerActiveBridge = registerActiveBridge,
                networkFingerprint = networkFingerprint,
                preferredDnsPath = preferredDnsPath,
                profile = profile,
                approachSnapshot = approachSnapshot,
                serviceMode = serviceMode,
                now = now,
                contextSnapshot = contextSnapshot,
            )
        }

        private fun buildScanRequest(
            request: ScanRequest,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            preferredDnsPath: EncryptedDnsPathCandidate?,
            nativeNetworkSnapshot: com.poyka.ripdpi.data.NativeNetworkSnapshot?,
        ): ScanRequest =
            when (request.kind) {
                ScanKind.CONNECTIVITY ->
                    buildConnectivityRequest(
                        request = request,
                        settings = settings,
                        pathMode = pathMode,
                        preferredDnsPath = preferredDnsPath,
                        nativeNetworkSnapshot = nativeNetworkSnapshot,
                    )

                ScanKind.STRATEGY_PROBE ->
                    buildStrategyProbeRequest(
                        request = request,
                        settings = settings,
                        pathMode = pathMode,
                        nativeNetworkSnapshot = nativeNetworkSnapshot,
                    )
            }

        private fun buildConnectivityRequest(
            request: ScanRequest,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            preferredDnsPath: EncryptedDnsPathCandidate?,
            nativeNetworkSnapshot: com.poyka.ripdpi.data.NativeNetworkSnapshot?,
        ): ScanRequest =
            request.copy(
                pathMode = pathMode,
                dnsTargets =
                    ConnectivityDnsTargetPlanner.expandTargets(
                        targets = request.dnsTargets,
                        activeDns = settings.activeDnsSettings(),
                        preferredPath = preferredDnsPath,
                    ),
                proxyHost = settings.proxyHostFor(pathMode),
                proxyPort = settings.proxyPortFor(pathMode),
                networkSnapshot = nativeNetworkSnapshot,
            )

        private fun buildStrategyProbeRequest(
            request: ScanRequest,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            nativeNetworkSnapshot: com.poyka.ripdpi.data.NativeNetworkSnapshot?,
        ): ScanRequest {
            require(pathMode == ScanPathMode.RAW_PATH) {
                "Automatic probing only supports raw-path scans"
            }
            require(!settings.enableCmdSettings) {
                "Automatic probing only supports UI-configured RIPDPI settings"
            }
            val baseProxyConfigJson =
                RipDpiProxyUIPreferences
                    .fromSettings(
                        settings,
                        resolveHostAutolearnStorePath(context),
                        null,
                        settings.activeDnsSettings().toRipDpiRuntimeContext(),
                    ).toNativeConfigJson()
            return request.copy(
                pathMode = ScanPathMode.RAW_PATH,
                proxyHost = null,
                proxyPort = null,
                strategyProbe =
                    (request.strategyProbe ?: StrategyProbeRequest()).copy(
                        baseProxyConfigJson = baseProxyConfigJson,
                    ),
                networkSnapshot = nativeNetworkSnapshot,
            )
        }

        private suspend fun buildPreparedScan(
            sessionId: String,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            requestForPath: ScanRequest,
            exposeProgress: Boolean,
            registerActiveBridge: Boolean,
            networkFingerprint: NetworkFingerprint?,
            preferredDnsPath: EncryptedDnsPathCandidate?,
            profile: DiagnosticProfileEntity,
            approachSnapshot: StoredApproachSnapshot,
            serviceMode: String,
            now: Long,
            contextSnapshot: DiagnosticContextModel,
        ): PreparedDiagnosticsScan =
            PreparedDiagnosticsScan(
                sessionId = sessionId,
                settings = settings,
                pathMode = pathMode,
                requestJson = json.encodeToString(ScanRequest.serializer(), requestForPath),
                exposeProgress = exposeProgress,
                registerActiveBridge = registerActiveBridge,
                networkFingerprint = networkFingerprint,
                preferredDnsPath = preferredDnsPath,
                initialSession = createInitialSession(sessionId, profile, approachSnapshot, pathMode, serviceMode, now),
                preScanSnapshot = createPreScanSnapshot(sessionId, now),
                preScanContext = createPreScanContext(sessionId, now, contextSnapshot),
            )

        private fun createInitialSession(
            sessionId: String,
            profile: DiagnosticProfileEntity,
            approachSnapshot: StoredApproachSnapshot,
            pathMode: ScanPathMode,
            serviceMode: String,
            now: Long,
        ): ScanSessionEntity =
            ScanSessionEntity(
                id = sessionId,
                profileId = profile.id,
                approachProfileId = approachSnapshot.profileId,
                approachProfileName = approachSnapshot.profileName,
                strategyId = approachSnapshot.strategyId,
                strategyLabel = approachSnapshot.strategyLabel,
                strategyJson = approachSnapshot.strategyJson,
                pathMode = pathMode.name,
                serviceMode = serviceMode,
                status = "running",
                summary = "Scan started",
                reportJson = null,
                startedAt = now,
                finishedAt = null,
            )

        private suspend fun createPreScanSnapshot(
            sessionId: String,
            now: Long,
        ): NetworkSnapshotEntity =
            NetworkSnapshotEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                snapshotKind = "pre_scan",
                payloadJson =
                    json.encodeToString(
                        NetworkSnapshotModel.serializer(),
                            networkMetadataProvider.captureSnapshot(includePublicIp = true),
                    ),
                capturedAt = now,
            )

        private fun createPreScanContext(
            sessionId: String,
            now: Long,
            contextSnapshot: DiagnosticContextModel,
        ): DiagnosticContextEntity =
            DiagnosticContextEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                contextKind = "pre_scan",
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), contextSnapshot),
                capturedAt = now,
            )

        private fun com.poyka.ripdpi.proto.AppSettings.proxyHostFor(pathMode: ScanPathMode): String? =
            if (pathMode == ScanPathMode.IN_PATH) {
                proxyIp.ifEmpty { "127.0.0.1" }
            } else {
                null
            }

        private fun com.poyka.ripdpi.proto.AppSettings.proxyPortFor(pathMode: ScanPathMode): Int? =
            if (pathMode == ScanPathMode.IN_PATH) {
                proxyPort.takeIf { it > 0 } ?: DefaultProxyPort
            } else {
                null
            }
    }
