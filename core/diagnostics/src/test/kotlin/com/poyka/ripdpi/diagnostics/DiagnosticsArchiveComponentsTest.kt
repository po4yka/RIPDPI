package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.toNativeEventArchiveSource
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveContextPayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSnapshotPayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSnapshotSource
import com.poyka.ripdpi.diagnostics.export.buildCompleteness
import com.poyka.ripdpi.diagnostics.export.buildNativeEventsCsv
import com.poyka.ripdpi.diagnostics.export.buildSectionStatuses
import com.poyka.ripdpi.diagnostics.export.buildStageIndexEntries
import com.poyka.ripdpi.diagnostics.export.selectedApproachProjection
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
import java.nio.file.attribute.PosixFilePermission

private val DiagnosticsArchiveComponentsJson =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

class DiagnosticsArchiveComponentsTest {
    private val json = DiagnosticsArchiveComponentsJson

    private val redactor = DiagnosticsArchiveRedactor(json)
    private val selector = DiagnosticsArchiveSessionSelector(redactor, json)

    @Test
    fun `file store creates timestamped targets and trims only managed archives`() {
        val cacheDir = Files.createTempDirectory("archive-store").toFile()
        val fileStore =
            DiagnosticsArchiveFileStore(
                cacheDir = cacheDir,
                pcapDirectory = cacheDir.resolve("pcap"),
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
        assertTrue(target.fileName.startsWith("${DiagnosticsArchiveFormat.fileNamePrefix}1700000000000-"))
        assertTrue(target.fileName.endsWith(".zip"))
        assertEquals(target.fileName, target.file.name)
        assertEquals(5, managedFiles.size)
        assertFalse(managedFiles.contains("${DiagnosticsArchiveFormat.fileNamePrefix}expired.zip"))
        assertTrue(archiveDir.resolve("notes.txt").exists())
    }

    @Test
    fun `file store reports deletion refusal and leaves the archive intact`() {
        val cacheDir = Files.createTempDirectory("archive-delete-refusal").toFile()
        val archiveDir = cacheDir.resolve(DiagnosticsArchiveFormat.directoryName).apply { mkdirs() }
        val archive =
            archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}expired.zip").apply {
                writeText("archive")
                setLastModified(0L)
            }
        val fileStore =
            DiagnosticsArchiveFileStore(
                cacheDir = cacheDir,
                pcapDirectory = cacheDir.resolve("pcap"),
                clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                deleteFile = { false },
            )

        val failure = runCatching { fileStore.cleanup() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(archive.exists())
    }

    @Test
    fun `pcap cleanup bounds completed captures but preserves active capture set`() =
        runTest {
            val cacheDir = Files.createTempDirectory("pcap-retention").toFile()
            val pcapDir = cacheDir.resolve("pcap").apply { mkdirs() }
            repeat(5) { index ->
                val setId = (index + 10).toString(16).padStart(16, '0')
                pcapDir.resolve("$setId-1-00.pcap").apply {
                    writeText("completed-$index")
                    setLastModified(1_700_000_000_000L - index * 1_000L)
                }
            }
            val activeSetId = "0000000000000002"
            val activeCompleted =
                pcapDir.resolve("$activeSetId-1-00.pcap").apply {
                    writeText("active-rotation")
                    setLastModified(0L)
                }
            val activeCurrent = pcapDir.resolve("$activeSetId-2-01.pcap.active").apply { writeText("active-current") }
            val fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = cacheDir,
                    pcapDirectory = cacheDir.resolve("pcap"),
                    clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                    withCaptureStorageLock = { it(2L) },
                )

            fileStore.cleanupPcapFiles()

            assertTrue(activeCompleted.exists())
            assertTrue(activeCurrent.exists())
            val inactiveCompleted =
                pcapDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "pcap" && !it.name.startsWith(activeSetId) }
            assertTrue("expected at most four inactive captures, got $inactiveCompleted", inactiveCompleted.size <= 4)
        }

    @Test
    fun `pcap cleanup removes stale markers and expired files from actual capture storage`() =
        runTest {
            val storage = Files.createTempDirectory("pcap-private-retention").toFile()
            try {
                val cacheDir = storage.resolve("cache").apply { mkdirs() }
                val pcapDir = storage.resolve("files/pcap").apply { mkdirs() }
                val staleCapture =
                    pcapDir.resolve("0000000000000001-1-00.pcap").apply {
                        writeText("expired capture")
                        setLastModified(0L)
                    }
                val staleMarker =
                    pcapDir.resolve("0000000000000001-2-01.pcap.active").apply {
                        writeText("orphaned writer")
                    }
                val unrelatedCacheFile = cacheDir.resolve("keep.txt").apply { writeText("unrelated") }
                val fileStore =
                    DiagnosticsArchiveFileStore(
                        cacheDir = cacheDir,
                        pcapDirectory = pcapDir,
                        clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                    )

                fileStore.cleanupPcapFiles()

                assertFalse(staleCapture.exists())
                assertFalse(staleMarker.exists())
                assertTrue(unrelatedCacheFile.exists())
            } finally {
                storage.deleteRecursively()
            }
        }

    @Test
    fun `zip writer rejects unsafe and duplicate entry names and uses owner only permissions`() {
        val cacheDir = Files.createTempDirectory("archive-zip-security").toFile()
        val target = cacheDir.resolve("safe.zip")
        val writer = DiagnosticsArchiveZipWriter()

        listOf("../escape.txt", "/absolute.txt", "stages\\escape.txt").forEach { unsafeName ->
            val failure =
                runCatching {
                    writer.write(target, listOf(DiagnosticsArchiveEntry(unsafeName, byteArrayOf(1))))
                }.exceptionOrNull()
            assertNotNull(unsafeName, failure)
        }
        val duplicateFailure =
            runCatching {
                writer.write(
                    target,
                    listOf(
                        DiagnosticsArchiveEntry("safe.txt", byteArrayOf(1)),
                        DiagnosticsArchiveEntry("safe.txt", byteArrayOf(2)),
                    ),
                )
            }.exceptionOrNull()
        assertNotNull(duplicateFailure)

        writer.write(target, listOf(DiagnosticsArchiveEntry("stages/safe/report.json", byteArrayOf(1))))
        val permissions = Files.getPosixFilePermissions(target.toPath())
        assertTrue(PosixFilePermission.OWNER_READ in permissions)
        assertTrue(PosixFilePermission.OWNER_WRITE in permissions)
        assertFalse(PosixFilePermission.GROUP_READ in permissions)
        assertFalse(PosixFilePermission.OTHERS_READ in permissions)
    }

    @Test
    fun `zip writer never replaces a colliding archive target`() {
        val cacheDir = Files.createTempDirectory("archive-zip-collision").toFile()
        val target = cacheDir.resolve("existing.zip").apply { writeText("existing archive") }

        val failure =
            runCatching {
                DiagnosticsArchiveZipWriter().write(
                    target,
                    listOf(DiagnosticsArchiveEntry("report.json", "replacement".encodeToByteArray())),
                )
            }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("existing archive", target.readText())
    }

    @Test
    fun `redactor hides sensitive network and context data and replaces undecodable payloads`() {
        val snapshotEntity = networkSnapshotEntity(sessionId = "session-1")
        val contextEntity = diagnosticContextEntity(sessionId = "session-1")
        val invalidSnapshot = snapshotEntity.copy(payloadJson = "{not-json}")

        val redactedSnapshot = redactor.redact(snapshotEntity)
        val redactedContext = redactor.redact(contextEntity)

        assertFalse(redactedSnapshot.payloadJson.contains("198.51.100.8"))
        assertTrue(redactedSnapshot.payloadJson.contains("redacted(1)"))
        assertFalse(redactedContext.payloadJson.contains("127.0.0.1:1080"))
        assertTrue(redactedContext.payloadJson.contains("\"proxyEndpoint\": \"redacted\""))
        val redactedInvalidSnapshot = redactor.redact(invalidSnapshot)
        val marker = "{\"redactionStatus\":\"payload_decode_failed\"}"
        assertEquals(invalidSnapshot.copy(payloadJson = marker), redactedInvalidSnapshot)
    }

    @Test
    fun `redactor removes credentials and raw network ids from native events`() {
        val event =
            NativeSessionEventEntity(
                id = "event-sensitive",
                sessionId = "session-1",
                source = "proxy",
                level = "warn",
                message =
                    "Proxy-Authorization: Basic secret ssid=\"Cafe Wifi\" bssid=00:11:22:33:44:55 " +
                        "url=https://user:pass@example.test/path?token=abc123",
                createdAt = 22L,
                runtimeId = "runtime-token=abc123",
                policySignature = "policy-secret=relay-password",
            )

        val redacted = redactor.redact(event)
        val encoded = json.encodeToString(NativeSessionEventEntity.serializer(), redacted)

        assertFalse(encoded.contains("Basic secret"))
        assertFalse(encoded.contains("Cafe Wifi"))
        assertFalse(encoded.contains("00:11:22:33:44:55"))
        assertFalse(encoded.contains("user:pass"))
        assertFalse(encoded.contains("abc123"))
        assertFalse(encoded.contains("relay-password"))
        assertTrue(encoded.contains("redacted"))
    }

    @Test
    fun `native events export keeps autolearn activation receipt but redacts correlators`() {
        val event =
            NativeSessionEventEntity(
                id = "autolearn-activation-fixture",
                sessionId = null,
                source = "app",
                level = "warn",
                message =
                    "autolearn_activation persisted=enabled resolved=enabled " +
                        "effective=disabled source=baseline_settings",
                createdAt = 22L,
                runtimeId = "rt-token=runtime-private-id",
                mode = "proxy",
                policySignature = "policy-private-signature",
                fingerprintHash = "fingerprint-private-hash",
                subsystem = "autolearn_activation",
                attemptSequence = 2L,
                stage = "runtime_ready",
                outcome = "mismatch",
            )

        val csv = buildNativeEventsCsv(primaryEvents = emptyList(), globalEvents = listOf(event))

        assertTrue(csv.contains("autolearn_activation persisted=enabled resolved=enabled"))
        assertTrue(csv.contains("autolearn_activation"))
        assertTrue(csv.contains("\"redacted\""))
        assertFalse(csv.contains("runtime-private-id"))
        assertFalse(csv.contains("policy-private-signature"))
        assertFalse(csv.contains("fingerprint-private-hash"))
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
                            nativeEvent(id = "ev-global", sessionId = null),
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
                    fileLogSnapshot = null,
                )

            val selectedSession = selector.selectPrimarySession(null, null, sourceData.sessions)
            val selection =
                selector.buildSelection(
                    request = archiveRequest(sessionId = null),
                    primarySession = selectedSession,
                    primaryResults = listOf(probeResult(sessionId = "session-latest")),
                    sourceData = sourceData,
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { sessionId ->
                        when (sessionId) {
                            "session-latest" -> listOf(nativeEvent(id = "ev-session", sessionId = sessionId))
                            else -> emptyList()
                        }.toNativeEventArchiveSource()
                    },
                )

            assertEquals("session-latest", selectedSession?.id)
            assertEquals("session-latest", selection.payload.session?.id)
            assertEquals(1, selection.primarySnapshots.size)
            assertEquals(1, selection.primaryContexts.size)
            assertEquals(1, selection.primaryEvents.size)
            assertNotNull(selection.latestPassiveSnapshot)
            assertNotNull(selection.latestPassiveContext)
            assertEquals(DiagnosticsArchiveSnapshotSource.SESSION, selection.latestSnapshotSource)
            assertEquals(listOf("ev-global"), selection.globalEvents.map { it.id })
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
    fun `selector fetches primary and composite events without treating other sessions as global`() =
        runTest {
            val fixture = compositeSelectionFixture()
            val primary = fixture.primary
            val stage = fixture.stage
            val fetchedSessionIds = mutableListOf<String>()

            val selection =
                selector.buildSelection(
                    request =
                        archiveRequest(sessionId = primary.id).copy(
                            homeRunId = "run-1",
                            sessionIds = listOf(primary.id, stage.id),
                        ),
                    primarySession = primary,
                    primaryResults = emptyList(),
                    sourceData = fixture.sourceData,
                    compositeOutcome = fixture.outcome,
                    compositeSessions = listOf(primary, stage),
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { sessionId ->
                        fetchedSessionIds += sessionId
                        listOf(nativeEvent(id = "ev-$sessionId", sessionId = sessionId)).toNativeEventArchiveSource()
                    },
                    loadStageTelemetry = { session, _ ->
                        val count =
                            if (session.id == primary.id) {
                                DiagnosticsArchiveFormat.telemetryLimit + 1
                            } else {
                                1
                            }
                        List(count) { index ->
                            telemetrySample(publicIp = null).copy(
                                id = "telemetry-${session.id}-$index",
                                sessionId = null,
                                createdAt = session.startedAt + index,
                            )
                        }
                    },
                )

            assertEquals(listOf("ev-session-primary"), selection.primaryEvents.map { it.id })
            assertEquals(listOf("ev-global"), selection.globalEvents.map { it.id })
            assertEquals(
                listOf(listOf("ev-session-primary"), listOf("ev-session-stage")),
                selection.compositeStages.map { stageSelection -> stageSelection.events.map { it.id } },
            )
            assertEquals(listOf("session-primary", "session-stage"), fetchedSessionIds)
            val primaryStage = selection.compositeStages.first()
            assertEquals(DiagnosticsArchiveFormat.telemetryLimit, primaryStage.telemetry.size)
            assertEquals(DiagnosticsArchiveFormat.telemetryLimit + 1, primaryStage.sourceTelemetryCount)
            val primaryIndex = buildStageIndexEntries(selection).first()
            assertTrue(primaryIndex.telemetryTruncated)
            assertEquals(DiagnosticsArchiveFormat.telemetryLimit, primaryIndex.includedTelemetryCount)
        }

    @Test
    fun `composite completeness counts raw stage overflow before included limits`() =
        runTest {
            val fixture = compositeSelectionFixture()
            val rawStageEventCount = DiagnosticsArchiveFormat.sessionEventLimit + 1
            val rawStageTelemetryCount = DiagnosticsArchiveFormat.telemetryLimit + 1
            val selection =
                selector.buildSelection(
                    request =
                        archiveRequest(sessionId = fixture.primary.id).copy(
                            homeRunId = fixture.outcome.runId,
                            sessionIds = listOf(fixture.primary.id, fixture.stage.id),
                        ),
                    primarySession = fixture.primary,
                    primaryResults = emptyList(),
                    sourceData = fixture.sourceData,
                    compositeOutcome = fixture.outcome,
                    compositeSessions = listOf(fixture.primary, fixture.stage),
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { sessionId ->
                        (
                            if (sessionId == fixture.stage.id) {
                                List(rawStageEventCount) { index ->
                                    nativeEvent(id = "raw-stage-event-$index", sessionId = sessionId)
                                }
                            } else {
                                emptyList()
                            }
                        ).toNativeEventArchiveSource()
                    },
                    loadStageTelemetry = { session, _ ->
                        if (session.id == fixture.stage.id) {
                            List(rawStageTelemetryCount) { index ->
                                telemetrySample(publicIp = null).copy(
                                    id = "raw-stage-telemetry-$index",
                                    sessionId = session.id,
                                    createdAt = session.startedAt + index,
                                )
                            }
                        } else {
                            emptyList()
                        }
                    },
                )

            val completeness =
                buildCompleteness(
                    selection = selection,
                    sectionStatuses = buildSectionStatuses(selection),
                    snapshotPayload =
                        DiagnosticsArchiveSnapshotPayload(
                            sessionSnapshots = emptyList(),
                            latestPassiveSnapshot = null,
                        ),
                    contextPayload =
                        DiagnosticsArchiveContextPayload(
                            sessionContexts = emptyList(),
                            latestPassiveContext = null,
                        ),
                )

            assertEquals(
                fixture.sourceData.events.size + rawStageEventCount,
                completeness.sourceCounts.archiveWide.nativeEvents,
            )
            assertEquals(rawStageTelemetryCount, completeness.sourceCounts.archiveWide.telemetrySamples)
            assertEquals(
                fixture.sourceData.events.size + DiagnosticsArchiveFormat.sessionEventLimit,
                completeness.includedCounts.archiveWide.nativeEvents,
            )
            assertEquals(
                DiagnosticsArchiveFormat.telemetryLimit,
                completeness.includedCounts.archiveWide.telemetrySamples,
            )
        }
}

class DiagnosticsArchiveCompositeSelectionTest {
    private val json = DiagnosticsArchiveComponentsJson
    private val redactor = DiagnosticsArchiveRedactor(json)
    private val selector = DiagnosticsArchiveSessionSelector(redactor, json)

    @Test
    fun `composite stages without sessions do not inherit passive artifacts`() =
        runTest {
            val sourceData = sessionlessCompositeSourceData()
            val outcome = sessionlessCompositeOutcome()

            val selection =
                selector.buildSelection(
                    request =
                        archiveRequest(sessionId = null).copy(
                            homeRunId = outcome.runId,
                            sessionIds = listOf("unavailable-stage-session"),
                        ),
                    primarySession = null,
                    primaryResults = emptyList(),
                    sourceData = sourceData,
                    compositeOutcome = outcome,
                    compositeSessions = emptyList(),
                    loadProbeResults = { error("A stage without a session must not load probe results") },
                    loadNativeEventSource = { error("A stage without a session must not load native events") },
                )

            assertEquals(4, selection.compositeStages.size)
            selection.compositeStages.forEach { stage ->
                assertEquals(null, stage.session)
                assertTrue(stage.results.isEmpty())
                assertTrue(stage.snapshots.isEmpty())
                assertTrue(stage.contexts.isEmpty())
                assertTrue(stage.events.isEmpty())
            }
            assertEquals("snap-passive", selection.latestPassiveSnapshot?.id)
            assertEquals("ctx-passive", selection.latestPassiveContext?.id)
            assertEquals(listOf("ev-global"), selection.globalEvents.map { it.id })
            assertEquals(
                "evidence_unavailable",
                buildStageIndexEntries(selection)
                    .single { it.stageKey == "completed-without-evidence" }
                    .status,
            )
            val passiveStage =
                buildStageIndexEntries(selection)
                    .single { it.stageKey == "vpn_route_evidence" }
            assertEquals("completed", passiveStage.status)
            assertEquals("passive_vpn_route", passiveStage.evidenceType)
            assertEquals("vpn_route_observation", passiveStage.vantage)
            assertEquals("unverified", passiveStage.targetReachability)
            assertEquals(42L, passiveStage.passiveVpnRouteEvidence?.vpnRouteEvidence?.lifecycleGeneration)
        }

    private fun sessionlessCompositeSourceData() =
        DiagnosticsArchiveSourceData(
            sessions = emptyList(),
            usageSessions = emptyList(),
            snapshots = listOf(networkSnapshotEntity(id = "snap-passive", sessionId = null)),
            telemetry = emptyList(),
            events = listOf(nativeEvent(id = "ev-global", sessionId = null)),
            contexts = listOf(diagnosticContextEntity(id = "ctx-passive", sessionId = null)),
            approachSummaries = emptyList(),
            appSettings = appSettings(),
            buildProvenance = buildProvenance(),
            collectionWarnings = emptyList(),
            logcatSnapshot = null,
            fileLogSnapshot = null,
        )

    private fun sessionlessCompositeOutcome() =
        DiagnosticsHomeCompositeOutcome(
            runId = "run-without-stage-sessions",
            actionable = false,
            headline = "Incomplete",
            summary = "Stages did not create scan sessions.",
            stageSummaries =
                listOf(
                    stageWithoutSession("skipped", DiagnosticsHomeCompositeStageStatus.SKIPPED),
                    stageWithoutSession("failed", DiagnosticsHomeCompositeStageStatus.FAILED),
                    stageWithoutSession("completed-without-evidence", DiagnosticsHomeCompositeStageStatus.COMPLETED),
                    passiveVpnRouteEvidenceStage(),
                ),
        )

    private fun passiveVpnRouteEvidenceStage() =
        stageWithoutSession("vpn_route_evidence", DiagnosticsHomeCompositeStageStatus.COMPLETED).copy(
            pathMode = null,
            passiveVpnRouteEvidence =
                NetworkPathValidationEvidence(
                    captureStatus = "captured",
                    vpnRouteEvidence =
                        VpnRouteEvidenceSnapshot(
                            lifecycleGeneration = 42L,
                            lifecycleState = "established",
                            callbackState = "complete",
                            ownerVerification = "verified",
                            ownPackageExcluded = true,
                            routeConsistency = "consistent",
                            forwardingOutcome = "running",
                            forwardingLifecycleGeneration = 42L,
                        ),
                ),
        )

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
                    fileLogSnapshot = null,
                )

            val selection =
                selector.buildSelection(
                    request = archiveRequest(reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE, sessionId = null),
                    primarySession = sourceData.sessions.single(),
                    primaryResults = emptyList(),
                    sourceData = sourceData,
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { emptyList<NativeSessionEventEntity>().toNativeEventArchiveSource() },
                )

            assertEquals(
                DiagnosticsArchiveSessionSelectionStatus.SUPPORT_BUNDLE,
                selection.sessionSelectionStatus,
            )
        }

    @Test
    fun `explicit older strategy session keeps its session scoped assessment outside recent summaries`() =
        runTest {
            val signature =
                BypassStrategySignature(
                    mode = "VPN",
                    configSource = "ui",
                    hostAutolearn = "disabled",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(host+1)",
                    tcpStrategyFamily = "split",
                    protocolToggles = listOf("HTTPS"),
                    tlsRecordSplitEnabled = false,
                    splitMarker = "host+1",
                )
            val strategyId = signature.stableId()
            val primary =
                scanSession(id = "older-explicit-session", strategyId = strategyId).copy(
                    strategyLabel = "split(host+1)",
                    strategyJson = json.encodeToString(BypassStrategySignature.serializer(), signature),
                )
            val sourceData =
                DiagnosticsArchiveSourceData(
                    sessions = emptyList(),
                    usageSessions = emptyList(),
                    snapshots = emptyList(),
                    telemetry = emptyList(),
                    events = emptyList(),
                    contexts = emptyList(),
                    approachSummaries = listOf(approachSummary(strategyId)),
                    appSettings = appSettings(),
                    buildProvenance = buildProvenance(),
                    collectionWarnings = emptyList(),
                    logcatSnapshot = null,
                    fileLogSnapshot = null,
                )

            val selection =
                selector.buildSelection(
                    request = archiveRequest(sessionId = primary.id),
                    primarySession = primary,
                    primaryResults = emptyList(),
                    sourceData = sourceData,
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { emptyList<NativeSessionEventEntity>().toNativeEventArchiveSource() },
                )

            assertEquals(strategyId, selection.selectedApproachSummary?.approachId?.value)
            assertNotNull(selection.selectedApproachSummary?.currentStrategyAssessment)
            assertEquals(
                CurrentStrategyCandidateVerdict.INCOMPLETE,
                selection.selectedApproachSummary?.currentStrategyAssessment?.candidateVerdict,
            )
            assertEquals(
                BypassApproachVerificationState.INCOMPLETE_EVIDENCE,
                selection.selectedApproachSummary?.verificationState,
            )
            assertEquals(0, selection.selectedApproachSummary?.validatedScanCount)
            assertEquals(0, selection.selectedApproachSummary?.validatedSuccessCount)
            assertNull(selection.selectedApproachSummary?.validatedSuccessRate)
            assertEquals(0, selection.selectedApproachSummary?.usageCount)
            assertEquals("strategy-1", selection.selectedApproachProjection()?.approachId?.value)
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
}

class DiagnosticsArchiveContextSelectionTest {
    private val selector =
        DiagnosticsArchiveSessionSelector(
            DiagnosticsArchiveRedactor(DiagnosticsArchiveComponentsJson),
            DiagnosticsArchiveComponentsJson,
        )

    @Test
    fun `selector retains post runtime restore receipt beyond generic context quota`() =
        runTest {
            val session = scanSession(id = "session-restore")
            val genericContexts =
                List(DiagnosticsArchiveFormat.snapshotLimit) { index ->
                    diagnosticContextEntity(
                        id = "generic-$index",
                        sessionId = session.id,
                        capturedAt = index.toLong(),
                    )
                }
            val receipt =
                diagnosticContextEntity(id = "restore-receipt", sessionId = session.id).copy(
                    contextKind = "post_runtime_restore",
                    payloadJson = "{\"settlement\":{}}",
                )
            val fixture = compositeSelectionFixture()
            val selection =
                selector.buildSelection(
                    request = archiveRequest(sessionId = session.id),
                    primarySession = session,
                    primaryResults = emptyList(),
                    sourceData =
                        fixture.sourceData.copy(
                            sessions = listOf(session),
                            contexts = genericContexts + receipt,
                        ),
                    loadProbeResults = { emptyList() },
                    loadNativeEventSource = { emptyList<NativeSessionEventEntity>().toNativeEventArchiveSource() },
                )

            assertEquals(DiagnosticsArchiveFormat.snapshotLimit + 1, selection.primaryContexts.size)
            assertTrue(selection.primaryContexts.any { it.id == receipt.id })
        }
}

private data class CompositeSelectionFixture(
    val primary: ScanSessionEntity,
    val stage: ScanSessionEntity,
    val sourceData: DiagnosticsArchiveSourceData,
    val outcome: DiagnosticsHomeCompositeOutcome,
)

private fun compositeSelectionFixture(): CompositeSelectionFixture {
    val primary = scanSession(id = "session-primary")
    val stage = scanSession(id = "session-stage")
    return CompositeSelectionFixture(
        primary = primary,
        stage = stage,
        sourceData =
            DiagnosticsArchiveSourceData(
                sessions = listOf(primary, stage),
                usageSessions = emptyList(),
                snapshots = emptyList(),
                telemetry = emptyList(),
                events = listOf(nativeEvent(id = "ev-global", sessionId = null)),
                contexts = emptyList(),
                approachSummaries = emptyList(),
                appSettings = appSettings(),
                buildProvenance = buildProvenance(),
                collectionWarnings = emptyList(),
                logcatSnapshot = null,
                fileLogSnapshot = null,
            ),
        outcome =
            DiagnosticsHomeCompositeOutcome(
                runId = "run-1",
                actionable = false,
                headline = "Complete",
                summary = "Complete",
                stageSummaries =
                    listOf(
                        compositeStageSummary("primary", "Primary", primary),
                        compositeStageSummary("stage", "Stage", stage),
                    ),
            ),
    )
}

private fun compositeStageSummary(
    stageKey: String,
    stageLabel: String,
    session: ScanSessionEntity,
): DiagnosticsHomeCompositeStageSummary =
    DiagnosticsHomeCompositeStageSummary(
        stageKey = stageKey,
        stageLabel = stageLabel,
        profileId = session.profileId,
        pathMode = ScanPathMode.IN_PATH,
        sessionId = session.id,
        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
        headline = "Complete",
        summary = "Complete",
    )

private fun scanSession(
    id: String,
    strategyId: String? = null,
    status: String = "finished",
    reportJson: String? = DiagnosticsArchiveComponentsJson.encodeToString(scanReport(id).toEngineScanReportWire()),
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

private fun stageWithoutSession(
    stageKey: String,
    status: DiagnosticsHomeCompositeStageStatus,
) = DiagnosticsHomeCompositeStageSummary(
    stageKey = stageKey,
    stageLabel = stageKey,
    profileId = "default",
    pathMode = ScanPathMode.RAW_PATH,
    status = status,
    headline = stageKey,
    summary = stageKey,
)

private fun probeResult(sessionId: String) =
    ProbeResultEntity(
        id = "probe-$sessionId",
        sessionId = sessionId,
        probeType = "dns",
        target = "blocked.example",
        outcome = "substituted",
        detailJson =
            DiagnosticsArchiveComponentsJson.encodeToString(
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
    payloadJson =
        DiagnosticsArchiveComponentsJson.encodeToString(NetworkSnapshotModel.serializer(), networkSnapshotModel()),
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
    payloadJson =
        DiagnosticsArchiveComponentsJson.encodeToString(
            DiagnosticContextModel.serializer(),
            diagnosticContextModel(),
        ),
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
        verificationState = BypassApproachVerificationState.CONFIRMED_WORKING,
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
