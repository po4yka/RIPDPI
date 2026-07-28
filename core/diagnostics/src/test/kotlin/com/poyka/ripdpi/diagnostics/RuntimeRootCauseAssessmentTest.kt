package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRootCauseAssessmentTest {
    @Test
    fun `cross family underlay and data plane roots are inconclusive`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only final=true generation=1 host=blocked.example",
                            createdAt = 10L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "network_transition",
                            source = "android_network_callback",
                            message =
                                "kind=capabilities_changed;path=non_vpn;generation=1;" +
                                    "internet=present;validated=absent",
                            createdAt = 11L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(
            listOf("data_plane_outbound_no_return", "network_transition_underlay_lost"),
            assessment.contradictoryCategories,
        )
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
    fun `underlay lost requires bounded non vpn path generation`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "network_transition",
                            source = "android_network_callback",
                            message = "kind=lost;sequence=2",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `device state constraints alone do not emit oem process kill`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "device_state",
                            source = "android_device_state",
                            level = "warn",
                            message = "trigger=service_destroyed background_restricted=enabled power_saver=enabled",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
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
                                "state=outbound_only final=true generation=1 host=secret.example " +
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
                    message = "state=outbound_only final=true generation=1 sequence=$index",
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
    fun `data plane without terminal generation stays inconclusive`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only final=true host=blocked.example",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `stale data plane generation is excluded when terminal generation is known`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only generation=1 host=stale.example",
                            createdAt = 1L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=tun_ingress_no_upstream final=true generation=2",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.VPN_ROUTE_LOOP, assessment.verdict)
        assertEquals(
            listOf("data_plane_tun_ingress_no_upstream"),
            assessment.evidenceRefs.map { it.category },
        )
    }

    @Test
    fun `mtu blackhole requires typed pmtu proof`() {
        val weakAssessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "pmtu",
                            message = "pmtu_blackhole=true mtu_band=low",
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
                            message =
                                "verdict=mtu_blackhole provenance=pmtu_probe_v1 " +
                                    "small_control=success larger_failures=2 post_control_recovery=success",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, weakAssessment.verdict)
        assertEquals(RuntimeRootCauseVerdict.MTU_BLACKHOLE, explicitAssessment.verdict)
    }

    @Test
    fun `dns source and subsystem event is counted once`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "dns",
                            source = "dns",
                            level = "error",
                            message = "event=dns_failure host=private.example",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
        assertEquals(1, assessment.evidenceRefs.single().count)
    }

    @Test
    fun `process exit events stay inconclusive without safe session correlation`() {
        val sessionlessAssessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = null,
                            subsystem = "process",
                            source = "android_last_exit_inspector",
                            level = "warn",
                            message = "reason=low_memory subtype=android_memory_limiter",
                            createdAt = 1L,
                        ),
                    ),
            )
        val correlatedAssessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "process",
                            source = "android_last_exit_inspector",
                            level = "warn",
                            message = "reason=low_memory subtype=android_memory_limiter",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, sessionlessAssessment.verdict)
        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, correlatedAssessment.verdict)
        assertTrue(sessionlessAssessment.evidenceRefs.isEmpty())
        assertTrue(correlatedAssessment.evidenceRefs.isEmpty())
    }

    private fun event(
        connectionSessionId: String?,
        subsystem: String,
        message: String,
        createdAt: Long,
        source: String = "service",
        level: String = "info",
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = "${connectionSessionId.orEmpty()}:$subsystem:$createdAt",
            sessionId = null,
            connectionSessionId = connectionSessionId,
            source = source,
            level = level,
            message = message,
            createdAt = createdAt,
            subsystem = subsystem,
        )
}
