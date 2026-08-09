package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.DefaultDiagnosticsArchiveExporter
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFileStore
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveIdGenerator
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRenderer
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveZipWriter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Verifies that `developer-analytics.json` inside a diagnostics archive never contains
 * fields that are not disclosed on the DataTransparencyScreen.
 *
 * These are the regression guards for the diagnostics archive AppSec boundary.
 */
class DeveloperAnalyticsAllowListTest {
    private val json = diagnosticsTestJson()

    private val compositeRunService =
        object : DiagnosticsHomeCompositeRunService {
            private val completedRuns = mutableMapOf<String, DiagnosticsHomeCompositeOutcome>()

            override suspend fun startHomeAnalysis(
                options: DiagnosticsHomeRunOptions,
            ): DiagnosticsHomeCompositeRunStarted = error("unused")

            override suspend fun startQuickAnalysis(
                options: DiagnosticsHomeRunOptions,
            ): DiagnosticsHomeCompositeRunStarted = error("unused")

            override fun observeHomeRun(runId: String) = error("unused")

            override suspend fun cancelHomeRun(runId: String) = error("unused")

            override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome =
                requireNotNull(completedRuns[runId]) { "Missing completed run $runId" }

            override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = completedRuns[runId]

            override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? = null

            override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit

            fun putCompletedRun(outcome: DiagnosticsHomeCompositeOutcome) {
                completedRuns[outcome.runId] = outcome
            }
        }

    private val allowList =
        setOf(
            "schemaVersion",
            "generatedAtIsoUtc",
            "stageTimings",
            "failureEnvelopes",
            "reproductionContext",
            "nativeRuntime",
            "effectiveConfigDiff",
            "networkSnapshots",
            "deviceState",
            "baselineDelta",
            "notes",
        )

    private val deniedTopLevelKeys = setOf("pcapManifest", "breadcrumbs")

    private fun violatingSource(): DeveloperAnalyticsSource =
        object : DeveloperAnalyticsSource {
            override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
                DeveloperAnalyticsPayload(
                    schemaVersion = 1,
                    generatedAtIsoUtc = "2026-05-16T00:00:00Z",
                    failureEnvelopes = listOf(hostileFailureEnvelope()),
                    reproductionContext =
                        DeveloperReproductionContext(
                            appVersionName = "1.0.0-fixture",
                            buildType = "debug",
                            nativeLibDigests = mapOf("libripdpi-fixture.so" to "sha256-fixture"),
                        ),
                    nativeRuntime =
                        DeveloperNativeRuntimeSnapshot(
                            threadCount = 4,
                            recentLogTail =
                                listOf(
                                    "Authorization: Bearer native-tail-secret",
                                    "operator=Sensitive Tail Operator",
                                    "https://private.tail.example/path",
                                ),
                            lastPanicBacktrace = "fixture-panic-backtrace",
                        ),
                    effectiveConfigDiff =
                        listOf(
                            DeveloperConfigDiffEntry(
                                key = "rootModeEnabled",
                                defaultValue = "false",
                                actualValue = "true",
                            ),
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
                            DeveloperBreadcrumb(timestampMs = 0L, category = "fixture", message = "fixture-breadcrumb"),
                        ),
                    networkSnapshots =
                        listOf(
                            DeveloperNetworkSnapshot(
                                stageKey = "dpi_full",
                                capturedAtIsoUtc = "2026-05-16T00:00:01Z",
                                transport = "cellular",
                                operatorOrSsid = "Sensitive Operator",
                                dnsServers = listOf("203.0.113.53", "198.51.100.53"),
                                signalStrengthDbm = -85,
                                cellularLevel = 3,
                                linkDownstreamKbps = 100_000,
                                linkUpstreamKbps = 20_000,
                                captivePortalDetected = false,
                                meteredNetwork = true,
                                vpnActive = false,
                                mtu = 1420,
                                handoverEvents =
                                    listOf(
                                        "Authorization: Bearer handover-secret",
                                        "operator=Sensitive Handover Operator",
                                    ),
                            ),
                        ),
                    deviceState = DeveloperDeviceState(locale = "en_US", androidSdk = 33),
                    baselineDelta = hostileBaselineDelta(),
                    notes = listOf("fixture note"),
                )
        }

    private fun hostileBaselineDelta() =
        DeveloperBaselineDelta(
            baselineClass = "device-local-distinct-networks-v1",
            baselineVersion = "device-local-v1",
            comparisons =
                listOf(
                    DeveloperBaselineMetric(
                        metric = "stage_success_rate",
                        userValue = "0.50",
                        baseline =
                            DeveloperBaselineDistribution(
                                cohort = "device_local_distinct_networks:8_stages",
                                sampleCount = 5,
                                p50 = 0.75,
                                p95 = 1.0,
                                asOfDate = "2026-04-05",
                                source = "device_local_probe_result_cache",
                            ),
                        verdict = "below_baseline",
                    ),
                ),
        )

    private fun hostileFailureEnvelope() =
        DeveloperFailureEnvelopeEntry(
            stageKey = "dpi_full",
            stageLabel = "failure-envelope-sensitive-marker-label",
            headline = "failure-envelope-sensitive-marker-headline",
            summary = "failure-envelope-sensitive-marker-summary",
            tcpErrors =
                listOf(
                    "failure-envelope-sensitive-marker-target",
                    "stages/dpi_full/report.json#/observations/1/tcp/status=CONNECT_FAILED",
                    "stages/dpi_full/report.json#/observations/5/tcp/status=BLOCKED_16KB",
                    "stages/dpi_full/report.json#/observations/6/tcp/status=BLOCKED16_KB",
                ),
            dnsErrors =
                listOf(
                    "stages/dpi_full/report.json#/observations/3/dns/status=CONNECT_FAILED",
                    "stages/dpi_full/report.json#/observations/4/dns/status=NXDOMAIN_MISMATCH",
                ),
            quicErrors = listOf("stages/dpi_full/report.json#/observations/2/quic/status=ERROR"),
        )

    private fun readDeveloperAnalyticsJson(archivePath: String): JsonObject =
        ZipFile(archivePath).use { zip ->
            val entry =
                zip.getEntry("developer-analytics.json")
                    ?: error("developer-analytics.json missing from archive")
            json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonObject
        }

    private fun readArchiveText(archivePath: String): String =
        ZipFile(archivePath).use { zip ->
            zip
                .entries()
                .asSequence()
                .filterNot { it.isDirectory }
                .joinToString("\n") { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }
        }

    private fun assertAllowList(archivePath: String) {
        val obj = readDeveloperAnalyticsJson(archivePath)

        val violatingKeys = obj.keys - allowList
        assertTrue(
            "developer-analytics.json contains undisclosed top-level keys: $violatingKeys",
            violatingKeys.isEmpty(),
        )

        for (denied in deniedTopLevelKeys) {
            assertFalse(
                "developer-analytics.json must not contain denied key '$denied'",
                obj.containsKey(denied),
            )
        }

        val serialized = obj.toString()
        assertEquals(
            "archive boundary must normalize developer analytics to its current schema",
            DeveloperAnalyticsSchemaVersion,
            obj
                .getValue("schemaVersion")
                .jsonPrimitive
                .content
                .toInt(),
        )
        assertFalse(
            "failure envelope must not export arbitrary source text",
            serialized.contains("failure-envelope-sensitive-marker"),
        )
        assertTrue(
            "failure envelope must retain typed TCP references",
            serialized.contains("stages/dpi_full/report.json#/observations/1/tcp/status=CONNECT_FAILED"),
        )
        assertFalse(
            "failure envelope must reject enum names that differ from report wire tokens",
            serialized.contains("stages/dpi_full/report.json#/observations/5/tcp/status=BLOCKED_16KB"),
        )
        assertTrue(
            "failure envelope must retain canonical report wire tokens",
            serialized.contains("stages/dpi_full/report.json#/observations/6/tcp/status=BLOCKED16_KB"),
        )
        assertTrue(
            "failure envelope must retain typed QUIC references",
            serialized.contains("stages/dpi_full/report.json#/observations/2/quic/status=ERROR"),
        )
        assertFalse(
            "failure envelope must reject enum values from a different typed field",
            serialized.contains("stages/dpi_full/report.json#/observations/3/dns/status=CONNECT_FAILED"),
        )
        assertTrue(
            "failure envelope must retain enum values valid for the typed field",
            serialized.contains("stages/dpi_full/report.json#/observations/4/dns/status=NXDOMAIN_MISMATCH"),
        )

        assertNestedDeveloperAnalyticsProjection(obj)
        assertNetworkSnapshotProjection(obj)
        assertBaselineProjection(obj)

        val encoded = readArchiveText(archivePath)
        listOf(
            "Sensitive Operator",
            "203.0.113.53",
            "198.51.100.53",
            "native-tail-secret",
            "Sensitive Tail Operator",
            "private.tail.example",
            "handover-secret",
            "Sensitive Handover Operator",
        ).forEach { sensitiveValue ->
            assertFalse(
                "developer analytics must not expose '$sensitiveValue' anywhere",
                encoded.contains(sensitiveValue),
            )
        }
    }

    private fun assertBaselineProjection(obj: JsonObject) {
        val comparison =
            obj
                .getValue("baselineDelta")
                .jsonObject
                .getValue("comparisons")
                .jsonArray
                .single()
                .jsonObject
        val baseline = comparison.getValue("baseline").jsonObject
        assertEquals(
            setOf("cohort", "sampleCount", "p50", "p95", "asOfDate", "source"),
            baseline.keys,
        )
        assertEquals("device_local_distinct_networks:8_stages", baseline.getValue("cohort").jsonPrimitive.content)
        assertEquals("5", baseline.getValue("sampleCount").jsonPrimitive.content)
        assertEquals("0.75", baseline.getValue("p50").jsonPrimitive.content)
        assertEquals("1.0", baseline.getValue("p95").jsonPrimitive.content)
        assertEquals("2026-04-05", baseline.getValue("asOfDate").jsonPrimitive.content)
        assertEquals("device_local_probe_result_cache", baseline.getValue("source").jsonPrimitive.content)
        assertEquals("below_baseline", comparison.getValue("verdict").jsonPrimitive.content)
    }

    private fun assertNestedDeveloperAnalyticsProjection(obj: JsonObject) {
        obj["reproductionContext"]?.jsonObject?.let { repro ->
            val digests = repro["nativeLibDigests"]?.jsonObject
            assertTrue(
                "reproductionContext.nativeLibDigests must be absent or empty",
                digests == null || digests.isEmpty(),
            )
        }

        obj["nativeRuntime"]?.jsonObject?.let { runtime ->
            val backtrace = runtime["lastPanicBacktrace"]
            assertTrue(
                "nativeRuntime.lastPanicBacktrace must be absent or null",
                backtrace == null || backtrace is JsonNull,
            )
            assertFalse(
                "nativeRuntime.recentLogTail must be absent",
                runtime.containsKey("recentLogTail"),
            )
        }

        val configDiff = obj["effectiveConfigDiff"] as? JsonArray
        configDiff?.forEach { element ->
            val key = runCatching { element.jsonObject["key"]?.jsonPrimitive?.content }.getOrNull()
            assertFalse(
                "effectiveConfigDiff must not contain denied key '$key'",
                key in setOf("rootModeEnabled", "enableCmdSettings"),
            )
        }
        assertTrue(
            "effectiveConfigDiff must retain allowed desyncMode evidence",
            configDiff
                ?.map { element -> element.jsonObject }
                ?.any { entry ->
                    entry["key"]?.jsonPrimitive?.content == "desyncMode" &&
                        entry["actualValue"]?.jsonPrimitive?.content == "manual"
                } == true,
        )
    }

    private fun assertNetworkSnapshotProjection(obj: JsonObject) {
        val networkSnapshotsElement = obj["networkSnapshots"]
        assertNotNull("hostile fixture must include networkSnapshots", networkSnapshotsElement)
        val networkSnapshots = requireNotNull(networkSnapshotsElement).jsonArray
        assertTrue("hostile fixture must include exactly one network snapshot", networkSnapshots.size == 1)
        val snapshot = networkSnapshots.single().jsonObject
        val allowedSnapshotKeys =
            setOf(
                "stageKey",
                "capturedAtIsoUtc",
                "transport",
                "dnsServers",
                "signalStrengthDbm",
                "cellularLevel",
                "linkDownstreamKbps",
                "linkUpstreamKbps",
                "captivePortalDetected",
                "meteredNetwork",
                "vpnActive",
                "mtu",
            )
        assertTrue("networkSnapshots must contain exactly the coarse allow-list", snapshot.keys == allowedSnapshotKeys)
        assertTrue(
            "networkSnapshots.dnsServers must retain only a coarse count",
            snapshot
                .getValue("dnsServers")
                .jsonArray
                .single()
                .jsonPrimitive.content == "redacted(2)",
        )
    }

    private fun createExporter(
        stores: FakeDiagnosticsHistoryStores,
        analyticsSource: DeveloperAnalyticsSource,
    ): DefaultDiagnosticsArchiveExporter {
        val context = TestContext()
        val appSettings = defaultDiagnosticsAppSettings()
        return DefaultDiagnosticsArchiveExporter(
            exportRecordStore = stores,
            sourceLoader =
                DiagnosticsArchiveSourceLoader(
                    appSettingsRepository = FakeAppSettingsRepository(appSettings),
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    artifactQueryStore = stores,
                    bypassUsageHistoryStore = stores,
                    logcatSnapshotCollector = FakeLogcatSnapshotCollector(snapshot = null),
                    fileLogWriter =
                        FileLogWriter(
                            java.nio.file.Files
                                .createTempDirectory("da-allowlist-test")
                                .toFile(),
                        ),
                    buildInfoProvider = buildInfoProvider(),
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    replayResultStore = ReplayResultStore(),
                    json = json,
                ),
            sessionSelector = DiagnosticsArchiveSessionSelector(DiagnosticsArchiveRedactor(json), json),
            renderer =
                DiagnosticsArchiveRenderer(
                    DiagnosticsArchiveRedactor(json),
                    DiagnosticsSummaryProjector(),
                    ReplayArchiveEntryBuilder(
                        ReplayArchiveRedactor(),
                        DiagnosticsArchiveClock { System.currentTimeMillis() },
                        json,
                    ),
                    json,
                ),
            fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = context.cacheDir,
                    clock =
                        DiagnosticsArchiveClock {
                            1_700_000_000_002L
                        },
                ),
            zipWriter = DiagnosticsArchiveZipWriter(),
            idGenerator = DiagnosticsArchiveIdGenerator { "export-da-1" },
            developerAnalyticsSource = analyticsSource,
        )
    }

    private fun buildInfoProvider(): DiagnosticsArchiveBuildInfoProvider =
        object : DiagnosticsArchiveBuildInfoProvider {
            override fun buildProvenance(): DiagnosticsArchiveBuildProvenance =
                DiagnosticsArchiveBuildProvenance(
                    applicationId = "com.poyka.ripdpi",
                    appVersionName = "0.0.2-test",
                    appVersionCode = 2L,
                    buildType = "debug",
                    gitCommit = "test-commit",
                    nativeLibraries = emptyList(),
                )
        }

    private suspend fun seedSession(
        stores: FakeDiagnosticsHistoryStores,
        id: String,
    ): com.poyka.ripdpi.data.diagnostics.ScanSessionEntity {
        val session =
            diagnosticsSession(
                id = id,
                profileId = "default",
                pathMode = ScanPathMode.IN_PATH.name,
                summary = "Test session",
            ).copy(serviceMode = "vpn")
        stores.sessionsState.value = listOf(session)
        stores.replaceProbeResults(
            sessionId = id,
            results =
                listOf(
                    ProbeResultEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = id,
                        probeType = "dns",
                        target = "blocked.example",
                        outcome = "dns_blocked",
                        detailJson = "[]",
                        createdAt = 20L,
                    ),
                ),
        )
        stores.snapshotsState.value =
            listOf(
                NetworkSnapshotEntity(
                    id = "snap-1",
                    sessionId = id,
                    snapshotKind = "post_scan",
                    payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), networkSnapshotModelForTest()),
                    capturedAt = 21L,
                ),
            )
        stores.contextsState.value =
            listOf(
                DiagnosticContextEntity(
                    id = "ctx-1",
                    sessionId = id,
                    contextKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            DiagnosticContextModel.serializer(),
                            FakeDiagnosticsContextProvider().captureContextForTest(),
                        ),
                    capturedAt = 22L,
                ),
            )
        stores.nativeEventsState.value =
            listOf(
                NativeSessionEventEntity(
                    id = "event-1",
                    sessionId = id,
                    source = "proxy",
                    level = "warn",
                    message = "fallback",
                    createdAt = 23L,
                ),
            )
        return session
    }

    @Test
    fun `developer analytics excludes undisclosed fields from share archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = seedSession(stores, "session-da-share")
            val exporter = createExporter(stores, violatingSource())

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 200L,
                    ),
                )

            assertAllowList(archive.absolutePath)
        }

    @Test
    fun `developer analytics excludes undisclosed fields from save archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = seedSession(stores, "session-da-save")
            val exporter = createExporter(stores, violatingSource())

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                        requestedAt = 201L,
                    ),
                )

            assertAllowList(archive.absolutePath)
        }

    @Test
    fun `developer analytics excludes undisclosed fields from support bundle`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-da-bundle",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Bundle session",
                ).copy(serviceMode = "vpn")
            stores.sessionsState.value = listOf(session)
            val exporter = createExporter(stores, violatingSource())

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = null,
                        reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE,
                        requestedAt = 202L,
                    ),
                )

            assertAllowList(archive.absolutePath)
        }

    private suspend fun seedCompositeStores(stores: FakeDiagnosticsHistoryStores) {
        val auditSession =
            diagnosticsSession(
                id = "da-audit-session",
                profileId = "automatic-audit",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "Audit complete",
            ).copy(serviceMode = "vpn")
        stores.sessionsState.value = listOf(auditSession)
        stores.replaceProbeResults(
            "da-audit-session",
            listOf(
                ProbeResultEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = "da-audit-session",
                    probeType = "https",
                    target = "blocked.example",
                    outcome = "ok",
                    detailJson = "[]",
                    createdAt = 30L,
                ),
            ),
        )
    }

    private fun buildCompositeOutcome(): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = "da-home-run",
            fingerprintHash = "fp-da",
            actionable = true,
            headline = "Analysis complete",
            summary = "Composite diagnostics finished.",
            recommendationSummary = "TCP split",
            confidenceSummary = "Confidence high",
            coverageSummary = "Coverage 90%",
            appliedSettings = emptyList(),
            recommendedSessionId = "da-audit-session",
            stageSummaries =
                listOf(
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "automatic_audit",
                        stageLabel = "Automatic audit",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "da-audit-session",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Audit complete",
                        summary = "Found a reusable recommendation.",
                        recommendationContributor = true,
                    ),
                ),
            completedStageCount = 1,
            failedStageCount = 0,
            skippedStageCount = 0,
            bundleSessionIds = listOf("da-audit-session"),
            connectivityAssessment =
                ConnectivityAssessment(
                    assessmentCode = ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING,
                    assessmentSummary = "Controls passed.",
                    confidence = "high",
                    rawPathEvidence =
                        ConnectivityEvidence(
                            sessionIds = listOf("da-audit-session"),
                            controls = listOf("cloudflare.com"),
                            affectedTargets = listOf("telegram.org"),
                            controlSuccessCount = 1,
                            affectedTargetFailureCount = 1,
                        ),
                    controlOutcome = "raw_controls_passed",
                    affectedTargets = listOf("telegram.org"),
                    recommendedNextAction = "Treat as direct-network blocking.",
                ),
        )

    @Test
    fun `developer analytics excludes undisclosed fields from home composite`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeStores(stores)
            val outcome = buildCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            val exporter = createExporter(stores, violatingSource())

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 203L,
                    ),
                )

            assertAllowList(archive.absolutePath)
        }

    @Test
    fun `developer analytics allowed fields match data transparency disclosure`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = seedSession(stores, "session-da-disclosure")
            val disclosedSource =
                object : DeveloperAnalyticsSource {
                    override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
                        DeveloperAnalyticsPayload(
                            schemaVersion = 1,
                            generatedAtIsoUtc = "2026-05-16T00:00:00Z",
                            reproductionContext =
                                DeveloperReproductionContext(
                                    appVersionName = "1.0.0-fixture",
                                    buildType = "release",
                                ),
                            deviceState = DeveloperDeviceState(locale = "en_US", timeZone = "UTC", androidSdk = 34),
                            notes = listOf("fixture note"),
                        )
                }
            val exporter = createExporter(stores, disclosedSource)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 204L,
                    ),
                )

            val obj = readDeveloperAnalyticsJson(archive.absolutePath)
            val undisclosedKeys = obj.keys - allowList
            assertTrue(
                "developer-analytics.json contains keys not in the disclosure allow-list: $undisclosedKeys",
                undisclosedKeys.isEmpty(),
            )
            assertNotNull("schemaVersion must be present", obj["schemaVersion"])
            assertNotNull("reproductionContext must be present", obj["reproductionContext"])
            assertNotNull("deviceState must be present", obj["deviceState"])
        }
}
