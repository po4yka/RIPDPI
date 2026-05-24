package com.poyka.ripdpi.diagnostics.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayStepEventTest {
    @Test
    fun stepKind_enumeratesAllFiveCanonical() {
        // P4 design lockdown: exactly 5 step kinds in this order
        assertEquals(5, ReplayStepKind.values().size)
        assertEquals(ReplayStepKind.DnsResolve, ReplayStepKind.values()[0])
        assertEquals(ReplayStepKind.FirstByte, ReplayStepKind.values()[4])
    }

    @Test
    fun finishedEvent_carriesVerdictAndOptionalTerminalStep() {
        val success =
            ReplayStepEvent.Finished(
                verdict = ReplayVerdict.Success,
                terminalStep = null,
                recommendationKey = "",
            )
        val failure =
            ReplayStepEvent.Finished(
                verdict = ReplayVerdict.Failure,
                terminalStep = ReplayStepKind.TlsHandshake,
                recommendationKey = "vpn_replay_rec_rst_after_client_hello",
            )
        assertTrue(success.terminalStep == null)
        assertEquals(ReplayStepKind.TlsHandshake, failure.terminalStep)
    }

    @Test
    fun stepFailed_classifiesByErrorKind() {
        val event =
            ReplayStepEvent.StepFailed(
                step = ReplayStepKind.TlsClientHello,
                errorKind = ReplayErrorKind.ConnectionReset,
                detail = "RST received in 84 ms before ServerHello",
            )
        assertEquals(ReplayErrorKind.ConnectionReset, event.errorKind)
    }
}
