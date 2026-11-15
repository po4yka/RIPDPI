package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemPingProberTest {
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)

    @Test(timeout = TestTimeoutMillis)
    fun `cancelling ping terminates and reaps its process`() =
        runBlocking {
            withFixture("exec sleep 30") { script ->
                val started = CompletableDeferred<Process>()
                val prober =
                    SystemPingProber(dispatchers, 4, 3) { command ->
                        ProcessBuilder(listOf(script.absolutePath) + command.drop(1))
                            .redirectErrorStream(true)
                            .start()
                            .also { if ("-c" in command) started.complete(it) }
                    }
                val pending = async(Dispatchers.Default) { prober.ping("127.0.0.1") }
                val process = withTimeout(5_000L) { started.await() }

                withTimeout(2_000L) { pending.cancelAndJoin() }

                assertFalse("Cancelled ping process must be reaped", process.isAlive)
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `timeout terminates and reaps its process`() =
        runBlocking {
            withFixture("exec sleep 30") { script ->
                lateinit var process: Process
                val prober =
                    SystemPingProber(dispatchers, 1, 1) { command ->
                        ProcessBuilder(listOf(script.absolutePath) + command.drop(1))
                            .redirectErrorStream(true)
                            .start()
                            .also { process = it }
                    }

                val result = withTimeout(3_000L) { prober.ping("127.0.0.1") }

                assertFalse(result.replied)
                assertFalse("Timed out ping process must be reaped", process.isAlive)
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `successful process returns its measurement and exits`() =
        runBlocking {
            withFixture("printf '%s\\n' 'rtt min/avg/max/mdev = 1.0/2.0/3.0/1.0 ms'") { script ->
                lateinit var process: Process
                val prober =
                    SystemPingProber(dispatchers, 1, 1) { command ->
                        ProcessBuilder(listOf(script.absolutePath) + command.drop(1))
                            .redirectErrorStream(true)
                            .start()
                            .also { process = it }
                    }

                val result = prober.ping("127.0.0.1")

                assertTrue(result.replied)
                assertEquals(2.0, result.rttMs!!, 0.0)
                assertEquals(1.0, result.jitterMs!!, 0.0)
                assertFalse(process.isAlive)
            }
        }

    private suspend fun withFixture(
        command: String,
        block: suspend (File) -> Unit,
    ) {
        val script = File.createTempFile("detection-ping-", ".sh")
        try {
            script.writeText(
                """
                |#!/bin/sh
                |if [ "${'$'}1" = "-V" ]; then
                |  printf '%s\n' 'ping utility, iputils-s20121221'
                |  exit 0
                |fi
                |$command
                """.trimMargin() + "\n",
            )
            check(script.setExecutable(true))
            block(script)
        } finally {
            script.delete()
        }
    }
}

private const val TestTimeoutMillis = 10_000L
