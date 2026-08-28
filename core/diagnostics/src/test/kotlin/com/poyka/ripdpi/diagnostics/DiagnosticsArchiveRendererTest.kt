package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.StartupJournalSnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveAnalysisPayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveInstalledArtifact
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveInstalledArtifactCollectionStatus
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveInstalledNativeLibrary
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeAbi
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSigningLineageBand
import com.poyka.ripdpi.diagnostics.export.ExecutionPlanArchivePayload
import com.poyka.ripdpi.diagnostics.export.buildMeasurementSnapshot
import com.poyka.ripdpi.diagnostics.export.buildSectionStatuses
import com.poyka.ripdpi.diagnostics.replay.ReplayErrorKind
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayStepKind
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

@Suppress("detekt.LargeClass")
class DiagnosticsArchiveRendererTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val providerState = FakeServiceStateStore()
    private val redactor = DiagnosticsArchiveRedactor(json)
    private val renderer =
        DiagnosticsArchiveRenderer(
            redactor,
            DiagnosticsSummaryProjector(),
            ReplayArchiveEntryBuilder(
                ReplayArchiveRedactor(),
                DiagnosticsArchiveClock { System.currentTimeMillis() },
                json,
            ),
            json,
            serviceStateStore = providerState,
        )

    @Test
    fun `exported ZIP includes current Xray context with verified integrity`() {
        providerState.updateTelemetry(
            com.poyka.ripdpi.data.ServiceTelemetrySnapshot(
                xrayProviderSnapshot = com.poyka.ripdpi.data.xray.XrayProviderDiagnosticsFixtures.healthy.snapshot,
            ),
        )
        val directory = Files.createTempDirectory("xray-diagnostics").toFile()
        val file = java.io.File(directory, "diagnostics.zip")
        try {
            val target = DiagnosticsArchiveTarget(file, file.name, 42L)
            val entries = renderer.render(target, buildFullRendererSelection())
            DiagnosticsArchiveZipWriter().write(file, entries)
            ZipFile(file).use { zip ->
                val summaryBytes = zip.getInputStream(zip.getEntry("summary.txt")).readBytes()
                val summary = summaryBytes.decodeToString()
                assertTrue(summary.contains("Current provider at export time"))
                assertTrue(summary.contains("readiness: OutboundHealthy"))
                assertFalse(summary.contains("profile: "))
                val integrity = zip.getInputStream(zip.getEntry("integrity.json")).reader().readText()
                val digest =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(summaryBytes)
                        .joinToString("") { "%02x".format(it) }
                assertTrue(integrity.contains(digest))
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun `schema 10 analysis decodes new strategy evidence as unverified defaults`() {
        val fixture =
            requireNotNull(javaClass.classLoader?.getResource("golden/archive/analysis_v10.json"))
                .readText()

        val decoded = json.decodeFromString(DiagnosticsArchiveAnalysisPayload.serializer(), fixture)

        assertNull(decoded.strategyExecutionDetail.currentStrategyAssessment)
        assertTrue(decoded.strategyExecutionDetail.tcpCandidates.all { it.executionAttempts.isEmpty() })
        assertTrue(decoded.strategyExecutionDetail.tcpCandidates.none { it.executionEvidenceComplete })
        assertTrue(decoded.strategyExecutionDetail.tcpCandidates.all { it.routeFeatures.isEmpty() })
    }

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
    fun `renderer does not retain recoverable socks hostname bytes`() {
        val encodedHostname = "c2Vuc2l0aXZlLmV4YW1wbGU="
        val numericHostname = "115,101,110,115,105,116,105,118,101,46,101,120,97,109,112,108,101"
        val base = buildFullRendererSelection()
        val hostileResult =
            rendererProbeResult(sessionId = "session-1").copy(
                detailJson =
                    """{"socksRequestBytes":"$encodedHostname","rawBytes":[$numericHostname]}""",
            )
        val selection =
            base.copy(
                payload = base.payload.copy(results = listOf(hostileResult)),
                primaryResults = listOf(hostileResult),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-raw-wire", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-raw-wire.zip",
                createdAt = 42L,
            )

        val renderedArchive = renderer.render(target, selection).joinToString("\n") { it.bytes.decodeToString() }

        assertFalse(renderedArchive.contains(encodedHostname))
        assertFalse(renderedArchive.contains(numericHostname))
        assertFalse(renderedArchive.contains("sensitive.example"))
    }

    @Test
    fun `measurement snapshot exports evidence for the detectability verdict`() {
        val selection = buildFullRendererSelection()
        val strategyProbe = rendererScanReport("session-1").strategyProbeReport

        val snapshot = buildMeasurementSnapshot(selection, strategyProbe, latestTelemetry = null)

        assertEquals(
            listOf(
                "lane=tcp;candidateId=tcp-prod;emitterTier=NON_ROOT_PRODUCTION;" +
                    "emitterDowngraded=false;exactEmitterRequiresRoot=false",
                "lane=quic;candidateId=quic-prod;emitterTier=NON_ROOT_PRODUCTION;" +
                    "emitterDowngraded=false;exactEmitterRequiresRoot=false",
            ),
            snapshot.detectabilityMetrics.evidence,
        )
    }

    @Test
    fun `analysis exports a chronological privacy-safe runtime snapshot timeline`() {
        val connectionId = "persisted-connection-id"
        val snapshots =
            listOf(
                rendererNetworkSnapshotEntity(
                    id = "persisted-snapshot-c",
                    sessionId = null,
                    capturedAt = 30L,
                ),
                rendererNetworkSnapshotEntity(
                    id = "persisted-snapshot-a",
                    sessionId = null,
                    capturedAt = 10L,
                ),
                rendererNetworkSnapshotEntity(
                    id = "persisted-snapshot-b",
                    sessionId = null,
                    capturedAt = 20L,
                ),
            ).map { snapshot ->
                snapshot.copy(
                    connectionSessionId = connectionId,
                    snapshotKind = "connection_sample",
                )
            }
        val selection = buildFullRendererSelection().copy(primarySnapshots = snapshots)
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-runtime-snapshot-timeline", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-runtime-snapshot-timeline.zip",
                createdAt = 42L,
            )

        val analysis =
            renderer
                .render(target, selection)
                .single { entry -> entry.name == "analysis.json" }
                .bytes
                .decodeToString()
                .let(json::parseToJsonElement)
                .jsonObject

        assertEquals(
            json.parseToJsonElement(
                """
                [
                    {"snapshotRef":"snapshot-1","connectionRef":"connection-1","capturedAt":10},
                    {"snapshotRef":"snapshot-2","connectionRef":"connection-1","capturedAt":20},
                    {"snapshotRef":"snapshot-3","connectionRef":"connection-1","capturedAt":30}
                ]
                """.trimIndent(),
            ),
            analysis["runtimeSnapshotTimeline"],
        )
    }

    @Test
    fun `runtime snapshot timeline references exported redacted snapshot records`() {
        val persistedConnectionId = "persisted-connection-id"
        val persistedSnapshotIds = listOf("persisted-snapshot-a", "persisted-snapshot-b")
        val snapshots =
            listOf(20L, 10L).mapIndexed { index, capturedAt ->
                rendererNetworkSnapshotEntity(
                    id = persistedSnapshotIds[index],
                    sessionId = null,
                    capturedAt = capturedAt,
                ).copy(
                    connectionSessionId = persistedConnectionId,
                    snapshotKind = "connection_sample",
                )
            }
        val selection = buildFullRendererSelection().copy(primarySnapshots = snapshots)
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-runtime-snapshot-records", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-runtime-snapshot-records.zip",
                createdAt = 42L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val timeline =
            entries
                .getValue("analysis.json")
                .bytes
                .decodeToString()
                .let(json::parseToJsonElement)
                .jsonObject
                .getValue("runtimeSnapshotTimeline")
                .jsonArray
        val networkSnapshots =
            entries
                .getValue("network-snapshots.json")
                .bytes
                .decodeToString()
                .let(json::parseToJsonElement)
                .jsonObject
        val records = networkSnapshots.getValue("runtimeSnapshots").jsonArray

        assertEquals(2, records.size)
        timeline.zip(records).forEach { (timelineEntry, record) ->
            val timelineObject = timelineEntry.jsonObject
            val recordObject = record.jsonObject
            assertEquals(timelineObject["snapshotRef"], recordObject["snapshotRef"])
            assertEquals(timelineObject["connectionRef"], recordObject["connectionRef"])
            assertEquals(timelineObject["capturedAt"], recordObject["capturedAt"])
            val redactedSnapshot = recordObject.getValue("snapshot").jsonObject
            assertEquals("wifi", redactedSnapshot.getValue("transport").jsonPrimitive.content)
            assertEquals(
                "redacted(1)",
                redactedSnapshot
                    .getValue("dnsServers")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals("redacted", redactedSnapshot.getValue("publicIp").jsonPrimitive.content)
            assertEquals("redacted", redactedSnapshot.getValue("publicAsn").jsonPrimitive.content)
        }
        val exportedRuntimeEvidence = timeline.toString() + records.toString()
        assertFalse(exportedRuntimeEvidence.contains(persistedConnectionId))
        persistedSnapshotIds.forEach { persistedId ->
            assertFalse(exportedRuntimeEvidence.contains(persistedId))
        }
    }

    @Test
    fun `analysis correlates operational failures without promoting a later generic warning`() {
        val connectionId = "persisted-connection-id"
        val snapshots =
            listOf(10L, 20L, 30L).mapIndexed { index, capturedAt ->
                rendererNetworkSnapshotEntity(
                    id = "persisted-snapshot-${index + 1}",
                    sessionId = null,
                    capturedAt = capturedAt,
                ).copy(connectionSessionId = connectionId, snapshotKind = "connection_sample")
            }

        fun failure(
            id: String,
            failureClass: String,
            createdAt: Long,
            eventConnectionId: String = connectionId,
        ) = NativeSessionEventEntity(
            id = id,
            connectionSessionId = eventConnectionId,
            source = "runtime",
            level = "error",
            message = "typed operational failure",
            createdAt = createdAt,
            subsystem = "proxy",
            failureStage = "connect",
            failureClass = failureClass,
        )
        val failures =
            listOf(
                failure("persisted-failure-after", "after_failure", 5L),
                failure("persisted-failure-exact", "exact_failure", 20L),
                failure("persisted-failure-before", "before_failure", 25L),
                failure("persisted-failure-unavailable", "unavailable_failure", 40L, "other-connection-id"),
                rendererNativeEvent(id = "generic-later-warning", sessionId = null, level = "warn").copy(
                    connectionSessionId = connectionId,
                    message = "generic warning must not become latest failure",
                    createdAt = 50L,
                ),
            )
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                payload = base.payload.copy(telemetry = emptyList(), globalEvents = failures),
                primaryEvents = emptyList(),
                globalEvents = failures,
                runtimeSnapshots = snapshots,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-failure-snapshot-correlation", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-failure-snapshot-correlation.zip",
                createdAt = 60L,
            )

        val failureEnvelope =
            renderer
                .render(target, selection)
                .single { entry -> entry.name == "analysis.json" }
                .bytes
                .decodeToString()
                .let(json::parseToJsonElement)
                .jsonObject
                .getValue("failureEnvelope")
                .jsonObject

        assertEquals(
            listOf(
                json.parseToJsonElement("\"unavailable_failure\""),
                json.parseToJsonElement(
                    """
                    [
                        {"failureClass":"after_failure","occurredAt":5,"correlation":"nearest_after","deltaMs":5,"snapshotRef":"snapshot-1"},
                        {"failureClass":"exact_failure","occurredAt":20,"correlation":"exact","deltaMs":0,"snapshotRef":"snapshot-2"},
                        {"failureClass":"before_failure","occurredAt":25,"correlation":"nearest_before","deltaMs":5,"snapshotRef":"snapshot-2"},
                        {"failureClass":"unavailable_failure","occurredAt":40,"correlation":"unavailable"}
                    ]
                    """.trimIndent(),
                ),
            ),
            listOf(failureEnvelope["latestFailureClass"], failureEnvelope["failureRecords"]),
        )
    }

    @Test
    fun `detectability verdict cites its exported evidence`() {
        val selection = buildFullRendererSelection()
        val strategyProbe = rendererScanReport("session-1").strategyProbeReport

        val snapshot = buildMeasurementSnapshot(selection, strategyProbe, latestTelemetry = null)
        val verdict =
            snapshot.rolloutGateAssessment.results.single { result ->
                result.id == "detectability_budget"
            }

        assertEquals(snapshot.detectabilityMetrics.evidence.joinToString(" | "), verdict.rationale)
    }

    @Test
    fun `detectability verdict fails closed without recommended candidate evidence`() {
        val snapshot =
            buildMeasurementSnapshot(
                buildFullRendererSelection(),
                strategyProbe = null,
                latestTelemetry = null,
            )
        val verdict =
            snapshot.rolloutGateAssessment.results.single { result ->
                result.id == "detectability_budget"
            }

        assertFalse(verdict.passed)
    }

    @Test
    fun `renderer confines installed artifact fingerprints to provenance entry`() {
        val baseHash = "a".repeat(64)
        val splitHash = "b".repeat(64)
        val signerHash = "c".repeat(64)
        val nativeHash = "d".repeat(64)
        val selection =
            buildFullRendererSelection().copy(
                installedArtifact =
                    DiagnosticsArchiveInstalledArtifact(
                        collectionStatus = DiagnosticsArchiveInstalledArtifactCollectionStatus.COMPLETE,
                        baseApkSha256 = baseHash,
                        splitApkSha256 = listOf(splitHash),
                        currentSignerCertificateSha256 = listOf(signerHash),
                        signingLineage = DiagnosticsArchiveSigningLineageBand.SINGLE_WITH_HISTORY,
                        packagedNativeLibrarySha256 =
                            listOf(
                                DiagnosticsArchiveInstalledNativeLibrary(
                                    abi = DiagnosticsArchiveNativeAbi.ARM64,
                                    name = "libripdpi.so",
                                    sha256 = nativeHash,
                                ),
                            ),
                        debuggable = false,
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-installed-artifact", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-installed-artifact.zip",
                createdAt = 45L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val provenanceText = entries.getValue("archive-provenance.json").bytes.decodeToString()
        val provenance = json.decodeFromString(DiagnosticsArchiveProvenancePayload.serializer(), provenanceText)

        assertEquals(
            DiagnosticsArchiveInstalledArtifactCollectionStatus.COMPLETE,
            provenance.installedArtifact?.collectionStatus,
        )
        assertTrue(provenanceText.contains(baseHash))
        assertTrue(provenanceText.contains(splitHash))
        assertTrue(provenanceText.contains(signerHash))
        assertTrue(provenanceText.contains(nativeHash))
        entries.filterKeys { it != "archive-provenance.json" }.forEach { (name, entry) ->
            val content = entry.bytes.decodeToString()
            assertFalse("installedArtifact must be absent from $name", content.contains("installedArtifact"))
            listOf(baseHash, splitHash, signerHash, nativeHash).forEach { hash ->
                assertFalse("artifact hash must be absent from $name", content.contains(hash))
            }
        }
        listOf(
            "sourceDir",
            "splitName",
            "installer",
            "lastUpdateTime",
            "modifiedAt",
            "size",
            "subject",
            "issuer",
            "serial",
            "exception",
        ).forEach { forbidden -> assertFalse("$forbidden must not appear", provenanceText.contains(forbidden)) }
    }

    @Test
    fun `renderer keeps active failure context when a later session context is halted and idle`() {
        val base = buildFullRendererSelection()
        val idleRuntime = RuntimeComponentSummary(state = "idle", health = "idle")
        val haltedContext =
            rendererDiagnosticContextModel().copy(
                service =
                    rendererDiagnosticContextModel().service.copy(
                        serviceStatus = "Halted",
                        lastNativeErrorHeadline = "none",
                        proxy = idleRuntime,
                        tunnel = idleRuntime,
                        relay = idleRuntime,
                        warp = idleRuntime,
                    ),
            )
        val activeFailureContext =
            rendererDiagnosticContextModel().copy(
                service =
                    rendererDiagnosticContextModel().service.copy(
                        serviceStatus = "Running",
                        lastNativeErrorHeadline = "relay transport failed",
                        relay =
                            RuntimeComponentSummary(
                                state = "failed",
                                health = "degraded",
                                lastError = "udp associate unsupported",
                                lastFailureClass = "protocol_unsupported",
                            ),
                    ),
                device = rendererDiagnosticContextModel().device.copy(androidVersion = "15", apiLevel = 35),
            )
        val sessionContextEntity =
            rendererDiagnosticContextEntity(sessionId = "session-1", capturedAt = 40L).copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), haltedContext),
            )
        val passiveContextEntity =
            rendererDiagnosticContextEntity(id = "ctx-passive", sessionId = null, capturedAt = 30L).copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), activeFailureContext),
            )
        val passiveSnapshot = rendererNetworkSnapshotEntity(id = "snap-passive", sessionId = null, capturedAt = 35L)
        val selection =
            base.copy(
                primaryContexts = listOf(sessionContextEntity),
                latestPassiveContext = passiveContextEntity,
                latestContextModel = activeFailureContext,
                sessionContextModel = haltedContext,
                latestPassiveSnapshot = passiveSnapshot,
                latestSnapshotModel = rendererNetworkSnapshotModel().copy(capturedAt = 35L),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-runtime-context", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-runtime-context.zip",
                createdAt = 50L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        assertMixedRuntimeContext(entries)
    }

    @Test
    fun `renderer keeps active failure context when a newer active context is healthy`() {
        val base = buildFullRendererSelection()
        val activeHealthyContext =
            rendererDiagnosticContextModel().copy(
                service =
                    rendererDiagnosticContextModel().service.copy(
                        serviceStatus = "Running",
                        lastNativeErrorHeadline = "none",
                        relay = RuntimeComponentSummary(state = "running", health = "healthy"),
                    ),
            )
        val activeFailureContext =
            activeHealthyContext.copy(
                service =
                    activeHealthyContext.service.copy(
                        lastNativeErrorHeadline = "relay transport failed",
                        relay =
                            RuntimeComponentSummary(
                                state = "failed",
                                health = "degraded",
                                lastFailureClass = "protocol_unsupported",
                            ),
                    ),
            )
        val sessionContextEntity =
            rendererDiagnosticContextEntity(sessionId = "session-1", capturedAt = 40L).copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), activeHealthyContext),
            )
        val passiveContextEntity =
            rendererDiagnosticContextEntity(id = "ctx-passive", sessionId = null, capturedAt = 30L).copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), activeFailureContext),
            )
        val selection =
            base.copy(
                primaryContexts = listOf(sessionContextEntity),
                latestPassiveContext = passiveContextEntity,
                latestContextModel = activeFailureContext,
                sessionContextModel = activeHealthyContext,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-active-failure", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-active-failure.zip",
                createdAt = 50L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val runtimeConfig =
            json.decodeFromString(
                DiagnosticsArchiveRuntimeConfigPayload.serializer(),
                entries.getValue("runtime-config.json").bytes.decodeToString(),
            )

        assertEquals("latest_passive_context", runtimeConfig.runtimeContextSource)
        assertEquals(30L, runtimeConfig.runtimeContextCapturedAt)
        assertEquals("degraded", runtimeConfig.relayRuntime?.health)
        assertEquals("session_context", runtimeConfig.terminalContextSource)
        assertEquals(40L, runtimeConfig.terminalContextCapturedAt)
    }

    private fun assertMixedRuntimeContext(entries: Map<String, DiagnosticsArchiveEntry>) {
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
        val manifest =
            json.decodeFromString(
                DiagnosticsArchiveManifest.serializer(),
                entries.getValue("manifest.json").bytes.decodeToString(),
            )
        val summary = entries.getValue("summary.txt").bytes.decodeToString()

        assertEquals("Running", runtimeConfig.serviceStatus)
        assertEquals("degraded", runtimeConfig.relayRuntime?.health)
        assertEquals("latest_passive_context", runtimeConfig.runtimeContextSource)
        assertEquals(30L, runtimeConfig.runtimeContextCapturedAt)
        assertEquals("latest_passive_snapshot", runtimeConfig.networkSnapshotSource)
        assertEquals(35L, runtimeConfig.networkSnapshotCapturedAt)
        assertEquals("Halted", runtimeConfig.terminalServiceStatus)
        assertEquals("session_context", runtimeConfig.terminalContextSource)
        assertEquals(40L, runtimeConfig.terminalContextCapturedAt)
        assertEquals("15", provenance.runtimeProvenance.androidVersion)
        assertEquals("Running", manifest.contextSummary?.service?.serviceStatus)
        assertTrue(summary.contains("android=15 (API 35)"))
    }

    @Test
    fun `runtime context selection leaves an ordinary single context unchanged`() {
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                latestPassiveContext = null,
                latestContextModel = null,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-single-runtime-context", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-single-runtime-context.zip",
                createdAt = 50L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val runtimeConfig =
            json.decodeFromString(
                DiagnosticsArchiveRuntimeConfigPayload.serializer(),
                entries.getValue("runtime-config.json").bytes.decodeToString(),
            )

        assertEquals("connected", runtimeConfig.serviceStatus)
        assertNull(runtimeConfig.runtimeContextSource)
        assertNull(runtimeConfig.runtimeContextCapturedAt)
        assertNull(runtimeConfig.networkSnapshotSource)
        assertNull(runtimeConfig.networkSnapshotCapturedAt)
        assertNull(runtimeConfig.terminalServiceStatus)
        assertNull(runtimeConfig.terminalContextSource)
        assertNull(runtimeConfig.terminalContextCapturedAt)
    }

    @Test
    fun `renderer redacts endpoint fields from logcat entry`() {
        val selection =
            buildFullRendererSelection().copy(
                logcatSnapshot =
                    LogcatSnapshot(
                        content =
                            "03-12 10:00:00.010 I/ripdpi-native: route selected " +
                                "host=private.example target=203.0.113.9:443 " +
                                "url=https://user:pass@private.example/path?token=abc\n",
                        captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                        byteCount = 144,
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-render", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-logcat.zip",
                createdAt = 44L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val logcatText = entries.getValue("logcat.txt").bytes.decodeToString()

        assertTrue(logcatText.contains("host=<redacted>"))
        assertTrue(logcatText.contains("target=<redacted>"))
        assertTrue(logcatText.contains("<url-redacted>"))
        assertFalse(logcatText.contains("private.example"))
        assertFalse(logcatText.contains("203.0.113.9:443"))
        assertFalse(logcatText.contains("token=abc"))
    }

    @Test
    fun `renderer removes reversibly encoded hostnames from logcat entry`() {
        val hostname = "privacy-sentinel.youtube.example"
        val hostnameBytes = hostname.encodeToByteArray()
        val socksRequest =
            byteArrayOf(5, 1, 0, 3, hostnameBytes.size.toByte()) +
                hostnameBytes +
                byteArrayOf(1, 187.toByte())
        val decimalCarrier = socksRequest.joinToString(", ") { byte -> (byte.toInt() and 0xff).toString() }
        val hexCarrier = socksRequest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val base64Payload = hostnameBytes + byteArrayOf(0xfb.toByte())
        val base64Carrier = Base64.getEncoder().encodeToString(base64Payload)
        val base64UrlCarrier = Base64.getUrlEncoder().withoutPadding().encodeToString(base64Payload)
        val benignDecimalCarrier = "[1, 2, 3, 4, 5, 6, 7, 8]"
        val benignBase64Carrier = Base64.getEncoder().encodeToString("diagnostics-payload".encodeToByteArray())
        val safeEvidence = "class=tls_reset stage=tls_handshake"
        val rawLogcat =
            "03-12 10:00:00.010 I/ripdpi-native: Bytes shorted version: [$decimalCarrier]\n" +
                "03-12 10:00:00.011 I/ripdpi-native: request_hex=$hexCarrier\n" +
                "03-12 10:00:00.012 I/ripdpi-native: request_base64=$base64Carrier\n" +
                "03-12 10:00:00.013 I/ripdpi-native: request_base64url=$base64UrlCarrier\n" +
                "03-12 10:00:00.014 I/ripdpi-native: diagnostic_bytes=$benignDecimalCarrier\n" +
                "03-12 10:00:00.015 I/ripdpi-native: diagnostic_base64=$benignBase64Carrier\n" +
                "03-12 10:00:00.016 I/ripdpi-native: failure classified $safeEvidence\n"
        val selection =
            buildFullRendererSelection().copy(
                logcatSnapshot =
                    LogcatSnapshot(
                        content = rawLogcat,
                        captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                        byteCount = rawLogcat.toByteArray(Charsets.UTF_8).size,
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-encoded-hostname", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-encoded-hostname.zip",
                createdAt = 45L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val logcatText = entries.getValue("logcat.txt").bytes.decodeToString()

        assertTrue(logcatText.contains(safeEvidence))
        assertTrue(logcatText.contains(benignDecimalCarrier))
        assertTrue(logcatText.contains(benignBase64Carrier))
        listOf(hostname, decimalCarrier, hexCarrier, base64Carrier, base64UrlCarrier).forEach { sensitive ->
            assertFalse(
                "encoded hostname carrier must not remain in logcat: $sensitive",
                logcatText.contains(sensitive),
            )
        }
    }

    @Test
    fun `renderer redacts raw socks wire values from logcat snapshot`() {
        val numericCarrier = "115, 101, 110, 115, 105, 116, 105, 118, 101, 46, 101, 120, 97, 109, 112, 108, 101"
        val encodedCarrier = "c2Vuc2l0aXZlLmV4YW1wbGU="
        val rawLog =
            "Bytes long version: [5, 1, 0, 3, 17, $numericCarrier]\n" +
                "rawPacketBase64=$encodedCarrier\n"
        val selection =
            buildFullRendererSelection().copy(
                logcatSnapshot =
                    LogcatSnapshot(
                        content = rawLog,
                        captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                        byteCount = rawLog.toByteArray().size,
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-render", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-logcat-raw-wire.zip",
                createdAt = 45L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val logcatText = entries.getValue("logcat.txt").bytes.decodeToString()

        assertTrue(logcatText.contains("Bytes long version: <raw-wire-redacted>"))
        assertTrue(logcatText.contains("rawPacketBase64=<raw-wire-redacted>"))
        assertFalse(logcatText.contains(numericCarrier))
        assertFalse(logcatText.contains(encodedCarrier))
    }

    @Test
    fun `archive privacy removes device identity and aliases correlations consistently`() {
        val correlationId = "123e4567-e89b-12d3-a456-426614174000"
        val sensitiveDevice =
            rendererDiagnosticContextModel().copy(
                device =
                    rendererDiagnosticContextModel().device.copy(
                        manufacturer = "private-manufacturer-marker",
                        model = "private-model-marker",
                        locale = "private-locale-marker",
                        timezone = "private-timezone-marker",
                    ),
            )
        val sessionContext =
            rendererDiagnosticContextEntity(sessionId = "session-1").copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), sensitiveDevice),
            )
        val passiveContext =
            rendererDiagnosticContextEntity(id = "ctx-passive", sessionId = null).copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), sensitiveDevice),
            )
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                request = base.request.copy(homeRunId = correlationId),
                payload =
                    base.payload.copy(
                        sessionContexts = listOf(sessionContext),
                        latestPassiveContext = passiveContext,
                    ),
                primaryContexts = listOf(sessionContext),
                latestPassiveContext = passiveContext,
                latestContextModel = sensitiveDevice,
                sessionContextModel = sensitiveDevice,
                homeRunId = correlationId,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-privacy", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-$correlationId.zip",
                createdAt = 44L,
            )

        val archiveText =
            renderer
                .render(target, selection)
                .joinToString("\n") { entry -> entry.bytes.decodeToString() }

        listOf(
            correlationId,
            "private-manufacturer-marker",
            "private-model-marker",
            "private-locale-marker",
            "private-timezone-marker",
        ).forEach { sensitiveValue ->
            assertFalse("archive must not contain $sensitiveValue", archiveText.contains(sensitiveValue))
        }
        val correlationAliases = Regex("correlation-[0-9]+").findAll(archiveText).map { it.value }.toList()
        assertTrue("correlation alias must be reused across entries", correlationAliases.size > 1)
        assertEquals("one source UUID must map to one alias", 1, correlationAliases.toSet().size)
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
    fun `completeness counts declare archive and primary session scopes`() {
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-completeness-scopes", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-completeness-scopes.zip",
                createdAt = 44L,
            )
        val completeness =
            renderer
                .render(target, buildFullRendererSelection())
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("completeness.json")
                .bytes
                .decodeToString()
                .let(json::parseToJsonElement)
                .jsonObject

        listOf("sourceCounts", "includedCounts").forEach { countSectionName ->
            val scopedCounts = completeness.getValue(countSectionName).jsonObject
            assertEquals(setOf("archiveWide", "primarySession"), scopedCounts.keys)
            assertEquals(
                setOf("telemetrySamples", "nativeEvents", "snapshots", "contexts"),
                scopedCounts.getValue("archiveWide").jsonObject.keys,
            )
            assertEquals(
                setOf("results", "snapshots", "contexts", "events"),
                scopedCounts.getValue("primarySession").jsonObject.keys,
            )
        }
        assertEquals(12, DiagnosticsArchiveFormat.schemaVersion)
    }

    @Test
    fun `renderer redacts logs before applying final utf8 byte limits`() {
        val rawLog = "oldest-log-evidence\n" + "http://a ".repeat(58_000) + "\nnewest-log-evidence"
        val rawBytes = rawLog.toByteArray(Charsets.UTF_8).size
        assertTrue(rawBytes < LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        val selection =
            buildTruncationRendererSelection().copy(
                includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true, fileLogIncluded = true),
                logcatSnapshot =
                    LogcatSnapshot(
                        content = rawLog,
                        captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                        byteCount = rawBytes,
                        truncated = false,
                    ),
                fileLogSnapshot =
                    FileLogSnapshot(
                        content = rawLog,
                        byteCount = rawBytes,
                        truncated = false,
                    ),
            )

        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-log-bound", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-42.zip",
                createdAt = 42L,
            )
        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )

        assertTrue(entries.getValue("logcat.txt").bytes.size <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        assertTrue(entries.getValue("app-log.txt").bytes.size <= FileLogWriter.MAX_LOG_FILE_BYTES)
        assertTrue(
            entries
                .getValue("logcat.txt")
                .bytes
                .decodeToString()
                .endsWith("newest-log-evidence"),
        )
        assertTrue(
            entries
                .getValue("app-log.txt")
                .bytes
                .decodeToString()
                .endsWith("newest-log-evidence"),
        )
        assertFalse(
            entries
                .getValue("logcat.txt")
                .bytes
                .decodeToString()
                .contains("oldest-log-evidence"),
        )
        assertFalse(
            entries
                .getValue("app-log.txt")
                .bytes
                .decodeToString()
                .contains("oldest-log-evidence"),
        )
        assertTrue(completeness.truncation.logcat)
        assertTrue(completeness.truncation.appLog)
    }

    @Test
    fun `renderer retains time bound logcat head marker after redaction`() {
        val startupMarker = "vpn-startup-complete-marker"
        val newestMarker = "latest-runtime-complete-marker"
        val rawLog =
            "$startupMarker\n" +
                "http://private.example/path ".repeat(40_000) +
                "\n$LogcatTruncationMarker$newestMarker\n"
        val selection =
            buildTruncationRendererSelection().copy(
                includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true),
                logcatSnapshot =
                    LogcatSnapshot(
                        content = rawLog,
                        captureScope = LogcatSnapshotCollector.TimeBoundSnapshotScope,
                        byteCount = rawLog.toByteArray(Charsets.UTF_8).size,
                        truncated = true,
                    ),
                fileLogSnapshot = null,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-time-bound-logcat", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-time-bound-logcat.zip",
                createdAt = 47L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val logcat = entries.getValue("logcat.txt").bytes.decodeToString()

        assertTrue(logcat.contains(startupMarker))
        assertTrue(logcat.contains(newestMarker))
        assertTrue(logcat.contains(LogcatTruncationMarker.trim()))
        assertTrue(entries.getValue("logcat.txt").bytes.size <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
    }

    @Test
    fun `completeness accounts final line aligned logcat after every redaction pass`() {
        val correlationId = "123e4567-e89b-12d3-a456-426614174000"
        val middleLine =
            "03-12 10:00:00.010 I/ripdpi-native: correlation=$correlationId " +
                "host=private.example payload=${"x".repeat(48)}\n"
        val newestLine =
            "03-12 10:59:59.999 I/ripdpi-native: correlation=$correlationId newest-evidence\n"
        val rawLog = middleLine.repeat(8_000) + newestLine
        val rawBytes = rawLog.toByteArray(Charsets.UTF_8).size
        val selection =
            buildFullRendererSelection().copy(
                includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true),
                logcatSnapshot =
                    LogcatSnapshot(
                        content = rawLog,
                        captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                        byteCount = rawBytes,
                        truncated = true,
                        sourceByteCount = rawBytes.toLong() + 127L,
                        retainedByteCount = rawBytes.toLong(),
                        droppedByteCount = 127L,
                        preCollectionRingLoss = LogcatPreCollectionRingLossStatus.UNKNOWN,
                        earliestRetainedTimestamp = "03-12 10:00:00.010",
                        latestRetainedTimestamp = "03-12 10:59:59.999",
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-final-logcat-accounting", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-final-logcat-accounting.zip",
                createdAt = 48L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val finalLogcat = entries.getValue("logcat.txt").bytes
        val finalText = finalLogcat.decodeToString()
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )
        val logcat = requireNotNull(completeness.logcat)
        val retainedLogLines =
            finalText
                .lineSequence()
                .filter(String::isNotBlank)
                .filterNot { line -> line.startsWith("[logcat truncated:") }
                .toList()
        val timestampRegex = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})")

        assertTrue(finalLogcat.size <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        assertTrue(finalText.contains(LogcatTailTruncationMarker.trim()))
        assertTrue(finalText.contains("newest-evidence"))
        assertFalse(finalText.contains(correlationId))
        assertFalse(finalText.contains("private.example"))
        assertTrue(retainedLogLines.all { line -> timestampRegex.containsMatchIn(line) })
        assertEquals(rawBytes.toLong() + 127L, logcat.collection.sourceBytes)
        assertEquals(rawBytes.toLong(), logcat.collection.retainedBytes)
        assertEquals(127L, logcat.collection.droppedBytes)
        assertEquals(
            logcat.postRedaction.sourceBytes,
            logcat.postRedaction.retainedBytes + logcat.postRedaction.droppedBytes,
        )
        assertEquals(finalLogcat.size, logcat.postRedaction.entryBytes)
        assertTrue(logcat.postRedaction.droppedBytes > 0L)
        assertEquals(
            timestampRegex.find(retainedLogLines.first())?.groupValues?.get(1),
            logcat.earliestRetainedTimestamp,
        )
        assertEquals("03-12 10:59:59.999", logcat.latestRetainedTimestamp)
        assertEquals("UNKNOWN", logcat.preCollectionRingLoss)
    }

    @Test
    fun `renderer includes startup journal as a bounded completeness section`() {
        val journal =
            StartupJournalSnapshot(
                content = "42 service_started mode=vpn\n",
                byteCount = 28,
                truncated = true,
            )
        val selection =
            buildFullRendererSelection().copy(
                startupJournalSnapshot = journal,
                includedFiles =
                    DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = true,
                        startupJournalIncluded = true,
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-startup-journal", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-startup-journal.zip",
                createdAt = 48L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )

        assertEquals("42 service_started mode=vpn\n", entries.getValue("startup-journal.txt").bytes.decodeToString())
        assertTrue(completeness.truncation.startupJournal)
        assertEquals(
            com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSectionStatus.TRUNCATED,
            completeness.sectionStatuses["startup-journal.txt"],
        )
    }

    @Test
    fun `decode failure counts subtract successfully decoded artifacts`() {
        val base = buildFullRendererSelection()
        val malformedSnapshot =
            rendererNetworkSnapshotEntity(id = "malformed-snapshot", sessionId = "session-1").copy(
                connectionSessionId = "primary-connection",
                payloadJson = "{bad",
            )
        val malformedContext =
            rendererDiagnosticContextEntity(id = "malformed-context", sessionId = "session-1").copy(
                payloadJson = "{bad",
            )
        val selection =
            base.copy(
                primarySnapshots = base.primarySnapshots + malformedSnapshot,
                primaryContexts = base.primaryContexts + malformedContext,
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-decode-count", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-decode-count.zip",
                createdAt = 44L,
            )

        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                renderer
                    .render(target, selection)
                    .associateBy(DiagnosticsArchiveEntry::name)
                    .getValue("completeness.json")
                    .bytes
                    .decodeToString(),
            )

        assertTrue(completeness.collectionWarnings.contains("snapshot_decode_failed_count:1"))
        assertTrue(completeness.collectionWarnings.contains("context_decode_failed_count:1"))
    }

    @Test
    fun `runtime snapshot completeness counts exported records and decode failures`() {
        val valid =
            rendererNetworkSnapshotEntity(id = "runtime-valid", sessionId = null).copy(
                connectionSessionId = "runtime-connection",
            )
        val malformed =
            valid.copy(
                id = "runtime-malformed",
                payloadJson = "{bad",
            )
        val selection =
            buildFullRendererSelection().copy(
                primarySnapshots = emptyList(),
                latestPassiveSnapshot = null,
                runtimeSnapshots = listOf(valid, malformed),
                compositeStages = emptyList(),
            )
        val entries =
            renderer
                .render(
                    DiagnosticsArchiveTarget(
                        file = Files.createTempFile("archive-runtime-completeness", ".zip").toFile(),
                        fileName = "ripdpi-diagnostics-runtime-completeness.zip",
                        createdAt = 45L,
                    ),
                    selection,
                ).associateBy(DiagnosticsArchiveEntry::name)
        val snapshotPayload =
            json.parseToJsonElement(entries.getValue("network-snapshots.json").bytes.decodeToString()).jsonObject
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )

        assertEquals(1, snapshotPayload.getValue("runtimeSnapshots").jsonArray.size)
        assertEquals(1, completeness.includedCounts.archiveWide.snapshots)
        assertTrue(completeness.collectionWarnings.contains("snapshot_decode_failed_count:1"))
        assertTrue(
            completeness.reasons.any {
                it.section == "snapshots" && it.code == "decode_failed" && it.count == 1
            },
        )
    }

    @Test
    fun `stage native event status uses its own fetch quota`() {
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                sourceCounts =
                    base.sourceCounts.copy(
                        archiveWide =
                            base.sourceCounts.archiveWide.copy(
                                nativeEvents =
                                    DiagnosticsArchiveFormat.globalEventLimit +
                                        DiagnosticsArchiveFormat.sessionEventLimit +
                                        1,
                            ),
                    ),
                rootSourceCounts =
                    base.rootSourceCounts.copy(globalEvents = DiagnosticsArchiveFormat.globalEventLimit + 1),
                includedFiles =
                    listOf(
                        "native-events.csv",
                        "stages/empty/native-events.csv",
                        "stages/exact/native-events.csv",
                        "stages/full/native-events.csv",
                    ),
                compositeStages =
                    listOf(
                        rendererCompositeStage("empty", emptyList()),
                        rendererCompositeStage(
                            "exact",
                            List(DiagnosticsArchiveFormat.sessionEventLimit) { index ->
                                rendererNativeEvent(id = "exact-event-$index", sessionId = "exact-session")
                            },
                        ),
                        rendererCompositeStage(
                            "full",
                            List(DiagnosticsArchiveFormat.sessionEventLimit + 1) { index ->
                                rendererNativeEvent(id = "event-$index", sessionId = "full-session")
                            },
                        ),
                    ),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(DiagnosticsArchiveSectionStatus.TRUNCATED, statuses["native-events.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.INCLUDED, statuses["stages/empty/native-events.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.INCLUDED, statuses["stages/exact/native-events.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.TRUNCATED, statuses["stages/full/native-events.csv"])
    }

    @Test
    fun `home composite root events use root quotas instead of archive aggregate`() {
        val base = buildFullRendererSelection()
        val primaryEvents =
            List(DiagnosticsArchiveFormat.sessionEventLimit) { index ->
                rendererNativeEvent(id = "primary-event-$index", sessionId = "session-1")
            }
        val globalEvents =
            List(DiagnosticsArchiveFormat.globalEventLimit) { index ->
                rendererNativeEvent(id = "global-event-$index", sessionId = null)
            }
        val stages =
            listOf("stage-one", "stage-two").map { stageKey ->
                rendererCompositeStage(
                    stageKey = stageKey,
                    events =
                        List(DiagnosticsArchiveFormat.sessionEventLimit) { index ->
                            rendererNativeEvent(id = "$stageKey-event-$index", sessionId = "$stageKey-session")
                        },
                )
            }
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                payload = base.payload.copy(sessionEvents = primaryEvents, globalEvents = globalEvents),
                primaryEvents = primaryEvents,
                globalEvents = globalEvents,
                sourceCounts =
                    base.sourceCounts.copy(
                        archiveWide =
                            base.sourceCounts.archiveWide.copy(
                                nativeEvents =
                                    primaryEvents.size + globalEvents.size + stages.sumOf { it.events.size },
                            ),
                        primarySession = base.sourceCounts.primarySession.copy(events = primaryEvents.size),
                    ),
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "quota-home-run",
                        actionable = false,
                        headline = "Complete",
                        summary = "Complete",
                        stageSummaries = stages.map { it.stageSummary },
                    ),
                compositeStages = stages,
                includedFiles =
                    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = false,
                        composite = true,
                        compositeStageKeys = stages.map { it.stageSummary.stageKey },
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-home-event-quotas", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-home-event-quotas.zip",
                createdAt = 44L,
            )

        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                renderer
                    .render(target, selection)
                    .associateBy(DiagnosticsArchiveEntry::name)
                    .getValue("completeness.json")
                    .bytes
                    .decodeToString(),
            )

        assertEquals(DiagnosticsArchiveSectionStatus.INCLUDED, completeness.sectionStatuses["native-events.csv"])
        assertFalse(completeness.truncation.nativeEvents)
    }

    @Test
    fun `home composite root collections use root quotas instead of archive aggregate`() {
        val base = buildFullRendererSelection()
        val rootTelemetry = listOf(rendererTelemetrySample(publicIp = null).copy(id = "root-telemetry"))
        val rootSnapshots = listOf(rendererNetworkSnapshotEntity(id = "root-snapshot", sessionId = "session-1"))
        val rootContexts = listOf(rendererDiagnosticContextEntity(id = "root-context", sessionId = "session-1"))
        val stages =
            listOf(
                rendererCompositeCollectionStage(
                    stageKey = "stage-one",
                    telemetryCount = DiagnosticsArchiveFormat.telemetryLimit,
                    artifactCount = DiagnosticsArchiveFormat.snapshotLimit,
                ),
                rendererCompositeCollectionStage(stageKey = "stage-two", telemetryCount = 1, artifactCount = 1),
            )
        val archiveTelemetryCount = rootTelemetry.size + stages.sumOf { it.telemetry.size }
        val archiveSnapshotCount = rootSnapshots.size + stages.sumOf { it.snapshots.size }
        val archiveContextCount = rootContexts.size + stages.sumOf { it.contexts.size }
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                payload =
                    base.payload.copy(
                        sessionSnapshots = rootSnapshots,
                        sessionContexts = rootContexts,
                        latestPassiveSnapshot = null,
                        latestPassiveContext = null,
                        telemetry = rootTelemetry,
                    ),
                primarySnapshots = rootSnapshots,
                primaryContexts = rootContexts,
                latestPassiveSnapshot = null,
                latestPassiveContext = null,
                sourceCounts =
                    base.sourceCounts.copy(
                        archiveWide =
                            base.sourceCounts.archiveWide.copy(
                                telemetrySamples = archiveTelemetryCount,
                                snapshots = archiveSnapshotCount,
                                contexts = archiveContextCount,
                            ),
                        primarySession =
                            base.sourceCounts.primarySession.copy(
                                snapshots = rootSnapshots.size,
                                contexts = rootContexts.size,
                            ),
                    ),
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "quota-home-run",
                        actionable = false,
                        headline = "Complete",
                        summary = "Complete",
                        stageSummaries = stages.map { it.stageSummary },
                    ),
                compositeStages = stages,
                includedFiles =
                    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = false,
                        composite = true,
                        compositeStageKeys = stages.map { it.stageSummary.stageKey },
                    ),
            )
        val completeness = renderCompleteness(selection, "archive-home-collection-quotas")

        assertEquals(DiagnosticsArchiveSectionStatus.INCLUDED, completeness.sectionStatuses["telemetry.csv"])
        assertEquals(DiagnosticsArchiveSectionStatus.REDACTED, completeness.sectionStatuses["network-snapshots.json"])
        assertEquals(DiagnosticsArchiveSectionStatus.REDACTED, completeness.sectionStatuses["diagnostic-context.json"])
        assertFalse(completeness.truncation.telemetrySamples)
        assertFalse(completeness.truncation.snapshots)
        assertFalse(completeness.truncation.contexts)
    }

    @Test
    fun `exact collection limits are complete rather than truncated`() {
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                sourceCounts =
                    base.sourceCounts.copy(
                        archiveWide =
                            DiagnosticsArchiveArchiveWideCounts(
                                telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit,
                                nativeEvents =
                                    DiagnosticsArchiveFormat.globalEventLimit +
                                        DiagnosticsArchiveFormat.sessionEventLimit,
                                snapshots = DiagnosticsArchiveFormat.snapshotLimit,
                                contexts = DiagnosticsArchiveFormat.snapshotLimit,
                            ),
                    ),
                includedFiles =
                    listOf(
                        "telemetry.csv",
                        "native-events.csv",
                        "network-snapshots.json",
                        "diagnostic-context.json",
                    ),
            )

        val statuses = buildSectionStatuses(selection)

        statuses.values.forEach { status ->
            assertFalse(status == DiagnosticsArchiveSectionStatus.TRUNCATED)
        }
    }

    @Test
    fun `execution plan sections are unavailable when legacy reports do not contain plan evidence`() {
        val base = buildFullRendererSelection()
        val stage = rendererCompositeStage("legacy", emptyList())
        val selection =
            base.copy(
                primaryReport = base.primaryReport?.copy(executionPlan = null),
                includedFiles = listOf("execution-plan.json", "stages/legacy/execution-plan.json"),
                compositeStages = listOf(stage.copy(report = stage.report?.copy(executionPlan = null))),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(
            mapOf(
                "execution-plan.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/execution-plan.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
            ),
            statuses,
        )
    }

    @Test
    fun `zip writer persists provided entries verbatim`() {
        val target = Files.createTempDirectory("archive-writer").resolve("archive.zip").toFile()

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

    @Test
    fun `renderer developer-analytics json omits forbidden fields from archive`() {
        val violatingPayload =
            DeveloperAnalyticsPayload(
                schemaVersion = 1,
                generatedAtIsoUtc = "2026-05-16T00:00:00Z",
                reproductionContext =
                    DeveloperReproductionContext(
                        appVersionName = "1.0.0-fixture",
                        buildType = "debug",
                        nativeLibDigests = mapOf("libripdpi-fixture.so" to "sha256-fixture-digest"),
                    ),
                nativeRuntime =
                    DeveloperNativeRuntimeSnapshot(
                        threadCount = 4,
                        lastPanicBacktrace = "fixture-panic-backtrace-content",
                    ),
                effectiveConfigDiff =
                    listOf(
                        DeveloperConfigDiffEntry(key = "rootModeEnabled", defaultValue = "false", actualValue = "true"),
                        DeveloperConfigDiffEntry(
                            key = "enableCmdSettings",
                            defaultValue = "false",
                            actualValue = "true",
                        ),
                        DeveloperConfigDiffEntry(key = "desyncMode", defaultValue = "auto", actualValue = "manual"),
                    ),
                pcapManifest = listOf(DeveloperPcapFileEntry(name = "capture-fixture.pcap", sizeBytes = 1024L)),
                breadcrumbs =
                    listOf(
                        DeveloperBreadcrumb(
                            timestampMs = 0L,
                            category = "fixture",
                            message = "fixture-breadcrumb-message",
                        ),
                    ),
                deviceState = DeveloperDeviceState(locale = "en_US", androidSdk = 33),
                notes = listOf("fixture note"),
            )
        val selection = buildFullRendererSelection()
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-da-forbidden", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-da-forbidden.zip",
                createdAt = 45L,
            )

        val entries = renderer.render(target, selection, violatingPayload).associateBy(DiagnosticsArchiveEntry::name)
        val daJson = entries.getValue("developer-analytics.json").bytes.decodeToString()
        val daObject = json.parseToJsonElement(daJson).jsonObject

        assertFalse("pcapManifest must be absent", daObject.containsKey("pcapManifest"))
        assertFalse("breadcrumbs must be absent", daObject.containsKey("breadcrumbs"))
        assertFalse(
            "fixture-panic-backtrace-content must not appear verbatim",
            daJson.contains("fixture-panic-backtrace-content"),
        )
        assertFalse("sha256-fixture-digest must not appear verbatim", daJson.contains("sha256-fixture-digest"))
        assertFalse("rootModeEnabled must be absent from effectiveConfigDiff", daJson.contains("\"rootModeEnabled\""))
        assertFalse(
            "enableCmdSettings must be absent from effectiveConfigDiff",
            daJson.contains("\"enableCmdSettings\""),
        )
        assertTrue("desyncMode (allowed diff key) must remain", daJson.contains("\"desyncMode\""))
        daObject["nativeRuntime"]?.jsonObject?.let { runtime ->
            val backtrace = runtime["lastPanicBacktrace"]
            assertTrue(
                "nativeRuntime.lastPanicBacktrace must be null",
                backtrace == null || backtrace is JsonNull,
            )
        }
        daObject["reproductionContext"]?.jsonObject?.let { repro ->
            val digests = repro["nativeLibDigests"]?.jsonObject
            assertTrue(
                "reproductionContext.nativeLibDigests must be absent or empty",
                digests == null || digests.isEmpty(),
            )
        }
    }

    @Test
    fun `whole zip redacts hostile native replay approach network and credential values`() {
        val selection = buildSensitiveRendererSelection()
        val archiveDirectory = Files.createTempDirectory("archive-redact")
        val target =
            DiagnosticsArchiveTarget(
                file = archiveDirectory.resolve("ripdpi-diagnostics-redact.zip").toFile(),
                fileName = "ripdpi-diagnostics-redact.zip",
                createdAt = 46L,
            )

        DiagnosticsArchiveZipWriter().write(target.file, renderer.render(target, selection))
        ZipFile(target.file).use { zip ->
            val snapshots =
                zip.getInputStream(zip.getEntry("network-snapshots.json")).bufferedReader().readText()
            assertTrue(snapshots.contains("\"observerRole\": \"vpn_owner_service\""))
            assertTrue(snapshots.contains("\"callingDefaultObserverRole\": \"unavailable\""))
            assertTrue(snapshots.contains("\"forwardingOutcome\": \"cross_layer_return_observed\""))
            val analysis = zip.getInputStream(zip.getEntry("analysis.json")).bufferedReader().readText()
            assertTrue(analysis.contains("\"candidateVerdict\": \"INEFFECTIVE_ON_TESTED_CANDIDATE_PATH\""))
            assertTrue(analysis.contains("\"activePathOutcome\": \"UNVERIFIED\""))
            assertTrue(analysis.contains("\"observationRole\": \"EPHEMERAL_CANDIDATE_RAW_PATH\""))
            assertTrue(analysis.contains("\"desyncExecutionRequired\": true"))
            assertTrue(analysis.contains("\"runtimeTerminalStatus\": \"CLEAN_SHUTDOWN\""))
            assertTrue(analysis.contains("\"disposition\": \"APPLIED\""))
            assertTrue(analysis.contains("\"connectionOrdinal\": 1"))
            assertTrue(analysis.contains("\"responseStage\": \"RESPONSE_NOT_OBSERVED\""))
            assertTrue(analysis.contains("\"routeFeatures\": ["))
            assertTrue(analysis.contains("\"UPSTREAM_RELAY\""))
            assertTrue(analysis.contains("\"markerBase\": \"HOST\""))
            assertTrue(analysis.contains("\"markerDelta\": 1"))
        }
        assertZipExcludes(target, hostileArchiveValues())
    }

    @Test
    fun `whole home composite zip redacts every hostile stage entry`() {
        val logFixture = truncatedLogTailFixture(buildSensitiveRendererSelection())
        val base = logFixture.selection
        val stage =
            rendererCompositeStage(
                stageKey = "automatic_audit",
                events = base.primaryEvents,
                session = base.primarySession,
            ).copy(
                results = base.primaryResults,
                snapshots = base.primarySnapshots,
                contexts = base.primaryContexts,
            )
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                request =
                    DiagnosticsArchiveRequest(
                        sessionIds = listOfNotNull(base.primarySession?.id),
                        homeRunId = "hostile-home-run",
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 46L,
                    ),
                homeRunId = "hostile-home-run",
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "hostile-home-run",
                        actionable = false,
                        headline = "Complete",
                        summary = "Complete",
                        stageSummaries = listOf(stage.stageSummary),
                        bundleSessionIds = listOfNotNull(base.primarySession?.id),
                    ),
                compositeStages = listOf(stage),
                includedFiles =
                    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = true,
                        fileLogIncluded = true,
                        composite = true,
                        compositeStageKeys = listOf("automatic_audit"),
                        replayIncluded = true,
                    ),
            )
        val archiveDirectory = Files.createTempDirectory("archive-redact-home")
        val target =
            DiagnosticsArchiveTarget(
                file = archiveDirectory.resolve("ripdpi-diagnostics-redact-home.zip").toFile(),
                fileName = "ripdpi-diagnostics-redact-home.zip",
                createdAt = 46L,
            )

        DiagnosticsArchiveZipWriter().write(target.file, renderer.render(target, selection))

        ZipFile(target.file).use { zip ->
            assertNotNull(zip.getEntry("stages/automatic_audit/report.json"))
            assertNotNull(zip.getEntry("stages/automatic_audit/native-events.csv"))
            assertNotNull(zip.getEntry("stages/automatic_audit/probe-results.csv"))
            val logcat = zip.getInputStream(zip.getEntry("logcat.txt")).bufferedReader().readText()
            val appLog = zip.getInputStream(zip.getEntry("app-log.txt")).bufferedReader().readText()
            val events = zip.getInputStream(zip.getEntry("native-events.csv")).bufferedReader().readText()
            assertFalse(logcat.contains(logFixture.partialLogcatLine))
            assertTrue(logcat.contains(logFixture.newestLogcatLine))
            assertTrue(logcat.contains("I/RIPDPI( 123): <path-redacted>"))
            assertFalse(appLog.contains(logFixture.partialAppLogLine))
            assertTrue(appLog.contains(logFixture.newestAppLogLine))
            assertFalse(events.contains("successfully before retry"))
            assertFalse(events.contains("status=failed"))
            assertTrue(events.contains("ready"))
        }
        assertZipExcludes(target, hostileArchiveValues())
    }

    private data class TruncatedLogTailFixture(
        val selection: DiagnosticsArchiveSelection,
        val partialLogcatLine: String,
        val newestLogcatLine: String,
        val partialAppLogLine: String,
        val newestAppLogLine: String,
    )

    private fun truncatedLogTailFixture(selection: DiagnosticsArchiveSelection): TruncatedLogTailFixture {
        val partialLogcatLine = "IPDPI( 123): ABC"
        val newestLogcatLine = "I/RIPDPI( 123): newest-complete-logcat-line"
        val partialAppLogLine = "OSTICS] partial-app-log-line"
        val newestAppLogLine = "[WARN] [Diagnostics] newest-complete-app-log-line"
        val baseLogcatSnapshot = requireNotNull(selection.logcatSnapshot)
        val logcatContent = "$partialLogcatLine\n${baseLogcatSnapshot.content}$newestLogcatLine\n"
        val appLogContent = "$partialAppLogLine\n$newestAppLogLine\n"
        return TruncatedLogTailFixture(
            selection =
                selection.copy(
                    logcatSnapshot =
                        baseLogcatSnapshot.copy(
                            content = logcatContent,
                            byteCount = logcatContent.toByteArray(Charsets.UTF_8).size,
                            truncated = true,
                        ),
                    fileLogSnapshot =
                        FileLogSnapshot(
                            content = appLogContent,
                            byteCount = appLogContent.toByteArray(Charsets.UTF_8).size,
                            truncated = true,
                        ),
                ),
            partialLogcatLine = partialLogcatLine,
            newestLogcatLine = newestLogcatLine,
            partialAppLogLine = partialAppLogLine,
            newestAppLogLine = newestAppLogLine,
        )
    }

    private fun hostileArchiveValues(): List<String> =
        listOf(
            "203.0.113.99",
            "AS64501",
            "203.0.113.53",
            "192.0.2.42",
            "SensitiveNetwork",
            "AA:BB:CC:DD:EE:FF",
            "192.0.2.1",
            "fp-render",
            "blocked.example",
            "telegram.org",
            "signal.org",
            "discord.com",
            "probe.private.example",
            "detail.private.example",
            "2001:db8::44",
            "/data/private/trace",
            "Sensitive Carrier",
            "198.51.100.77",
            "host-policy.private.example",
            "opaque-resolver-endpoint",
            "opaque-bootstrap-ip",
            "opaque-resolver-host",
            "opaque-tls-server-name",
            "opaque-doh-url",
            "opaque-dnscrypt-public-key",
            "replay-user:replay-password",
            "replay.private.example",
            "replay-secret-token",
            "private-certificate-material",
            "native-certificate-material",
            "private-truncated-key-material",
            "native-secret-token",
            "approach-private-value",
            "receipt-rationale.private.example",
            "receipt-note.private.example",
            "hostile-candidate-id-private",
            "hostile-candidate-label-private",
            "hostile-candidate-family-private",
            "hostile-quic-layout-private",
            "hostile-candidate-outcome-private",
            "route-owner-private.example/uid-4242/192.0.2.222",
            "Private Approach Name",
            "private-validation-result",
            "private-runtime-end-reason",
            "private-failure-outcome",
            "::1",
            "fe80::1",
            "2001:db8::53",
            "пример.рф",
            "пример。рф",
            "пример．рф",
            "пример｡рф",
            "resolver.xn--p1ai",
            "resolver。xn--p1ai",
            "xn--p1ai",
            "/data/private/My Files/native trace.log",
            "My Files/native trace.log",
            "/storage/emulated/0/John, Doe/private.pem",
            "John, Doe/private.pem",
            "/data/private/key: backup.pem",
            "key: backup.pem",
            "/data/private/John'Doe/key.pem",
            "John'Doe/key.pem",
            "C:\\Users\\John,Doe\\key:backup.pem",
            "John,Doe\\key:backup.pem",
            "C:\\Users\\John\"Doe\\private.pem",
            "John\"Doe\\private.pem",
            "/data/private/compact,key.pem",
            "compact,key.pem",
            "TkFUSVZFX1BFTV9UQUlMX01BVEVSSUFM",
            "YQ==",
            "YWI=",
            "qzxwvut",
            "UVdFUlRZVVlJT1BB",
            "jkvlmno",
        ) + hostileStrategyArchiveValues() + hostilePathArchiveValues()

    private fun hostileStrategyArchiveValues(): List<String> =
        listOf(
            "hostile-pilot-bucket-private.example",
            "hostile-cohort-id-private.example",
            "hostile-cohort-label-private.example",
            "hostile-domain-target-private.example",
            "hostile-quic-target-private.example",
        )

    private fun hostilePathArchiveValues(): List<String> =
        listOf(
            "unc-secret.pem",
            "root-secret.pem",
            "extended-secret.pem",
            "extended-unc-secret.pem",
            "PhysicalDrive0",
            "nt-secret.pem",
            "mixed-secret.pem",
            "escaped-solidus-secret.pem",
            "escaped-backslash-secret.pem",
            "unicode-solidus-secret.pem",
            "unicode-backslash-secret.pem",
            "nested-unicode-secret.pem",
            "encoded-drive-secret.pem",
            "percent-slash-secret.pem",
            "percent-backslash-secret.pem",
            "percent-nested-slash-secret.pem",
            "percent-nested-backslash-secret.pem",
            "percent-double-nested-secret.pem",
            "percent-mixed-secret.pem",
        )

    private fun assertZipExcludes(
        target: DiagnosticsArchiveTarget,
        hostileValues: List<String>,
    ) {
        ZipFile(target.file).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val content = zip.getInputStream(entry).readBytes().decodeToString()
                hostileValues.forEach { hostileValue ->
                    assertFalse(
                        "$hostileValue must not appear in ${entry.name}",
                        content.contains(hostileValue),
                    )
                }
            }
        }
    }

    private fun buildSensitiveRendererSelection(): DiagnosticsArchiveSelection {
        val sensitiveSnapshot = sensitiveNetworkSnapshot()
        val base = buildFullRendererSelection()
        val strategyContext = buildSensitiveStrategyContext(base)
        val hostileResult =
            rendererProbeResult(sessionId = "session-1").copy(
                target = "probe.private.example",
                detailJson =
                    """{"target":"detail.private.example","address":"2001:db8::44","path":"/data/private/trace"}""",
            )
        val hostileEvent = hostileNativeEvent()
        val hostileReplay = hostileReplayResult()
        val hostileApproach = hostileApproachSummary(strategyContext.strategyId)
        return base.copy(
            payload =
                base.payload.copy(
                    session = strategyContext.primarySession,
                    primaryReport = strategyContext.primaryReport,
                    results = listOf(hostileResult),
                    sessionEvents = listOf(hostileEvent),
                    approachSummaries = listOf(hostileApproach),
                ),
            primaryResults = listOf(hostileResult),
            primarySession = strategyContext.primarySession,
            primaryReport = strategyContext.primaryReport,
            primaryEvents = listOf(hostileEvent),
            selectedApproachSummary = hostileApproach,
            effectiveStrategySignature = strategyContext.strategySignature,
            replayResults = listOf(hostileReplay),
            includedFiles = base.includedFiles + "replay-results.json",
            primarySnapshots =
                listOf(
                    NetworkSnapshotEntity(
                        id = "snap-sensitive",
                        sessionId = "session-1",
                        snapshotKind = "post_scan",
                        payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), sensitiveSnapshot),
                        capturedAt = 46L,
                    ),
                ),
            latestSnapshotModel = sensitiveSnapshot,
            logcatSnapshot = sensitiveLogcatSnapshot(),
        )
    }

    private fun buildSensitiveStrategyContext(base: DiagnosticsArchiveSelection): SensitiveStrategyContext {
        val strategySignature =
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
        val strategyId = strategySignature.stableId()
        val primaryReport =
            requireNotNull(base.primaryReport)
                .toScanReport()
                .copy(
                    pathMode = ScanPathMode.RAW_PATH,
                    strategyProbeReport = hostileStrategyProbeReport(base),
                ).toEngineScanReportWire()
        val primarySession =
            requireNotNull(base.primarySession).copy(
                strategyId = strategyId,
                strategyLabel = strategyId,
                strategyJson = json.encodeToString(BypassStrategySignature.serializer(), strategySignature),
                pathMode = ScanPathMode.RAW_PATH.name,
                reportJson = json.encodeToString(primaryReport),
            )
        return SensitiveStrategyContext(strategySignature, strategyId, primaryReport, primarySession)
    }

    private fun hostileStrategyProbeReport(base: DiagnosticsArchiveSelection): StrategyProbeReport {
        val strategyProbe = requireNotNull(base.primaryReport?.strategyProbeReport)
        val candidates =
            strategyProbe.tcpCandidates.mapIndexed { index, candidate ->
                if (index == 0) {
                    candidate.copy(
                        id = "baseline_current",
                        label = "hostile-candidate-label-private",
                        family = "hostile-candidate-family-private",
                        quicLayoutFamily = "hostile-quic-layout-private",
                        outcome = "hostile-candidate-outcome-private",
                        rationale = "receipt-rationale.private.example",
                        notes = listOf("receipt-note.private.example"),
                        activeSnapshotFaithful = true,
                        desyncExecutionRequired = true,
                        runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN,
                        executionAttempts =
                            candidate.executionAttempts.map { attempt ->
                                attempt.copy(
                                    responseStage = StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED,
                                    receipts =
                                        attempt.receipts.mapIndexed { receiptIndex, receipt ->
                                            receipt.copy(connectionOrdinal = receiptIndex + 1)
                                        },
                                )
                            },
                    )
                } else {
                    candidate
                }
            } +
                strategyProbe.tcpCandidates.first().copy(
                    id = "hostile-candidate-id-private",
                    label = "hostile-candidate-label-private",
                    family = "hostile-candidate-family-private",
                    quicLayoutFamily = "hostile-quic-layout-private",
                    outcome = "hostile-candidate-outcome-private",
                )
        return strategyProbe.copy(
            tcpCandidates = candidates,
            pilotBucketLabels = listOf("hostile-pilot-bucket-private.example"),
            targetSelection =
                StrategyProbeTargetSelection(
                    cohortId = "hostile-cohort-id-private.example",
                    cohortLabel = "hostile-cohort-label-private.example",
                    domainHosts = listOf("hostile-domain-target-private.example"),
                    quicHosts = listOf("hostile-quic-target-private.example"),
                ),
        )
    }

    private fun sensitiveLogcatSnapshot() =
        LogcatSnapshot(
            content =
                "I/RIPDPI( 123): qzxwvut\n" +
                    "I/RIPDPI( 123): UVdFUlRZVVlJT1BB\n" +
                    "I/RIPDPI( 123): jkvlmno\n" +
                    "I/RIPDPI( 123): -----END PRIVATE KEY-----\n" +
                    "I/RIPDPI( 123): YQ==\n" +
                    "I/RIPDPI( 123): -----END CERTIFICATE-----\n" +
                    "I/RIPDPI( 123): file=/storage/emulated/0/John, Doe/private.pem suffix\n" +
                    "03-12 10:00:00.012 I/RIPDPI: resolver。xn--p1ai\n",
            captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
            byteCount = 192,
        )

    private data class SensitiveStrategyContext(
        val strategySignature: BypassStrategySignature,
        val strategyId: String,
        val primaryReport: EngineScanReportWire,
        val primarySession: ScanSessionEntity,
    )

    private fun sensitiveNetworkSnapshot() =
        NetworkSnapshotModel(
            transport = "wifi",
            capabilities = listOf("validated"),
            dnsServers = listOf("203.0.113.53"),
            privateDnsMode = "strict",
            mtu = 1500,
            localAddresses = listOf("192.0.2.42"),
            publicIp = "203.0.113.99",
            publicAsn = "AS64501",
            captivePortalDetected = false,
            networkValidated = true,
            wifiDetails =
                WifiNetworkDetails(
                    ssid = "SensitiveNetwork",
                    bssid = "AA:BB:CC:DD:EE:FF",
                    band = "5 GHz",
                    wifiStandard = "802.11ax",
                    gateway = "192.0.2.1",
                ),
            pathValidation =
                NetworkPathValidationEvidence(
                    captureStatus = "captured",
                    callingDefaultObserverRole = "route-owner-private.example/uid-4242/192.0.2.222",
                    vpnRouteEvidence =
                        VpnRouteEvidenceSnapshot(
                            observerRole = "vpn_owner_service",
                            observerSource = "route-owner-private.example/uid-4242/192.0.2.222",
                            lifecycleGeneration = 12L,
                            lifecycleState = "bridge_ready",
                            callbackState = "complete",
                            ownerVerification = "verified",
                            intendedDefaultRouteFamilies =
                                listOf("ipv4", "route-owner-private.example/uid-4242/192.0.2.222"),
                            observedDefaultRouteFamilies = listOf("ipv4"),
                            routeConsistency = "consistent",
                            vpnPresent = true,
                            forwardingOutcome = "cross_layer_return_observed",
                            forwardingLifecycleGeneration = 12L,
                            forwardingTerminal = true,
                        ),
                ),
            capturedAt = 46L,
        )

    private fun hostileNativeEvent(): NativeSessionEventEntity {
        val certificateStart = listOf("-----BEGIN", "CERTIFICATE-----").joinToString(" ")
        val certificateEnd = listOf("-----END", "CERTIFICATE-----").joinToString(" ")
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        return rendererNativeEvent(id = "hostile-event", sessionId = "session-1").copy(
            message =
                "Authorization: Bearer native-secret-token; carrier=Sensitive Carrier; " +
                    "resolver=198.51.100.77; loopback=::1; linkLocal=fe80::1; resolverV6=2001:db8::53; " +
                    "$certificateStart\nnative-certificate-material\n$certificateEnd\n" +
                    "unicode=пример.рф; idna=пример。рф,пример．рф,пример｡рф; " +
                    "punycode=resolver.xn--p1ai,resolver。xn--p1ai; " +
                    "url=https://native.private.example/secret/path; " +
                    "file=/data/private/My Files/native trace.log; " +
                    "file=/storage/emulated/0/John, Doe/private.pem; " +
                    "path=/data/private/key: backup.pem; " +
                    "file=/data/private/John'Doe/key.pem; " +
                    "file=C:\\Users\\John,Doe\\key:backup.pem; " +
                    "path=C:\\Users\\John\"Doe\\private.pem; status=failed; " +
                    "opened /data/private/key.pem successfully before retry; " +
                    "path=/data/foo;status=failed\n" +
                    "json={\"path\":\"/data/private/compact,key.pem\",\"status\":\"ready\"}\n" +
                    hostileEncodedPathLines() +
                    "\n" +
                    "TkFUSVZFX1BFTV9UQUlMX01BVEVSSUFM\n$privateKeyEnd\n" +
                    "YQ==\n$privateKeyEnd\n" +
                    "YWI=\n$certificateEnd",
            policySignature = "host-policy.private.example",
        )
    }

    private fun hostileEncodedPathLines(): String =
        listOf(
            "\\\\server\\share\\unc-secret.pem",
            "\\Users\\Private\\root-secret.pem",
            "\\\\?\\C:\\Users\\Private\\extended-secret.pem",
            "\\\\?\\UNC\\server\\share\\extended-unc-secret.pem",
            "\\\\.\\PhysicalDrive0",
            "\\??\\C:\\Users\\Private\\nt-secret.pem",
            "C:/Users\\Private/mixed-secret.pem",
            "\\/storage\\/emulated\\/0\\/escaped-solidus-secret.pem",
            """{"detail":"C:\\Users\\Private\\escaped-backslash-secret.pem"}""",
            """{"detail":"\u002fdata\u002Fprivate\u002funicode-solidus-secret.pem"}""",
            """{"detail":"\u005cUsers\u005CPrivate\u005cunicode-backslash-secret.pem"}""",
            """{"detail":"\\u005cUsers\\u005CPrivate\\u005cnested-unicode-secret.pem"}""",
            """{"detail":"C:\u005cUsers\u005cPrivate\u005cencoded-drive-secret.pem"}""",
            "%2Fprivate%2Fpercent-slash-secret.pem",
            "%5cUsers%5CPrivate%5cpercent-backslash-secret.pem",
            "%252fprivate%252Fpercent-nested-slash-secret.pem",
            "%255CUsers%255cPrivate%255Cpercent-nested-backslash-secret.pem",
            "%25252Fprivate%25252fpercent-double-nested-secret.pem",
            "C:%252FUsers%255cPrivate%2Fpercent-mixed-secret.pem",
        ).joinToString("\n")

    private fun hostileReplayResult(): ReplayProbeResult {
        val privateKeyStart = listOf("-----BEGIN", "PRIVATE KEY-----").joinToString(" ")
        val certificateStart = listOf("-----BEGIN", "CERTIFICATE-----").joinToString(" ")
        val certificateEnd = listOf("-----END", "CERTIFICATE-----").joinToString(" ")
        val detail =
            "https://replay-user:replay-password@replay.private.example/private/path?token=replay-secret-token " +
                "$certificateStart\nprivate-certificate-material\n$certificateEnd\n" +
                "$privateKeyStart\nprivate-truncated-key-material"
        return ReplayProbeResult(
            request = ReplayProbeRequest("replay.private.example", "strategy-fast", 1_000L),
            events =
                persistentListOf(
                    ReplayStepEvent.StepFailed(
                        ReplayStepKind.TlsHandshake,
                        ReplayErrorKind.TlsHandshakeFailed,
                        detail,
                    ),
                ),
            verdict = ReplayVerdict.Failure,
            terminalStep = ReplayStepKind.TlsHandshake,
            recommendationKey = "replay_failure",
        )
    }

    private fun hostileApproachSummary(strategyId: String) =
        rendererApproachSummary(strategyId = strategyId).copy(
            displayName = "Private Approach Name",
            secondaryLabel = "private.secondary.example",
            lastValidatedResult = "private-validation-result",
            recentRuntimeHealth =
                BypassRuntimeHealthSummary(
                    totalErrors = 1,
                    lastEndedReason = "private-runtime-end-reason",
                ),
            topFailureOutcomes = listOf("private-failure-outcome"),
            currentStrategyAssessment =
                CurrentStrategyAssessment(
                    candidateVerdict = CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH,
                    observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                    reason = CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED,
                ),
        )

    @Test
    fun `renderer never exports content from undecodable snapshot or context payloads`() {
        val malformedSnapshot =
            rendererNetworkSnapshotEntity(sessionId = "session-1").copy(
                payloadJson = "{\"secret\":\"snapshot-secret-token\",\"ssid\":\"PrivateNetwork\"}",
            )
        val malformedContext =
            rendererDiagnosticContextEntity(sessionId = "session-1").copy(
                payloadJson = "{\"password\":\"context-secret-token\",\"endpoint\":\"private.example\"}",
            )
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                payload =
                    base.payload.copy(
                        sessionSnapshots = listOf(malformedSnapshot),
                        sessionContexts = listOf(malformedContext),
                        latestPassiveSnapshot = malformedSnapshot.copy(id = "passive-snapshot", sessionId = null),
                        latestPassiveContext = malformedContext.copy(id = "passive-context", sessionId = null),
                    ),
                primarySnapshots = listOf(malformedSnapshot),
                primaryContexts = listOf(malformedContext),
                latestPassiveSnapshot = malformedSnapshot.copy(id = "passive-snapshot", sessionId = null),
                latestPassiveContext = malformedContext.copy(id = "passive-context", sessionId = null),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-malformed-payload", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-malformed-payload.zip",
                createdAt = 47L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val allBytes = entries.values.joinToString("") { it.bytes.decodeToString() }
        val report = entries.getValue("report.json").bytes.decodeToString()

        listOf(
            "snapshot-secret-token",
            "PrivateNetwork",
            "context-secret-token",
            "private.example",
        ).forEach { secret ->
            assertFalse("$secret must not appear in any archive entry", allBytes.contains(secret))
        }
        assertTrue(report.contains("payload_decode_failed"))
    }

    @Test
    fun `renderer exports one safe post runtime restore receipt without raw errors`() {
        val sensitiveDetail = "restore failed for alice@example.test private-detail-marker"
        val restoreContext =
            rendererDiagnosticContextEntity(id = "restore-receipt", sessionId = "session-1").copy(
                contextKind = "post_runtime_restore",
                payloadJson =
                    """{
                    |"settlement":{"raw_window_generation":7,"resume_intent_generation":8,
                    |"outcome":"restore_failed","runtime_was_running":true,"resume_required":true,
                    |"post_runtime_context":{"status":"halted","mode":"vpn"},
                    |"restore_failure":"$sensitiveDetail"},
                    |"execution_outcome":"entry_failed","execution_failure":"$sensitiveDetail"
                    |}
                    """.trimMargin(),
            )
        val base = buildFullRendererSelection()
        val genericContext = rendererDiagnosticContextEntity(id = "generic-context", sessionId = "session-1")
        val selection =
            base.copy(
                primaryContexts =
                    listOf(genericContext, restoreContext, restoreContext.copy(id = "duplicate-receipt")),
                payload = base.payload.copy(sessionContexts = listOf(genericContext, restoreContext)),
            )
        val entries =
            renderer
                .render(
                    DiagnosticsArchiveTarget(
                        file = Files.createTempFile("archive-runtime-restore", ".zip").toFile(),
                        fileName = "ripdpi-diagnostics-runtime-restore.zip",
                        createdAt = 48L,
                    ),
                    selection,
                ).associateBy(DiagnosticsArchiveEntry::name)

        val context =
            json.parseToJsonElement(entries.getValue("diagnostic-context.json").bytes.decodeToString()).jsonObject
        val receipt = context.getValue("postRuntimeRestore").jsonObject
        assertEquals("restore_failed", receipt.getValue("settlementOutcome").jsonPrimitive.content)
        assertEquals("entry_failed", receipt.getValue("executionOutcome").jsonPrimitive.content)
        assertEquals("halted", receipt.getValue("postRuntimeStatus").jsonPrimitive.content)
        assertEquals("vpn", receipt.getValue("postRuntimeMode").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("restoreErrorPresent").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("executionFailurePresent").jsonPrimitive.content)
        assertEquals(1, context.getValue("sessionContexts").jsonArray.size)
        assertFalse(entries.values.joinToString("") { it.bytes.decodeToString() }.contains(sensitiveDetail))
    }

    @Test
    fun `renderer reports malformed post runtime restore receipt separately from generic contexts`() {
        val malformed =
            rendererDiagnosticContextEntity(id = "malformed-restore", sessionId = "session-1").copy(
                contextKind = "post_runtime_restore",
                payloadJson = "{\"restore_failure\":\"secret failure\"}",
            )
        val base = buildFullRendererSelection()
        val entries =
            renderer
                .render(
                    DiagnosticsArchiveTarget(
                        file = Files.createTempFile("archive-malformed-runtime-restore", ".zip").toFile(),
                        fileName = "ripdpi-diagnostics-malformed-runtime-restore.zip",
                        createdAt = 49L,
                    ),
                    base.copy(primaryContexts = listOf(malformed)),
                ).associateBy(DiagnosticsArchiveEntry::name)
        val completeness =
            json.decodeFromString(
                DiagnosticsArchiveCompletenessPayload.serializer(),
                entries.getValue("completeness.json").bytes.decodeToString(),
            )

        assertTrue(
            completeness.reasons.any {
                it.section == "post_runtime_restore" && it.code == "unavailable_malformed" && it.count == 1
            },
        )
        assertFalse(completeness.collectionWarnings.any { it.contains("context_decode_failed_count") })
        assertFalse(entries.values.joinToString("") { it.bytes.decodeToString() }.contains("secret failure"))
    }

    @Test
    fun `renderer redacts policy handover trigger fingerprints from report payloads`() {
        val previousFingerprint = "private-previous-fingerprint"
        val currentFingerprint = "private-current-fingerprint"
        val session =
            rendererScanSession(id = "session-1").copy(
                triggerPreviousFingerprintHash = previousFingerprint,
                triggerCurrentFingerprintHash = currentFingerprint,
            )
        val stageSession =
            rendererScanSession(id = "stage-session").copy(
                triggerPreviousFingerprintHash = previousFingerprint,
                triggerCurrentFingerprintHash = currentFingerprint,
            )
        val base = buildFullRendererSelection()
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                payload = base.payload.copy(session = session),
                primarySession = session,
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "redaction-run",
                        actionable = false,
                        headline = "Complete",
                        summary = "Complete",
                    ),
                compositeStages =
                    listOf(
                        rendererCompositeStage(
                            stageKey = "automatic_audit",
                            events = emptyList(),
                            session = stageSession,
                        ),
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-redacted-policy-trigger", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-redacted-policy-trigger.zip",
                createdAt = 48L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val report = entries.getValue("report.json").bytes.decodeToString()
        val stageReport = entries.getValue("stages/automatic_audit/report.json").bytes.decodeToString()

        listOf(report, stageReport).forEach { content ->
            assertFalse(content.contains(previousFingerprint))
            assertFalse(content.contains(currentFingerprint))
            assertFalse(content.contains("triggerPreviousFingerprintHash"))
            assertFalse(content.contains("triggerCurrentFingerprintHash"))
        }
        entries.values.forEach { entry ->
            assertFalse(
                "Stable fingerprint must not appear in ${entry.name}",
                entry.bytes.decodeToString().contains("fp-render"),
            )
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
                DiagnosticsArchiveScopedCounts(
                    archiveWide =
                        DiagnosticsArchiveArchiveWideCounts(
                            telemetrySamples = 1,
                            nativeEvents = 2,
                            snapshots = 2,
                            contexts = 2,
                        ),
                    primarySession =
                        DiagnosticsArchivePrimarySessionCounts(
                            results = 1,
                            snapshots = 1,
                            contexts = 1,
                            events = 1,
                        ),
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

    private fun rendererCompositeStage(
        stageKey: String,
        events: List<NativeSessionEventEntity>,
        session: ScanSessionEntity? = null,
    ) = DiagnosticsArchiveCompositeStageSelection(
        stageSummary =
            DiagnosticsHomeCompositeStageSummary(
                stageKey = stageKey,
                stageLabel = stageKey,
                profileId = "default",
                pathMode = ScanPathMode.IN_PATH,
                sessionId = "$stageKey-session",
                status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                headline = "Complete",
                summary = "Complete",
            ),
        session = session,
        report = null,
        results = emptyList(),
        snapshots = emptyList(),
        contexts = emptyList(),
        events = events,
    )

    private fun rendererCompositeCollectionStage(
        stageKey: String,
        telemetryCount: Int,
        artifactCount: Int,
    ) = rendererCompositeStage(stageKey = stageKey, events = emptyList()).copy(
        telemetry =
            List(telemetryCount) { index ->
                rendererTelemetrySample(publicIp = null).copy(id = "$stageKey-telemetry-$index")
            },
        snapshots =
            List(artifactCount) { index ->
                rendererNetworkSnapshotEntity(id = "$stageKey-snapshot-$index", sessionId = "$stageKey-session")
            },
        contexts =
            List(artifactCount) { index ->
                rendererDiagnosticContextEntity(id = "$stageKey-context-$index", sessionId = "$stageKey-session")
            },
        sourceTelemetryCount = telemetryCount,
        sourceSnapshotCount = artifactCount,
        sourceContextCount = artifactCount,
    )

    private fun renderCompleteness(
        selection: DiagnosticsArchiveSelection,
        filePrefix: String,
    ): DiagnosticsArchiveCompletenessPayload {
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile(filePrefix, ".zip").toFile(),
                fileName = "ripdpi-diagnostics-$filePrefix.zip",
                createdAt = 44L,
            )
        val content =
            renderer
                .render(target, selection)
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("completeness.json")
                .bytes
                .decodeToString()
        return json.decodeFromString(DiagnosticsArchiveCompletenessPayload.serializer(), content)
    }

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
            rootSourceCounts =
                DiagnosticsArchiveRootSourceCounts(
                    telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit + 1,
                    primarySnapshots = DiagnosticsArchiveFormat.snapshotLimit + 1,
                    primaryContexts = DiagnosticsArchiveFormat.snapshotLimit + 1,
                    globalEvents = DiagnosticsArchiveFormat.globalEventLimit + 1,
                ),
            selectedApproachSummary = null,
            latestSnapshotModel = rendererNetworkSnapshotModel(),
            latestContextModel = rendererDiagnosticContextModel(),
            sessionContextModel = rendererDiagnosticContextModel(),
            buildProvenance = rendererBuildProvenance(),
            sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
            effectiveStrategySignature = null,
            appSettings = rendererAppSettings(),
            sourceCounts =
                DiagnosticsArchiveScopedCounts(
                    archiveWide =
                        DiagnosticsArchiveArchiveWideCounts(
                            telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit + 1,
                            nativeEvents =
                                DiagnosticsArchiveFormat.globalEventLimit +
                                    DiagnosticsArchiveFormat.sessionEventLimit +
                                    1,
                            snapshots = DiagnosticsArchiveFormat.snapshotLimit + 1,
                            contexts = DiagnosticsArchiveFormat.snapshotLimit + 1,
                        ),
                    primarySession =
                        DiagnosticsArchivePrimarySessionCounts(
                            results = 1,
                            snapshots = 1,
                            contexts = 1,
                            events = 1,
                        ),
                ),
            collectionWarnings = listOf("logcat_capture_failed:none"),
            includedFiles = DiagnosticsArchiveFormat.includedFiles(logcatIncluded = true),
            logcatSnapshot =
                LogcatSnapshot(
                    content = "x".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES),
                    captureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                    byteCount = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
                    truncated = true,
                ),
            fileLogSnapshot = null,
        )
    }

    private fun assertRenderedEntryContent(entries: Map<String, DiagnosticsArchiveEntry>) {
        val summaryText = entries.getValue("summary.txt").bytes.decodeToString()
        val reportText = entries.getValue("report.json").bytes.decodeToString()
        val analysisText = entries.getValue("analysis.json").bytes.decodeToString()
        val telemetryCsv = entries.getValue("telemetry.csv").bytes.decodeToString()
        val executionPlanText = entries.getValue("execution-plan.json").bytes.decodeToString()
        val executionPlan = json.decodeFromString(ExecutionPlanArchivePayload.serializer(), executionPlanText)
        assertTrue(entries.containsKey("summary.txt"))
        assertTrue(entries.containsKey("report.json"))
        assertTrue(entries.containsKey("execution-plan.json"))
        assertTrue(entries.containsKey("logcat.txt"))
        assertTrue(summaryText.contains("generatedAt=42"))
        assertTrue(summaryText.contains("publicIp=redacted"))
        assertTrue(summaryText.contains("classifierVersion=ru_ooni_v1"))
        assertTrue(summaryText.contains("Diagnoses:"))
        assertTrue(summaryText.contains("dns_tampering=DNS answers were substituted"))
        assertTrue(summaryText.contains("pack.ru-independent-media=1"))
        assertFalse(reportText.contains("198.51.100.8"))
        assertTrue(reportText.contains("\"classifierVersion\": \"ru_ooni_v1\""))
        assertTrue(analysisText.contains("\"networkIdentityBucket\": \"wifi:steady:redacted\""))
        assertTrue(
            analysisText.contains("\"targetBucket\": \"pilot-bucket-1|pilot-bucket-2\""),
        )
        assertTrue(analysisText.contains("\"inferredUnavailableCapabilities\": ["))
        assertTrue(analysisText.contains("\"root_helper_available\""))
        assertTrue(reportText.contains("\"tlsPathSuppressionReason\": \"proxy_mode_browser_native_tls_suppressed\""))
        assertTrue(analysisText.contains("\"policyVersion\": \"phase16_rollout_gates_v1\""))
        assertFalse(reportText.contains("127.0.0.1:1080"))
        assertTrue(telemetryCsv.contains("redacted"))
        assertTrue(telemetryCsv.contains("networkIdentityBucket"))
        assertTrue(telemetryCsv.contains("pilot-bucket-1|pilot-bucket-2"))
        assertTrue(telemetryCsv.contains("root_helper_available"))
        assertEquals("execution_plan_v1", executionPlan.executionPlan?.planVersion)
        assertEquals(
            44,
            executionPlan.executionPlan
                ?.strategy
                ?.tcpCandidates
                ?.size,
        )
        assertEquals(
            8,
            executionPlan.executionPlan
                ?.strategy
                ?.quicCandidates
                ?.size,
        )
        assertEquals(44, executionPlan.executionPlan?.strategy?.maxCandidates)
        assertFalse(executionPlanText.contains("telegram.org"))
        assertFalse(executionPlanText.contains("blocked.example"))
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
            "archive/manifest_v12.json",
            entries.getValue("manifest.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/archive_provenance_v6.json",
            entries.getValue("archive-provenance.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/runtime_config_v6.json",
            entries.getValue("runtime-config.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/analysis_v12.json",
            entries.getValue("analysis.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/completeness_v12.json",
            entries.getValue("completeness.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/integrity_v12.json",
            entries.getValue("integrity.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/execution_plan_v6.json",
            entries.getValue("execution-plan.json").bytes.decodeToString(),
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

    @Suppress("detekt.LongMethod")
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
            resolverRecommendation =
                ResolverRecommendation(
                    triggerOutcome = "dns_blocked",
                    selectedResolverId = "fixture",
                    selectedProtocol = "doh",
                    selectedEndpoint = "opaque-resolver-endpoint",
                    selectedBootstrapIps = listOf("opaque-bootstrap-ip"),
                    selectedHost = "opaque-resolver-host",
                    selectedTlsServerName = "opaque-tls-server-name",
                    selectedDohUrl = "opaque-doh-url",
                    selectedDnscryptPublicKey = "opaque-dnscrypt-public-key",
                    rationale = "fallback",
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
                                observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                                executionEvidenceComplete = true,
                                executionAttempts =
                                    listOf(
                                        StrategyProbeAttemptExecutionEvidence(
                                            probeSucceeded = false,
                                            complete = true,
                                            receipts =
                                                listOf(
                                                    StrategyDesyncExecutionReceipt(
                                                        disposition = StrategyExecutionDisposition.APPLIED,
                                                        configuredFamily = StrategyExecutionFamily.SPLIT,
                                                        effectiveFamily = StrategyExecutionFamily.SPLIT,
                                                        markerBase = StrategyOffsetMarkerBase.HOST,
                                                        markerDelta = 1,
                                                        resolvedOffset = 18,
                                                        plannedSteps = 1,
                                                        attemptedActions = 3,
                                                        completedActions = 3,
                                                        realWritesCommitted = 2,
                                                        completedAwaits = 1,
                                                        payloadBytesCommitted = 96,
                                                    ),
                                                ),
                                        ),
                                    ),
                                routeFeatures = listOf(StrategyProbeRouteFeature.UPSTREAM_RELAY),
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
                            strategySignature =
                                deriveBypassStrategySignature(
                                    rendererAppSettings(),
                                    routeGroup = "private-route",
                                ),
                            tlsPathSuppressed = true,
                            tlsPathSuppressionReason = "proxy_mode_browser_native_tls_suppressed",
                            tlsPathSuppressionSummary =
                                "Proxy mode leaves browser-originated TLS under the browser/OS stack; " +
                                    "the selected TLS template applies only to traffic the app originates itself.",
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
                            cohortId = "manual-sensitive",
                            cohortLabel = "Manual sensitive",
                            domainHosts = listOf("telegram.org", "signal.org"),
                            quicHosts = listOf("discord.com"),
                        ),
                    pilotBucketLabels = listOf("foreign:cloudflare:ech=yes", "domestic:domesticcdn:ech=no"),
                ),
            classifierVersion = "ru_ooni_v1",
            packVersions = mapOf("ru-independent-media" to 1),
            executionPlan = rendererExecutionPlan(),
        )

    private fun rendererExecutionPlan() =
        ExecutionPlanSnapshot(
            planVersion = "execution_plan_v1",
            scanKind = ScanKind.STRATEGY_PROBE,
            profileFamily = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
            pathMode = ScanPathMode.IN_PATH,
            transportKind = "socks5",
            stageOrder =
                listOf(
                    "environment",
                    "strategy_dns_baseline",
                    "strategy_tcp_candidates",
                    "strategy_quic_candidates",
                    "strategy_connection_concurrency",
                    "strategy_recommendation",
                ),
            totalSteps = 55,
            scanDeadlineMs = 270_000,
            packRefs = listOf("ru-independent-media@1"),
            probeTaskFamilies = listOf(ExecutionPlanProbeTaskFamily.TCP, ExecutionPlanProbeTaskFamily.QUIC),
            targetCounts =
                ExecutionPlanTargetCounts(
                    domainTargetCount = 6,
                    dnsTargetCount = 2,
                    tcpTargetCount = 3,
                    quicTargetCount = 2,
                    serviceTargetCount = 0,
                    circumventionTargetCount = 0,
                    throughputTargetCount = 0,
                    whitelistSniCount = 2,
                    telegramTargetCount = 0,
                    strategySelectedDomainCount = 2,
                    strategySelectedQuicCount = 1,
                ),
            strategy =
                StrategyExecutionPlanSnapshot(
                    suiteId = "full_matrix_v1",
                    inventorySemantics = "ordered_pre_runtime_filter_pool",
                    probeSeed = "42",
                    maxCandidates = 44,
                    tcpCandidates =
                        List(44) { index ->
                            rendererCandidatePlan(
                                id = "tcp-candidate-${index + 1}",
                                family = if (index == 0) "baseline_current" else "tlsrec_split",
                            )
                        },
                    quicCandidates =
                        List(8) { index ->
                            rendererCandidatePlan(
                                id = "quic-candidate-${index + 1}",
                                family = if (index == 7) "quic_disabled" else "quic_sni_split",
                            )
                        },
                    shortCircuitHostfake = true,
                    shortCircuitQuicBurst = true,
                    familyFailureThreshold = 3,
                ),
        )

    private fun rendererCandidatePlan(
        id: String,
        family: String,
    ) = StrategyCandidatePlanSnapshot(
        id = id,
        label = id,
        family = family,
        emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
        exactEmitterRequiresRoot = false,
        eligibility = "always",
        warmup = "none",
        preserveAdaptiveFakeTtl = false,
        requiresFakeTtl = false,
        requiresTcpFastOpen = false,
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
