package com.poyka.ripdpi.core.detection.checker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFingerprintCheckerTest {
    @Test
    fun `native default input is normalized to chrome stable in findings`() =
        runTest {
            val result = TlsFingerprintChecker.check("native_default")

            assertTrue(
                result.findings.any { finding -> finding.description == "Fingerprint profile: chrome_stable" },
            )
            assertTrue(
                result.findings.any { finding -> finding.description == "Using Chrome-stable TLS profile" },
            )
        }
}
