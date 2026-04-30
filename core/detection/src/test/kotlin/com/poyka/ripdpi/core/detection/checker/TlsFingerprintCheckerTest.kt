package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TlsFingerprintCheckerTest {
    @Test
    fun `native default input is normalized to chrome stable in findings`() =
        runTest {
            val result = TlsFingerprintChecker.check(testDispatchers(), "native_default")

            assertTrue(
                result.findings.any { finding -> finding.description == "Fingerprint profile: chrome_stable" },
            )
            assertTrue(
                result.findings.any { finding -> finding.description == "Using Chrome-stable TLS profile" },
            )
        }

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = UnconfinedTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }
}
