package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedPolicyChangeClassifierTest {
    @Test
    fun `unchanged snapshot produces empty change set`() {
        val snap = baseSnapshot()

        val changes = NetworkChangeClassifier.classify(prev = snap, next = snap)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `null prev produces empty change set`() {
        val changes = NetworkChangeClassifier.classify(prev = null, next = baseSnapshot())

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `dns server list change produces DnsChange`() {
        val prev = baseSnapshot(dnsServers = listOf("1.1.1.1"))
        val next = baseSnapshot(dnsServers = listOf("8.8.8.8"))

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.DnsChange), changes)
    }

    @Test
    fun `route presence change produces RouteChange`() {
        val prev = baseSnapshot(hasDefaultRoute = true)
        val next = baseSnapshot(hasDefaultRoute = false)

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.RouteChange), changes)
    }

    @Test
    fun `metered flag change produces MeteredChange`() {
        val prev = baseSnapshot(metered = false)
        val next = baseSnapshot(metered = true)

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.MeteredChange), changes)
    }

    @Test
    fun `captive portal flag change produces CaptiveChange`() {
        val prev = baseSnapshot(captivePortal = false)
        val next = baseSnapshot(captivePortal = true)

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.CaptiveChange), changes)
    }

    @Test
    fun `suspended flag change produces SuspendedChange`() {
        val prev = baseSnapshot(suspended = false)
        val next = baseSnapshot(suspended = true)

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.SuspendedChange), changes)
    }

    @Test
    fun `transport set change produces TransportChange`() {
        val prev = baseSnapshot(transportKey = "wifi")
        val next = baseSnapshot(transportKey = "cellular")

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertEquals(setOf(ScopedPolicyChange.TransportChange), changes)
    }

    @Test
    fun `multiple simultaneous changes produce the full change set`() {
        val prev = baseSnapshot(dnsServers = listOf("1.1.1.1"), metered = false, transportKey = "wifi")
        val next = baseSnapshot(dnsServers = listOf("9.9.9.9"), metered = true, transportKey = "cellular")

        val changes = NetworkChangeClassifier.classify(prev, next)

        assertTrue(changes.contains(ScopedPolicyChange.DnsChange))
        assertTrue(changes.contains(ScopedPolicyChange.MeteredChange))
        assertTrue(changes.contains(ScopedPolicyChange.TransportChange))
    }
}

private fun baseSnapshot(
    dnsServers: List<String> = listOf("1.1.1.1"),
    hasDefaultRoute: Boolean = true,
    metered: Boolean = false,
    captivePortal: Boolean = false,
    suspended: Boolean = false,
    transportKey: String = "wifi",
): NetworkSnapshot =
    NetworkSnapshot(
        dnsServers = dnsServers,
        hasDefaultRoute = hasDefaultRoute,
        metered = metered,
        captivePortal = captivePortal,
        suspended = suspended,
        transportKey = transportKey,
    )
