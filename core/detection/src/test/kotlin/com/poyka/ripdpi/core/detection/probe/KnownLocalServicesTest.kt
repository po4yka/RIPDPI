package com.poyka.ripdpi.core.detection.probe

import org.junit.Assert.assertTrue
import org.junit.Test

class KnownLocalServicesTest {
    @Test
    fun `WeChat ports are excluded`() {
        assertTrue(24012 in KnownLocalServices.excludedPorts)
        assertTrue(24013 in KnownLocalServices.excludedPorts)
    }

    @Test
    fun `ADB ports are excluded`() {
        assertTrue(5037 in KnownLocalServices.excludedPorts)
        assertTrue(5555 in KnownLocalServices.excludedPorts)
    }

    @Test
    fun `Samsung Knox ports are excluded`() {
        assertTrue(8610 in KnownLocalServices.excludedPorts)
        assertTrue(8615 in KnownLocalServices.excludedPorts)
    }

    @Test
    fun `random port is not excluded`() {
        assertTrue(12345 !in KnownLocalServices.excludedPorts)
    }
}
