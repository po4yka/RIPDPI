package com.poyka.ripdpi.services

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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
                        rootProcessTerminator = { globalKills++ },
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
                manager.stopOnDestroy()
                assertEquals(socket, manager.socketPath)
                assertTrue(manager.isRunning())
                assertFalse(manager.nfqwsActive)
                assertTrue(File(context.filesDir, "root_helper.sock.nonce").exists())
                assertEquals(0, globalKills)

                rejectShutdown = false
                manager.stop()
                assertNull(manager.socketPath)
                assertFalse(manager.isRunning())
                assertFalse(File(context.filesDir, "root_helper.sock.nonce").exists())
                assertEquals(0, globalKills)
            }
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
                    rootProcessTerminator = {},
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
                    rootProcessTerminator = {},
                    nfqwsExtractor = { context.filesDir },
                    nfqwsRequester = { _, _, command, _ ->
                        if (command == "v3/stop_nfqws2" && rejectStop) throw IOException("rule removal pending")
                        buildJsonObject { put("running", command == "v3/start_nfqws2") }
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
