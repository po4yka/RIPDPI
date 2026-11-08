package com.poyka.ripdpi.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.services.ProtectSocketFdCleanupProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectSocketFdCleanupInstrumentedTest {
    @Test
    fun failureBeforeProtectionClosesEveryTransferredDescriptor() {
        verify(ProtectSocketFdCleanupProbe.FailurePoint.BeforeProtect)
    }

    @Test
    fun extractionFailureClosesTheCurrentAndRemainingDescriptors() {
        verify(ProtectSocketFdCleanupProbe.FailurePoint.ExtractSecond)
    }

    @Test
    fun failureMonitorExceptionClosesEveryTransferredDescriptor() {
        verify(ProtectSocketFdCleanupProbe.FailurePoint.ReportFailure)
    }

    private fun verify(failurePoint: ProtectSocketFdCleanupProbe.FailurePoint) {
        val result = ProtectSocketFdCleanupProbe().run(failurePoint)

        assertTrue("Cleanup must preserve the original failure", result.originalFailurePreserved)
        assertEquals("No pipe writer may survive the failed batch", listOf(true, true, true), result.pipeEof)
    }
}
