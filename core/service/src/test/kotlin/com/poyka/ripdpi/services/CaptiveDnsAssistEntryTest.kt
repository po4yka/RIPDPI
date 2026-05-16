package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Captive mode is entered ONLY when an explicit [CaptivePortalDetection.Detected] signal is
 * supplied. Passing [CaptivePortalDetection.NotDetected] must leave the controller in
 * [CaptivePortalState.StrictDns].
 */
class CaptiveDnsAssistEntryTest {
    @Test
    fun `enter with Detected signal transitions to CaptiveAssist`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.hotel.example"))
        assertEquals(CaptivePortalState.CaptiveAssist, controller.state())
    }

    @Test
    fun `enter with NotDetected signal is rejected and state stays StrictDns`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.NotDetected)
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }

    @Test
    fun `initial state is StrictDns`() {
        val controller = CaptiveDnsAssistController()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
    }

    @Test
    fun `entering captive mode records the portal host`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.cafe.example"))
        assertNotNull(controller.activePortalHost())
        assertEquals("login.cafe.example", controller.activePortalHost())
    }

    @Test
    fun `entering with NotDetected leaves portal host null`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.NotDetected)
        assertNull(controller.activePortalHost())
    }
}
