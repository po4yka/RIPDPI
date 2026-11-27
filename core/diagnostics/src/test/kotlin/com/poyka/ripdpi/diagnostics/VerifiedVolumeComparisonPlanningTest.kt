package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedVolumeComparisonPlanningTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val policy = ActiveProbeSafetyPolicy()
    private val intent =
        strategyProbeIntent(
            settings = settings,
            baseProxyConfigJson = null,
            profileId = TransferVolumeComparisonProfileId,
            domainTargets = emptyList(),
            quicTargets = emptyList(),
        ).copy(kind = ScanKind.CONNECTIVITY, strategyProbe = null, routeProbe = RouteProbeConfig())

    private val input =
        TransferVolumeComparisonInput(
            connectIp = "192.0.2.10",
            short = VerifiedDownloadInput("https://fixture.example/short", "32768", "AB".repeat(32)),
            long = VerifiedDownloadInput("https://fixture.example:443/long", "1048576", "cd".repeat(32)),
        )

    @Test
    fun `wire holds two ordered requests pinned to one origin with independent proofs`() {
        val prepared =
            policy.enforceTargetBudget(
                intent.withTransferVolumeComparison(input),
                DiagnosticsScanOrigin.USER_INITIATED,
                isManual = true,
            )
        val context = strategyProbeContext(settings, preferredDnsPath = null)
        val wire = DefaultEngineRequestEncoder().encode(DefaultDiagnosticsPlanner().plan(prepared, context))
        val (short, long) = wire.throughputTargets

        assertEquals(ScanPathMode.RAW_PATH, wire.pathMode)
        assertEquals(listOf("transfer-volume-short", "transfer-volume-long"), wire.throughputTargets.map { it.id })
        assertEquals(listOf(input.short.url, input.long.url), wire.throughputTargets.map { it.url })
        assertEquals(DownloadVerification(32_768, "ab".repeat(32)), short.verification)
        assertEquals(DownloadVerification(1_048_576, "cd".repeat(32)), long.verification)
        assertEquals(listOf(32_769, 1_048_577), wire.throughputTargets.map { it.windowBytes })
        wire.throughputTargets.forEach {
            assertEquals(input.connectIp, it.connectIp)
            assertTrue(it.connectIps.isEmpty())
            assertNull(it.port)
            assertEquals(1, it.runs)
        }
        assertEquals(
            listOf(EngineProbeTaskFamily.THROUGHPUT, EngineProbeTaskFamily.THROUGHPUT),
            wire.probeTasks.map {
                it.family
            },
        )
        assertEquals(listOf(short.id, long.id), wire.probeTasks.map { it.targetId })
        assertNull(wire.routeProbe)
    }

    @Test
    fun `only distinct paths and increasing sizes on one valid origin are admitted`() {
        assertTrue(input.isValid())
        listOf(
            input.copy(long = input.long.copy(url = "https://other.example/long")),
            input.copy(long = input.long.copy(url = "https://fixture.example:8443/long")),
            input.copy(long = input.long.copy(url = "https://fixture.example:443/short")),
            input.copy(long = input.long.copy(url = "https://fixture.example/long?token=opaque")),
            input.copy(long = input.long.copy(url = "https://fixture.example/long#fragment")),
            input.copy(long = input.long.copy(url = "https://user@fixture.example/long")),
            input.copy(long = input.long.copy(url = "https://fixture.example//long")),
            input.copy(long = input.long.copy(url = "https://fixture.example/объект")),
            input.copy(long = input.long.copy(expectedBodyBytes = "32768")),
            input.copy(long = input.long.copy(expectedBodyBytes = "1048577")),
            input.copy(long = input.long.copy(sha256 = "wrong")),
            input.copy(short = input.short.copy(expectedBodyBytes = "32769")),
            input.copy(connectIp = "fixture.example"),
            input.copy(connectIp = "0.0.0.0"),
            input.copy(connectIp = "::ffff:255.255.255.255"),
            input.copy(connectIp = "ff02::1"),
        ).forEach { invalid -> assertFalse(invalid.toString(), invalid.isValid()) }
    }

    @Test
    fun `declared class and AS are a valid pair and travel on both targets`() {
        val declared = input.copy(targetClass = TargetClassCdn, destinationAsn = "13335")
        assertTrue(declared.isValid())
        assertTrue(declared.toTargets().all { it.targetClass == TargetClassCdn && it.destinationAsn == 13_335L })
        assertTrue(declared.toTargets().isValidTransferVolumeComparison())
        assertTrue(input.toTargets().all { it.targetClass == null && it.destinationAsn == null })
        val (short, long) = declared.toTargets()
        assertFalse(listOf(short, long.copy(destinationAsn = 15_169L)).isValidTransferVolumeComparison())
        listOf(
            input.copy(targetClass = TargetClassCdn),
            input.copy(destinationAsn = "13335"),
            declared.copy(targetClass = "other"),
            declared.copy(destinationAsn = "0"),
            declared.copy(destinationAsn = "23456"),
            declared.copy(destinationAsn = "64496"),
            declared.copy(destinationAsn = "4200000000"),
            declared.copy(destinationAsn = "AS13335"),
        ).forEach { invalid -> assertFalse(invalid.toString(), invalid.isValid()) }
    }

    @Test
    fun `only a manual RAW scan of the fixed pair is admitted`() {
        val prepared = intent.withTransferVolumeComparison(input)
        val (short, long) = prepared.throughputTargets
        assertTrue(runCatching { intent.withTransferVolumeComparison(null) }.isFailure)
        assertTrue(runCatching { intent.copy(profileId = "default").withTransferVolumeComparison(input) }.isFailure)
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(prepared, DiagnosticsScanOrigin.USER_INITIATED, isManual = false)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(prepared, DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND, isManual = true)
            }.isFailure,
        )
        listOf(
            prepared.copy(requestedPathMode = ScanPathMode.IN_PATH),
            prepared.copy(domainTargets = listOf(DomainTarget(host = "other.example"))),
            prepared.copy(routeProbe = RouteProbeConfig()),
            prepared.copy(throughputTargets = listOf(long, short)),
            prepared.copy(throughputTargets = listOf(short)),
            prepared.copy(throughputTargets = listOf(short, long.copy(connectIp = "192.0.2.42"))),
            prepared.copy(throughputTargets = listOf(short, long.copy(connectIps = listOf("192.0.2.42")))),
            prepared.copy(throughputTargets = listOf(short, long.copy(port = 443))),
            prepared.copy(throughputTargets = listOf(short, long.copy(runs = 2))),
            prepared.copy(throughputTargets = listOf(short, long.copy(windowBytes = 1_048_576))),
        ).forEach { invalid ->
            assertTrue(
                runCatching {
                    policy.enforceTargetBudget(invalid, DiagnosticsScanOrigin.USER_INITIATED, isManual = true)
                }.isFailure,
            )
        }
    }
}
