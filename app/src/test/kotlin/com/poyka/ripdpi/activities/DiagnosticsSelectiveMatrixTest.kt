package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSelectiveMatrixTest {
    @Test
    fun optionalHostsNormalizeAndDeduplicate() {
        assertEquals(emptyList<String>(), parseSelectiveMatrixHosts(" \n "))
        assertEquals(
            listOf("example.org", "example.net"),
            parseSelectiveMatrixHosts("EXAMPLE.org\nexample.net,example.org"),
        )
    }

    @Test
    fun hostInputRejectsExcessAndLocalTargets() {
        listOf(
            "a.org b.org c.org d.org e.org",
            "localhost",
            "127.0.0.1",
            "https://user:password@example.org/",
            "https://example.org/?secret=value",
            "https://example.org/#fragment",
            "x".repeat(SelectiveMatrixInputLimit + 1),
        ).forEach { assertNull(it, parseSelectiveMatrixHosts(it)) }
    }

    @Test
    fun missingStagesRemainUnmeasuredAndBodyIsNotAssumedComplete() {
        val attempt = probe("dnsStatus" to "failed").toSelectiveMatrixAttempt()!!
        assertEquals("failed", attempt.stages.first().value)
        assertTrue(attempt.stages.drop(1).all { it.value == "not_run" })
        assertFalse(attempt.bodyComplete)
        assertEquals("0", attempt.bodyBytes)
    }

    @Test
    fun bodyCountAndProxyDnsRemainSeparateFromVerifiedSuccess() {
        val attempt =
            probe(
                "dnsStatus" to "proxy_resolved_unknown",
                "bodyByteCount" to "1024",
                "bodyComplete" to "true",
                "attempt" to "2",
                "cohort" to "user",
            ).toSelectiveMatrixAttempt()!!
        assertEquals("proxy_resolved_unknown", attempt.stages.first().value)
        assertEquals("1024", attempt.bodyBytes)
        assertEquals("2", attempt.attempt)
        assertTrue(attempt.bodyComplete)
        assertEquals("user", attempt.cohort)
    }

    @Test
    fun unknownVerificationAndNoFailureDoNotAppearAsEvidence() {
        listOf("", "unknown", "UNKNOWN", "null").forEach { value ->
            val attempt = probe("lastVerifiedAt" to value, "failureStage" to "none").toSelectiveMatrixAttempt()!!
            assertNull(attempt.verifiedAt)
            assertEquals("", attempt.failureStage)
        }
        assertEquals("tls", probe("failureStage" to "tls").toSelectiveMatrixAttempt()!!.failureStage)
    }

    @Test
    fun otherProbeFamiliesDoNotBecomeMatrixRows() {
        assertNull(probe().copy(probeType = "https").toSelectiveMatrixAttempt())
    }

    @Test
    fun historyUsesFinalizedReportInsteadOfStaleAvailableEntity() {
        val stale = ProbeResult("selective_availability_summary", "matrix", "matrix_selective")
        val final = stale.copy(outcome = "matrix_inconclusive")
        val report = DiagnosticsSessionProjection(results = listOf(final))
        assertEquals("matrix_inconclusive", finalizedMatrixResults(listOf(stale), report).single().outcome)
    }

    @Test
    fun historyCannotAuthorizeIncompleteOrUnverifiedScope() {
        val stale = ProbeResult("selective_availability_summary", "matrix", "matrix_available")
        val partial =
            DiagnosticsSessionProjection(
                results = listOf(stale),
                completionKind = ScanCompletionKind.PARTIAL_RESULTS,
            )
        val changed =
            DiagnosticsSessionProjection(
                results = listOf(stale),
                diagnoses = listOf(Diagnosis("network_scope_unverified", "Network changed")),
            )
        listOf(null, partial, changed).forEach { report ->
            assertEquals("matrix_inconclusive", finalizedMatrixResults(listOf(stale), report).single().outcome)
        }
        assertEquals(
            "network_scope_unverified",
            finalizedMatrixResults(listOf(stale), changed)
                .single()
                .details
                .single { it.key == "reason" }
                .value,
        )
    }

    private fun probe(vararg values: Pair<String, String>) =
        DiagnosticsProbeResultUiModel(
            id = "attempt-1",
            probeType = "selective_availability",
            target = "example.org",
            outcome = "matrix_target_transport_failed",
            tone = DiagnosticsTone.Warning,
            details = values.map { DiagnosticsFieldUiModel(it.first, it.second) }.toImmutableList(),
        )
}
