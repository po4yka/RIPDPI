package com.poyka.ripdpi.services.dns.bootstrap

import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapAaaaPolicyTest {
    @Test
    fun `IPV4_ONLY mode rejects AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY)
        assertFalse(policy.isAaaaPermitted())
    }

    @Test
    fun `BLOCK mode rejects AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.BLOCK)
        assertFalse(policy.isAaaaPermitted())
    }

    @Test
    fun `NATIVE mode rejects AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.NATIVE)
        assertFalse(policy.isAaaaPermitted())
    }

    @Test
    fun `TRANSLATED mode rejects AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.TRANSLATED)
        assertFalse(policy.isAaaaPermitted())
    }

    @Test
    fun `ALLOW dual-stack mode permits AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.ALLOW)
        assertTrue(policy.isAaaaPermitted())
    }

    @Test
    fun `PREFER dual-stack mode permits AAAA`() {
        val policy = Ipv6BootstrapPolicy(Ipv6Policy.PREFER)
        assertTrue(policy.isAaaaPermitted())
    }
}
