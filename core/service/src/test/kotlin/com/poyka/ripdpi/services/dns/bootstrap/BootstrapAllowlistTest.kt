package com.poyka.ripdpi.services.dns.bootstrap

import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapAllowlistTest {
    private val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example", "fixture-resolver.net"))

    @Test
    fun `name in allowlist is permitted`() {
        val result = allowlist.check("fixture-proxy.example")
        assertTrue(result is AllowlistResult.Permitted)
    }

    @Test
    fun `name outside allowlist is denied`() {
        val result = allowlist.check("fixture-isp-dns.example")
        assertTrue(result is AllowlistResult.Denied)
    }

    @Test
    fun `second allowlisted name is permitted`() {
        val result = allowlist.check("fixture-resolver.net")
        assertTrue(result is AllowlistResult.Permitted)
    }

    @Test
    fun `empty name is denied`() {
        val result = allowlist.check("")
        assertTrue(result is AllowlistResult.Denied)
    }
}
