package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

class DiagnosticsArchiveComponentsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val redactor = DiagnosticsArchiveRedactor(json)
    private val renderer = DiagnosticsArchiveRenderer(redactor, DiagnosticsSummaryProjector(), json)
    private val selector = DiagnosticsArchiveSessionSelector(redactor, json)

    @Test
    fun `file store creates timestamped targets and trims only managed archives`() {
        val cacheDir = Files.createTempDirectory("archive-store").toFile()
        val fileStore =
            DiagnosticsArchiveFileStore(
                cacheDir = cacheDir,
                clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
            )
        val archiveDir = cacheDir.resolve(DiagnosticsArchiveFormat.directoryName).apply { mkdirs() }
        repeat(7) { index ->
            archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}$index.zip").apply {
                writeText("archive-$index")
                setLastModified(1_700_000_000_000L - index * 1_000L)
            }
        }
        archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}expired.zip").apply {
            writeText("expired")
            setLastModified(1_700_000_000_000L - DiagnosticsArchiveFormat.maxArchiveAgeMs - 5_000L)
        }
        archiveDir.resolve("notes.txt").writeText("keep me")

        val target = fileStore.createTarget()
        fileStore.cleanup()

        val managedFiles =
            archiveDir
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith(DiagnosticsArchiveFormat.fileNamePrefix) && it.extension == "zip" }
                .map { it.name }
                .sorted()
        assertEquals(
            "${DiagnosticsArchiveFormat.fileNamePrefix}1700000000000.zip",
            target.fileName,
        )
        assertEquals(target.fileName, target.file.name)
        assertEquals(5, managedFiles.size)
        assertFalse(managedFiles.contains("${DiagnosticsArchiveFormat.fileNamePrefix}expired.zip"))
        assertTrue(archiveDir.resolve("notes.txt").exists())
    }

    @Test
    fun `redactor hides sensitive network and context data and leaves undecodable payloads alone`() {
        val snapshotEntity = networkSnapshotEntity(sessionId = "session-1")
        val contextEntity = diagnosticContextEntity(sessionId = "session-1")
        val invalidSnapshot = snapshotEntity.copy(payloadJson = "{not-json}")

        val redactedSnapshot = redactor.redact(snapshotEntity)
        val redactedContext = redactor.redact(contextEntity)

        assertFalse(redactedSnapshot.payloadJson.contains("198.51.100.8"))
        assertTrue(redactedSnapshot.payloadJson.contains("redacted(1)"))
        assertFalse(redactedContext.payloadJson.contains("127.0.0.1:1080"))
        assertTrue(redactedContext.payloadJson.contains("\"proxyEndpoint\": \"redacted\""))
        assertEquals(invalidSnapshot, redactor.redact(invalidSnapshot))
    }

    @Test
    fun `selector chooses latest completed session and partitions passive data`() =
        runTest {
            val latestCompleted =
                scanSession(
                    id = "session-latest",
                    reportJson = json.encodeToString(scanReport("session-latest").toEngineScanReportWire()),
                    strategyId = "strategy-fast",
                )
            val running =
                scanSession(
                    id = "session-running",
                    status = "running",
                    reportJson = null,
                    startedAt = 20L,
                )
            val sourceData =
                DiagnosticsArchiveSourceData(
                    sessions = listOf(latestCompleted, running),
                    usageSessions = emptyList(),
                    snapshots =
                        listOf(
                            networkSnapshotEntity(id = "snap-session", sessionId = "session-latest", capturedAt = 15L),
                            networkSnapshotEntity(id = "snap-passive", sessionId = null, capturedAt = 18L),
                        ),
                    telemetry = listOf(telemetrySample(publicIp = "198.51.100.8")),
                    events =
                        listOf(
                            nativeEvent(id = "ev-session", sessionId = "session-latest"),
                            nativeEvent(id = "ev-global", sessionId = null),
                            nativeEvent(id = "ev-other", sessionId = "session-running"),
                        ),
                    contexts =
                        listOf(
                            diagnosticContextEntity(id = "ctx-session", sessionId = "session-latest", capturedAt = 16L),
                            diagnosticContextEntity(id = "ctx-passive", sessionId = null, capturedAt = 19L),
                        ),
                    approachSummaries = listOf(approachSummary(strategyId = "strategy-fast")),
                    appSettings = appSettings(),
                    buildProvenance = buildProvenance(),
                    collectionWarnings = emptyList(),
                    logcatSnapshot = null,
                )

            val selectedSession = selector.selectPrimarySession(null, null, sourceData.sessions)
            val selection =
                selector.buildSelection(
                    request = archiveRequest(sessionId = null),
                    primarySession = selectedSession,
                    primaryResults = listOf(probeResult(sessionId = "session-latest")),
                    sourceData = sourceData,
                    loadProbeResults = { emptyList() },
                )

            assertEquals("session-latest", selectedSession?.id)
            assertEquals("session-latest", selection.payload.session?.id)
            assertEquals(1, selection.primarySnapshots.size)
            assertEquals(1, selection.primaryContexts.size)
            assertEquals(1, selection.primaryEvents.size)
            assertNotNull(selection.latestPassiveSnapshot)
            assertNotNull(selection.latestPassiveContext)
            assertEquals(listOf("ev-global", "ev-other"), selection.globalEvents.map { it.id })
            assertEquals("strategy-fast", selection.selectedApproachSummary?.approachId?.value)
            assertEquals(
                DiagnosticsArchiveSessionSelectionStatus.LATEST_COMPLETED_SESSION,
                selection.sessionSelectionStatus,
            )
            assertEquals(
                DiagnosticsArchiveFormat.includedFiles(logcatIncluded = false),
                selection.includedFiles,
            )
        }

    @Test
    fun `selector marks support bundle exports explicitly`() =
        runTest {
            val sourceData =
                DiagnosticsArchiveSourceData(
                    sessions = listOf(scanSession(id = "session-1")),
                    usageSessions = emptyList(),
                    snapshots = emptyList(),
                    telemetry = emptyList(),
                    events = emptyList(),
                    contexts = emptyList(),
                    approachSummaries = emptyList(),
                    appSettings = appSettings(),
                    buildProvenance = buildProvenance(),
                    collectionWarnings = emptyList(),
                    logcatSnapshot = null,
                )

            val selection =
                selector.buildSelection(
                    request = archiveRequest(reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE, sessionId = null),
                    primarySession = sourceData.sessions.single(),
                    primaryResults = emptyList(),
                    sourceData = sourceData,
                    loadProbeResults = { emptyList() },
                )

            assertEquals(
                DiagnosticsArchiveSessionSelectionStatus.SUPPORT_BUNDLE,
                selection.sessionSelectionStatus,
            )
        }

    @Test
    fun `selector rejects missing requested session`() {
        val error =
            try {
                selector.selectPrimarySession(
                    requestedSessionId = "missing-session",
                    requestedSession = null,
                    sessions = listOf(scanSession(id = "session-1")),
                )
                fail("Expected selectPrimarySession to reject a missing requested session")
                null
            } catch (error: IllegalArgumentException) {
                error
            }

        assertTrue(error?.message.orEmpty().contains("missing-session"))
    }

    @Test
    fun `renderer emits redacted archive entries with manifest summaries`() {
        val selection =
            DiagnosticsArchiveSelection(
                runType = DiagnosticsArchiveRunType.SINGLE_SESSION,
                request = archiveRequest(),
                payload =
                    DiagnosticsArchivePayload(
                        schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                        scope = DiagnosticsArchiveFormat.scope,
                        privacyMode = DiagnosticsArchiveFormat.privacyMode,
                        session = scanSession(id = "session-1", strategyId = "strategy-fast"),
                        results = listOf(probeResult(sessionId = "session-1")),
                        sessionSnapshots = listOf(networkSnapshotEntity(sessionId = "session-1")),
                        sessionContexts = listOf(diagnosticContextEntity(sessionId = "session-1")),
                        sessionEvents = listOf(nativeEvent(id = "ev-session", sessionId = "session-1")),
                        latestPassiveSnapshot = networkSnapshotEntity(id = "snap-passive", sessionId = null),
                        latestPassiveContext = diagnosticContextEntity(id = "ctx-passive", sessionId = null),
                        telemetry = listOf(telemetrySample(publicIp = "198.51.100.8")),
                        globalEvents = listOf(nativeEvent(id = "ev-global", sessionId = null, level = "warn")),
                        approachSummaries = listOf(approachSummary(strategyId = "strategy-fast")),
                    ),
                primarySession = scanSession(id = "session-1", strategyId = "strategy-fast"),
                primaryReport = scanReport("session-1").toEngineScanReportWire(),
                primaryResults = listOf(probeResult(sessionId = "session-1")),
                primarySnapshots = listOf(networkSnapshotEntity(sessionId = "session-1")),
                primaryContexts = listOf(diagnosticContextEntity(sessionId = "session-1")),
                primaryEvents = listOf(nativeEvent(id = "ev-session", sessionId = "session-1")),
                latestPassiveSnapshot = networkSnapshotEntity(id = "snap-passive", sessionId = null),
                latestPassiveContext = diagnosticContextEntity(id = "ctx-passive", sessionId = null),
                globalEvents = listOf(nativeEvent(id = "ev-global", sessionId = null, level = "warn")),
                selectedApproachSummary = approachSummary(strategyId = "strategy-fast"),
                latestSnapshotModel = networkSnapshotModel(),
                latestContextModel = diagnosticContextModel(),
                sessionContextModel = diagnosticContextModel(),
                buildProvenance = buildProvenance(),
                sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
                effectiveStrategySignature = null,
                appSettings = appSettings(),
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
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-render", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-42.zip",
                createdAt = 42L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
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

        assertTrue(entries.containsKey("summary.txt"))
        assertTrue(entries.containsKey("report.json"))
        assertTrue(entries.containsKey("logcat.txt"))
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("generatedAt=42"),
        )
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("publicIp=redacted"),
        )
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("classifierVersion=ru_ooni_v1"),
        )
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("Diagnoses:"),
        )
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("dns_tampering=DNS answers were substituted"),
        )
        assertTrue(
            entries
                .getValue("summary.txt")
                .bytes
                .decodeToString()
                .contains("pack.ru-independent-media=1"),
        )
        assertFalse(
            entries
                .getValue("report.json")
                .bytes
                .decodeToString()
                .contains("198.51.100.8"),
        )
        assertTrue(
            entries
                .getValue("report.json")
                .bytes
                .decodeToString()
                .contains("\"classifierVersion\": \"ru_ooni_v1\""),
        )
        assertFalse(
            entries
                .getValue("report.json")
                .bytes
                .decodeToString()
                .contains("127.0.0.1:1080"),
        )
        assertTrue(
            entries
                .getValue("telemetry.csv")
                .bytes
                .decodeToString()
                .contains("redacted"),
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
            assertEquals(sha256Hex(entry.bytes), file.sha256)
        }
        GoldenContractSupport.assertJsonGolden(
            "archive/manifest_v3.json",
            entries.getValue("manifest.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/archive_provenance_v3.json",
            entries.getValue("archive-provenance.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/runtime_config_v3.json",
            entries.getValue("runtime-config.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/analysis_v3.json",
            entries.getValue("analysis.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/completeness_v3.json",
            entries.getValue("completeness.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/integrity_v3.json",
            entries.getValue("integrity.json").bytes.decodeToString(),
        )
    }

    @Test
    fun `renderer marks truncated collections and decode failures in completeness metadata`() {
        val invalidSnapshot = networkSnapshotEntity(sessionId = "session-1").copy(payloadJson = "{bad")
        val invalidContext = diagnosticContextEntity(sessionId = "session-1").copy(payloadJson = "{bad")
        val selection =
            DiagnosticsArchiveSelection(
                runType = DiagnosticsArchiveRunType.SINGLE_SESSION,
                request = archiveRequest(reason = DiagnosticsArchiveReason.SAVE_ARCHIVE),
                payload =
                    DiagnosticsArchivePayload(
                        schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                        scope = DiagnosticsArchiveFormat.scope,
                        privacyMode = DiagnosticsArchiveFormat.privacyMode,
                        session = scanSession(id = "session-1"),
                        results = listOf(probeResult(sessionId = "session-1")),
                        sessionSnapshots = listOf(invalidSnapshot),
                        sessionContexts = listOf(invalidContext),
                        sessionEvents = listOf(nativeEvent(id = "ev-session", sessionId = "session-1")),
                        latestPassiveSnapshot = invalidSnapshot.copy(id = "passive-snap", sessionId = null),
                        latestPassiveContext = invalidContext.copy(id = "passive-ctx", sessionId = null),
                        telemetry = listOf(telemetrySample(publicIp = "198.51.100.8")),
                        globalEvents = listOf(nativeEvent(id = "ev-global", sessionId = null)),
                        approachSummaries = emptyList(),
                    ),
                primarySession = scanSession(id = "session-1"),
                primaryReport = scanReport("session-1").toEngineScanReportWire(),
                primaryResults = listOf(probeResult(sessionId = "session-1")),
                primarySnapshots = listOf(invalidSnapshot),
                primaryContexts = listOf(invalidContext),
                primaryEvents = listOf(nativeEvent(id = "ev-session", sessionId = "session-1")),
                latestPassiveSnapshot = invalidSnapshot.copy(id = "passive-snap", sessionId = null),
                latestPassiveContext = invalidContext.copy(id = "passive-ctx", sessionId = null),
                globalEvents = listOf(nativeEvent(id = "ev-global", sessionId = null)),
                selectedApproachSummary = null,
                latestSnapshotModel = networkSnapshotModel(),
                latestContextModel = diagnosticContextModel(),
                sessionContextModel = diagnosticContextModel(),
                buildProvenance = buildProvenance(),
                sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
                effectiveStrategySignature = null,
                appSettings = appSettings(),
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
            )
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
        assertEquals(
            DiagnosticsArchiveSectionStatus.TRUNCATED,
            completeness.sectionStatuses["telemetry.csv"],
        )
        assertEquals(
            DiagnosticsArchiveSectionStatus.TRUNCATED,
            completeness.sectionStatuses["native-events.csv"],
        )
        assertEquals(
            DiagnosticsArchiveSectionStatus.TRUNCATED,
            completeness.sectionStatuses["logcat.txt"],
        )
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

    private fun scanSession(
        id: String,
        strategyId: String? = null,
        status: String = "finished",
        reportJson: String? = json.encodeToString(scanReport(id).toEngineScanReportWire()),
        startedAt: Long = 10L,
    ) = ScanSessionEntity(
        id = id,
        profileId = "default",
        strategyId = strategyId,
        strategyLabel = strategyId,
        pathMode = "IN_PATH",
        serviceMode = "vpn",
        status = status,
        summary = "Blocked DNS",
        reportJson = reportJson,
        startedAt = startedAt,
        finishedAt = if (status == "finished") startedAt + 5L else null,
    )

    private fun probeResult(sessionId: String) =
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

    private fun networkSnapshotEntity(
        id: String = "snap",
        sessionId: String?,
        capturedAt: Long = 20L,
    ) = NetworkSnapshotEntity(
        id = id,
        sessionId = sessionId,
        snapshotKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), networkSnapshotModel()),
        capturedAt = capturedAt,
    )

    private fun diagnosticContextEntity(
        id: String = "ctx",
        sessionId: String?,
        capturedAt: Long = 21L,
    ) = DiagnosticContextEntity(
        id = id,
        sessionId = sessionId,
        contextKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), diagnosticContextModel()),
        capturedAt = capturedAt,
    )

    private fun telemetrySample(publicIp: String?) =
        TelemetrySampleEntity(
            id = "telemetry",
            sessionId = null,
            activeMode = "vpn",
            connectionState = "connected",
            networkType = "wifi",
            publicIp = publicIp,
            txPackets = 1,
            txBytes = 2,
            rxPackets = 3,
            rxBytes = 4,
            createdAt = 50L,
        )

    private fun nativeEvent(
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

    private fun approachSummary(strategyId: String) =
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

    private fun scanReport(sessionId: String) =
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
            classifierVersion = "ru_ooni_v1",
            packVersions = mapOf("ru-independent-media" to 1),
        )

    private fun networkSnapshotModel() =
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

    private fun diagnosticContextModel() =
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

    private fun archiveRequest(
        reason: DiagnosticsArchiveReason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
        sessionId: String? = "session-1",
    ) = DiagnosticsArchiveRequest(
        requestedSessionId = sessionId,
        reason = reason,
        requestedAt = 24L,
    )

    private fun buildProvenance() =
        DiagnosticsArchiveBuildProvenance(
            applicationId = "com.poyka.ripdpi",
            appVersionName = "0.0.2",
            appVersionCode = 2L,
            buildType = "debug",
            gitCommit = "unavailable",
            nativeLibraries =
                listOf(
                    DiagnosticsArchiveNativeLibraryProvenance(
                        name = "libripdpi.so",
                        version = "unavailable",
                    ),
                    DiagnosticsArchiveNativeLibraryProvenance(
                        name = "libripdpi-tunnel.so",
                        version = "unavailable",
                    ),
                ),
        )

    private fun appSettings(): AppSettings =
        AppSettings
            .newBuilder()
            .setRipdpiMode("vpn")
            .setEnableCmdSettings(true)
            .setCmdArgs("--fake --split 2")
            .setDiagnosticsActiveProfileId("default")
            .build()

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
