package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRootCauseAssessmentTest {
    @Test
    fun `dns and relay text stay fail closed without producer event kinds`() {
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
        assertTrue(assessment.evidenceRefs.isEmpty())
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
                                "state=outbound_only mode=vpn generation=1 final=true host=secret.example " +
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
                    message = "state=outbound_only mode=vpn generation=1 sequence=$index",
                    createdAt = index,
                )
            } +
                event(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    message = "state=outbound_only mode=vpn generation=1 final=true",
                    createdAt = 81L,
                )

        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events = events,
            )

        assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, assessment.verdict)
        assertEquals(64, assessment.evidenceEventCount)
        assertEquals(1, assessment.evidenceRefs.size)
        assertEquals(1, assessment.evidenceRefs.single().count)
        assertEquals(0L, assessment.evidenceRefs.single().firstSeenOffsetMillis)
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
                            message = "state=outbound_only mode=vpn generation=1 host=stale.example",
                            createdAt = 1L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=tun_ingress_no_upstream mode=vpn generation=2 final=true",
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
    fun `newer benign final blocks stale failing data plane final`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mode=vpn generation=1 final=true",
                            createdAt = 1L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=evidence_unavailable mode=vpn generation=2 final=true",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `data plane final requires persisted event kind provenance`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mode=vpn generation=1 final=true",
                            createdAt = 1L,
                            preserveMessage = true,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
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
                                    "small_control=success larger_failures=2 " +
                                    "post_control_recovery=success ptb_observation=not_observed_in_run",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, weakAssessment.verdict)
        assertEquals(RuntimeRootCauseVerdict.MTU_BLACKHOLE, explicitAssessment.verdict)
    }

    @Test
    fun `mtu blackhole rejects missing ptb observation`() {
        val assessment =
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
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `internal data plane conflict blocks direct root verdict`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=tun_ingress_no_upstream mode=vpn generation=1 final=true",
                            createdAt = 1L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "vpn_protect",
                            level = "warn",
                            message = "event=protect_failed",
                            createdAt = 2L,
                        ),
                        event(
                            connectionSessionId = "conn-a",
                            subsystem = "dns",
                            level = "error",
                            message = "event=dns_failure",
                            createdAt = 3L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(
            listOf("data_plane_tun_ingress_no_upstream", "protect_failure"),
            assessment.contradictoryCategories,
        )
    }

    @Test
    fun `dns source and subsystem event stays fail closed without event kind`() {
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

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
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
        preserveMessage: Boolean = false,
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = "${connectionSessionId.orEmpty()}:$subsystem:$createdAt",
            sessionId = null,
            connectionSessionId = connectionSessionId,
            source = source,
            level = level,
            message =
                if (!preserveMessage && subsystem == "data_plane" && "final=true" in message) {
                    "$message event_kind=data_plane_final"
                } else {
                    message
                },
            createdAt = createdAt,
            subsystem = subsystem,
        )
}
