package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captive resolver only resolves portal-scoped hosts.
 * Non-portal queries must be returned as null so they route through strict policy.
 */
class CaptiveDnsScopingTest {
    private fun controllerInCaptiveMode(portalHost: String = "login.portal.example"): CaptiveDnsAssistController {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = portalHost))
        return controller
    }

    @Test
    fun `resolve returns address for the exact portal host`() {
        val controller = controllerInCaptiveMode("login.portal.example")
        val result = controller.resolve(host = "login.portal.example")
        assertNotNull(result)
    }

    @Test
    fun `resolve returns null for a non-portal host`() {
        val controller = controllerInCaptiveMode("login.portal.example")
        val result = controller.resolve(host = "www.google.com")
        assertNull(result)
    }

    @Test
    fun `resolve returns null for a host that is a suffix of portal host`() {
        val controller = controllerInCaptiveMode("login.portal.example")
        val result = controller.resolve(host = "portal.example")
        assertNull(result)
    }

    @Test
    fun `resolve returns null for arbitrary browsing destinations`() {
        val controller = controllerInCaptiveMode("login.hotel.example")
        assertNull(controller.resolve("wikipedia.org"))
        assertNull(controller.resolve("1.1.1.1"))
        assertNull(controller.resolve("example.com"))
    }

    @Test
    fun `resolve is case insensitive for portal host`() {
        val controller = controllerInCaptiveMode("Login.Portal.Example")
        assertNotNull(controller.resolve("login.portal.example"))
        assertNotNull(controller.resolve("LOGIN.PORTAL.EXAMPLE"))
    }

    @Test
    fun `resolve returns null when controller is in StrictDns state`() {
        val controller = CaptiveDnsAssistController()
        // No entry call — remains StrictDns
        assertNull(controller.resolve("login.portal.example"))
    }

    @Test
    fun `isPortalHost returns true for the portal host`() {
        val controller = controllerInCaptiveMode("login.airport.example")
        assertTrue(controller.isPortalHost("login.airport.example"))
    }

    @Test
    fun `isPortalHost returns false for unrelated host`() {
        val controller = controllerInCaptiveMode("login.airport.example")
        assertFalse(controller.isPortalHost("example.com"))
    }
}
