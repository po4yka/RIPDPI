package com.poyka.ripdpi.data

import org.junit.Assert.assertThrows
import org.junit.Test

class MultiDisorderSettingsTest {
    @Test
    fun `later default or equivalent flags inherit the first marker flags`() {
        val first = TcpChainStepModel(TcpChainStepKind.MultiDisorder, "4", tcpFlagsOrigSet = "cwr")
        for (flags in listOf("", "0", "cwr", "0x80")) {
            validateTcpChain(listOf(first, first.copy(marker = "2", tcpFlagsOrigSet = flags)))
        }
    }

    @Test
    fun `later nondefault flags must agree with the first marker`() {
        val first = TcpChainStepModel(TcpChainStepKind.MultiDisorder, "0")
        for (later in listOf(first.copy(tcpFlagsOrigSet = "cwr"), first.copy(tcpFlagsOrigUnset = "psh"))) {
            assertThrows(IllegalArgumentException::class.java) { validateTcpChain(listOf(first, later)) }
        }
        val configured = first.copy(tcpFlagsOrigSet = "cwr", tcpFlagsOrigUnset = "psh")
        for (later in listOf(configured.copy(tcpFlagsOrigSet = "ece"), configured.copy(tcpFlagsOrigUnset = "ack"))) {
            assertThrows(IllegalArgumentException::class.java) { validateTcpChain(listOf(configured, later)) }
        }
    }
}
