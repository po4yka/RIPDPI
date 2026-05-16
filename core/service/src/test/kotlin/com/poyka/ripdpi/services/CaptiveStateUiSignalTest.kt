package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The controller surfaces a UI flag signalling that DNS is temporarily not private
 * while in captive-portal assist mode.
 */
class CaptiveStateUiSignalTest {
    @Test
    fun `uiFlag returns null when in StrictDns state`() {
        val controller = CaptiveDnsAssistController()
        assertNull(controller.uiFlag())
    }

    @Test
    fun `uiFlag returns dns_temporarily_not_private when in CaptiveAssist`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.hotel.example"))
        assertEquals("dns_temporarily_not_private", controller.uiFlag())
    }

    @Test
    fun `uiFlag returns null after markSuccess transitions back to StrictDns`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.hotel.example"))
        controller.markSuccess()
        assertNull(controller.uiFlag())
    }

    @Test
    fun `uiFlag returns null after markTimeout transitions back to StrictDns`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.hotel.example"))
        controller.markTimeout()
        assertNull(controller.uiFlag())
    }

    @Test
    fun `uiFlag returns null when Timeout state is reached`() {
        val controller = CaptiveDnsAssistController()
        controller.enter(CaptivePortalDetection.Detected(portalHost = "login.hotel.example"))
        controller.markTimeout()
        assertEquals(CaptivePortalState.StrictDns, controller.state())
        assertNull(controller.uiFlag())
    }
}
