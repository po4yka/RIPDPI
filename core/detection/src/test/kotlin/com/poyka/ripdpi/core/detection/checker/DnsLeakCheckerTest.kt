package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DnsLeakCheckerTest {
    @Test
    fun `resolver addresses and app setting do not prove a leak`() {
        val result = DnsLeakChecker.resultFrom(listOf("127.0.0.1", "192.0.2.53"), encryptedDnsEnabled = false)

        assertFalse(result.detected)
        assertFalse(result.needsReview)
        assertEquals(CheckExecutionStatus.OBSERVATION_ONLY, result.executionStatus)
        assertEquals(emptyList<Any>(), result.evidence)
        assertEquals(
            listOf(
                "Active network DNS servers: 127.0.0.1, 192.0.2.53",
                "App encrypted DNS setting: disabled",
            ),
            result.findings.map { it.description },
        )
    }

    @Test
    fun `missing link properties remain inconclusive`() {
        val result = DnsLeakChecker.resultFrom(emptyList(), encryptedDnsEnabled = true)

        assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
        assertFalse(result.detected)
    }
}
