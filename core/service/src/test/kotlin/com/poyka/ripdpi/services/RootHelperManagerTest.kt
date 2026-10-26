package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RootSettingsSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootHelperManagerTest {
    @Test
    fun `root helper has one process scoped DI owner`() {
        assertTrue(RootHelperManager::class.java.isAnnotationPresent(Singleton::class.java))
    }

    @Test
    fun `helper binary install replaces the file without writing the running inode`() {
        val target = File(RuntimeEnvironment.getApplication().filesDir, "ripdpi-root-helper-install-test")
        target.writeBytes(byteArrayOf(1, 1, 1))
        target.inputStream().use { runningImage ->
            installRootHelperBinary(ByteArrayInputStream(byteArrayOf(2, 2, 2, 2)), target)

            assertArrayEquals("the old inode is left intact", byteArrayOf(1, 1, 1), runningImage.readBytes())
        }
        assertArrayEquals(byteArrayOf(2, 2, 2, 2), target.readBytes())
        assertTrue(target.canExecute())
        assertFalse(File(target.parentFile, "${target.name}.pending").exists())
        target.delete()
    }

    @Test
    fun `start uses app private socket and nonce paths`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess()
            lateinit var launchedSocket: File
            lateinit var launchedNonce: File
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, socket, nonceFile ->
                        launchedSocket = socket
                        launchedNonce = nonceFile
                        listOf(
                            RootHelperLaunchAttempt("test root helper launcher") {
                                fakeProcess
                            },
                        )
                    },
                    readinessProbe = { socket, _, _ ->
                        assertEquals(launchedSocket, socket)
                        true
                    },
                    rootProcessTerminator = {},
                )

            val result = manager.start(context)

            assertEquals(File(context.filesDir, "root_helper.sock"), launchedSocket)
            assertEquals(File(context.filesDir, "root_helper.sock.nonce"), launchedNonce)
            assertEquals(launchedSocket.absolutePath, result)
            assertEquals(result, manager.socketPath)
        }

    @Test
    fun `start does not publish socket path until root helper socket is connectable`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess()
            var readinessCalls = 0
            lateinit var nonceFile: File
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, file ->
                        nonceFile = file
                        listOf(
                            RootHelperLaunchAttempt("test root helper launcher") {
                                assertValidNonceFile(nonceFile)
                                File(context.filesDir, "root_helper.sock").writeText("stale")
                                fakeProcess
                            },
                        )
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
            assertTrue(!nonceFile.exists())
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
                    processLaunchAttempts = { _, _, nonceFile ->
                        listOf(
                            RootHelperLaunchAttempt("test root helper launcher") {
                                assertValidNonceFile(nonceFile)
                                fakeProcess
                            },
                        )
                    },
                    readinessProbe = { _, _, _ -> true },
                    rootProcessTerminator = {},
                )

            val result = manager.start(context)
            manager.stop()

            assertNotNull(result)
            assertNull(manager.socketPath)
            assertTrue(fakeProcess.destroyed)
            assertTrue(fakeProcess.forceDestroyed)
            assertEquals(1, fakeProcess.waitForTimeoutCalls)
        }

    @Test
    fun `start tries next root helper launcher when first su form is not ready`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val firstProcess = RecordingProcess()
            val secondProcess = RecordingProcess()
            val launchOrder = mutableListOf<String>()
            val readinessResults = ArrayDeque(listOf(false, true))
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, nonceFile ->
                        listOf(
                            RootHelperLaunchAttempt("magisk su") {
                                assertValidNonceFile(nonceFile)
                                launchOrder += "magisk"
                                firstProcess
                            },
                            RootHelperLaunchAttempt("aosp su") {
                                assertValidNonceFile(nonceFile)
                                launchOrder += "aosp"
                                secondProcess
                            },
                        )
                    },
                    readinessProbe = { _, _, _ -> readinessResults.removeFirst() },
                )

            val result = manager.start(context)

            assertEquals(listOf("magisk", "aosp"), launchOrder)
            assertTrue(firstProcess.destroyed)
            assertEquals(File(context.filesDir, "root_helper.sock").absolutePath, result)
            assertEquals(result, manager.socketPath)
        }

    @Test
    fun `start tries next root helper launcher when first launch throws`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val secondProcess = RecordingProcess()
            val launchOrder = mutableListOf<String>()
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, nonceFile ->
                        listOf(
                            RootHelperLaunchAttempt("missing su") {
                                assertValidNonceFile(nonceFile)
                                launchOrder += "missing"
                                throw java.io.IOException("su missing")
                            },
                            RootHelperLaunchAttempt("absolute su") {
                                assertValidNonceFile(nonceFile)
                                launchOrder += "absolute"
                                secondProcess
                            },
                        )
                    },
                    readinessProbe = { _, _, _ -> true },
                    rootProcessTerminator = {},
                )

            val result = manager.start(context)

            assertEquals(listOf("missing", "absolute"), launchOrder)
            assertEquals(File(context.filesDir, "root_helper.sock").absolutePath, result)
            assertEquals(result, manager.socketPath)
        }

    @Test
    fun `stop removes root helper session nonce file`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            lateinit var nonceFile: File
            val fakeProcess = RecordingProcess()
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, file ->
                        nonceFile = file
                        listOf(RootHelperLaunchAttempt("test root helper launcher") { fakeProcess })
                    },
                    readinessProbe = { _, _, _ -> true },
                    rootProcessTerminator = {},
                )

            val result = manager.start(context)

            assertNotNull(result)
            assertTrue(nonceFile.exists())
            assertValidNonceFile(nonceFile)

            manager.stop()

            assertNull(manager.socketPath)
            assertTrue(fakeProcess.destroyed)
            assertTrue(!nonceFile.exists())
        }

    @Test
    fun `stop requests helper shutdown before destroying launcher process`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess()
            lateinit var nonceFile: File
            val shutdownRequests = mutableListOf<Pair<String, String?>>()
            var rootTerminated = false
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, file ->
                        nonceFile = file
                        listOf(RootHelperLaunchAttempt("test root helper launcher") { fakeProcess })
                    },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { socketPath, noncePath -> shutdownRequests += socketPath to noncePath },
                    rootProcessTerminator = { rootTerminated = true },
                )

            val result = requireNotNull(manager.start(context))
            manager.stop()

            assertEquals(listOf(result to nonceFile.absolutePath), shutdownRequests)
            assertTrue(fakeProcess.destroyed)
            assertTrue(rootTerminated)
        }

    @Test
    fun `syncRootMode disabled stops live helper and degrades to null socket`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val fakeProcess = RecordingProcess()
            var shutdownRequests = 0
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, _ ->
                        listOf(RootHelperLaunchAttempt("test root helper launcher") { fakeProcess })
                    },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> shutdownRequests += 1 },
                    rootProcessTerminator = {},
                )

            assertNotNull(manager.syncRootMode(context, RootSettingsSection(rootModeEnabled = true)))
            val disabledResult = manager.syncRootMode(context, RootSettingsSection(rootModeEnabled = false))

            assertNull(disabledResult)
            assertNull(manager.socketPath)
            assertTrue(fakeProcess.destroyed)
            assertEquals(1, shutdownRequests)
        }

    @Test
    fun `start drains helper stdout and stderr so a chatty helper never blocks on a full pipe`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val chattyProcess = ChattyProcess(bytesPerStream = ChattyBytesPerStream)
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, _ ->
                        listOf(
                            RootHelperLaunchAttempt("chatty root helper") {
                                chattyProcess.also { it.startLogging() }
                            },
                        )
                    },
                    // The helper only serves IPC once its log writes return; a full pipe stalls it here.
                    readinessProbe = { _, _, _ -> chattyProcess.awaitOutputWritten(ChattyTimeoutMs) },
                    rootProcessTerminator = {},
                )

            val result = manager.start(context)

            assertEquals(File(context.filesDir, "root_helper.sock").absolutePath, result)
            assertTrue(chattyProcess.awaitOutputWritten(0L))
            manager.stop()
        }

    @Test
    fun `stop ends helper output drain threads and restarts do not leak them`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fakeBinary = File(context.filesDir, "fake-root-helper").apply { writeText("bin") }
            val processes = mutableListOf<ChattyProcess>()
            val manager =
                RootHelperManager(
                    binaryExtractor = { fakeBinary },
                    processLaunchAttempts = { _, _, _ ->
                        listOf(
                            RootHelperLaunchAttempt("chatty root helper") {
                                ChattyProcess(bytesPerStream = ChattyBytesPerStream).also {
                                    processes += it
                                    it.startLogging()
                                }
                            },
                        )
                    },
                    readinessProbe = { _, _, _ -> processes.last().awaitOutputWritten(ChattyTimeoutMs) },
                    rootProcessTerminator = {},
                )

            repeat(2) {
                assertNotNull(manager.start(context))
                manager.stop()
            }

            assertEquals(2, processes.size)
            assertTrue(processes.all { it.destroyed })
            assertTrue(
                "root helper output drain threads outlived the helper",
                awaitNoLiveThreads(DrainThreadNamePrefix, ChattyTimeoutMs),
            )
        }
}

private const val ChattyPipeCapacity = 64 * 1024
private const val ChattyBytesPerStream = 4 * ChattyPipeCapacity
private const val ChattyTimeoutMs = 5_000L
private const val ThreadPollIntervalMs = 20L
private const val DrainThreadNamePrefix = "ripdpi-root-helper-output"

private fun awaitNoLiveThreads(
    namePrefix: String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith(namePrefix) }) return true
        Thread.sleep(ThreadPollIntervalMs)
    }
    return Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith(namePrefix) }
}

/**
 * Models the real helper: tracing writes to a kernel pipe of [ChattyPipeCapacity] bytes and a
 * write blocks once the pipe is full and nobody reads it.
 */
private class ChattyProcess(
    private val bytesPerStream: Int,
) : Process() {
    private val stdoutSink = PipedOutputStream()
    private val stderrSink = PipedOutputStream()
    private val stdout = PipedInputStream(stdoutSink, ChattyPipeCapacity)
    private val stderr = PipedInputStream(stderrSink, ChattyPipeCapacity)
    private val outputWritten = CountDownLatch(2)

    @Volatile
    var destroyed: Boolean = false
        private set

    fun startLogging() {
        val line = "2026-10-13T00:00:00Z  INFO ripdpi_root_helper: received command command=v3/nfqws2_status\n"
        val bytes = line.toByteArray(Charsets.US_ASCII)
        listOf(stdoutSink, stderrSink).forEach { sink ->
            thread(name = "fake-root-helper-writer", isDaemon = true) {
                runCatching {
                    var written = 0
                    while (written < bytesPerStream) {
                        sink.write(bytes)
                        written += bytes.size
                    }
                    sink.flush()
                }.onSuccess { outputWritten.countDown() }
            }
        }
    }

    fun awaitOutputWritten(timeoutMs: Long): Boolean = outputWritten.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    override fun exitValue(): Int {
        if (!destroyed) throw IllegalThreadStateException("process is running")
        return 0
    }

    override fun destroy() {
        destroyed = true
        // Process exit closes the helper's write ends of both pipes.
        runCatching { stdoutSink.close() }
        runCatching { stderrSink.close() }
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}

private fun assertValidNonceFile(nonceFile: File) {
    assertTrue(nonceFile.exists())
    val nonce = nonceFile.readText(Charsets.US_ASCII)
    assertTrue(nonce.length >= 32)
    assertTrue(nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
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
