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

class PingCommandProfileTest {
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)

    @Test(timeout = TestTimeoutMillis)
    fun `iputils uses numeric output without reverse DNS`() =
        runBlocking {
            withExecutable(
                """
                case "${'$'}1" in
                  -V) printf '%s\n' 'ping utility, iputils-s20121221'; exit 0 ;;
                  -n) printf '%s\n' 'rtt min/avg/max/mdev = 1.0/1.25/1.5/0.25 ms'; exit 0 ;;
                  *) printf '%s\n' 'numeric output required'; exit 2 ;;
                esac
                """,
            ) { script ->
                val result = prober(script).ping("127.0.0.1")

                assertTrue(result.replied)
                assertEquals(1.25, result.rttMs!!, 0.0)
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `toybox keeps numeric default without unsupported n flag`() =
        runBlocking {
            withExecutable(
                """
                case "${'$'}1" in
                  -V) printf '%s\n' 'Unknown option V'; exit 1 ;;
                  --help) printf '%s\n' 'Toybox 0.8.12' 'usage: ping [-c COUNT] [-W SEC] HOST'; exit 0 ;;
                esac
                for arg in "${'$'}@"; do
                  if [ "${'$'}arg" = '-n' ]; then printf '%s\n' 'Unknown option n'; exit 1; fi
                done
                printf '%s\n' 'round-trip min/avg/max = 2.0/3.0/4.0 ms'
                """,
            ) { script ->
                val result = prober(script).ping("127.0.0.1")

                assertTrue(result.replied)
                assertEquals(3.0, result.rttMs!!, 0.0)
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `unknown implementation is unavailable instead of guessing numeric flags`() =
        runBlocking {
            withExecutable("printf '%s\\n' 'Unknown ping utility'; exit 1") { script ->
                val result = prober(script).ping("127.0.0.1")

                assertFalse(result.replied)
                assertEquals("Unsupported ping numeric-output profile", result.rawOutput)
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `hung capability command is bounded and its processes are reaped`() =
        runBlocking {
            withExecutable("exec sleep 30") { script ->
                val processes = mutableListOf<Process>()
                val prober =
                    SystemPingProber(dispatchers, 1, 1) { command ->
                        start(script, command).also { processes.add(it) }
                    }

                val result = withTimeout(5_000L) { prober.ping("127.0.0.1") }

                assertFalse(result.replied)
                assertTrue(processes.isNotEmpty())
                assertTrue(processes.all { !it.isAlive })
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `cancelling capability command reaps its process`() =
        runBlocking {
            withExecutable("exec sleep 30") { script ->
                val started = CompletableDeferred<Process>()
                val prober =
                    SystemPingProber(dispatchers, 1, 1) { command ->
                        start(script, command).also { started.complete(it) }
                    }
                val pending = async(Dispatchers.Default) { prober.ping("127.0.0.1") }
                val process = withTimeout(2_000L) { started.await() }

                withTimeout(2_000L) { pending.cancelAndJoin() }

                assertFalse(process.isAlive)
            }
        }

    private fun prober(script: File): SystemPingProber =
        SystemPingProber(dispatchers, 1, 1) { command -> start(script, command) }

    private fun start(
        script: File,
        command: List<String>,
    ): Process = ProcessBuilder(listOf(script.absolutePath) + command.drop(1)).redirectErrorStream(true).start()

    private suspend fun withExecutable(
        scriptText: String,
        block: suspend (File) -> Unit,
    ) {
        val script = File.createTempFile("detection-ping-profile-", ".sh")
        try {
            script.writeText("#!/bin/sh\n${scriptText.trimIndent()}\n")
            check(script.setExecutable(true))
            block(script)
        } finally {
            script.delete()
        }
    }
}

private const val TestTimeoutMillis = 10_000L
