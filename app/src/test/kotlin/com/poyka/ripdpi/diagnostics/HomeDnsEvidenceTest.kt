package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDnsEvidenceTest {
    @Test
    fun `different public CDN answers do not prove poisoning`() {
        val result =
            characterizeHomeDns(
                listOf("104.16.132.229"),
                listOf("142.250.74.46"),
                listOf("104.16.133.229"),
                listOf("142.250.185.78"),
            )
        assertEquals(HomeDnsResolverClass.UNKNOWN, result.resolverClass)
        assertTrue(result.poisonedHosts.isEmpty())
    }

    @Test
    fun `matching control answers retain healthy resolver result`() {
        val addresses = listOf("1.1.1.1")
        assertEquals(
            HomeDnsResolverClass.SYSTEM_RESOLVER_OK,
            characterizeHomeDns(addresses, addresses, addresses, addresses).resolverClass,
        )
    }

    @Test
    fun `missing canary answer remains inconclusive`() {
        val addresses = listOf("1.1.1.1")
        assertEquals(
            HomeDnsResolverClass.UNKNOWN,
            characterizeHomeDns(addresses, emptyList(), addresses, addresses).resolverClass,
        )
    }
}
