package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class SubprocessRelayProcessSupervisorTest {
    @Test
    fun `stop retains process ownership until forced exit is confirmed`() {
        val process = FakeProcess(exitOnTimedWait = false)
        val supervisor = supervisor(process)
        supervisor.start(ProcessBuilder(), launchSpec(), onOutputEvent = {})

        val firstError = supervisor.stop()

        assertNotNull(firstError)
        assertTrue(process.destroyedForcibly)
        assertTrue(supervisor.isRunning())
        assertThrows(IllegalStateException::class.java) {
            supervisor.start(ProcessBuilder(), launchSpec(), onOutputEvent = {})
        }

        process.exitOnTimedWait = true
        assertNull(supervisor.stop())
        assertFalse(supervisor.isRunning())
    }

    @Test
    fun `wait for exit releases confirmed process`() =
        runTest {
            val process = FakeProcess(exitOnBlockingWait = true)
            val supervisor = supervisor(process)
            supervisor.start(ProcessBuilder(), launchSpec(), onOutputEvent = {})

            assertTrue(supervisor.isRunning())
            assertTrue(supervisor.waitForExit() == 0)
            assertFalse(supervisor.isRunning())
        }

    @Test
    fun `failed stdin initialization retains process when termination is unconfirmed`() {
        val process = FakeProcess(exitOnTimedWait = false, failOutput = true)
        val supervisor = supervisor(process)

        assertThrows(IOException::class.java) {
            supervisor.start(
                ProcessBuilder(),
                launchSpec().copy(standardInput = byteArrayOf(1)),
                onOutputEvent = {},
            )
        }

        assertTrue(process.destroyedForcibly)
        assertTrue(supervisor.isRunning())
    }

    private fun supervisor(process: Process) =
        SubprocessRelayProcessSupervisor(
            outputParser = SubprocessRelayOutputParser(),
            startProcess = { process },
        )

    private fun launchSpec() =
        SubprocessSocksRelayLaunchSpec(
            binaryName = "relay",
            commandArguments = emptyList(),
            runtimeKind = "test",
        )
}

private class FakeProcess(
    var exitOnTimedWait: Boolean = false,
    private val exitOnBlockingWait: Boolean = false,
    private val failOutput: Boolean = false,
) : Process() {
    private var running = true
    var destroyedForcibly = false
        private set

    override fun getOutputStream(): OutputStream =
        if (failOutput) {
            object : OutputStream() {
                override fun write(value: Int) = throw IOException("stdin failed")
            }
        } else {
            ByteArrayOutputStream()
        }

    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun waitFor(): Int {
        if (exitOnBlockingWait) running = false
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        if (exitOnTimedWait) running = false
        return !running
    }

    override fun exitValue(): Int {
        check(!running) { "still running" }
        return 0
    }

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
        destroyedForcibly = true
        return this
    }
}
