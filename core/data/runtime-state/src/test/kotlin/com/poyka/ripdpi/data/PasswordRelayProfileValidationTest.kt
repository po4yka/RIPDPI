package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRelayProfileValidationTest {
    @Test
    fun `password relay ports must be valid TCP endpoints`() {
        for (port in listOf(-1, 0, 1, 65535, 65536)) {
            for (profile in profiles(port = port)) {
                assertEquals("port $port", port in 1..65535, validateNativeRelayProfileResult(profile).isSuccess)
            }
        }
    }

    @Test
    fun `password relays reject blank servers and authentication passwords`() {
        for (blank in listOf("", " ", "\t")) {
            for (profile in profiles(server = blank) + profiles(password = blank)) {
                assertTrue(validateNativeRelayProfileResult(profile).isFailure)
            }
        }
    }

    @Test
    fun `password relays accept nonblank credentials and valid endpoint forms`() {
        for (server in listOf("relay.example", "127.0.0.1", "::1")) {
            for (profile in profiles(server = server, password = " ${fixturePassword(1)} ")) {
                assertTrue(validateNativeRelayProfileResult(profile).isSuccess)
            }
        }
    }

    private fun fixturePassword(slot: Int): String = "password-relay-fixture-$slot"

    private fun profiles(
        server: String = "relay.example",
        port: Int = 443,
        password: String = "fixture-password",
    ): List<ProxyProfile> =
        listOf(
            ProxyProfile.Trojan("trojan", "Trojan", "group", server, port, password),
            ProxyProfile.AnyTls("anytls", "AnyTLS", "group", server, port, server, password),
        )
}
