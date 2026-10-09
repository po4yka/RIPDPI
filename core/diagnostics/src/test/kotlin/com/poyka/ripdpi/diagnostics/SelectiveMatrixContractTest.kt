package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.finalization.withoutNetworkScopeAuthority
import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectiveMatrixContractTest {
    @Test
    fun `host validation rejects URLs credentials local names literals and malformed labels`() {
        listOf(
            "https://example.com",
            "user@example.com",
            "example.com/a",
            "example.com?token=a",
            "127.0.0.1",
            "[::1]",
            "localhost",
            "router.local",
            "router.home.arpa",
            "a.invalid",
            "a..com",
            "-a.com",
            "a-.com",
            "example.com.",
            "example.com:443",
        ).forEach { host ->
            assertTrue(host, runCatching { normalizeSelectiveMatrixHost(host) }.isFailure)
        }
        assertEquals("example.com", normalizeSelectiveMatrixHost(" EXAMPLE.COM "))
        assertEquals("xn--e1afmkfd.xn--p1ai", normalizeSelectiveMatrixHost("пример.рф"))
    }

    @Test
    fun `user targets are bounded transient and cannot claim independent controls`() {
        val updated = matrix().withUserHosts(listOf("example.com", "example.net"))
        assertEquals(3, updated.targets.size)
        assertEquals("https://example.net/", updated.targets.last().url)
        assertEquals(
            setOf("user-unverified"),
            updated.targets
                .drop(1)
                .map { it.infrastructureGroup }
                .toSet(),
        )
        assertTrue(runCatching { matrix().withUserHosts(List(5) { "example$it.com" }) }.isFailure)
        assertTrue(runCatching { matrix().withUserHosts(listOf("cloudflare.com")) }.isFailure)
        assertTrue(runCatching { matrix().copy(repetitions = 4).validate() }.isFailure)
        assertTrue(runCatching { matrix().copy(maxResponseBytes = 65_537).validate() }.isFailure)
        assertTrue(runCatching { matrix().copy(timeoutMs = Long.MAX_VALUE).validate() }.isFailure)
        assertEquals(1, matrix().targets.size)
    }

    @Test
    fun `optional matrix roundtrips without changing legacy requests`() {
        val request =
            EngineScanRequestWire(profileId = "legacy", displayName = "Legacy", pathMode = ScanPathMode.RAW_PATH)
        val encoded =
            RipDpiJson.encodeToString(
                EngineScanRequestWire.serializer(),
                request.copy(selectiveMatrix = matrix()),
            )
        assertEquals(matrix(), RipDpiJson.decodeFromString(EngineScanRequestWire.serializer(), encoded).selectiveMatrix)
        assertFalse(RipDpiJson.encodeToString(EngineScanRequestWire.serializer(), request).contains("selectiveMatrix"))
    }

    @Test
    fun `network scope revocation preserves stage measurements but revokes aggregate`() {
        val result = EngineProbeResultWire("selective_availability", "target", "matrix_target_available")
        val summary = EngineProbeResultWire("selective_availability_summary", "matrix", "matrix_selective")
        val report =
            EngineScanReportWire(
                sessionId = "test",
                profileId = SelectiveMatrixProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "Selective",
                results = listOf(result, summary),
            ).withoutNetworkScopeAuthority()
        assertEquals(result, report.results.first())
        assertEquals("matrix_inconclusive", report.results.last().outcome)
        assertTrue(
            report.results
                .last()
                .details
                .contains(ProbeDetail("reason", "network_scope_unverified")),
        )
    }

    @Test
    fun `summary keeps completion and provenance evidence without response payload`() {
        val lines =
            listOf(
                ProbeResult(
                    "selective_availability",
                    "user.example",
                    "matrix_target_body_incomplete",
                    listOf(
                        ProbeDetail("bodyByteCount", "32"),
                        ProbeDetail("bodyComplete", "false"),
                        ProbeDetail("sourceDate", "2026-10-09"),
                        ProbeDetail("body", "secret response"),
                    ),
                ),
            ).selectiveMatrixSummaryLines()
        assertTrue(lines.contains("bodyByteCount=32"))
        assertTrue(lines.contains("sourceDate=2026-10-09"))
        assertFalse(lines.any { it.contains("secret response") })
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketSelectiveMatrix("matrix_target_body_incomplete"))
    }

    private fun matrix() =
        SelectiveMatrixConfig(
            catalogVersion = "test-v1",
            targets =
                listOf(
                    SelectiveMatrixTarget(
                        id = "cf",
                        label = "Cloudflare",
                        url = "https://cloudflare.com/robots.txt",
                        cohort = "global",
                        infrastructureGroup = "cloudflare",
                        sourceUrl = "https://cloudflare.com",
                        sourceDate = "2026-10-09",
                    ),
                ),
        )
}
