package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
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
import kotlinx.serialization.json.int
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
        )

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
    fun `renderer exports strategy attempts as privacy safe json lines`() {
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-attempts", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-attempts.zip",
                createdAt = 43L,
            )

        val entries = renderer.render(target, buildFullRendererSelection()).associateBy(DiagnosticsArchiveEntry::name)
        val attempts = entries.getValue("attempts.jsonl").bytes.decodeToString()

        assertTrue(attempts.endsWith('\n'))
        assertFalse(attempts.contains("telegram.org"))
        assertFalse(attempts.contains("blocked.example"))
        assertFalse(attempts.contains("proxyConfigJson"))
        val rows =
            attempts
                .lineSequence()
                .filter(String::isNotBlank)
                .map { line -> json.parseToJsonElement(line).jsonObject }
                .toList()
        assertEquals(2, rows.size)
        assertEquals("tcp-prod", rows[0].getValue("candidateId").jsonPrimitive.content)
        assertEquals("target-1", rows[0].getValue("targetAlias").jsonPrimitive.content)
        assertEquals("EXECUTED", rows[0].getValue("status").jsonPrimitive.content)
        assertEquals("target-2", rows[1].getValue("targetAlias").jsonPrimitive.content)
        assertEquals("SKIPPED", rows[1].getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `renderer exports capability evidence without guessing availability`() {
        val base = buildFullRendererSelection()
        val report = capabilityEvidenceReport(requireNotNull(base.primaryReport))
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-capabilities", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-capabilities.zip",
                createdAt = 44L,
            )

        val text =
            renderer
                .render(target, base.copy(primaryReport = report))
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("capabilities.json")
                .bytes
                .decodeToString()
        val capabilities =
            json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("capabilities")
                .jsonArray

        assertEquals(
            listOf("root_helper_available", "ttl_write"),
            capabilities.map { item ->
                item.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("UNAVAILABLE", "AVAILABLE"),
            capabilities.map { item ->
                item.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            },
        )
        assertTrue(
            capabilities.all { item ->
                item.jsonObject
                    .getValue("evidenceRefs")
                    .jsonArray
                    .isNotEmpty()
            },
        )
        assertFalse(text.contains("blocked.example"))
        assertFalse(text.contains("telegram.org"))
        GoldenContractSupport.assertJsonGolden("archive/capabilities_v10.json", text)
    }

    @Test
    fun `renderer exports evidenced emission receipts without claiming skipped emissions`() {
        val base = buildFullRendererSelection()
        val report = capabilityEvidenceReport(requireNotNull(base.primaryReport))
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-emission-receipts", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-emission-receipts.zip",
                createdAt = 45L,
            )

        val text =
            renderer
                .render(target, base.copy(primaryReport = report))
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("emission-receipts.jsonl")
                .bytes
                .decodeToString()
        val rows =
            text
                .lineSequence()
                .filter(String::isNotBlank)
                .map(json::parseToJsonElement)
                .map { row -> row.jsonObject }
                .toList()

        assertEquals(listOf("tcp-prod", "tcp-rooted"), rows.map { it.getValue("candidateId").jsonPrimitive.content })
        assertEquals(
            listOf("EXACT_EMITTER_EXECUTED", "NOT_EMITTED"),
            rows.map { it.getValue("emissionStatus").jsonPrimitive.content },
        )
        assertEquals(
            listOf("ATTEMPT_EXECUTED", "CAPABILITY_SKIPPED"),
            rows.map { it.getValue("evidenceBasis").jsonPrimitive.content },
        )
        assertTrue(rows.all { row -> row.getValue("evidenceRefs").jsonArray.isNotEmpty() })
        assertFalse(text.contains("blocked.example"))
        assertFalse(text.contains("telegram.org"))
        GoldenContractSupport.assertTextGolden("archive/emission_receipts_v10.jsonl", text)
    }

    @Test
    fun `renderer does not resolve conflicting emission evidence into a verdict`() {
        val base = buildFullRendererSelection()
        val report = capabilityEvidenceReport(requireNotNull(base.primaryReport))
        val strategyReport = requireNotNull(report.strategyProbeReport)
        val conflicted =
            report.copy(
                strategyProbeReport =
                    strategyReport.copy(
                        tcpCandidates =
                            strategyReport.tcpCandidates.map { candidate ->
                                if (candidate.id == "tcp-prod") {
                                    candidate.copy(outcome = "capability_skipped", skipped = true)
                                } else {
                                    candidate
                                }
                            },
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-conflicting-emission", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-conflicting-emission.zip",
                createdAt = 46L,
            )

        val entries =
            renderer
                .render(target, base.copy(primaryReport = conflicted))
                .associateBy(DiagnosticsArchiveEntry::name)
        val capabilities =
            json
                .parseToJsonElement(entries.getValue("capabilities.json").bytes.decodeToString())
                .jsonObject
                .getValue("capabilities")
                .jsonArray
        val receipt =
            entries
                .getValue("emission-receipts.jsonl")
                .bytes
                .decodeToString()
                .lineSequence()
                .first()
                .let(json::parseToJsonElement)
                .jsonObject

        assertEquals(
            "UNKNOWN",
            capabilities
                .single {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "ttl_write"
                }.jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertEquals("UNVERIFIED", receipt.getValue("emissionStatus").jsonPrimitive.content)
        assertEquals("CONFLICTING_EVIDENCE", receipt.getValue("evidenceBasis").jsonPrimitive.content)
    }

    @Test
    fun `renderer preserves executed attempt without asserting emitter when summary is absent`() {
        val base = buildFullRendererSelection()
        val report = capabilityEvidenceReport(requireNotNull(base.primaryReport))
        val withoutSummaries =
            report.copy(
                strategyProbeReport = requireNotNull(report.strategyProbeReport).copy(tcpCandidates = emptyList()),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-attempt-without-summary", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-attempt-without-summary.zip",
                createdAt = 47L,
            )

        val receipt =
            renderer
                .render(target, base.copy(primaryReport = withoutSummaries))
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("emission-receipts.jsonl")
                .bytes
                .decodeToString()
                .lineSequence()
                .first()
                .let(json::parseToJsonElement)
                .jsonObject

        assertEquals("UNVERIFIED", receipt.getValue("emissionStatus").jsonPrimitive.content)
        assertEquals("ATTEMPT_EXECUTED", receipt.getValue("evidenceBasis").jsonPrimitive.content)
        assertEquals(1, receipt.getValue("executedAttemptCount").jsonPrimitive.int)
    }

    @Test
    fun `renderer exports evidenced privacy safe protocol milestones`() {
        val base = buildFullRendererSelection()
        val report = protocolMilestoneReport(requireNotNull(base.primaryReport))
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-protocol-milestones", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-protocol-milestones.zip",
                createdAt = 44L,
            )

        val text =
            renderer
                .render(target, base.copy(primaryReport = report))
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("protocol-milestones.jsonl")
                .bytes
                .decodeToString()
        val rows =
            text
                .lineSequence()
                .filter(String::isNotBlank)
                .map(json::parseToJsonElement)
                .toList()

        assertEquals(3, rows.size)
        assertEquals(
            listOf("TLS_HANDSHAKE", "HTTP_RESPONSE", "QUIC_RESPONSE"),
            rows.map { row ->
                row.jsonObject
                    .getValue("milestone")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("FAILED", "REACHED", "REACHED"),
            rows.map { row ->
                row.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            listOf(1, 2, 3),
            rows.map { row ->
                row.jsonObject
                    .getValue("sequence")
                    .jsonPrimitive.content
                    .toInt()
            },
        )
        assertTrue(
            rows.all { row ->
                row.jsonObject
                    .getValue("evidenceRef")
                    .jsonPrimitive.content
                    .isNotBlank()
            },
        )
        assertFalse(text.contains("blocked.example"))
        assertFalse(text.contains("quic.example"))
        assertFalse(text.contains("CLIENT_HELLO"))
        GoldenContractSupport.assertTextGolden("archive/protocol_milestones_v9.jsonl", text)
    }

    @Test
    fun `renderer exports protocol milestones for every composite stage`() {
        val base = buildFullRendererSelection()
        val report = protocolMilestoneReport(requireNotNull(base.primaryReport))
        val stage =
            rendererCompositeStage(
                stageKey = "automatic_audit",
                events = base.primaryEvents,
                session = base.primarySession,
            ).copy(report = report)
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                homeRunId = "protocol-home-run",
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "protocol-home-run",
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
                        composite = true,
                        compositeStageKeys = listOf("automatic_audit"),
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-stage-protocol-milestones", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-stage-protocol-milestones.zip",
                createdAt = 44L,
            )

        val text =
            renderer
                .render(target, selection)
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("stages/automatic_audit/protocol-milestones.jsonl")
                .bytes
                .decodeToString()

        assertTrue(text.lineSequence().filter(String::isNotBlank).count() == 3)
        assertFalse(text.contains("blocked.example"))
        assertFalse(text.contains("quic.example"))
    }

    @Test
    fun `renderer exports capability and emission evidence for every composite stage`() {
        val base = buildFullRendererSelection()
        val report = capabilityEvidenceReport(requireNotNull(base.primaryReport))
        val stage =
            rendererCompositeStage(
                stageKey = "automatic_audit",
                events = base.primaryEvents,
                session = base.primarySession,
            ).copy(report = report)
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                homeRunId = "emission-home-run",
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "emission-home-run",
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
                        composite = true,
                        compositeStageKeys = listOf("automatic_audit"),
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-stage-emission", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-stage-emission.zip",
                createdAt = 46L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val capabilities = entries.getValue("stages/automatic_audit/capabilities.json").bytes.decodeToString()
        val receipts = entries.getValue("stages/automatic_audit/emission-receipts.jsonl").bytes.decodeToString()

        assertTrue(capabilities.contains("stages/automatic_audit/execution-plan.json#"))
        assertTrue(receipts.contains("stages/automatic_audit/attempts.jsonl#L1"))
        assertFalse(capabilities.contains("blocked.example"))
        assertFalse(receipts.contains("telegram.org"))
    }

    @Test
    fun `renderer projects every structured network protocol family`() {
        val base = buildFullRendererSelection()
        val report =
            requireNotNull(base.primaryReport).copy(
                observations = networkProtocolFamilyObservations(),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-network-protocol-families", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-network-protocol-families.zip",
                createdAt = 45L,
            )

        val rows =
            renderer
                .render(target, base.copy(primaryReport = report))
                .associateBy(DiagnosticsArchiveEntry::name)
                .getValue("protocol-milestones.jsonl")
                .bytes
                .decodeToString()
                .lineSequence()
                .filter(String::isNotBlank)
                .map(json::parseToJsonElement)
                .toList()

        assertEquals(
            listOf(
                "DNS_COMPARISON",
                "TCP_CONNECT",
                "TCP_FLOW",
                "HTTP_BOOTSTRAP",
                "HTTP_MEDIA_RESPONSE",
                "SERVICE_ENDPOINT",
                "QUIC_RESPONSE",
                "HTTP_BOOTSTRAP",
                "CIRCUMVENTION_HANDSHAKE",
            ),
            rows.map { row ->
                row.jsonObject
                    .getValue("milestone")
                    .jsonPrimitive.content
            },
        )
        assertTrue(
            rows.all { row ->
                row.jsonObject
                    .getValue("evidenceRef")
                    .jsonPrimitive.content
                    .startsWith("report.json#/")
            },
        )
    }

    @Test
    fun `renderer exports ordered privacy safe decision trace`() {
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-decision-trace", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-decision-trace.zip",
                createdAt = 44L,
            )

        val entries = renderer.render(target, buildFullRendererSelection()).associateBy(DiagnosticsArchiveEntry::name)
        val traceText = entries.getValue("decision-trace.json").bytes.decodeToString()
        val trace = json.parseToJsonElement(traceText).jsonObject
        val decisions = trace.getValue("decisions").jsonArray

        assertEquals("decision_trace_v1", trace.getValue("traceVersion").jsonPrimitive.content)
        assertEquals(
            listOf(
                "SCAN_COMPLETION",
                "DIAGNOSIS",
                "RESOLVER_SELECTION",
                "STRATEGY_PROBE_COMPLETION",
                "STRATEGY_SELECTION",
                "STRATEGY_AUDIT",
            ),
            decisions.map { decision ->
                decision.jsonObject
                    .getValue("decisionType")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            (1..decisions.size).toList(),
            decisions.map { decision ->
                decision.jsonObject
                    .getValue("sequence")
                    .jsonPrimitive.content
                    .toInt()
            },
        )
        assertTrue(
            decisions.all { decision ->
                decision.jsonObject
                    .getValue("evidenceRefs")
                    .jsonArray
                    .isNotEmpty()
            },
        )
        assertTrue(traceText.contains("tcp-prod"))
        assertTrue(traceText.contains("quic-prod"))
        assertFalse(traceText.contains("blocked.example"))
        assertFalse(traceText.contains("opaque-resolver-endpoint"))
        assertFalse(traceText.contains("recommendedProxyConfigJson"))
    }

    @Test
    fun `renderer exports decision trace for every composite stage`() {
        val base = buildFullRendererSelection()
        val stage =
            rendererCompositeStage(
                stageKey = "automatic_audit",
                events = base.primaryEvents,
                session = base.primarySession,
            ).copy(report = base.primaryReport)
        val selection =
            base.copy(
                runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
                homeRunId = "decision-home-run",
                homeCompositeOutcome =
                    DiagnosticsHomeCompositeOutcome(
                        runId = "decision-home-run",
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
                        composite = true,
                        compositeStageKeys = listOf("automatic_audit"),
                    ),
            )
        val target =
            DiagnosticsArchiveTarget(
                file = Files.createTempFile("archive-stage-decision-trace", ".zip").toFile(),
                fileName = "ripdpi-diagnostics-stage-decision-trace.zip",
                createdAt = 44L,
            )

        val entries = renderer.render(target, selection).associateBy(DiagnosticsArchiveEntry::name)
        val traceText = entries.getValue("stages/automatic_audit/decision-trace.json").bytes.decodeToString()
        val trace = json.parseToJsonElement(traceText).jsonObject

        assertEquals("decision_trace_v1", trace.getValue("traceVersion").jsonPrimitive.content)
        assertTrue(trace.getValue("decisions").jsonArray.isNotEmpty())
        assertFalse(traceText.contains("blocked.example"))
        assertFalse(traceText.contains("opaque-resolver-endpoint"))
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
        assertEquals(10, DiagnosticsArchiveFormat.schemaVersion)
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
    fun `decode failure counts subtract successfully decoded artifacts`() {
        val base = buildFullRendererSelection()
        val malformedSnapshot = rendererNetworkSnapshotEntity(sessionId = "session-1").copy(payloadJson = "{bad")
        val malformedContext = rendererDiagnosticContextEntity(sessionId = "session-1").copy(payloadJson = "{bad")
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
                primaryReport =
                    base.primaryReport?.copy(
                        executionPlan = null,
                        strategyProbeReport = base.primaryReport.strategyProbeReport?.copy(attempts = emptyList()),
                    ),
                includedFiles =
                    listOf(
                        "execution-plan.json",
                        "attempts.jsonl",
                        "stages/legacy/execution-plan.json",
                        "stages/legacy/attempts.jsonl",
                    ),
                compositeStages = listOf(stage.copy(report = stage.report?.copy(executionPlan = null))),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(
            mapOf(
                "execution-plan.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "attempts.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/execution-plan.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/attempts.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
            ),
            statuses,
        )
    }

    @Test
    fun `capability and emission sections are unavailable without a strategy execution plan`() {
        val base = buildFullRendererSelection()
        val stage = rendererCompositeStage("legacy", emptyList())
        val rootReport = requireNotNull(base.primaryReport)
        val rootExecutionPlan = requireNotNull(rootReport.executionPlan)
        val legacyReport = rootReport.copy(executionPlan = rootExecutionPlan.copy(strategy = null))
        val selection =
            base.copy(
                primaryReport = legacyReport,
                includedFiles =
                    listOf(
                        "capabilities.json",
                        "emission-receipts.jsonl",
                        "stages/legacy/capabilities.json",
                        "stages/legacy/emission-receipts.jsonl",
                    ),
                compositeStages =
                    listOf(
                        stage.copy(report = legacyReport),
                    ),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(
            mapOf(
                "capabilities.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "emission-receipts.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/capabilities.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/emission-receipts.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
            ),
            statuses,
        )
    }

    @Test
    fun `decision trace sections are unavailable when reports are missing`() {
        val base = buildFullRendererSelection()
        val stage = rendererCompositeStage("legacy", emptyList())
        val selection =
            base.copy(
                primaryReport = null,
                includedFiles =
                    listOf(
                        "decision-trace.json",
                        "stages/legacy/decision-trace.json",
                    ),
                compositeStages = listOf(stage),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(
            mapOf(
                "decision-trace.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/decision-trace.json" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
            ),
            statuses,
        )
    }

    @Test
    fun `protocol milestone sections are unavailable without structured observations`() {
        val base = buildFullRendererSelection()
        val stage = rendererCompositeStage("legacy", emptyList())
        val selection =
            base.copy(
                primaryReport = base.primaryReport?.copy(observations = emptyList()),
                includedFiles =
                    listOf(
                        "protocol-milestones.jsonl",
                        "stages/legacy/protocol-milestones.jsonl",
                    ),
                compositeStages = listOf(stage),
            )

        val statuses = buildSectionStatuses(selection)

        assertEquals(
            mapOf(
                "protocol-milestones.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
                "stages/legacy/protocol-milestones.jsonl" to DiagnosticsArchiveSectionStatus.UNAVAILABLE,
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
                report = base.primaryReport,
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
            val attemptsEntry = requireNotNull(zip.getEntry("stages/automatic_audit/attempts.jsonl"))
            assertNotNull(zip.getEntry("stages/automatic_audit/native-events.csv"))
            assertNotNull(zip.getEntry("stages/automatic_audit/probe-results.csv"))
            val logcat = zip.getInputStream(zip.getEntry("logcat.txt")).bufferedReader().readText()
            val appLog = zip.getInputStream(zip.getEntry("app-log.txt")).bufferedReader().readText()
            val events = zip.getInputStream(zip.getEntry("native-events.csv")).bufferedReader().readText()
            val attempts = zip.getInputStream(attemptsEntry).bufferedReader().readText()
            assertFalse(logcat.contains(logFixture.partialLogcatLine))
            assertTrue(logcat.contains(logFixture.newestLogcatLine))
            assertTrue(logcat.contains("I/RIPDPI( 123): <path-redacted>"))
            assertFalse(appLog.contains(logFixture.partialAppLogLine))
            assertTrue(appLog.contains(logFixture.newestAppLogLine))
            assertFalse(events.contains("successfully before retry"))
            assertFalse(events.contains("status=failed"))
            assertTrue(events.contains("ready"))
            assertTrue(attempts.contains("\"candidateId\":\"tcp-prod\""))
            assertTrue(attempts.contains("\"targetAlias\":\"target-1\""))
            assertFalse(attempts.contains("telegram.org"))
            assertFalse(attempts.contains("blocked.example"))
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
        ) + hostilePathArchiveValues()

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
        val hostileResult =
            rendererProbeResult(sessionId = "session-1").copy(
                target = "probe.private.example",
                detailJson =
                    """{"target":"detail.private.example","address":"2001:db8::44","path":"/data/private/trace"}""",
            )
        val hostileEvent = hostileNativeEvent()
        val hostileReplay = hostileReplayResult()
        val hostileApproach = hostileApproachSummary()
        return base.copy(
            payload =
                base.payload.copy(
                    results = listOf(hostileResult),
                    sessionEvents = listOf(hostileEvent),
                    approachSummaries = listOf(hostileApproach),
                ),
            primaryResults = listOf(hostileResult),
            primaryEvents = listOf(hostileEvent),
            selectedApproachSummary = hostileApproach,
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
            logcatSnapshot =
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
                ),
        )
    }

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

    private fun hostileApproachSummary() =
        rendererApproachSummary(strategyId = "approach-private-value").copy(
            displayName = "Private Approach Name",
            secondaryLabel = "private.secondary.example",
            lastValidatedResult = "private-validation-result",
            recentRuntimeHealth =
                BypassRuntimeHealthSummary(
                    totalErrors = 1,
                    lastEndedReason = "private-runtime-end-reason",
                ),
            topFailureOutcomes = listOf("private-failure-outcome"),
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

    private fun protocolMilestoneReport(report: EngineScanReportWire) =
        report.copy(
            observations =
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "blocked.example",
                        domain =
                            DomainObservationFact(
                                host = "blocked.example",
                                httpStatus = HttpProbeStatus.BLOCKPAGE,
                                tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                transportFailure = TransportFailureKind.RESET,
                                tlsError = "reset after blocked.example",
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.QUIC,
                        target = "quic.example",
                        quic =
                            QuicObservationFact(
                                host = "quic.example",
                                status = QuicProbeStatus.INITIAL_RESPONSE,
                            ),
                    ),
                ),
        )

    private fun capabilityEvidenceReport(report: EngineScanReportWire): EngineScanReportWire {
        val executionPlan = requireNotNull(report.executionPlan)
        val strategyPlan = requireNotNull(executionPlan.strategy)
        return report.copy(
            executionPlan =
                executionPlan.copy(
                    strategy =
                        strategyPlan.copy(
                            tcpCandidates =
                                listOf(
                                    rendererCandidatePlan("tcp-prod", "tlsrec_split").copy(
                                        requiredCapabilities = listOf("ttl_write"),
                                    ),
                                    rendererCandidatePlan("tcp-rooted", "seqovl").copy(
                                        emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                                        exactEmitterRequiresRoot = true,
                                        requiredCapabilities = listOf("root_helper_available"),
                                    ),
                                ),
                            quicCandidates = emptyList(),
                        ),
                ),
            strategyProbeReport = report.strategyProbeReport?.copy(quicCandidates = emptyList()),
        )
    }

    private fun networkProtocolFamilyObservations() =
        listOf(
            ObservationFact(
                kind = ObservationKind.DNS,
                target = "dns.example",
                dns =
                    DnsObservationFact(
                        domain = "dns.example",
                        status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                    ),
            ),
            ObservationFact(
                kind = ObservationKind.TCP,
                target = "tcp.example",
                tcp = TcpObservationFact(provider = "provider", status = TcpProbeStatus.BLOCKED_16KB),
            ),
            ObservationFact(
                kind = ObservationKind.SERVICE,
                target = "service.example",
                service =
                    ServiceObservationFact(
                        service = "media",
                        bootstrapStatus = HttpProbeStatus.OK,
                        mediaStatus = HttpProbeStatus.BLOCKPAGE,
                        endpointStatus = EndpointProbeStatus.FAILED,
                        endpointFailure = TransportFailureKind.TIMEOUT,
                        quicStatus = QuicProbeStatus.ERROR,
                        quicFailure = TransportFailureKind.RESET,
                    ),
            ),
            ObservationFact(
                kind = ObservationKind.CIRCUMVENTION,
                target = "circumvention.example",
                circumvention =
                    CircumventionObservationFact(
                        tool = "tool",
                        bootstrapStatus = HttpProbeStatus.OK,
                        handshakeStatus = EndpointProbeStatus.BLOCKED,
                        handshakeFailure = TransportFailureKind.CLOSE,
                    ),
            ),
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
            analysisText.contains("\"targetBucket\": \"foreign:cloudflare:ech=yes|domestic:domesticcdn:ech=no\""),
        )
        assertTrue(analysisText.contains("\"inferredUnavailableCapabilities\": ["))
        assertTrue(analysisText.contains("\"root_helper_available\""))
        assertTrue(reportText.contains("\"tlsPathSuppressionReason\": \"proxy_mode_browser_native_tls_suppressed\""))
        assertTrue(analysisText.contains("\"policyVersion\": \"phase16_rollout_gates_v1\""))
        assertFalse(reportText.contains("127.0.0.1:1080"))
        assertTrue(telemetryCsv.contains("redacted"))
        assertTrue(telemetryCsv.contains("networkIdentityBucket"))
        assertTrue(telemetryCsv.contains("foreign:cloudflare:ech=yes|domestic:domesticcdn:ech=no"))
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
            "archive/manifest_v10.json",
            entries.getValue("manifest.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/archive_provenance_v10.json",
            entries.getValue("archive-provenance.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/runtime_config_v10.json",
            entries.getValue("runtime-config.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/analysis_v10.json",
            entries.getValue("analysis.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/completeness_v10.json",
            entries.getValue("completeness.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/integrity_v10.json",
            entries.getValue("integrity.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/execution_plan_v10.json",
            entries.getValue("execution-plan.json").bytes.decodeToString(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/decision_trace_v10.json",
            entries.getValue("decision-trace.json").bytes.decodeToString(),
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
                    attempts =
                        listOf(
                            StrategyProbeAttempt(
                                attemptVersion = "strategy_attempt_v1",
                                sequence = 1,
                                candidateIndex = 1,
                                candidateId = "tcp-prod",
                                candidateLabel = "TLS split",
                                candidateFamily = "tlsrec_split",
                                lane = StrategyProbeProgressLane.TCP,
                                target = "telegram.org",
                                targetIndex = 1,
                                isControl = false,
                                protocol = "HTTPS",
                                round = StrategyProbeAttemptRound.FULL_MATRIX,
                                status = StrategyProbeAttemptStatus.EXECUTED,
                                startedAtMs = 10L,
                                durationMs = 120L,
                                retryCount = 1,
                                outcome = "tls_ok",
                            ),
                            StrategyProbeAttempt(
                                attemptVersion = "strategy_attempt_v1",
                                sequence = 2,
                                candidateIndex = 2,
                                candidateId = "tcp-rooted",
                                candidateLabel = "Rooted seqovl",
                                candidateFamily = "seqovl",
                                lane = StrategyProbeProgressLane.TCP,
                                target = "blocked.example",
                                targetIndex = 2,
                                isControl = true,
                                protocol = "HTTP",
                                round = StrategyProbeAttemptRound.NOT_STARTED,
                                status = StrategyProbeAttemptStatus.SKIPPED,
                                outcome = "capability_skipped",
                                reason = "Requires root_helper_available for blocked.example",
                            ),
                        ),
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
