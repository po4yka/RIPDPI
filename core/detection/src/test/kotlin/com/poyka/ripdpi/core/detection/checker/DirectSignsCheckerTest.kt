package com.poyka.ripdpi.core.detection.checker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSignsCheckerTest {
    @Test
    fun `known proxy port 1080 is detected`() {
        assertTrue(DirectSignsChecker.isKnownProxyPort("1080"))
    }

    @Test
    fun `known proxy port 9050 is detected`() {
        assertTrue(DirectSignsChecker.isKnownProxyPort("9050"))
    }

    @Test
    fun `port in range 16000-16100 is detected`() {
        assertTrue(DirectSignsChecker.isKnownProxyPort("16050"))
    }

    @Test
    fun `non-proxy port is not detected`() {
        assertFalse(DirectSignsChecker.isKnownProxyPort("22"))
    }

    @Test
    fun `null port returns false`() {
        assertFalse(DirectSignsChecker.isKnownProxyPort(null))
    }

    @Test
    fun `non-numeric port returns false`() {
        assertFalse(DirectSignsChecker.isKnownProxyPort("abc"))
    }
}
