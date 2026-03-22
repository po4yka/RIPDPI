package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
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
    fun `selector chooses latest completed session and partitions passive data`() {
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
                logcatSnapshot = null,
            )

        val selectedSession = selector.selectPrimarySession(null, null, sourceData.sessions)
        val selection =
            selector.buildSelection(
                primarySession = selectedSession,
                primaryResults = listOf(probeResult(sessionId = "session-latest")),
                sourceData = sourceData,
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
            DiagnosticsArchiveFormat.includedFiles(logcatIncluded = false),
            selection.includedFiles,
        )
    }

    @Test
    fun `renderer emits redacted archive entries with manifest summaries`() {
        val selection =
            DiagnosticsArchiveSelection(
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
                .contains("diagnosis.dns_tampering=DNS answers were substituted"),
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
        assertEquals("redacted", manifest.networkSummary?.publicIp)
        assertEquals("redacted", manifest.contextSummary?.service?.proxyEndpoint)
        assertEquals("ru_ooni_v1", manifest.classifierVersion)
        assertEquals(1, manifest.diagnosisCount)
        assertEquals(1, manifest.packVersions["ru-independent-media"])
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
}
