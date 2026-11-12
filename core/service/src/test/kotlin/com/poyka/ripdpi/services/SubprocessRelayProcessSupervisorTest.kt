package com.poyka.ripdpi.services

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SubprocessRelayProcessSupervisorTest {
    @Test
    fun `message free stop exception still reports retained process`() {
        val process = FakeProcess(stopException = IOException())
        val supervisor = supervisor(process)
        supervisor.start(ProcessBuilder(), launchSpec(), onOutputEvent = {})

        assertNotNull(supervisor.stop())
        assertTrue(supervisor.isRunning())
        process.stopException = null
        process.exitOnTimedWait = true
        assertNull(supervisor.stop())
    }

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
    fun `wait for exit returns control to an undispatched caller on an io worker`() {
        val exit = CountDownLatch(1)
        val supervisor = supervisor(FakeProcess(exitOnBlockingWait = true, blockingWaitGate = exit))
        supervisor.start(ProcessBuilder(), launchSpec(), onOutputEvent = {})
        val launchReturned = CountDownLatch(1)
        val runner =
            thread {
                runBlocking(Dispatchers.IO) {
                    val waiter = launch(start = CoroutineStart.UNDISPATCHED) { supervisor.waitForExit() }
                    launchReturned.countDown()
                    waiter.join()
                }
            }

        val returned = launchReturned.await(5, TimeUnit.SECONDS)
        exit.countDown()
        runner.join(5_000)

        assertTrue("the helper exit wait must not block the starting thread", returned)
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

    @Test
    fun `output reader ends quietly when stop closes stdout under a blocked read`() {
        // Android's Process.destroy() closes stdout, so the blocked read fails instead of returning EOF.
        val process = FakeProcess(exitOnTimedWait = true, stdoutFailure = IOException("Stream closed"))
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }
        try {
            supervisor(process).start(ProcessBuilder(), launchSpec(), onOutputEvent = {})
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name == "subprocess-relay-output-test" }
                .forEach(Thread::join)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertNull("a closed stdout must not reach the process handler", uncaught.get())
    }

    @Test
    fun `pending output from a stopped run cannot change the next negotiation`() {
        assertPendingOutputIsIgnored("VERSION 1\nCMETHOD webtunnel socks5 127.0.0.1:41001\nCMETHODS DONE\n")
    }

    @Test
    fun `pending malformed output from a stopped run cannot fail the next negotiation`() {
        assertPendingOutputIsIgnored("CMETHODS DONE")
    }

    private fun assertPendingOutputIsIgnored(transcript: String) {
        val pendingOutput = PausedOutput(transcript)
        val nextOutput = PipedInputStream()
        val writer = PipedOutputStream(nextOutput)
        val processes =
            ArrayDeque(
                listOf(
                    FakeProcess(exitOnTimedWait = true, stdout = pendingOutput),
                    FakeProcess(exitOnTimedWait = true, stdout = nextOutput),
                ),
            )
        val supervisor = SubprocessRelayProcessSupervisor(SubprocessRelayOutputParser()) { processes.removeFirst() }
        val state = AtomicReference<SubprocessRelayOutputEvent?>()
        val ready = CountDownLatch(1)
        val spec =
            launchSpec().copy(
                managedClientBridge = ManagedClientSocksBridgeSpec("webtunnel", "127.0.0.1", 443, ""),
            )
        val receive: (SubprocessRelayOutputEvent) -> Unit = { event ->
            state.set(event)
            ready.countDown()
        }
        try {
            supervisor.start(ProcessBuilder(), spec, receive)
            assertTrue(pendingOutput.pending.await(5, TimeUnit.SECONDS))
            assertNull(supervisor.stop())
            supervisor.start(ProcessBuilder(), spec, receive)
            pendingOutput.resume.countDown()
            pendingOutput.reader.get().join(5_000)
            assertFalse("the old output reader must exit", pendingOutput.reader.get().isAlive)
            assertNull("the stopped run must not publish its buffered transcript", state.get())
            writer.write("VERSION 1\nCMETHOD webtunnel socks5 127.0.0.1:41002\nCMETHODS DONE\n".toByteArray())
            writer.flush()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(
                SubprocessRelayOutputEvent.ManagedClientListener(InetSocketAddress("127.0.0.1", 41002)),
                state.get(),
            )
        } finally {
            pendingOutput.resume.countDown()
            writer.close()
            supervisor.stop()
        }
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
    var stopException: IOException? = null,
    private val stdoutFailure: IOException? = null,
    private val blockingWaitGate: CountDownLatch? = null,
    private val stdout: InputStream = ByteArrayInputStream(byteArrayOf()),
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

    override fun getInputStream(): InputStream =
        stdoutFailure?.let { failure ->
            object : InputStream() {
                override fun read(): Int = throw failure
            }
        } ?: stdout

    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun waitFor(): Int {
        blockingWaitGate?.await()
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

    override fun destroy() {
        stopException?.let { throw it }
    }

    override fun destroyForcibly(): Process {
        destroyedForcibly = true
        return this
    }
}

private class PausedOutput(
    transcript: String,
) : ByteArrayInputStream(transcript.toByteArray()) {
    val pending = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val reader = AtomicReference<Thread>()

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(target, offset, length)
        if (count > 0) {
            reader.set(Thread.currentThread())
            pending.countDown()
            // A completed read can still return buffered bytes after process termination.
            while (resume.count != 0L) {
                try {
                    resume.await()
                } catch (_: InterruptedException) {
                    // stop interrupts the reader; retain the pending bytes until the test releases them.
                }
            }
        }
        return count
    }
}
