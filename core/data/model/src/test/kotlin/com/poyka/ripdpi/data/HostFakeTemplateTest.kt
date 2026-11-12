package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFakeTemplateTest {
    @Test
    fun `hostfake rejects non ASCII and malformed templates before saving`() {
        for (template in listOf("cdn/ex.example", "*.example.com", "пример.рф", "١.example", "K.example")) {
            val result = parseStrategyChainDsl("[tcp]\nhostfake endhost host=$template")

            assertTrue("Accepted invalid host template: $template", result.isFailure)
        }
    }

    @Test
    fun `hostfake normalizes ASCII templates without changing A labels`() {
        val parsed = parseStrategyChainDsl("[tcp]\nhostfake endhost host=XN--E1AFMKFD.XN--P1AI.").getOrThrow()

        assertEquals("xn--e1afmkfd.xn--p1ai", parsed.tcpSteps.single().fakeHostTemplate)
    }
}
