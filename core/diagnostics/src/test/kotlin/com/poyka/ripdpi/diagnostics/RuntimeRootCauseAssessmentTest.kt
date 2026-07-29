package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRootCauseAssessmentTest {
    @Test
    fun `dns and relay text stay fail closed without producer event kinds`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "dns",
                            level = "error",
                            message = "event=dns_failure host=private.example",
                            createdAt = 1L,
                        ),
                        rootCauseEvent(
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
    fun `typed dns runtime state classifies dns failure with medium confidence`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 1L,
                        ),
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
        assertEquals(RuntimeRootCauseConfidence.MEDIUM, assessment.confidence)
        assertEquals(listOf("dns_failure"), assessment.evidenceRefs.map { it.category })
        assertTrue(assessment.terminalEvidenceSealed)
    }

    @Test
    fun `unsealed typed dns runtime state stays inconclusive`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 1L,
                        ),
                    ),
                terminalEvidenceSealed = false,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertFalse(assessment.terminalEvidenceSealed)
    }

    @Test
    fun `recovered typed dns runtime state stays inconclusive`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "recovered",
                            createdAt = 1L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `spoofed dns runtime state requires deterministic producer shape`() {
        val variants =
            listOf(
                typedDnsRuntimeEvent(
                    connectionSessionId = "conn-a",
                    state = "failure_threshold",
                    createdAt = 1L,
                    id = "typed_runtime_state:dns:conn-b",
                ),
                typedDnsRuntimeEvent(
                    connectionSessionId = "conn-a",
                    state = "failure_threshold",
                    createdAt = 2L,
                    source = "service",
                ),
                typedDnsRuntimeEvent(
                    connectionSessionId = "conn-a",
                    state = "failure_threshold",
                    createdAt = 3L,
                    subsystem = "service",
                ),
                typedDnsRuntimeEvent(
                    connectionSessionId = "conn-a",
                    state = "failure_threshold",
                    createdAt = 4L,
                    message = "event=dns_failure evidence=dns_counter_transition_v1 state=failure_threshold",
                ),
            )
        variants.forEach { spoofed ->
            val assessment =
                RuntimeRootCauseClassifier.assess(
                    connectionSessionId = "conn-a",
                    events = listOf(spoofed),
                )

            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.evidenceRefs.isEmpty())
        }
    }

    @Test
    fun `relay runtime failure blocks roots but relay stall remains unreachable`() {
        val relayOnly =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedRelayRuntimeEvent(
                            connectionSessionId = "conn-a",
                            relayFailed = true,
                            createdAt = 1L,
                        ),
                    ),
            )
        val withDns =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 1L,
                        ),
                        typedRelayRuntimeEvent(
                            connectionSessionId = "conn-a",
                            relayFailed = true,
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, relayOnly.verdict)
        assertEquals(listOf("relay_runtime_failure"), relayOnly.contradictoryCategories)
        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, withDns.verdict)
        assertEquals(listOf("dns_failure", "relay_runtime_failure"), withDns.contradictoryCategories)
    }
}

class RuntimeRootCauseEvidenceBoundaryTest {
    @Test
    fun `missing and foreign session evidence stays inconclusive`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
    fun `unsealed terminal rejects competing data plane and pmtu verdicts`() {
        val dataPlaneAssessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mode=vpn generation=1 final=true",
                            createdAt = 1L,
                        ),
                    ),
                terminalEvidenceSealed = false,
            )
        val pmtuAssessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "pmtu",
                            message =
                                "verdict=mtu_blackhole provenance=pmtu_probe_v1 " +
                                    "small_control=success larger_failures=2 " +
                                    "post_control_recovery=success ptb_observation=not_observed_in_run",
                            createdAt = 2L,
                        ),
                    ),
                terminalEvidenceSealed = false,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, dataPlaneAssessment.verdict)
        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, pmtuAssessment.verdict)
        assertFalse(dataPlaneAssessment.terminalEvidenceSealed)
        assertFalse(pmtuAssessment.terminalEvidenceSealed)
    }

    @Test
    fun `assessment evidence is bounded to recent scoped events`() {
        val events =
            (1L..80L).map { index ->
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    message = "state=outbound_only mode=vpn generation=1 sequence=$index",
                    createdAt = index,
                )
            } +
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    message = "state=outbound_only mode=vpn generation=1 final=true",
                    createdAt = 81L,
                )

        val assessment =
            assessRootCause(
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
}

class RuntimeRootCausePathEvidenceTest {
    @Test
    fun `free form relay text does not classify without canonical tokens`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mode=vpn generation=1 host=stale.example",
                            createdAt = 1L,
                        ),
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=outbound_only mode=vpn generation=1 final=true",
                            createdAt = 1L,
                        ),
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
    fun `mtu blackhole remains unreachable without typed pmtu producer`() {
        val weakAssessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "pmtu",
                            message = "pmtu_blackhole=true mtu_band=low",
                            createdAt = 1L,
                        ),
                    ),
            )
        val explicitAssessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, explicitAssessment.verdict)
        assertTrue(explicitAssessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `mtu blackhole rejects missing ptb observation`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
    fun `terminal DNS failure outranks internal data plane conflict`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=tun_ingress_no_upstream mode=vpn generation=1 final=true",
                            createdAt = 1L,
                        ),
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "vpn_protect",
                            level = "warn",
                            message = "event=protect_failed event_kind=protect_failure",
                            createdAt = 2L,
                        ),
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 3L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
        assertEquals(
            listOf("data_plane_tun_ingress_no_upstream", "protect_failure"),
            assessment.contradictoryCategories,
        )
    }

    @Test
    fun `cumulative cross layer return does not veto terminal DNS failure`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 1L,
                        ),
                        rootCauseEvent(
                            connectionSessionId = "conn-a",
                            subsystem = "data_plane",
                            message = "state=cross_layer_return_observed mode=vpn generation=1 final=true",
                            createdAt = 2L,
                        ),
                    ),
            )

        assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
        assertTrue(assessment.contradictoryCategories.isEmpty())
    }

    @Test
    fun `shaped protect text requires canonical source and event kind`() {
        val variants =
            listOf(
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "vpn_protect",
                    source = "service",
                    level = "warn",
                    message = "event=protect_failed",
                    createdAt = 1L,
                ),
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "vpn_protect",
                    source = "proxy",
                    level = "warn",
                    message = "event=protect_failed event_kind=protect_failure",
                    createdAt = 2L,
                ),
            )
        variants.forEach { spoofed ->
            val assessment = assessRootCause(connectionSessionId = "conn-a", events = listOf(spoofed))

            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.evidenceRefs.isEmpty())
        }
    }

    @Test
    fun `shaped data plane text requires canonical service event kind`() {
        val variants =
            listOf(
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    source = "proxy",
                    message = "state=outbound_only mode=vpn generation=1 final=true event_kind=data_plane_final",
                    createdAt = 1L,
                    preserveMessage = true,
                ),
                rootCauseEvent(
                    connectionSessionId = "conn-a",
                    subsystem = "data_plane",
                    source = "service",
                    message = "state=outbound_only mode=vpn generation=1 final=true",
                    createdAt = 2L,
                    preserveMessage = true,
                ),
            )
        variants.forEach { spoofed ->
            val assessment = assessRootCause(connectionSessionId = "conn-a", events = listOf(spoofed))

            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.evidenceRefs.isEmpty())
        }
    }

    @Test
    fun `dns source and subsystem event stays fail closed without event kind`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
}

class RuntimeRootCauseProcessExitTest {
    @Test
    fun `root cause taxonomy does not expose unsupported OEM attribution`() {
        assertFalse(RuntimeRootCauseVerdict.entries.any { verdict -> verdict.name.contains("OEM") })
    }

    @Test
    fun `Android memory limiter correlation stays inconclusive`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedProcessExitCorrelationEvent(
                            connectionSessionId = "conn-a",
                            createdAt = 1L,
                        ),
                    ),
                terminalEvidenceSealed = false,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(RuntimeRootCauseConfidence.LOW, assessment.confidence)
        assertTrue(assessment.evidenceRefs.isEmpty())
        assertFalse(assessment.terminalEvidenceSealed)
    }

    @Test
    fun `unsealed correlated process exit stays inconclusive`() {
        val assessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedProcessExitCorrelationEvent(
                            connectionSessionId = "conn-a",
                            createdAt = 1L,
                        ),
                    ),
                terminalEvidenceSealed = false,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertFalse(assessment.terminalEvidenceSealed)
    }

    @Test
    fun `generic Android pressure exits never classify as OEM kills`() {
        listOf("low_memory", "excessive_resource_usage").forEach { reason ->
            val assessment =
                assessRootCause(
                    connectionSessionId = "conn-a",
                    events =
                        listOf(
                            typedProcessExitCorrelationEvent(
                                connectionSessionId = "conn-a",
                                createdAt = 1L,
                                reason = reason,
                                subtype = "none",
                                message =
                                    "event=process_exit_correlation verdict=inconclusive " +
                                        "evidence=last_exit_inspector_v1 reason=$reason " +
                                        "subtype=none importance=service",
                            ),
                        ),
                    terminalEvidenceSealed = false,
                )

            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertFalse(assessment.terminalEvidenceSealed)
            assertTrue(assessment.evidenceRefs.isEmpty())
        }
    }

    @Test
    fun `spoofed process exit correlations stay inconclusive`() {
        val variants =
            listOf(
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 1L,
                    id = "application_exit_correlation:conn-b",
                ),
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 2L,
                    source = "android_last_exit_inspector",
                ),
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 3L,
                    subsystem = "service",
                ),
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 4L,
                    message =
                        "event=process_exit_correlation verdict=oem_process_kill " +
                            "reason=low_memory subtype=none importance=service",
                ),
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 5L,
                    reason = "crash",
                ),
                typedProcessExitCorrelationEvent(
                    connectionSessionId = "conn-a",
                    createdAt = 6L,
                    importance = "cached",
                ),
            )
        variants.forEach { spoofed ->
            val assessment =
                RuntimeRootCauseClassifier.assess(
                    connectionSessionId = "conn-a",
                    events = listOf(spoofed),
                )

            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.evidenceRefs.isEmpty())
        }
    }

    @Test
    fun `Android process exit does not override typed DNS verdict`() {
        val assessment =
            RuntimeRootCauseClassifier.assess(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        typedDnsRuntimeEvent(
                            connectionSessionId = "conn-a",
                            state = "failure_threshold",
                            createdAt = 1L,
                        ),
                        typedProcessExitCorrelationEvent(
                            connectionSessionId = "conn-a",
                            createdAt = 2L,
                        ),
                    ),
                terminalEvidenceSealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
        assertTrue(assessment.contradictoryCategories.isEmpty())
    }

    @Test
    fun `process exit events stay inconclusive without safe session correlation`() {
        val sessionlessAssessment =
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
            assessRootCause(
                connectionSessionId = "conn-a",
                events =
                    listOf(
                        rootCauseEvent(
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
}
