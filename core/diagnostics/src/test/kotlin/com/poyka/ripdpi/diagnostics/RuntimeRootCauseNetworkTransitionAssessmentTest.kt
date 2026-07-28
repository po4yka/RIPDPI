package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRootCauseNetworkTransitionAssessmentTest {
    @Test
    fun `cross family underlay and data plane roots are inconclusive`() {
        val assessment =
            assess(
                events =
                    listOf(
                        event(
                            subsystem = "data_plane",
                            message =
                                "state=outbound_only mode=vpn generation=1 final=true " +
                                    "event_kind=data_plane_final",
                            createdAt = 10L,
                            source = "service",
                        ),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;generation=1;" +
                                "internet=present;validated=absent;sequence=1",
                            11L,
                        ),
                    ),
                sealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertEquals(
            listOf("data_plane_outbound_no_return", "network_transition_underlay_lost"),
            assessment.contradictoryCategories,
        )
    }

    @Test
    fun `underlay loss requires an authoritative non vpn generation`() {
        val assessment = assess(listOf(transition("kind=lost;sequence=2", 1L)))

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `same generation loss needs a successful terminal seal`() {
        val events =
            listOf(
                transition(
                    "kind=capabilities_changed;path=non_vpn;internet=present;" +
                        "validated=present;generation=7;sequence=1",
                    1L,
                ),
                transition("kind=lost;generation=7;sequence=2", 2L),
            )

        val unsealed = assess(events)
        val sealed = assess(events, sealed = true)

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, unsealed.verdict)
        assertEquals(RuntimeRootCauseVerdict.UNDERLAY_LOST, sealed.verdict)
        assertTrue(sealed.terminalEvidenceSealed)
    }

    @Test
    fun `transition sequence orders same millisecond loss and same generation cannot recover it`() {
        val assessment =
            assess(
                events =
                    listOf(
                        transition("kind=lost;generation=7;sequence=2", 1L),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=7;sequence=1",
                            1L,
                        ),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=7;sequence=3",
                            1L,
                        ),
                    ),
                sealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.UNDERLAY_LOST, assessment.verdict)
    }

    @Test
    fun `same generation capability recovery clears non terminal capability failure`() {
        val assessment =
            assess(
                listOf(
                    transition(
                        "kind=capabilities_changed;path=non_vpn;internet=present;" +
                            "validated=absent;generation=3;sequence=1",
                        1L,
                    ),
                    transition(
                        "kind=capabilities_changed;path=non_vpn;internet=present;" +
                            "validated=present;generation=3;sequence=2",
                        2L,
                    ),
                ),
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `newer validated non vpn generation clears prior generation loss`() {
        val assessment =
            assess(
                events =
                    listOf(
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=3;sequence=1",
                            1L,
                        ),
                        transition("kind=lost;generation=3;sequence=2", 2L),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=4;sequence=3",
                            3L,
                        ),
                    ),
                sealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `monotonic transition sequence survives wall clock reversal`() {
        val assessment =
            assess(
                events =
                    listOf(
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=3;sequence=1",
                            300L,
                        ),
                        transition("kind=lost;generation=3;sequence=2", 200L),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=4;sequence=3",
                            100L,
                        ),
                    ),
                sealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        assertTrue(assessment.evidenceRefs.isEmpty())
    }

    @Test
    fun `transition without monotonic sequence cannot override canonical evidence`() {
        val assessment =
            assess(
                events =
                    listOf(
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=absent;generation=3;sequence=1",
                            1L,
                        ),
                        transition(
                            "kind=capabilities_changed;path=non_vpn;internet=present;" +
                                "validated=present;generation=3",
                            2L,
                        ),
                    ),
                sealed = true,
            )

        assertEquals(RuntimeRootCauseVerdict.UNDERLAY_LOST, assessment.verdict)
    }

    private fun assess(
        events: List<NativeSessionEventEntity>,
        sealed: Boolean = false,
    ): RuntimeRootCauseAssessment =
        RuntimeRootCauseClassifier.assess(
            connectionSessionId = ConnectionSessionId,
            events = events,
            terminalEvidenceSealed = sealed,
        )

    private fun transition(
        message: String,
        createdAt: Long,
    ): NativeSessionEventEntity = event("network_transition", message, createdAt, "android_network_callback")

    private fun event(
        subsystem: String,
        message: String,
        createdAt: Long,
        source: String,
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = "$subsystem:$createdAt:$message",
            sessionId = null,
            connectionSessionId = ConnectionSessionId,
            source = source,
            level = "info",
            message = message,
            createdAt = createdAt,
            subsystem = subsystem,
        )

    private companion object {
        const val ConnectionSessionId = "conn-a"
    }
}
