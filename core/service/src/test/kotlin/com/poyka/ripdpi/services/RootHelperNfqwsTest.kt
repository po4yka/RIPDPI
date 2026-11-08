package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootHelperNfqwsTest {
    @Test
    fun `failed shutdown keeps active or uncertain backend ownership until retry succeeds`() =
        runTest {
            for (startFails in listOf(false, true)) {
                val context = RuntimeEnvironment.getApplication()
                val process = NfqwsTestProcess()
                var rejectShutdown = true
                var globalKills = 0
                val manager =
                    RootHelperManager(
                        binaryExtractor = { File(context.filesDir, "helper") },
                        processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                        readinessProbe = { _, _, _ -> true },
                        shutdownRequester = { _, _ -> if (rejectShutdown) throw IOException("cleanup pending") },
                        endpointCleanup = { _, _ -> globalKills++ },
                        nfqwsExtractor = { context.filesDir },
                        nfqwsRequester = { _, _, _, _ ->
                            if (startFails) throw IOException("lost startup response")
                            buildJsonObject { put("running", true) }
                        },
                        nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val socket = manager.start(context)
                val activation = runCatching { manager.syncNfqws(context, settings()) }
                assertEquals(startFails, activation.isFailure)
                assertThrows(RuntimeCleanupPendingException::class.java) { manager.stop() }
                manager.stopOnDestroy(Mode.VPN)
                assertEquals(socket, manager.socketPath)
                assertTrue(manager.isRunning())
                assertFalse(manager.nfqwsActive)
                assertTrue(File(context.filesDir, "root_helper.sock.nonce").exists())
                assertEquals("only the predecessor reap before the first launch", 1, globalKills)

                rejectShutdown = false
                manager.stop()
                assertNull(manager.socketPath)
                assertFalse(manager.isRunning())
                assertFalse(File(context.filesDir, "root_helper.sock.nonce").exists())
                assertEquals("only the predecessor reap before the first launch", 1, globalKills)
            }
        }

    @Test
    fun `stop after the helper died clears backend ownership instead of wedging`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = NfqwsTestProcess()
            var globalKills = 0
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> throw IOException("connection refused") },
                    endpointCleanup = { _, _ -> globalKills++ },
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, _, _ -> buildJsonObject { put("running", true) } },
                    nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.start(context)
            manager.syncNfqws(context, settings())
            process.destroy()

            manager.stop()

            assertNull(manager.socketPath)
            assertFalse(manager.nfqwsActive)
            assertFalse(File(context.filesDir, "root_helper.sock.nonce").exists())
            assertEquals("predecessor reap plus the dead helper's detached processes", 2, globalKills)
        }

    @Test
    fun `same backend is reused and unexpected exit reaches session failure callback`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = NfqwsTestProcess()
            val commands = mutableListOf<String>()
            var running = true
            var failure: IOException? = null
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { _, _ -> },
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, command, params ->
                        commands += command
                        if (command == "v3/start_nfqws2") {
                            assertNotNull(params["owner_pid"])
                            assertTrue(params["args"].toString().contains("zapret-tests.lua"))
                        }
                        buildJsonObject { put("running", running) }
                    },
                    nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.start(context)
            manager.syncNfqws(context, settings())
            manager.syncNfqws(context, settings())
            assertEquals(listOf("v3/start_nfqws2", "v3/nfqws2_status"), commands)
            assertTrue(manager.nfqwsActive)
            manager.monitorNfqws(backgroundScope) { failure = it }
            runCurrent()
            running = false
            advanceTimeBy(1000)
            runCurrent()
            assertNotNull(failure)
            assertFalse(manager.nfqwsActive)
            manager.stop()
        }

    @Test
    fun `a helper that exits before monitor registration reports one failure`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
            val failures = mutableListOf<IOException>()
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { _, _ -> },
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, _, _ -> buildJsonObject { put("running", true) } },
                    nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                manager.start(context)
                manager.syncNfqws(context, settings())
                process.destroy()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))

                manager.monitorNfqws(backgroundScope) { failures += it }
                runCurrent()
                advanceTimeBy(2_000)
                runCurrent()

                assertEquals(1, failures.size)
                assertFalse(manager.nfqwsActive)
            } finally {
                manager.stop()
                process.destroyForcibly()
            }
        }

    @Test
    fun `a stopped session does not report a queued helper failure`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = NfqwsTestProcess()
            val failures = mutableListOf<IOException>()
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { _, _ -> },
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, _, _ -> buildJsonObject { put("running", true) } },
                    nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.start(context)
            manager.syncNfqws(context, settings())
            process.destroy()

            manager.monitorNfqws(backgroundScope) { failures += it }
            manager.stop()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()

            assertTrue(failures.isEmpty())
        }

    @Test
    fun `planned backend stop cancels monitoring and retains ownership on failure`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val process = NfqwsTestProcess()
            var rejectStop = true
            var failure: IOException? = null
            val manager =
                RootHelperManager(
                    binaryExtractor = { File(context.filesDir, "helper") },
                    processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                    readinessProbe = { _, _, _ -> true },
                    shutdownRequester = { _, _ -> },
                    endpointCleanup = { _, _ -> },
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, command, _ ->
                        if (command == "v3/stop_nfqws2" && rejectStop) throw IOException("rule removal pending")
                        buildJsonObject {
                            put("running", command == "v3/start_nfqws2")
                            put("cleanup_pending", false)
                        }
                    },
                    nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.start(context)
            manager.syncNfqws(context, settings())
            manager.monitorNfqws(backgroundScope) { failure = it }
            runCurrent()
            assertTrue(runCatching { manager.syncNfqws(context, AppSettings.getDefaultInstance()) }.isFailure)
            advanceTimeBy(1000)
            runCurrent()
            assertNull(failure)
            assertFalse(manager.nfqwsActive)
            assertNotNull(manager.socketPath)
            rejectStop = false
            manager.syncNfqws(context, AppSettings.getDefaultInstance())
            manager.stop()
        }

    @Test
    fun `ordinary root policy rejects startup cleanup debt without a prior nfqws session`() =
        runTest {
            val uncleanResponses =
                listOf(
                    null,
                    """{"running":true,"cleanup_pending":false}""",
                    """{"running":false,"cleanup_pending":true}""",
                    """{"running":false}""",
                    """{"cleanup_pending":false}""",
                    """{"running":"false","cleanup_pending":false}""",
                    """{"running":false,"cleanup_pending":"false"}""",
                    """{"running":null,"cleanup_pending":false}""",
                    """{"running":false,"cleanup_pending":null}""",
                    """{"running":{},"cleanup_pending":false}""",
                    """{"running":false,"cleanup_pending":{}}""",
                )
            for (uncleanResponse in uncleanResponses) {
                val context = RuntimeEnvironment.getApplication()
                val process = NfqwsTestProcess()
                var cleanupPending = true
                val manager =
                    RootHelperManager(
                        binaryExtractor = { File(context.filesDir, "helper") },
                        processLaunchAttempts = { _, _, _ -> listOf(RootHelperLaunchAttempt("test") { process }) },
                        readinessProbe = { _, _, _ -> true },
                        shutdownRequester = { _, _ -> },
                        endpointCleanup = { _, _ -> },
                        nfqwsRequester = { _, _, _, _ ->
                            if (cleanupPending) {
                                if (uncleanResponse == null) throw IOException("startup firewall cleanup is pending")
                                Json.parseToJsonElement(uncleanResponse).jsonObject
                            } else {
                                buildJsonObject {
                                    put("running", false)
                                    put("cleanup_pending", false)
                                }
                            }
                        },
                        nfqwsDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val ordinarySettings = AppSettings.newBuilder().setRootModeEnabled(true).build()
                val socketPath = manager.start(context)
                val nonceFile = File(context.filesDir, "root_helper.sock.nonce")
                val nonce = nonceFile.readText()
                try {
                    val failure = runCatching { manager.syncNfqws(context, ordinarySettings) }.exceptionOrNull()
                    if (uncleanResponse == null) {
                        assertTrue(failure is IOException)
                    } else {
                        assertTrue(failure is RuntimeCleanupPendingException)
                    }
                    assertEquals(socketPath, manager.socketPath)
                    assertEquals(nonce, nonceFile.readText())
                    assertTrue(manager.isRunning())
                    assertFalse(manager.nfqwsActive)

                    cleanupPending = false
                    manager.syncNfqws(context, ordinarySettings)
                    assertTrue(manager.isRunning())
                    assertFalse(manager.nfqwsActive)
                } finally {
                    cleanupPending = false
                    manager.stop()
                }
            }
        }

    @Test
    fun `ordinary settings without a helper do not require root or helper IPC`() =
        runTest {
            val manager =
                RootHelperManager(
                    binaryExtractor = { throw AssertionError("ordinary settings must not extract a helper") },
                    processLaunchAttempts = { _, _, _ -> throw AssertionError("ordinary settings must not launch su") },
                    readinessProbe = { _, _, _ -> throw AssertionError("ordinary settings must not probe a helper") },
                    nfqwsRequester = { _, _, _, _ -> throw AssertionError("no helper endpoint is active") },
                )

            manager.syncNfqws(RuntimeEnvironment.getApplication(), AppSettings.getDefaultInstance())

            assertNull(manager.socketPath)
            assertFalse(manager.isRunning())
            assertFalse(manager.nfqwsActive)
        }

    private fun settings(): AppSettings =
        AppSettings
            .newBuilder()
            .setRootModeEnabled(true)
            .setEnableCmdSettings(true)
            .setCmdArgs("nfqws2")
            .build()
}

private class NfqwsTestProcess : Process() {
    private var stopped = false

    override fun getOutputStream() = ByteArrayOutputStream()

    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

    override fun waitFor() = 0

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ) = true

    override fun exitValue(): Int {
        if (!stopped) throw IllegalThreadStateException("running")
        return 0
    }

    override fun destroy() {
        stopped = true
    }
}
