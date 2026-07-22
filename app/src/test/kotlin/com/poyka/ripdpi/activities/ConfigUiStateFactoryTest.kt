package com.poyka.ripdpi.activities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigUiStateFactoryTest {
    @Test
    fun `profile recovery propagates coroutine cancellation`() =
        runTest {
            val cancellation = CancellationException("projection cancelled")

            val failure =
                runCatching {
                    loadRelayProfileRecordsOrEmpty { throw cancellation }
                }.exceptionOrNull()

            assertSame(cancellation, failure)
        }

    @Test
    fun `profile recovery tolerates storage failures`() =
        runTest {
            val records =
                loadRelayProfileRecordsOrEmpty {
                    throw IllegalStateException("profile storage unavailable")
                }

            assertTrue(records.isEmpty())
        }
}
