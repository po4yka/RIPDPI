package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportQuic
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.PreferredEdgeTransportThroughput
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScanRequestFactoryTargetSelectionTest {
    @Test
    fun `automatic audit request selects one cohort and records target selection`() =
        runTest {
            val request =
                prepareStrategyProbeRequest(
                    settings = defaultDiagnosticsAppSettings(),
                    profileId = "automatic-audit",
                    family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                    suiteId = "full_matrix_v1",
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    strategyProbeTargetCohorts =
                        listOf(
                            auditCohort(
                                id = "safe-default",
                                label = "Safe default",
                                domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                                quicHosts = listOf("www.youtube.com", "discord.com"),
                            ),
                            auditCohort(
                                id = "manual-sensitive",
                                label = "Manual sensitive",
                                domainHosts = listOf("telegram.org", "signal.org", "www.whatsapp.com"),
                                quicHosts = listOf("discord.com", "www.whatsapp.com"),
                            ),
                        ),
                )

            val selection = requireNotNull(request.strategyProbe?.targetSelection)

            assertEquals(3, request.domainTargets.size)
            assertEquals(2, request.quicTargets.size)
            assertTrue(selection.cohortId in listOf("safe-default", "manual-sensitive"))
            assertEquals(request.domainTargets.map(DomainTarget::host), selection.domainHosts)
            assertEquals(request.quicTargets.map(QuicTarget::host), selection.quicHosts)
        }

    @Test
    fun `different session ids can produce different automatic audit cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "safe-default",
                            label = "Safe default",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "manual-sensitive",
                            label = "Manual sensitive",
                            domainHosts = listOf("telegram.org", "signal.org", "www.whatsapp.com"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val firstSelection =
            requireNotNull(
                selectStrategyProbeTargetsForSession("audit-session-1", intent).strategyProbe?.targetSelection,
            )
        val secondSelection =
            generateSequence(2) { it + 1 }
                .map { attempt ->
                    requireNotNull(
                        selectStrategyProbeTargetsForSession(
                            "audit-session-$attempt",
                            intent,
                        ).strategyProbe?.targetSelection,
                    )
                }.first { selection -> selection.cohortId != firstSelection.cohortId }

        assertNotEquals(firstSelection.cohortId, secondSelection.cohortId)
    }

    @Test
    fun `automatic audit falls back to fixed targets when cohorts are invalid`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                domainTargets =
                    listOf(
                        DomainTarget(host = "fallback-a.example"),
                        DomainTarget(host = "fallback-b.example"),
                        DomainTarget(host = "fallback-c.example"),
                    ),
                quicTargets =
                    listOf(
                        QuicTarget(host = "fallback-quic-a.example"),
                        QuicTarget(host = "fallback-quic-b.example"),
                    ),
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "invalid",
                            label = "Invalid",
                            domainHosts = listOf("only-one.example"),
                            quicHosts = listOf("only-one-quic.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("audit-session", intent)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertEquals(intent.quicTargets.map(QuicTarget::host), selected.quicTargets.map(QuicTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `manual analysis merges all valid cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "safe-default",
                            label = "Safe default",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "manual-sensitive",
                            label = "Manual sensitive",
                            domainHosts = listOf("telegram.org", "signal.org", "www.whatsapp.com"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-1", intent, isManual = true)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertEquals("all", selection.cohortId)
        assertEquals("All cohorts", selection.cohortLabel)
        // 6 unique domain hosts (3+3, no overlap)
        assertEquals(6, selected.domainTargets.size)
        // 3 unique QUIC hosts (discord.com appears in both cohorts)
        assertEquals(3, selected.quicTargets.size)
        assertEquals(6, selection.domainHosts.size)
        assertEquals(3, selection.quicHosts.size)
    }

    @Test
    fun `manual analysis deduplicates overlapping hosts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "safe-default",
                            label = "Safe default",
                            domainHosts = listOf("shared-a.example", "shared-b.example", "unique-a.example"),
                            quicHosts = listOf("shared-quic.example", "unique-quic-a.example"),
                        ),
                        auditCohort(
                            id = "lab-only",
                            label = "Lab only",
                            domainHosts = listOf("shared-a.example", "shared-b.example", "unique-b.example"),
                            quicHosts = listOf("shared-quic.example", "unique-quic-b.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-2", intent, isManual = true)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertEquals("all", selection.cohortId)
        assertEquals("All cohorts", selection.cohortLabel)
        // shared-a and shared-b appear in both cohorts but are deduplicated;
        // unique-a and unique-b each appear once; total = 4 unique hosts
        val domainHosts = selected.domainTargets.map(DomainTarget::host)
        assertEquals(4, domainHosts.size)
        assertTrue(domainHosts.contains("shared-a.example"))
        assertTrue(domainHosts.contains("shared-b.example"))
        assertTrue(domainHosts.contains("unique-a.example"))
        assertTrue(domainHosts.contains("unique-b.example"))
        // shared-quic.example appears in both cohorts but is deduplicated;
        // unique-quic-a.example and unique-quic-b.example each appear once; total = 3 unique
        val quicHosts = selected.quicTargets.map(QuicTarget::host)
        assertEquals(3, quicHosts.size)
        assertTrue(quicHosts.contains("shared-quic.example"))
        assertTrue(quicHosts.contains("unique-quic-a.example"))
        assertTrue(quicHosts.contains("unique-quic-b.example"))
    }

    @Test
    fun `non-manual analysis still rotates cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "safe-default",
                            label = "Safe default",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "manual-sensitive",
                            label = "Manual sensitive",
                            domainHosts = listOf("telegram.org", "signal.org", "www.whatsapp.com"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-1", intent, isManual = false)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertNotEquals("all", selection.cohortId)
        assertTrue(selection.cohortId in listOf("safe-default", "manual-sensitive"))
        // Single cohort selected: exactly 3 domain and 2 QUIC targets
        assertEquals(3, selected.domainTargets.size)
        assertEquals(2, selected.quicTargets.size)
    }

    @Test
    fun `manual analysis with invalid cohorts falls back`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                domainTargets =
                    listOf(
                        DomainTarget(host = "fallback-a.example"),
                        DomainTarget(host = "fallback-b.example"),
                        DomainTarget(host = "fallback-c.example"),
                    ),
                quicTargets =
                    listOf(
                        QuicTarget(host = "fallback-quic-a.example"),
                        QuicTarget(host = "fallback-quic-b.example"),
                    ),
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "invalid",
                            label = "Invalid",
                            domainHosts = listOf("only-one.example"),
                            quicHosts = listOf("only-one-quic.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("audit-session", intent, isManual = true)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertEquals(intent.quicTargets.map(QuicTarget::host), selected.quicTargets.map(QuicTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `quick strategy probe ignores audit cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-probing",
                family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                suiteId = "quick_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "safe-default",
                            label = "Safe default",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("probe-session", intent)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `preferred edges populate ordered connect ips for planner targets`() =
        runTest {
            val request =
                prepareStrategyProbeRequest(
                    settings = defaultDiagnosticsAppSettings(),
                    domainTargets = listOf(DomainTarget(host = "example.org")),
                    quicTargets = listOf(QuicTarget(host = "example.org")),
                    throughputTargets =
                        listOf(
                            ThroughputTarget(
                                id = "throughput",
                                label = "Throughput",
                                url = "https://example.org/file.bin",
                            ),
                        ),
                    preferredEdges =
                        mapOf(
                            "example.org" to
                                listOf(
                                    PreferredEdgeCandidate(
                                        ip = "203.0.113.10",
                                        transportKind = PreferredEdgeTransportTcp,
                                        ipVersion = PreferredEdgeIpVersionV4,
                                    ),
                                    PreferredEdgeCandidate(
                                        ip = "203.0.113.20",
                                        transportKind = PreferredEdgeTransportTcp,
                                        ipVersion = PreferredEdgeIpVersionV4,
                                    ),
                                    PreferredEdgeCandidate(
                                        ip = "203.0.113.30",
                                        transportKind = PreferredEdgeTransportQuic,
                                        ipVersion = PreferredEdgeIpVersionV4,
                                    ),
                                    PreferredEdgeCandidate(
                                        ip = "203.0.113.40",
                                        transportKind = PreferredEdgeTransportThroughput,
                                        ipVersion = PreferredEdgeIpVersionV4,
                                    ),
                                ),
                        ),
                )

            assertEquals(listOf("203.0.113.10", "203.0.113.20"), request.domainTargets.single().connectIps)
            assertEquals("203.0.113.10", request.domainTargets.single().connectIp)
            assertEquals(listOf("203.0.113.30"), request.quicTargets.single().connectIps)
            assertEquals("203.0.113.30", request.quicTargets.single().connectIp)
            assertEquals(listOf("203.0.113.40"), request.throughputTargets.single().connectIps)
            assertEquals("203.0.113.40", request.throughputTargets.single().connectIp)
        }

    private fun strategyProbeIntent(
        settings: com.poyka.ripdpi.proto.AppSettings,
        baseProxyConfigJson: String?,
        profileId: String = "strategy-probe",
        family: DiagnosticProfileFamily = DiagnosticProfileFamily.AUTOMATIC_PROBING,
        suiteId: String = "quick_v1",
        domainTargets: List<DomainTarget> = listOf(DomainTarget(host = "example.org")),
        quicTargets: List<QuicTarget> = listOf(QuicTarget(host = "example.org")),
        throughputTargets: List<ThroughputTarget> = emptyList(),
        strategyProbeTargetCohorts: List<StrategyProbeTargetCohortSpec> = emptyList(),
    ) = DiagnosticsIntent(
        profileId = profileId,
        displayName = "Strategy probe",
        settings = settings,
        kind = ScanKind.STRATEGY_PROBE,
        family = family,
        regionTag = null,
        executionPolicy =
            ExecutionPolicy(
                manualOnly = false,
                allowBackground = false,
                requiresRawPath = true,
                probePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
            ),
        packRefs = emptyList(),
        domainTargets = domainTargets,
        dnsTargets = emptyList(),
        tcpTargets = emptyList(),
        quicTargets = quicTargets,
        serviceTargets = emptyList(),
        circumventionTargets = emptyList(),
        throughputTargets = throughputTargets,
        whitelistSni = emptyList(),
        telegramTarget = null,
        strategyProbe = StrategyProbeRequest(suiteId = suiteId, baseProxyConfigJson = baseProxyConfigJson),
        strategyProbeTargetCohorts = strategyProbeTargetCohorts,
        requestedPathMode = ScanPathMode.RAW_PATH,
    )

    private fun auditCohort(
        id: String,
        label: String,
        domainHosts: List<String>,
        quicHosts: List<String>,
    ) = StrategyProbeTargetCohortSpec(
        id = id,
        label = label,
        domainTargets = domainHosts.map { host -> DomainTarget(host = host) },
        quicTargets = quicHosts.map { host -> QuicTarget(host = host) },
    )

    private suspend fun prepareStrategyProbeRequest(
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredDnsPath: com.poyka.ripdpi.data.EncryptedDnsPathCandidate? = null,
        baseProxyConfigJson: String? = null,
        profileId: String = "strategy-probe",
        family: DiagnosticProfileFamily = DiagnosticProfileFamily.AUTOMATIC_PROBING,
        suiteId: String = "quick_v1",
        domainTargets: List<DomainTarget> = listOf(DomainTarget(host = "example.org")),
        quicTargets: List<QuicTarget> = listOf(QuicTarget(host = "example.org")),
        throughputTargets: List<ThroughputTarget> = emptyList(),
        preferredEdges: Map<String, List<PreferredEdgeCandidate>> = emptyMap(),
        strategyProbeTargetCohorts: List<StrategyProbeTargetCohortSpec> = emptyList(),
        scanOrigin: DiagnosticsScanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
    ): com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire {
        val json = diagnosticsTestJson()
        val intent =
            strategyProbeIntent(
                settings = settings,
                baseProxyConfigJson = baseProxyConfigJson,
                profileId = profileId,
                family = family,
                suiteId = suiteId,
                domainTargets = domainTargets,
                quicTargets = quicTargets,
                throughputTargets = throughputTargets,
                strategyProbeTargetCohorts = strategyProbeTargetCohorts,
            )
        val contextProvider = FakeDiagnosticsContextProvider()
        val contextSnapshot = contextProvider.captureContextForTest()
        val context =
            com.poyka.ripdpi.diagnostics.domain.ScanContext(
                settings = settings,
                pathMode = ScanPathMode.RAW_PATH,
                networkFingerprint = null,
                preferredDnsPath = preferredDnsPath,
                preferredEdges = preferredEdges,
                networkSnapshot = null,
                serviceMode = com.poyka.ripdpi.data.Mode.VPN.name,
                contextSnapshot = contextSnapshot,
                approachSnapshot = createStoredApproachSnapshot(json, settings, null, contextSnapshot),
            )
        val factory =
            DiagnosticsScanRequestFactory(
                context = TestContext(),
                networkMetadataProvider = FakeNetworkMetadataProvider(),
                intentResolver =
                    object : DiagnosticsIntentResolver {
                        override suspend fun resolve(
                            profileId: String,
                            pathMode: ScanPathMode,
                        ): DiagnosticsIntent = intent
                    },
                scanContextCollector =
                    object : ScanContextCollector {
                        override suspend fun collect(intent: DiagnosticsIntent): ScanContext = context
                    },
                diagnosticsPlanner = DefaultDiagnosticsPlanner(),
                engineRequestEncoder = DefaultEngineRequestEncoder(),
                json = json,
            )

        val prepared =
            factory.prepareScan(
                profile =
                    com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity(
                        id = "strategy-probe",
                        name = "Strategy probe",
                        source = "test",
                        version = 1,
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "strategy-probe",
                                displayName = "Strategy probe",
                                kind = ScanKind.STRATEGY_PROBE,
                                family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                                targets =
                                    DiagnosticsProfileTargets(
                                        strategyProbe = StrategyProbeRequest(suiteId = suiteId),
                                    ),
                                allowBackground = true,
                            ),
                        updatedAt = 1L,
                    ),
                settings = settings,
                pathMode = ScanPathMode.RAW_PATH,
                scanOrigin = scanOrigin,
                exposeProgress = false,
                registerActiveBridge = false,
            )

        return json.decodeFromString(
            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
                .serializer(),
            prepared.requestJson,
        )
    }
}
