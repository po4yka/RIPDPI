package com.poyka.ripdpi.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficShapeConfigTest {
    @Test
    fun `traffic shaping schema is default off with stable bounded presets`() {
        val defaultConfig = TrafficShapeConfig()
        val opusConfig = TrafficShapeConfig(profile = TrafficShapeProfile.OpusVoip)
        val videoConfig = TrafficShapeConfig(profile = TrafficShapeProfile.WebRtcVideo)

        assertFalse(defaultConfig.enabled)
        assertTrue(opusConfig.enabled)
        assertEquals(200, opusConfig.profile.minimumFrameBytes)
        assertEquals(200, opusConfig.profile.maximumFrameBytes)
        assertEquals(20, opusConfig.profile.intervalMillis)
        assertEquals(600, videoConfig.profile.minimumFrameBytes)
        assertEquals(1_200, videoConfig.profile.maximumFrameBytes)
        assertEquals(10, videoConfig.profile.intervalMillis)
        assertEquals("\"off\"", Json.encodeToString(TrafficShapeProfile.Off))
        assertEquals("\"opus_voip\"", Json.encodeToString(TrafficShapeProfile.OpusVoip))
        assertEquals("\"webrtc_video\"", Json.encodeToString(TrafficShapeProfile.WebRtcVideo))
    }
}
