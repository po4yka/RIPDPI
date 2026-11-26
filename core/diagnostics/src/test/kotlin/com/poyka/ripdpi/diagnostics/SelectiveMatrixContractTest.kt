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
    fun `class and AS come together and the AS must be public`() {
        val target = matrix().targets.single()

        fun valid(candidate: SelectiveMatrixTarget) =
            runCatching {
                matrix().copy(targets = listOf(candidate)).validate()
            }.isSuccess
        listOf("", "global", "CDN").forEach { assertFalse(it, valid(target.copy(targetClass = it))) }
        assertFalse(valid(target.copy(destinationAsn = null)))
        assertFalse(valid(target.copy(targetClass = null)))
        listOf(0L, 23_456L, 64_496L, 64_512L, 65_535L, 65_552L, 131_071L, 4_200_000_000L, 4_294_967_295L, -1L).forEach {
            assertFalse(it.toString(), valid(target.copy(destinationAsn = it)))
        }
        listOf(1L, 131_072L, 396_982L, 4_199_999_999L).forEach {
            assertTrue(it.toString(), valid(target.copy(destinationAsn = it)))
        }
        assertTrue(valid(target.copy(targetClass = null, destinationAsn = null)))
        matrix().withUserHosts(listOf("example.com")).targets.last().let {
            assertEquals(null, it.targetClass)
            assertEquals(null, it.destinationAsn)
        }
    }

    @Test
    fun `four byte AS numbers decode and the redacted summary keeps class and AS but not addresses`() {
        val decoded =
            RipDpiJson.decodeFromString(
                SelectiveMatrixTarget.serializer(),
                """{"id":"a","label":"A","url":"https://a.example/","cohort":"global","infrastructureGroup":"a",
                    |"sourceUrl":"","sourceDate":"","targetClass":"cdn","destinationAsn":4199999999}
                """.trimMargin(),
            )
        assertEquals(4_199_999_999L, decoded.destinationAsn)
        val lines =
            listOf(
                ProbeResult(
                    "selective_availability",
                    "A",
                    "matrix_target_available",
                    listOf(
                        ProbeDetail("targetClass", "cdn"),
                        ProbeDetail("destinationAsn", "13335"),
                        ProbeDetail("remoteAddress", "203.0.113.9"),
                    ),
                ),
            ).selectiveMatrixSummaryLines()
        assertTrue(lines.contains("targetClass=cdn"))
        assertTrue(lines.contains("destinationAsn=13335"))
        assertFalse(lines.any { it.contains("203.0.113.9") })
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
                        ProbeDetail("targetStability", "unstable"),
                        ProbeDetail("sourceDate", "2026-10-09"),
                        ProbeDetail("body", "secret response"),
                    ),
                ),
            ).selectiveMatrixSummaryLines()
        assertTrue(lines.contains("bodyByteCount=32"))
        assertTrue(lines.contains("sourceDate=2026-10-09"))
        assertTrue(lines.contains("targetStability=unstable"))
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
                        targetClass = "cdn",
                        destinationAsn = 13335,
                        sourceUrl = "https://cloudflare.com",
                        sourceDate = "2026-10-09",
                    ),
                ),
        )
}
