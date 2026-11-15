package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionHistorySerializationTest {
    @Test
    fun `older history remains readable and new incomplete status survives round trip`() {
        val old =
            """
            {"networkFingerprint":"scope","networkSummary":"wifi","timestamp":1,
             "verdict":"NOT_DETECTED","stealthScore":100,"evidenceCount":0}
            """.trimIndent()
        val entry = RipDpiJson.decodeFromString<DetectionHistoryEntry>(old)
        assertTrue(entry.measurementsComplete)
        val encoded = RipDpiJson.encodeToString(entry.copy(measurementsComplete = false))
        assertFalse(RipDpiJson.decodeFromString<DetectionHistoryEntry>(encoded).measurementsComplete)
    }
}
