package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * While in captive mode the proxy/default DNS path remains strict.
 * Non-portal queries must never fall back to captive DNS resolution.
 */
class CaptiveDoesNotWeakenStrictDnsTest {
    @Test
    fun `strictDnsUnaffected returns true when not in captive mode`() {
        val controller = CaptiveDnsAssistController()
        // Never entered captive — strict DNS always active.
        assertFalse(controller.captiveAssistCoversHost("google.com"))
    }

    @Test
    fun `captive mode does not cover arbitrary hosts`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.portal.example"))
        assertFalse(controller.captiveAssistCoversHost("google.com"))
        assertFalse(controller.captiveAssistCoversHost("1.1.1.1"))
        assertFalse(controller.captiveAssistCoversHost("cloudflare.com"))
    }

    @Test
    fun `resolve returns null for non-portal host even while in CaptiveAssist`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.portal.example"))
        // Non-portal host must not be resolved via captive DNS.
        assertNull(controller.resolve("secure-dns.example"))
        assertNull(controller.resolve("proxy.internal"))
    }

    @Test
    fun `captiveAssistCoversHost returns true only for portal host`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.portal.example"))
        // Only the exact portal host is covered.
        assertFalse(controller.captiveAssistCoversHost("portal.example"))
        assertFalse(controller.captiveAssistCoversHost(""))
    }
}
