package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.core.SshHostKeyProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SshProbeOperationRunnerTest {
    @Test
    fun `cancelled caller retains slot until the owned worker ends`() =
        runTest {
            val runner = SshProbeOperationRunner(backgroundScope, StandardTestDispatcher(testScheduler))
            val releaseWorker = CompletableDeferred<Unit>()
            val first =
                async {
                    runner.run {
                        releaseWorker.await()
                        SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.ConnectFailed)
                    }
                }
            runCurrent()

            first.cancelAndJoin()

            try {
                assertEquals(
                    SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.Busy),
                    runner.run { SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.ConnectFailed) },
                )
            } finally {
                releaseWorker.complete(Unit)
                runCurrent()
            }
        }
}
