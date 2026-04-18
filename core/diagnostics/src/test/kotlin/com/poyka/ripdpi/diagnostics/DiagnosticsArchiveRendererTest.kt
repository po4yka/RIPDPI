package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

class DiagnosticsArchiveRendererTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val redactor = DiagnosticsArchiveRedactor(json)
    private val renderer = DiagnosticsArchiveRenderer(redactor, DiagnosticsSummaryProjector(), json)

    @Test
    fun `renderer emits redacted archive entries with manifest summaries`() {
        val selection = buildFullRendererSelection()
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-render", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-42.zip",
                createdAt = 42L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        assertRenderedEntryContent(entries)
        assertRenderedManifestAndProvenance(entries)
        assertGoldenContracts(entries)
    }

    @Test
    fun `renderer marks truncated collections and decode failures in completeness metadata`() {
        val selection = buildTruncationRendererSelection()
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-render", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-43.zip",
                createdAt = 43L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )

        assertTrue(completeness.truncation.telemetrySamples)
        assertTrue(completeness.truncation.nativeEvents)
        assertTrue(completeness.truncation.snapshots)
        assertTrue(completeness.truncation.contexts)
        assertTrue(completeness.truncation.logcat)
        assertEquals(DiagnosticsArchiveSectionStatus.TRUNCATED, completeness.sectionStatuses["telemetry.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.TRUNCATED, completeness.sectionStatuses["native-events.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.TRUNCATED, completeness.sectionStatuses["logcat.txt"])
        assertTrue(completeness.collectionWarnings.any { it.contains("snapshot_decode_failed_count:2") })
        assertTrue(completeness.collectionWarnings.any { it.contains("context_decode_failed_count:2") })
    }

    @Test
    fun `zip writer persists provided entries verbatim`() {
        val target = Files.createTempFile("archive-writer", ".zip").toFile()

        DiagnosticsArchiveZipWriter().write(
            target = target,
            entries =
                listOf(
                    DiagnosticsArchiveEntry("summary.txt", "summary".toByteArray()),
                    DiagnosticsArchiveEntry("manifest.json", "{\"ok\":true}".toByteArray()),
                ),
        )

        ZipFile(target).use { zip ->
            assertEquals("summary", zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText())
            assertEquals("{\"ok\":true}", zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            assertNull(zip.getEntry("missing.txt"))
        }
    }

    private fun buildFullRendererSelection(): DiagnosticsArchiveSelection =
        DiagnosticsArchiveSelection(
            runType = DiagnosticsArchiveRunType.SINGLE_SESSION,
            request = rendererArchiveRequest(),
            payload =
                DiagnosticsArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    scope = DiagnosticsArchiveFormat.scope,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    session = rendererScanSession(id = "session-1", strategyId = "strategy-fast"),
                    results = listOf(rendererProbeResult(sessionId = "session-1")),
                    sessionSnapshots = listOf(rendererNetworkSnapshotEntity(sessionId = "session-1")),
                    sessionContexts = listOf(rendererDiagnosticContextEntity(sessionId = "session-1")),
                    sessionEvents = listOf(rendererNativeEvent(id = "ev-session", sessionId = "session-1")),
                    latestPassiveSnapshot = rendererNetworkSnapshotEntity(id = "snap-passive", sessionId = null),
                    latestPassiveContext = rendererDiagnosticContextEntity(id = "ctx-passive", sessionId = null),
                    telemetry = listOf(rendererTelemetrySample(publicIp = "198.51.100.8")),
                    globalEvents = listOf(rendererNativeEvent(id = "ev-global", sessionId = null, level = "warn")),
                    approachSummaries = listOf(rendererApproachSummary(strategyId = "strategy-fast")),
                ),
            primarySession = rendererScanSession(id = "session-1", strategyId = "strategy-fast"),
            primaryReport = rendererScanReport("session-1").toEngineScanReportWire(),
            primaryResults = listOf(rendererProbeResult(sessionId = "session-1")),
            primarySnapshots = listOf(rendererNetworkSnapshotEntity(sessionId = "session-1")),
            primaryContexts = listOf(rendererDiagnosticContextEntity(sessionId = "session-1")),
            primaryEvents = listOf(rendererNativeEvent(id = "ev-session", sessionId = "session-1")),
            latestPassiveSnapshot = rendererNetworkSnapshotEntity(id = "snap-passive", sessionId = null),
            latestPassiveContext = rendererDiagnosticContextEntity(id = "ctx-passive", sessionId = null),
            globalEvents = listOf(rendererNativeEvent(id = "ev-global", sessionId = null, level = "warn")),
            selectedApproachSummary = rendererApproachSummary(strategyId = "strategy-fast"),
            latestSnapshotModel = rendererNetworkSnapshotModel(),
            latestContextModel = rendererDiagnosticContextModel(),
            sessionContextModel = rendererDiagnosticContextModel(),
            buildProvenance = rendererBuildProvenance(),
            sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
            effectiveStrategySignature = null,
            appSettings = rendererAppSettings(),
            sourceCounts =
                DiagnosticsArchiveSourceCounts(
                    telemetrySamples = 1,
                    nativeEvents = 2,
                    snapshots = 2,
                    contexts = 2,
                    sessionResults = 1,
                    sessionSnapshots = 1,
                    sessionContexts = 1,
                    sessionEvents = 1,
                ),
            collectionWarnings = emptyList(),
            includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true),
            logcatSnapshot =
                LogcatSnapshot(
                    content = "03-12 10:00:00.000 I/RIPDPI: diagnostics ready\n",
                    captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                    byteCount = 48,
                ),
            fileLogSnapshot = null,
        )

    private fun buildTruncationRendererSelection(): DiagnosticsArchiveSelection {
        val invalidSnapshot = rendererNetworkSnapshotEntity(sessionId = "session-1").copy(payloadJson = "{bad")
        val invalidContext = rendererDiagnosticContextEntity(sessionId = "session-1").copy(payloadJson = "{bad")
        return DiagnosticsArchiveSelection(
            runType = DiagnosticsArchiveRunType.SINGLE_SESSION,
            request = rendererArchiveRequest(reason = DiagnosticsArchiveReason.SAVE_ARCHIVE),
            payload =
                DiagnosticsArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    scope = DiagnosticsArchiveFormat.scope,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    session = rendererScanSession(id = "session-1"),
                    results = listOf(rendererProbeResult(sessionId = "session-1")),
                    sessionSnapshots = listOf(invalidSnapshot),
                    sessionContexts = listOf(invalidContext),
                    sessionEvents = listOf(rendererNativeEvent(id = "ev-session", sessionId = "session-1")),
                    latestPassiveSnapshot = invalidSnapshot.copy(id = "passive-snap", sessionId = null),
                    latestPassiveContext = invalidContext.copy(id = "passive-ctx", sessionId = null),
                    telemetry = listOf(rendererTelemetrySample(publicIp = "198.51.100.8")),
                    globalEvents = listOf(rendererNativeEvent(id = "ev-global", sessionId = null)),
                    approachSummaries = emptyList(),
                ),
            primarySession = rendererScanSession(id = "session-1"),
            primaryReport = rendererScanReport("session-1").toEngineScanReportWire(),
            primaryResults = listOf(rendererProbeResult(sessionId = "session-1")),
            primarySnapshots = listOf(invalidSnapshot),
            primaryContexts = listOf(invalidContext),
            primaryEvents = listOf(rendererNativeEvent(id = "ev-session", sessionId = "session-1")),
            latestPassiveSnapshot = invalidSnapshot.copy(id = "passive-snap", sessionId = null),
            latestPassiveContext = invalidContext.copy(id = "passive-ctx", sessionId = null),
            globalEvents = listOf(rendererNativeEvent(id = "ev-global", sessionId = null)),
            selectedApproachSummary = null,
            latestSnapshotModel = rendererNetworkSnapshotModel(),
            latestContextModel = rendererDiagnosticContextModel(),
            sessionContextModel = rendererDiagnosticContextModel(),
            buildProvenance = rendererBuildProvenance(),
            sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
            effectiveStrategySignature = null,
            appSettings = rendererAppSettings(),
            sourceCounts =
                DiagnosticsArchiveSourceCounts(
                    telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit,
                    nativeEvents = DiagnosticsArchiveFormat.globalEventLimit,
                    snapshots = DiagnosticsArchiveFormat.snapshotLimit,
                    contexts = DiagnosticsArchiveFormat.snapshotLimit,
                    sessionResults = 1,
                    sessionSnapshots = 1,
                    sessionContexts = 1,
                    sessionEvents = 1,
                ),
            collectionWarnings = listOf("logcat_capture_failed:none"),
            includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true),
            logcatSnapshot =
                LogcatSnapshot(
                    content = "x".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES),
                    captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                    byteCount = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
                ),
            fileLogSnapshot = null,
        )
    }

    private fun assertRenderedEntryContent(entries: Map<String, DiagnosticsArchiveEntry>) {
        val summaryText = entries.getValue("summary.txt").bytes.decodeToString()
        val reportText = entries.getValue("report.json").bytes.decodeToString()
        val analysisText = entries.getValue("analysis.json").bytes.decodeToString()
        val telemetryCsv = entries.getValue("telemetry.csv").bytes.decodeToString()
        assertTrue(entries.containsKey("summary.txt"))
        assertTrue(entries.containsKey("report.json"))
        assertTrue(entries.containsKey("logcat.txt"))
        assertTrue(summaryText.contains("generatedAt=42"))
        assertTrue(summaryText.contains("publicIp=redacted"))
        assertTrue(summaryText.contains("classifierVersion=ru_ooni_v1"))
        assertTrue(summaryText.contains("Diagnoses:"))
        assertTrue(summaryText.contains("dns_tampering=DNS answers were substituted"))
        assertTrue(summaryText.contains("pack.ru-independent-media=1"))
        assertFalse(reportText.contains("198.51.100.8"))
        assertTrue(reportText.contains("\"classifierVersion\": \"ru_ooni_v1\""))
        assertTrue(analysisText.contains("\"networkIdentityBucket\": \"wifi:steady:fp-render\""))
        assertTrue(
            analysisText.contains("\"targetBucket\": \"foreign:cloudflare:ech=yes|domestic:domesticcdn:ech=no\""),
        )
        assertTrue(analysisText.contains("\"inferredUnavailableCapabilities\": ["))
        assertTrue(analysisText.contains("\"root_helper_available\""))
        assertTrue(analysisText.contains("\"policyVersion\": \"phase16_rollout_gates_v1\""))
        assertFalse(reportText.contains("127.0.0.1:1080"))
        assertTrue(telemetryCsv.contains("redacted"))
        assertTrue(telemetryCsv.contains("networkIdentityBucket"))
        assertTrue(telemetryCsv.contains("foreign:cloudflare:ech=yes|domestic:domesticcdn:ech=no"))
        assertTrue(telemetryCsv.contains("root_helper_available"))
    }

    private fun assertRenderedManifestAndProvenance(entries: Map<String, DiagnosticsArchiveEntry>) {
        val manifest =
            json.decodeFromString(
                DiagnosticsArchiveManifest.serializer(),
                entries.getValue("manifest.json").bytes.decodeToString(),
            )
        val runtimeConfig =
            json.decodeFromString(
                DiagnosticsArchiveRuntimeConfigPayload.serializer(),
                entries.getValue("runtime-config.json").bytes.decodeToString(),
            )
        val provenance =
            json.decodeFromString(
                DiagnosticsArchiveProvenancePayload.serializer(),
                entries.getValue("archive-provenance.json").bytes.decodeToString(),
            )
        val integrity =
            json.decodeFromString(
                DiagnosticsArchiveIntegrityPayload.serializer(),
                entries.getValue("integrity.json").bytes.decodeToString(),
            )
        assertEquals("session-1", manifest.includedSessionId)
        assertEquals(DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true), manifest.includedFiles)
        assertEquals(DiagnosticsArchiveReason.SHARE_ARCHIVE, manifest.archiveReason)
        assertEquals("redacted", manifest.networkSummary?.publicIp)
        assertEquals("redacted", manifest.contextSummary?.service?.proxyEndpoint)
        assertEquals("ru_ooni_v1", manifest.classifierVersion)
        assertEquals(1, manifest.diagnosisCount)
        assertEquals(1, manifest.packVersions["ru-independent-media"])
        assertEquals("sha256", manifest.integrityAlgorithm)
        assertTrue(entries.containsKey("archive-provenance.json"))
        assertTrue(entries.containsKey("runtime-config.json"))
        assertTrue(entries.containsKey("analysis.json"))
        assertTrue(entries.containsKey("completeness.json"))
        assertTrue(entries.containsKey("integrity.json"))
        assertTrue(runtimeConfig.commandLineSettingsEnabled)
        assertNotNull(runtimeConfig.commandLineArgsHash)
        assertFalse(
            entries
                .getValue("runtime-config.json")
                .bytes
                .decodeToString()
                .contains("--fake --split 2"),
        )
        assertEquals("session-1", provenance.selectedSessionId)
        assertEquals(DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION, provenance.sessionSelectionStatus)
        assertEquals("unavailable", provenance.buildProvenance.gitCommit)
        assertEquals(entries.keys - "integrity.json", integrity.files.map { it.name }.toSet())
        integrity.files.forEach { file ->
            val entry = entries.getValue(file.name)
            assertEquals(entry.bytes.size, file.byteCount)
            assertEquals(rendererSha256Hex(entry.bytes), file.sha256)
        }
    }

    private fun assertGoldenContracts(entries: Map<String, DiagnosticsArchiveEntry>) {
        GoldenContractSupport.assertJsonGolden(
            "archive/manifest_v4.json",
            entries.getValue("manifest.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/archive_provenance_v4.json",
            entries.getValue("archive-provenance.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/runtime_config_v4.json",
            entries.getValue("runtime-config.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/analysis_v4.json",
            entries.getValue("analysis.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/completeness_v4.json",
            entries.getValue("completeness.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/integrity_v4.json",
            entries.getValue("integrity.json").bytes.decodeToString(),
        )
    }

    private fun rendererScanSession(
        id: String,
        strategyId: String? = null,
        status: String = "finished",
        startedAt: Long = 10L,
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            profileId = "default",
            strategyId = strategyId,
            strategyLabel = strategyId,
            pathMode = "IN_PATH",
            serviceMode = "vpn",
            status = status,
            summary = "Blocked DNS",
            reportJson = json.encodeToString(rendererScanReport(id).toEngineScanReportWire()),
            startedAt = startedAt,
            finishedAt = if (status == "finished") startedAt + 5L else null,
        )

    private fun rendererProbeResult(sessionId: String) =
        ProbeResultEntity(
            id = "probe-$sessionId",
            sessionId = sessionId,
            probeType = "dns",
            target = "blocked.example",
            outcome = "substituted",
            detailJson =
                json.encodeToString(
                    ListSerializer(ProbeDetail.serializer()),
                    listOf(ProbeDetail("attempts", "baseline:fail|fallback:ok")),
                ),
            createdAt = 30L,
        )

    private fun rendererNetworkSnapshotEntity(
        id: String = "snap",
        sessionId: String?,
        capturedAt: Long = 20L,
    ) = NetworkSnapshotEntity(
        id = id,
        sessionId = sessionId,
        snapshotKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), rendererNetworkSnapshotModel()),
        capturedAt = capturedAt,
    )

    private fun rendererDiagnosticContextEntity(
        id: String = "ctx",
        sessionId: String?,
        capturedAt: Long = 21L,
    ) = DiagnosticContextEntity(
        id = id,
        sessionId = sessionId,
        contextKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), rendererDiagnosticContextModel()),
        capturedAt = capturedAt,
    )

    private fun rendererTelemetrySample(publicIp: String?) =
        TelemetrySampleEntity(
            id = "telemetry",
            sessionId = null,
            activeMode = "vpn",
            connectionState = "connected",
            networkType = "wifi",
            publicIp = publicIp,
            telemetryNetworkFingerprintHash = "fp-render",
            winningTcpStrategyFamily = "tlsrec_split",
            winningQuicStrategyFamily = "quic_sni_split",
            proxyRttBand = "50_99",
            resolverRttBand = "lt50",
            proxyRouteRetryCount = 1,
            tunnelRecoveryRetryCount = 0,
            resolverId = "adguard",
            resolverProtocol = "doh",
            resolverEndpoint = "https://dns.adguard-dns.com/dns-query",
            resolverLatencyMs = 42L,
            dnsFailuresTotal = 1,
            resolverFallbackActive = false,
            resolverFallbackReason = "none",
            networkHandoverClass = "steady",
            txPackets = 1,
            txBytes = 2,
            rxPackets = 3,
            rxBytes = 4,
            createdAt = 50L,
        )

    private fun rendererNativeEvent(
        id: String,
        sessionId: String?,
        level: String = "info",
    ) = NativeSessionEventEntity(
        id = id,
        sessionId = sessionId,
        source = "proxy",
        level = level,
        message = "warning",
        createdAt = 60L,
    )

    private fun rendererApproachSummary(strategyId: String) =
        BypassApproachSummary(
            approachId = BypassApproachId(BypassApproachKind.Strategy, strategyId),
            displayName = "Fast Strategy",
            secondaryLabel = "Strategy",
            verificationState = "validated",
            validatedScanCount = 1,
            validatedSuccessCount = 1,
            validatedSuccessRate = 1.0f,
            lastValidatedResult = "ok",
            usageCount = 2,
            totalRuntimeDurationMs = 100L,
            recentRuntimeHealth = BypassRuntimeHealthSummary(),
            lastUsedAt = 99L,
        )

    private fun rendererScanReport(sessionId: String) =
        ScanReport(
            sessionId = sessionId,
            profileId = "default",
            pathMode = ScanPathMode.IN_PATH,
            startedAt = 10L,
            finishedAt = 15L,
            summary = "Blocked DNS",
            results =
                listOf(
                    ProbeResult(
                        probeType = "dns",
                        target = "blocked.example",
                        outcome = "substituted",
                        details = listOf(ProbeDetail("attempts", "baseline:fail|fallback:ok")),
                    ),
                ),
            diagnoses =
                listOf(
                    Diagnosis(
                        code = "dns_tampering",
                        summary = "DNS answers were substituted",
                        target = "blocked.example",
                        evidence = listOf("dns:blocked.example=substituted"),
                    ),
                ),
            strategyProbeReport =
                StrategyProbeReport(
                    suiteId = "full_matrix_v1",
                    tcpCandidates =
                        listOf(
                            StrategyProbeCandidateSummary(
                                id = "tcp-prod",
                                label = "TLS split",
                                family = "tlsrec_split",
                                emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
                                outcome = "success",
                                rationale = "winner",
                                succeededTargets = 3,
                                totalTargets = 4,
                                weightedSuccessScore = 9,
                                totalWeight = 12,
                                qualityScore = 9,
                                averageLatencyMs = 120L,
                            ),
                            StrategyProbeCandidateSummary(
                                id = "tcp-rooted",
                                label = "Rooted seqovl",
                                family = "seqovl",
                                emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                                exactEmitterRequiresRoot = true,
                                outcome = "capability_skipped",
                                rationale = "Requires rooted production emitter tier",
                                succeededTargets = 0,
                                totalTargets = 4,
                                weightedSuccessScore = 0,
                                totalWeight = 12,
                                qualityScore = 0,
                                skipped = true,
                                notes = listOf("Requires rooted production emitter tier (root_helper_available)"),
                            ),
                        ),
                    quicCandidates =
                        listOf(
                            StrategyProbeCandidateSummary(
                                id = "quic-prod",
                                label = "QUIC split",
                                family = "quic_sni_split",
                                emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
                                quicLayoutFamily = "split_initial",
                                outcome = "success",
                                rationale = "winner",
                                succeededTargets = 2,
                                totalTargets = 3,
                                weightedSuccessScore = 4,
                                totalWeight = 6,
                                qualityScore = 4,
                                averageLatencyMs = 90L,
                            ),
                        ),
                    recommendation =
                        StrategyProbeRecommendation(
                            tcpCandidateId = "tcp-prod",
                            tcpCandidateLabel = "TLS split",
                            tcpCandidateFamily = "tlsrec_split",
                            quicCandidateId = "quic-prod",
                            quicCandidateLabel = "QUIC split",
                            quicCandidateFamily = "quic_sni_split",
                            quicCandidateLayoutFamily = "split_initial",
                            dnsStrategyFamily = "resolver_override",
                            dnsStrategyLabel = "AdGuard",
                            rationale = "best path",
                            recommendedProxyConfigJson = "{}",
                        ),
                    auditAssessment =
                        StrategyProbeAuditAssessment(
                            dnsShortCircuited = false,
                            coverage =
                                StrategyProbeAuditCoverage(
                                    tcpCandidatesPlanned = 2,
                                    tcpCandidatesExecuted = 1,
                                    tcpCandidatesSkipped = 1,
                                    tcpCandidatesNotApplicable = 0,
                                    quicCandidatesPlanned = 1,
                                    quicCandidatesExecuted = 1,
                                    quicCandidatesSkipped = 0,
                                    quicCandidatesNotApplicable = 0,
                                    tcpWinnerSucceededTargets = 3,
                                    tcpWinnerTotalTargets = 4,
                                    quicWinnerSucceededTargets = 2,
                                    quicWinnerTotalTargets = 3,
                                    matrixCoveragePercent = 82,
                                    winnerCoveragePercent = 75,
                                ),
                            confidence =
                                StrategyProbeAuditConfidence(
                                    level = StrategyProbeAuditConfidenceLevel.HIGH,
                                    score = 86,
                                    rationale = "Renderer fixture confidence",
                                ),
                        ),
                    targetSelection =
                        StrategyProbeTargetSelection(
                            cohortId = "media-messaging",
                            cohortLabel = "Media and messaging",
                            domainHosts = listOf("meduza.io", "telegram.org"),
                            quicHosts = listOf("discord.com"),
                        ),
                    pilotBucketLabels = listOf("foreign:cloudflare:ech=yes", "domestic:domesticcdn:ech=no"),
                ),
            classifierVersion = "ru_ooni_v1",
            packVersions = mapOf("ru-independent-media" to 1),
        )

    private fun rendererNetworkSnapshotModel() =
        NetworkSnapshotModel(
            transport = "wifi",
            capabilities = listOf("validated"),
            dnsServers = listOf("1.1.1.1"),
            privateDnsMode = "strict",
            mtu = 1500,
            localAddresses = listOf("192.0.2.10"),
            publicIp = "198.51.100.8",
            publicAsn = "AS64500",
            captivePortalDetected = false,
            networkValidated = true,
            wifiDetails =
                WifiNetworkDetails(
                    ssid = "RIPDPI Lab",
                    bssid = "00:11:22:33:44:55",
                    band = "5 GHz",
                    wifiStandard = "802.11ac",
                    gateway = "192.0.2.1",
                ),
            capturedAt = 20L,
        )

    private fun rendererDiagnosticContextModel() =
        DiagnosticContextModel(
            service =
                ServiceContextModel(
                    serviceStatus = "connected",
                    configuredMode = "vpn",
                    activeMode = "vpn",
                    selectedProfileId = "default",
                    selectedProfileName = "Default",
                    configSource = "ui",
                    proxyEndpoint = "127.0.0.1:1080",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(1)",
                    routeGroup = "wifi",
                    sessionUptimeMs = 1_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 0,
                    hostAutolearnEnabled = "enabled",
                    learnedHostCount = 1,
                    penalizedHostCount = 0,
                    lastAutolearnHost = "example.org",
                    lastAutolearnGroup = "wifi",
                    lastAutolearnAction = "allow",
                ),
            permissions =
                PermissionContextModel(
                    vpnPermissionState = "granted",
                    notificationPermissionState = "granted",
                    batteryOptimizationState = "ignored",
                    dataSaverState = "disabled",
                ),
            device =
                DeviceContextModel(
                    appVersionName = "0.0.1",
                    appVersionCode = 1L,
                    buildType = "debug",
                    androidVersion = "14",
                    apiLevel = 34,
                    manufacturer = "Google",
                    model = "Pixel",
                    primaryAbi = "arm64-v8a",
                    locale = "en-US",
                    timezone = "UTC",
                ),
            environment =
                EnvironmentContextModel(
                    batterySaverState = "off",
                    powerSaveModeState = "off",
                    networkMeteredState = "false",
                    roamingState = "false",
                ),
        )

    private fun rendererArchiveRequest(
        reason: DiagnosticsArchiveReason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
        sessionId: String? = "session-1",
    ) = DiagnosticsArchiveRequest(
        requestedSessionId = sessionId,
        reason = reason,
        requestedAt = 24L,
    )

    private fun rendererBuildProvenance() =
        DiagnosticsArchiveBuildProvenance(
            applicationId = "com.poyka.ripdpi",
            appVersionName = "0.0.2",
            appVersionCode = 2L,
            buildType = "debug",
            gitCommit = "unavailable",
            nativeLibraries =
                listOf(
                    DiagnosticsArchiveNativeLibraryProvenance(name = "libripdpi.so", version = "unavailable"),
                    DiagnosticsArchiveNativeLibraryProvenance(name = "libripdpi-tunnel.so", version = "unavailable"),
                ),
        )

    private fun rendererAppSettings(): AppSettings =
        AppSettings
            .newBuilder()
            .setRipdpiMode("vpn")
            .setEnableCmdSettings(true)
            .setCmdArgs("--fake --split 2")
            .setDiagnosticsActiveProfileId("default")
            .build()

    private fun rendererSha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
