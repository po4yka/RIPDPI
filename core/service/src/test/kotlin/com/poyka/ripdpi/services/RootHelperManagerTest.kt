package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootHelperManagerTest {
    @Test
    fun `start does not publish socket path until root helper socket is connectable`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess()
            var readinessCalls = 0
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLauncher = { _, socket ->
                        socket.writeText("stale")
                        fakeProcess
                    },
                    readinessProbe = { socket, _, _ ->
                        readinessCalls += 1
                        assertTrue(socket.exists())
                        false
                    },
                )

            val result = manager.start(context)

            assertNull(result)
            assertNull(manager.socketPath)
            assertEquals(1, readinessCalls)
            assertTrue(fakeProcess.destroyed)
        }

    @Test
    fun `stop force kills root helper when graceful destroy does not finish`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess(waitForTimeoutResult = false)
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLauncher = { _, _ -> fakeProcess },
                    readinessProbe = { _, _, _ -> true },
                )

            val result = manager.start(context)
            manager.stop()

            assertNotNull(result)
            assertNull(manager.socketPath)
            assertTrue(fakeProcess.destroyed)
            assertTrue(fakeProcess.forceDestroyed)
            assertEquals(1, fakeProcess.waitForTimeoutCalls)
        }
}

private class RecordingProcess(
    private val waitForTimeoutResult: Boolean = true,
) : Process() {
    var destroyed: Boolean = false
        private set
    var forceDestroyed: Boolean = false
        private set
    var waitForTimeoutCalls: Int = 0
        private set

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        waitForTimeoutCalls += 1
        return waitForTimeoutResult
    }

    override fun exitValue(): Int {
        if (!destroyed && !forceDestroyed) {
            throw IllegalThreadStateException("process is running")
        }
        return 0
    }

    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        forceDestroyed = true
        return this
    }
}
