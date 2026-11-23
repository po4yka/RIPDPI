package com.poyka.ripdpi.platform

import com.poyka.ripdpi.data.parseStrategyChainDsl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPacketCapabilitiesTest {
    @Test
    fun `ordinary chains are still accepted`() {
        val chain = parseStrategyChainDsl("[tcp]\ntlsrec extlen\nsplit midsld\n[udp]\nfake_burst 3").getOrThrow()

        assertTrue(chain.supportedInDistribution())
    }

    @Test
    fun `packet operations from saved or pasted chains are unavailable`() {
        val tcpChains = listOf("seqovl host+1", "hostfake endhost", "multidisorder host+1\nmultidisorder endhost")
        for (dsl in tcpChains + listOf("fakerst host+1", "ipfrag2 host+1")) {
            val chain = parseStrategyChainDsl("[tcp]\n$dsl").getOrThrow()
            assertFalse(dsl, chain.supportedInDistribution())
        }
        val udp = parseStrategyChainDsl("[udp]\nipfrag2_udp 64").getOrThrow()
        assertFalse(udp.supportedInDistribution())
    }
}
