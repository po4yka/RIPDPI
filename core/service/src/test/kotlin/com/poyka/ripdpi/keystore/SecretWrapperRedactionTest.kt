package com.poyka.ripdpi.keystore

import com.poyka.ripdpi.data.Redacted
import com.poyka.ripdpi.data.SecretString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretWrapperRedactionTest {
    @Test
    fun `SecretString toString returns redacted placeholder`() {
        val secret = SecretString("fixture-password-1")
        assertEquals("<redacted>", secret.toString())
    }

    @Test
    fun `SecretString toString does not expose raw value`() {
        val secret = SecretString("fixture-token-1")
        assertFalse(secret.toString().contains("fixture-token-1"))
    }

    @Test
    fun `Redacted toString does not expose raw value`() {
        val secret = Redacted("fixture-token-2")
        assertFalse(secret.toString().contains("fixture-token-2"))
        assertEquals("<redacted>", secret.toString())
    }

    @Test
    fun `SecretString hashCode is constant regardless of value`() {
        val a = SecretString("fixture-password-2")
        val b = SecretString("fixture-password-3")
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(0, a.hashCode())
    }

    @Test
    fun `SecretString equals compares inner value not identity`() {
        val a = SecretString("fixture-same-value")
        val b = SecretString("fixture-same-value")
        assertEquals(a, b)
    }

    @Test
    fun `SecretString equals false for different values`() {
        val a = SecretString("fixture-value-a")
        val b = SecretString("fixture-value-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `log emission of SecretString does not contain raw value`() {
        val secret = SecretString("fixture-uuid-log-1")
        // Simulate what a logging framework would do: call toString()
        val logLine = "Profile uuid=$secret"
        assertFalse(
            "log line must not contain raw uuid",
            logLine.contains("fixture-uuid-log-1"),
        )
        assertEquals("Profile uuid=<redacted>", logLine)
    }

    @Test
    fun `diagnostics export of SecretString does not contain raw value`() {
        val secret = SecretString("fixture-token-diag-1")
        // Simulate embedding in a diagnostics map
        val diagnosticsEntry = mapOf("subscriptionToken" to secret.toString())
        assertFalse(
            "diagnostics must not contain raw token",
            diagnosticsEntry.values.any { it.contains("fixture-token-diag-1") },
        )
        assertEquals("<redacted>", diagnosticsEntry["subscriptionToken"])
    }
}
