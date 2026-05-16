package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * After captive portal success or timeout the controller must return to
 * [CaptivePortalState.StrictDns] and stop covering any portal host.
 */
class CaptiveTimeoutReturnsStrictTest {
    private fun controllerInCaptiveMode(): CaptiveDnsAssistController {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.transit.example"))
        return controller
    }

    @Test
    fun `markSuccess transitions state to StrictDns`() {
        val controller = controllerInCaptiveMode()
        controller.markSuccess()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }

    @Test
    fun `markTimeout transitions state to StrictDns`() {
        val controller = controllerInCaptiveMode()
        controller.markTimeout()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }

    @Test
    fun `after markSuccess resolve returns null even for the former portal host`() {
        val controller = controllerInCaptiveMode()
        controller.markSuccess()
        assertNull(controller.resolve("login.transit.example"))
    }

    @Test
    fun `after markTimeout resolve returns null even for the former portal host`() {
        val controller = controllerInCaptiveMode()
        controller.markTimeout()
        assertNull(controller.resolve("login.transit.example"))
    }

    @Test
    fun `after markSuccess portal host is cleared`() {
        val controller = controllerInCaptiveMode()
        controller.markSuccess()
        assertNull(controller.activePortalHost())
    }

    @Test
    fun `after markTimeout portal host is cleared`() {
        val controller = controllerInCaptiveMode()
        controller.markTimeout()
        assertNull(controller.activePortalHost())
    }

    @Test
    fun `markSuccess on StrictDns controller is a no-op`() {
        val controller = CaptiveDnsAssistController()
        controller.markSuccess()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }

    @Test
    fun `markTimeout on StrictDns controller is a no-op`() {
        val controller = CaptiveDnsAssistController()
        controller.markTimeout()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }
}
