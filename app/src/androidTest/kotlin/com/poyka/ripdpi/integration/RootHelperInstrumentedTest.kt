package com.poyka.ripdpi.integration

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.services.RootHelperManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootHelperInstrumentedTest {
    private val suCommandCandidates = listOf("su", "/system/xbin/su", "/system/bin/su")
    private var manager: RootHelperManager? = null

    private companion object {
        private const val RootHelperSmokeArg = "ripdpi.rootHelperSmoke"
    }

    @After
    fun tearDown() {
        manager?.stop()
        manager = null
    }

    @Test
    fun rootHelperStartsPublishesSocketAndStopsOnRootedTarget() =
        runBlocking {
            assumeTrue(
                "Root helper smoke is opt-in; pass $RootHelperSmokeArg=true on a target with app-granting su",
                rootHelperSmokeEnabled(),
            )
            assumeTrue("Root helper smoke requires app-granting su", hasRootShell())
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val rootHelper = RootHelperManager()
            manager = rootHelper

            val socketPath = rootHelper.start(context)

            assertNotNull("Root helper did not publish a socket path", socketPath)
            val socketFile = File(requireNotNull(socketPath))
            assertTrue("Root helper socket does not exist at $socketPath", socketFile.exists())
            assertTrue("Root helper process is not running", rootHelper.isRunning())
            assertSocketConnects(socketPath)

            rootHelper.stop()

            assertNull("Root helper socket path should be cleared after stop", rootHelper.socketPath)
            assertFalse("Root helper process should not remain running after stop", rootHelper.isRunning())
            assertFalse("Root helper socket should be removed after stop", socketFile.exists())
            manager = null
        }

    private fun rootHelperSmokeEnabled(): Boolean =
        InstrumentationRegistry
            .getArguments()
            .getString(RootHelperSmokeArg)
            ?.equals("true", ignoreCase = true) == true

    private fun hasRootShell(): Boolean =
        suCommandCandidates.any { suCommand ->
            rootCommandSucceeds(arrayOf(suCommand, "0", "id")) ||
                rootCommandSucceeds(arrayOf(suCommand, "-c", "id"))
        }

    private fun rootCommandSucceeds(command: Array<String>): Boolean =
        runCatching {
            val process = Runtime.getRuntime().exec(command)
            try {
                val exited = process.waitFor(2, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    return@runCatching false
                }
                process.exitValue() == 0 &&
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .contains("uid=0")
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }.getOrDefault(false)

    private fun assertSocketConnects(socketPath: String) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }
}
