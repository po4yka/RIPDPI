package com.poyka.ripdpi.services

import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootHelperEndpointCleanupTest {
    @Test
    fun `start authenticates its predecessor before replacing the nonce`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val socket = File(context.filesDir, "root_helper.sock").apply { writeText("owned endpoint") }
            val nonceFile = File(context.filesDir, "root_helper.sock.nonce").apply { writeText(PreviousNonce) }
            val foreignNonce = File(context.filesDir, "other-helper.nonce").apply { writeText("foreign owner") }
            var observedNonce: String? = null
            val process = EndpointTestProcess()
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { ownedSocket, ownedNonce ->
                        assertEquals(socket, ownedSocket)
                        assertEquals(nonceFile, ownedNonce)
                        if (ownedSocket.exists()) {
                            observedNonce = ownedNonce.readText()
                            ownedSocket.delete()
                        }
                    },
                )
            try {
                assertNotNull(manager.start(context))
                assertEquals(PreviousNonce, observedNonce)
                assertTrue(nonceFile.readText() != PreviousNonce)
                assertEquals("foreign owner", foreignNonce.readText())
            } finally {
                manager.stop()
                foreignNonce.delete()
            }
        }

    @Test
    fun `uncertain predecessor retains its endpoint and rejects launch until retry`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val socket = File(context.filesDir, "root_helper.sock").apply { writeText("owned endpoint") }
            val nonceFile = File(context.filesDir, "root_helper.sock.nonce").apply { writeText(PreviousNonce) }
            var cleanupPending = true
            var launched = false
            val process = EndpointTestProcess()
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ ->
                        launched = true
                        listOf(RootHelperLaunchAttempt("test") { process })
                    },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { ownedSocket, _ ->
                        if (cleanupPending) throw RuntimeCleanupPendingException(IOException("owner is unresponsive"))
                        ownedSocket.delete()
                    },
                )
            val failure = runCatching { manager.start(context) }.exceptionOrNull()
            assertTrue(failure is RuntimeCleanupPendingException)
            assertFalse(launched)
            assertTrue(socket.exists())
            assertEquals(PreviousNonce, nonceFile.readText())
            assertThrows(RuntimeCleanupPendingException::class.java) { manager.stop() }
            assertEquals(PreviousNonce, nonceFile.readText())

            cleanupPending = false
            try {
                assertNotNull(manager.ensureStarted(context))
                assertTrue(launched)
            } finally {
                manager.stop()
            }
        }

    @Test
    fun `failed endpoint cleanup during stop keeps the nonce for a later stop`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = EndpointTestProcess()
            var rejectCleanup = false
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { _, _ ->
                        if (rejectCleanup) throw RuntimeCleanupPendingException(IOException("cleanup pending"))
                    },
                )
            val socketPath = manager.start(context)
            val nonceFile = File(context.filesDir, "root_helper.sock.nonce")
            val nonce = nonceFile.readText()
            rejectCleanup = true

            assertThrows(RuntimeCleanupPendingException::class.java) { manager.stop() }
            assertEquals(socketPath, manager.socketPath)
            assertEquals(nonce, nonceFile.readText())

            rejectCleanup = false
            manager.stop()
            assertNull(manager.socketPath)
            assertFalse(nonceFile.exists())
        }

    @Test
    fun `startup cleanup debt retains a live helper without an app nfqws session`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = EndpointTestProcess()
            var cleanupPending = false
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ ->
                        if (cleanupPending) throw IOException("nfqws2 cleanup is pending; helper remains active")
                    },
                    endpointCleanup = { _, _ -> },
                )
            val socketPath = manager.start(context)
            val nonceFile = File(context.filesDir, "root_helper.sock.nonce")
            val nonce = nonceFile.readText()
            cleanupPending = true

            assertThrows(RuntimeCleanupPendingException::class.java) { manager.stop() }
            assertTrue(manager.isRunning())
            assertEquals(socketPath, manager.socketPath)
            assertEquals(nonce, nonceFile.readText())

            cleanupPending = false
            manager.stop()
            assertFalse(manager.isRunning())
            assertFalse(nonceFile.exists())
        }

    @Test
    fun `an unready launcher that cannot exit retains ownership and blocks another attempt`() =
        runTest {
            for (destroyThrows in listOf(false, true)) {
                val context = RuntimeEnvironment.getApplication()
                val process =
                    EndpointTestProcess().apply {
                        refuseDestroy = destroyThrows
                        keepAlive = !destroyThrows
                    }
                var launches = 0
                val manager =
                    RootHelperManager(
                        binaryExtractor = { File(context.filesDir, "helper") },
                        processLaunchAttempts = { _, _, _ ->
                            listOf(
                                RootHelperLaunchAttempt("first") {
                                    launches++
                                    process
                                },
                                RootHelperLaunchAttempt("second") {
                                    launches++
                                    EndpointTestProcess()
                                },
                            )
                        },
                        readinessProbe = { _, _, _ -> false },
                        shutdownRequester = { _, _ -> },
                        endpointCleanup = { _, _ -> },
                    )
                val failure = runCatching { manager.start(context) }.exceptionOrNull()
                val nonceFile = File(context.filesDir, "root_helper.sock.nonce")
                assertTrue(failure is RuntimeCleanupPendingException)
                assertEquals(1, launches)
                assertTrue(manager.isRunning())
                assertTrue(nonceFile.exists())

                process.refuseDestroy = false
                process.keepAlive = false
                manager.stop()
                assertFalse(manager.isRunning())
                assertFalse(nonceFile.exists())
            }
        }

    @Test
    fun `authenticated shutdown waits for its endpoint to close`() {
        val socket = File.createTempFile("root-endpoint-", ".sock")
        val nonce = File.createTempFile("root-endpoint-", ".nonce").apply { writeText(PreviousNonce) }
        val requested = CountDownLatch(1)
        val owner =
            thread(name = "root-endpoint-test-owner", isDaemon = true) {
                if (requested.await(5, TimeUnit.SECONDS)) {
                    Thread.sleep(50)
                    socket.delete()
                }
            }
        try {
            shutdownRootHelperEndpoint(
                socket,
                nonce,
                requestShutdown = { socketPath, noncePath, _ ->
                    assertEquals(socket.absolutePath, socketPath)
                    assertEquals(PreviousNonce, File(requireNotNull(noncePath)).readText())
                    requested.countDown()
                },
                endpointIsClosed = { file, _ -> !file.exists() },
            )
            assertFalse(socket.exists())
            assertEquals(PreviousNonce, nonce.readText())
        } finally {
            requested.countDown()
            owner.join(1_000)
            assertFalse(owner.isAlive)
            socket.delete()
            nonce.delete()
        }
    }

    @Test
    fun `rejected or unfinished shutdown stays pending without deleting private files`() {
        val socket = File.createTempFile("root-endpoint-", ".sock")
        val nonce = File.createTempFile("root-endpoint-", ".nonce").apply { writeText(PreviousNonce) }
        try {
            for (rejectRequest in listOf(false, true)) {
                assertThrows(RuntimeCleanupPendingException::class.java) {
                    shutdownRootHelperEndpoint(
                        socket,
                        nonce,
                        requestShutdown = { _, _, _ -> if (rejectRequest) throw IOException("nonce rejected") },
                        endpointIsClosed = { _, _ -> false },
                        timeoutMillis = 0,
                    )
                }
                assertTrue(socket.exists())
                assertEquals(PreviousNonce, nonce.readText())
            }
            shutdownRootHelperEndpoint(
                socket,
                nonce,
                requestShutdown = { _, _, _ -> throw IOException("connection refused") },
                endpointIsClosed = { _, _ -> true },
            )
        } finally {
            socket.delete()
            nonce.delete()
        }
    }

    @Test
    fun `only a refused connection or missing endpoint proves absence`() {
        for (errno in listOf(OsConstants.ECONNREFUSED, OsConstants.ENOENT)) {
            assertTrue(rootHelperConnectRefused(IOException("connect", ErrnoException("connect", errno))))
        }
        for (errno in listOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EAGAIN, OsConstants.EMFILE)) {
            assertFalse(rootHelperConnectRefused(IOException("connect", ErrnoException("connect", errno))))
        }
        assertFalse(rootHelperConnectRefused(IOException("unknown socket error")))
        assertFalse(rootHelperConnectRefused(IOException()))
    }
}

private const val PreviousNonce = "abcdefghijklmnopqrstuvwxyzABCDEF"

private class EndpointTestProcess : Process() {
    private var alive = true
    var refuseDestroy = false
    var keepAlive = false

    override fun getOutputStream() = ByteArrayOutputStream()

    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

    override fun waitFor() = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ) = !alive

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("running")
        return 0
    }

    override fun destroy() {
        if (refuseDestroy) throw SecurityException("cannot stop launcher")
        if (!keepAlive) alive = false
    }

    override fun destroyForcibly(): Process {
        if (!keepAlive) alive = false
        return this
    }
}
