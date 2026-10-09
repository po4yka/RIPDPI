package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.finalization.revokePersistedNetworkScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal fun pmtuEvidenceForTest() =
    PmtuProbeEvidence(
        addressFamily = PmtuAddressFamily.IPV4,
        status = PmtuProbeStatus.OBSERVED,
        peerAddress = "192.0.2.1",
        tlsValidated = true,
        alpn = "h3",
        fragmentationPrevented = true,
        currentUdpPayloadBytes = 1_400,
        acknowledgedUdpPayloadLowerBoundBytes = 1_400,
        sentProbeCount = 5,
        lostProbeCount = 2,
        observationWindowComplete = true,
        durationMs = 3_100,
        handshakeElapsedMs = 100,
    )

class PmtuProbeEvidenceTest {
    private val evidence = pmtuEvidenceForTest()

    @Test
    fun `initial size and ceiling cannot become a measured lower bound`() {
        assertEquals(evidence, parsePmtuProbeEvidence(encode(evidence)))
        listOf(
            evidence.copy(acknowledgedUdpPayloadLowerBoundBytes = 1_200),
            evidence.copy(currentUdpPayloadBytes = 1_200),
            evidence.copy(configuredUpperBoundReached = true),
            evidence.copy(tlsValidated = false),
            evidence.copy(fragmentationPrevented = false),
            evidence.copy(sentProbeCount = 0),
            evidence.copy(lostProbeCount = 5),
            evidence.copy(lostProbeCount = 6),
            evidence.copy(observationWindowComplete = false),
            evidence.copy(alpn = "h2"),
            evidence.copy(handshakeElapsedMs = 4_000),
            evidence.copy(pathScope = PmtuPathScope.UNSUPPORTED_PROXY),
            evidence.copy(status = PmtuProbeStatus.UNSUPPORTED),
            evidence.copy(addressFamily = PmtuAddressFamily.IPV6),
            evidence.copy(reason = "private-canary"),
        ).forEach { assertNull(it.validatedPmtuProbeEvidence()) }
        assertNull(parsePmtuProbeEvidence(listOf(detail(evidence), detail(evidence))))
        assertNull(parsePmtuProbeEvidence("{}"))
        assertNull(parsePmtuProbeEvidence(" ".repeat(4_097)))
    }

    @Test
    fun `partial or unverified measurements retain facts without healthy authority`() {
        for (status in listOf(PmtuProbeStatus.TIMEOUT, PmtuProbeStatus.CANCELLED, PmtuProbeStatus.NOT_OBSERVED)) {
            val partial = evidence.copy(status = status, observationWindowComplete = false)
            assertEquals(1_400, parsePmtuProbeEvidence(encode(partial))?.acknowledgedUdpPayloadLowerBoundBytes)
        }
        assertNotNull(
            PmtuProbeEvidence(
                addressFamily = PmtuAddressFamily.IPV6,
                status = PmtuProbeStatus.INCONCLUSIVE,
            ).validatedPmtuProbeEvidence(),
        )
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketPmtu("pmtu_timeout"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketPmtu("pmtu_unknown"))
    }

    @Test
    fun `redacted nested evidence keeps facts and removes peer and unknown data`() {
        val raw = encode(evidence).dropLast(1) + ",\"rawBody\":\"private-body-canary\"}"
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                "pmtu",
                "www.cloudflare.com",
                "pmtu_observed",
                Json.encodeToString(
                    ListSerializer(ProbeDetail.serializer()),
                    listOf(ProbeDetail("pmtuEvidence", raw)),
                ),
                1,
            )
        val redacted = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(redacted.detailJson.contains("private-body-canary"))
        assertFalse(redacted.detailJson.contains("192.0.2.1"))
        val details = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), redacted.detailJson)
        val parsed = requireNotNull(parsePmtuProbeEvidence(details))
        assertNull(parsed.peerAddress)
        assertEquals(true, parsed.tlsValidated)
        assertEquals(1_400, parsed.acknowledgedUdpPayloadLowerBoundBytes)
    }

    @Test
    fun `persisted scope revocation preserves measurements and is idempotent`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val row =
                ProbeResultEntity(
                    "pmtu-result",
                    "test",
                    "pmtu",
                    "control",
                    "pmtu_observed",
                    Json.encodeToString(ListSerializer(ProbeDetail.serializer()), listOf(detail(evidence))),
                    1,
                )
            stores.persistCompletedScan(
                diagnosticsSession(id = "test", profileId = PmtuProfileId, pathMode = "RAW_PATH", summary = "Measured"),
                listOf(row),
            )
            revokePersistedNetworkScope("test", stores, Json)
            val scoped = stores.getProbeResults("test").single()
            assertEquals("pmtu-result", scoped.id)
            assertEquals("pmtu_not_observed", scoped.outcome)
            val parsed =
                requireNotNull(
                    parsePmtuProbeEvidence(
                        Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), scoped.detailJson),
                    ),
                )
            assertEquals(PmtuProbeStatus.NOT_OBSERVED, parsed.status)
            assertEquals(1_400, parsed.acknowledgedUdpPayloadLowerBoundBytes)
            revokePersistedNetworkScope("test", stores, Json)
            assertEquals(listOf(scoped), stores.getProbeResults("test"))
        }

    private fun encode(value: PmtuProbeEvidence) = Json.encodeToString(PmtuProbeEvidence.serializer(), value)

    private fun detail(value: PmtuProbeEvidence) = ProbeDetail("pmtuEvidence", encode(value))
}
