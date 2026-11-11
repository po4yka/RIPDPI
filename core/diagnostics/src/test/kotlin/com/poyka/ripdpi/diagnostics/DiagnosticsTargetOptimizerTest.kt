package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.application.selectStrategyProbeTargetsForSession
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTargetOptimizerTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val policy = ActiveProbeSafetyPolicy(automaticAuditDomainTargets = 2)
    private val optimizer = DiagnosticsTargetOptimizer()

    @Test
    fun `healthy baselines cannot hide a known failure of another candidate`() {
        val intent = intent()
        repeat(3) { index ->
            val baseline = report(index.toLong())
            val candidateResults =
                listOf("slow.example", "fast.example").map { host ->
                    ProbeResult(
                        "strategy_https",
                        "Candidate X · $host",
                        if (host == "slow.example") "tls_handshake_failed" else "tls_ok",
                        listOf(ProbeDetail("candidateId", "candidate_x")),
                    )
                }
            optimizer.record("scope", baseline.copy(results = baseline.results + candidateResults), settings)
        }
        assertEquals(intent, compact(intent))
    }

    @Test
    fun `audit optimization selects a complete cheaper registered cohort`() {
        val quic = listOf(QuicTarget("qa.example"), QuicTarget("qb.example"))
        val cohorts =
            listOf("slow", "fast").map { name ->
                StrategyProbeTargetCohortSpec(
                    name,
                    name,
                    listOf(DomainTarget("control.example", isControl = true), DomainTarget("$name.example")),
                    quic,
                )
            }
        val intent =
            intent().copy(
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                strategyProbe = StrategyProbeRequest(suiteId = "full_matrix_v1"),
                strategyProbeTargetCohorts = cohorts,
                quicTargets = quic,
            )
        repeat(3) { index ->
            val baseline = report(index.toLong(), suite = "full_matrix_v1")
            optimizer.record(
                "scope",
                baseline.copy(
                    profileId = intent.profileId,
                    results =
                        baseline.results +
                            quic.map {
                                ProbeResult(
                                    "strategy_quic",
                                    "QUIC real multi-initial · ${it.host}",
                                    "quic_response",
                                    listOf(
                                        ProbeDetail("candidateId", "quic_multi_initial_realistic"),
                                        ProbeDetail("latencyMs", "1"),
                                    ),
                                )
                            },
                ),
                settings,
            )
        }
        val selected = selectStrategyProbeTargetsForSession("any", compact(intent), activeProbeSafetyPolicy = policy)
        assertEquals("fast", selected.strategyProbe?.targetSelection?.cohortId)
        assertEquals(cohorts[1].domainTargets, selected.domainTargets)
        assertEquals(quic, selected.quicTargets)
    }

    @Test
    fun `automatic selection keeps zero latency observations cheap and preserves control`() {
        val intent = intent()
        train(intent)
        val compact = optimizer.compact(intent, "scope", DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND, policy, 10)
        val selected = selectStrategyProbeTargetsForSession("session", compact, activeProbeSafetyPolicy = policy)
        assertEquals(listOf("control.example", "fast.example"), selected.domainTargets.map(DomainTarget::host))
        assertEquals(
            selected.domainTargets.map(DomainTarget::host),
            selected.strategyProbe?.targetSelection?.domainHosts,
        )
    }

    @Test
    fun `manual scans and different scope path configuration or expired history keep original targets`() {
        val intent = intent()
        train(intent)
        assertEquals(intent, optimizer.compact(intent, "scope", DiagnosticsScanOrigin.USER_INITIATED, policy, 10))
        assertEquals(intent, compact(intent, "other"))
        val differentPath = intent.copy(requestedPathMode = ScanPathMode.IN_PATH)
        assertEquals(differentPath, compact(differentPath))
        val differentSettings = intent.copy(settings = settings.toBuilder().setDnsIp("192.0.2.1").build())
        assertEquals(differentSettings, compact(differentSettings))
        assertEquals(
            intent,
            optimizer.compact(intent, "scope", DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND, policy, 3_600_000),
        )
    }

    @Test
    fun `missing or failed targets cannot be removed to satisfy a budget`() {
        val intent = intent()
        train(intent, failed = true)
        assertEquals(listOf("control.example", "slow.example"), compact(intent).domainTargets.map(DomainTarget::host))
        val withNew = intent.copy(domainTargets = intent.domainTargets + DomainTarget("new.example"))
        assertEquals(withNew, compact(withNew))
    }

    @Test
    fun `partial reports do not activate optimization`() {
        val intent = intent()
        optimizer.record("scope", report(1, completion = ScanCompletionKind.PARTIAL_RESULTS), settings)
        assertEquals(intent, compact(intent))
    }

    @Test
    fun `one manual report of another selected profile compacts the automatic profile`() {
        val intent = intent()
        val manualSettings = settings.toBuilder().setDiagnosticsActiveProfileId("manual-profile").build()
        optimizer.record("scope", report(1).copy(profileId = "manual-profile"), manualSettings)
        assertEquals(listOf("control.example", "fast.example"), compact(intent).domainTargets.map(DomainTarget::host))
    }

    @Test
    fun `evidence from another strategy suite or a scan without one keeps original targets`() {
        val intent = intent()
        optimizer.record("scope", report(1, suite = "full_matrix_v1"), settings)
        optimizer.record("scope", report(2).copy(strategyProbeReport = null), settings)
        assertEquals(intent, compact(intent))
    }

    @Test
    fun `partial strategy evidence invalidates an older healthy model`() {
        val intent = intent()
        train(intent)
        repeat(3) { index ->
            optimizer.record(
                "scope",
                report(index.toLong()).copy(
                    strategyProbeReport =
                        StrategyProbeReport("quick_v1", completionKind = StrategyProbeCompletionKind.PARTIAL_RESULTS),
                ),
                settings,
            )
        }
        assertEquals(intent, compact(intent))
    }

    @Test
    fun `an oversized new report invalidates an older healthy model`() {
        val intent = intent()
        train(intent)
        val next = report(3)
        optimizer.record("scope", next.copy(results = List(257) { next.results.first() }), settings)
        assertEquals(intent, compact(intent))
    }

    private fun compact(
        intent: DiagnosticsIntent,
        fingerprint: String = "scope",
    ) = optimizer.compact(intent, fingerprint, DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND, policy, 10)

    private fun train(
        intent: DiagnosticsIntent,
        failed: Boolean = false,
    ) {
        repeat(3) { index -> optimizer.record("scope", report(index.toLong(), failed), intent.settings) }
    }

    private fun report(
        time: Long,
        failed: Boolean = false,
        completion: ScanCompletionKind = ScanCompletionKind.NORMAL,
        suite: String = "quick_v1",
    ) = ScanReport(
        sessionId = "$time",
        profileId = "repeat",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = time,
        finishedAt = time,
        summary = "",
        completionKind = completion,
        strategyProbeReport = StrategyProbeReport(suite),
        results =
            listOf("control.example", "slow.example", "fast.example").map { host ->
                ProbeResult(
                    probeType = "strategy_https",
                    target = "Plain direct · $host",
                    outcome = if (failed && host == "slow.example") "tls_handshake_failed" else "tls_ok",
                    details =
                        listOf(
                            ProbeDetail("candidateId", "baseline_plain_direct"),
                            ProbeDetail("latencyMs", if (host == "slow.example") "100" else "0"),
                        ),
                )
            },
    )

    private fun intent() =
        DiagnosticsIntent(
            profileId = "repeat",
            displayName = "Repeat",
            settings = settings,
            kind = ScanKind.STRATEGY_PROBE,
            family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
            regionTag = null,
            executionPolicy = ExecutionPolicy(false, true, true, ProbePersistencePolicy.MANUAL_ONLY),
            packRefs = emptyList(),
            domainTargets =
                listOf(
                    DomainTarget("control.example", isControl = true),
                    DomainTarget("slow.example"),
                    DomainTarget("fast.example"),
                ),
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = StrategyProbeRequest(targetSelection = StrategyProbeTargetSelection("all", "All")),
            requestedPathMode = ScanPathMode.RAW_PATH,
        )
}
