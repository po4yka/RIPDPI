package com.poyka.ripdpi.core.detection.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcLeakCheckerTest {
    @Test
    fun `STUN reachability is an observation not a WebRTC leak`() {
        val result = WebRtcLeakChecker.resultFrom(webRtcProtectionEnabled = true, stunReachable = true)

        assertFalse(result.detected)
        assertFalse(result.needsReview)
        assertEquals(1, result.evidence.size)
        assertFalse(result.evidence.single().detected)
    }

    @Test
    fun `a missing response from the first server tries the second`() {
        val hosts = mutableListOf<String>()
        val reachable =
            WebRtcLeakChecker.probeStunReachability { host, _ ->
                hosts += host
                hosts.size == 2
            }

        assertTrue(reachable)
        assertEquals(listOf("stun.l.google.com", "stun1.l.google.com"), hosts)
    }

    @Test
    fun `binding response must match cookie transaction and advertised length`() {
        val request = ByteArray(20).also { bytes ->
            bytes[4] = 0x21
            bytes[5] = 0x12
            bytes[6] = 0xa4.toByte()
            bytes[7] = 0x42
            bytes[8] = 0x5a
        }
        val response =
            request.copyOf(32).also {
                it[0] = 0x01
                it[1] = 0x01
                it[3] = 0x0c
                it[21] = 0x20
                it[23] = 0x08
                it[25] = 0x01
            }

        val headerOnly =
            request.copyOf().also {
                it[0] = 0x01
                it[1] = 0x01
            }
        assertFalse(WebRtcLeakChecker.validBindingResponse(headerOnly, headerOnly.size, request))
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, 20, request))
        assertTrue(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
        assertFalse(WebRtcLeakChecker.validBindingResponse(response.copyOf(36), 36, request))
        response[21] = 0x01
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
        response[21] = 0x20
        response[23] = 0x04
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
        response[23] = 0x08
        response[8] = 0x00
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
        response[8] = 0x5a
        response[4] = 0x00
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
        response[4] = 0x21
        response[3] = 0x10
        assertFalse(WebRtcLeakChecker.validBindingResponse(response, response.size, request))
    }
}
