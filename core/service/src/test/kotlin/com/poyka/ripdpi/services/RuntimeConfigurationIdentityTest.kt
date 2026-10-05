package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigurationIdentityTest {
    @Test
    fun `identities are private process local domain separated and length prefixed`() {
        val factory = RuntimeConfigurationIdentityFactory()
        val baseline = factory.capture(listOf("profile", "secret"), listOf("dns"))
        assertTrue(baseline.matches(factory.capture(listOf("profile", "secret"), listOf("dns"))))
        assertFalse(baseline.matches(factory.capture(listOf("profile", "changed-secret"), listOf("dns"))))
        assertFalse(
            baseline.matches(RuntimeConfigurationIdentityFactory().capture(listOf("profile", "secret"), listOf("dns"))),
        )
        assertFalse(
            factory.capture(listOf("a", "bc"), emptyList()).matches(factory.capture(listOf("ab", "c"), emptyList())),
        )
        assertFalse(
            factory
                .capture(
                    listOf("same"),
                    emptyList(),
                ).transport
                .matches(factory.capture(emptyList(), listOf("same")).dns),
        )
        assertFalse(baseline.toString().contains("secret"))
        assertFalse(baseline.transport.toString().contains("secret"))
    }

    @Test
    fun `partial DNS acknowledgment preserves the last transport identity`() {
        val factory = RuntimeConfigurationIdentityFactory()
        val acknowledged = factory.capture(listOf("old-transport"), listOf("old-dns"))
        val current = factory.capture(listOf("new-transport"), listOf("new-dns"))
        val partial = acknowledged.withDns(current)
        assertTrue(partial.transport.matches(acknowledged.transport))
        assertTrue(partial.dns.matches(current.dns))
        assertFalse(partial.matches(current))
    }
}
