package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRootCauseAssessmentTest {
    @Test
    fun `underlay evidence explains data plane path loss`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only final=true host=blocked.example",
                            createdAt = 10L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "network_transition",
                            source = "android_network_callback",
                            message = "kind=capabilities_changed;path=non_vpn;internet=present;validated=absent",
                            createdAt = 11L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.UNDERLAY_LOST, assessment.verdict)
        assertTrue(assessment.evidenceRefs.any { it.category == "network_transition_underlay_lost" })
    }

    @Test
    fun `conflicting typed roots are inconclusive`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "dns",
                            level = "error",
                            message = "event=dns_failure host=private.example",
                            createdAt = 1L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "relay",
                            level = "warn",
                            message = "state=stalled upstream=10.0.0.10:443",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(RuntimeRootCauseConfidence.LOW, assessment.confidence)
        assertEquals(listOf("dns_failure", "relay_stall"), assessment.contradictoryCategories)
    }

    @Test
    fun `missing and foreign session evidence stays inconclusive`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-b",
                            subsystem = "network_transition",
                            message = "kind=lost;generation=1;sequence=2",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(0, assessment.evidenceEventCount)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `raw event values are not copied into assessment json`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message =
                                "state=outbound_only final=true host=secret.example " +
                                    "ip=203.0.113.77 profile_id=profile-secret",
                            createdAt = 1L,
                        ),
                    ),
            )

        val json = RuntimeHistoryJson.encodeToString(assessment)
        assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, assessment.verdict)
        assertFalse(json.contains("secret.example"))
        assertFalse(json.contains("203.0.113.77"))
        assertFalse(json.contains("profile-secret"))
        assertTrue(json.contains("data_plane_outbound_no_return"))
    }

    @Test
    fun `assessment evidence is bounded to recent scoped events`() {
        val events =
            (1L..80L).map { index ->
                event(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    message = "state=outbound_only final=true sequence=$index",
                    createdAt = index,
                )
            }

        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events = events,
            )

        assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, assessment.verdict)
        assertEquals(64, assessment.evidenceEventCount)
        assertEquals(1, assessment.evidenceRefs.size)
        assertEquals(64, assessment.evidenceRefs.single().count)
        assertEquals(63L, assessment.evidenceRefs.single().firstSeenOffsetMillis)
        assertEquals(0L, assessment.evidenceRefs.single().lastSeenOffsetMillis)
    }

    @Test
    fun `free form relay text does not classify without canonical tokens`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "relay",
                            level = "warn",
                            message = "relay stall timeout for secret.example",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `mtu blackhole requires explicit pmtu evidence`() {
        val weakAssessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mtu=1280",
                            createdAt = 1L,
                        ),
                    ),
            )
        val explicitAssessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "pmtu",
                            message = "pmtu_blackhole=true mtu_band=low",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, weakAssessment.verdict)
        assertEquals(RuntimeRootCauseVerdict.MTU_BLACKHOLE, explicitAssessment.verdict)
    }

    private fun event(
        connectionSessionId: String,
        subsystem: String,
        message: String,
        createdAt: Long,
        source: String = "service",
        level: String = "info",
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = "$connectionSessionId:$subsystem:$createdAt",
            sessionId = null,
            connectionSessionId = connectionSessionId,
            source = source,
            level = level,
            message = message,
            createdAt = createdAt,
            subsystem = subsystem,
        )
}
