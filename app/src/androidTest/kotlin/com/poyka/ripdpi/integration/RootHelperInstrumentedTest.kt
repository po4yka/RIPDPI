package com.poyka.ripdpi.integration

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.services.RootHelperManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

    private data class RootCommandProbe(
        val command: String,
        val success: Boolean,
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
        val stdout: String = "",
        val stderr: String = "",
        val error: String? = null,
    ) {
        fun summary(): String =
            buildString {
                append(command)
                when {
                    success -> append(" succeeded")
                    timedOut -> append(" timed out")
                    exitCode != null -> append(" exited ").append(exitCode)
                    error != null -> append(" failed: ").append(error)
                    else -> append(" failed")
                }
                appendOutput("stdout", stdout)
                appendOutput("stderr", stderr)
            }

        private fun StringBuilder.appendOutput(
            label: String,
            output: String,
        ) {
            if (output.isNotBlank()) {
                append(' ')
                append(label)
                append("='")
                append(compactOutput(output))
                append('\'')
            }
        }

        private fun compactOutput(
            output: String,
            maxLength: Int = 160,
        ): String {
            val compact =
                output
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
            return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
        }
    }

    private data class RootShellProbeResult(
        val available: Boolean,
        val attempts: List<RootCommandProbe>,
    ) {
        fun failureSummary(): String = attempts.joinToString(separator = "; ") { it.summary() }
    }

    private companion object {
        private const val RootHelperSmokeArg = "ripdpi.rootHelperSmoke"
        private const val RootHelperProtocolVersion = 3
        private const val ProbeCapabilitiesCommand = "v3/probe_capabilities"
        private const val RawIpv4Capability = "raw_ipv4"
        private const val RawIpv6Capability = "raw_ipv6"
        private const val TcpRepairCapability = "tcp_repair"
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
            val rootProbe = probeRootShell()
            assumeTrue(
                "Root helper smoke requires app-granting su; attempts: ${rootProbe.failureSummary()}",
                rootProbe.available,
            )
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val rootHelper = RootHelperManager()
            manager = rootHelper

            val socketPath = rootHelper.start(context)

            assertNotNull("Root helper did not publish a socket path", socketPath)
            val socketFile = File(requireNotNull(socketPath))
            assertTrue("Root helper socket does not exist at $socketPath", socketFile.exists())
            assertTrue("Root helper process is not running", rootHelper.isRunning())
            assertSocketConnects(socketPath)
            assertCapabilityProbeSucceeds(socketPath)

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

    private fun probeRootShell(): RootShellProbeResult {
        val attempts =
            suCommandCandidates
                .flatMap { suCommand ->
                    listOf(
                        arrayOf(suCommand, "0", "id"),
                        arrayOf(suCommand, "-c", "id"),
                    )
                }.map(::runRootCommandProbe)
        return RootShellProbeResult(
            available = attempts.any { it.success },
            attempts = attempts,
        )
    }

    private fun runRootCommandProbe(command: Array<String>): RootCommandProbe =
        runCatching {
            val process = Runtime.getRuntime().exec(command)
            try {
                val exited = process.waitFor(2, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    return@runCatching RootCommandProbe(
                        command = command.joinToString(" "),
                        success = false,
                        timedOut = true,
                    )
                }
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                RootCommandProbe(
                    command = command.joinToString(" "),
                    success = process.exitValue() == 0 && stdout.contains("uid=0"),
                    exitCode = process.exitValue(),
                    stdout = stdout,
                    stderr = stderr,
                )
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }.getOrElse { error ->
            RootCommandProbe(
                command = command.joinToString(" "),
                success = false,
                error = error.toDiagnosticString(),
            )
        }

    private fun Throwable.toDiagnosticString(): String = "${this::class.simpleName}: ${message.orEmpty().singleLine()}"

    private fun String.singleLine(maxLength: Int = 160): String {
        val compact =
            replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }

    private fun assertSocketConnects(socketPath: String) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }

    private fun assertCapabilityProbeSucceeds(socketPath: String) {
        val sessionNonce = File("$socketPath.nonce").readText().trim()
        assertTrue("Root helper session nonce should be valid", sessionNonce.length in 32..128)

        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val request =
                JSONObject()
                    .put("protocol_version", RootHelperProtocolVersion)
                    .put("command", ProbeCapabilitiesCommand)
                    .put("session_nonce", sessionNonce)
                    .toString()
            socket.outputStream.write("$request\n".toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()

            val responseLine = socket.inputStream.bufferedReader(Charsets.UTF_8).readLine()
            assertNotNull("Root helper did not return a capability probe response", responseLine)
            val response = JSONObject(requireNotNull(responseLine))
            assertTrue(response.optString("error"), response.optBoolean("ok"))
            val data = response.getJSONObject("data")
            assertBooleanCapability(data, RawIpv4Capability)
            assertBooleanCapability(data, RawIpv6Capability)
            assertBooleanCapability(data, TcpRepairCapability)
        }
    }

    private fun assertBooleanCapability(
        data: JSONObject,
        key: String,
    ) {
        assertTrue("Root helper capability '$key' is missing", data.has(key))
        assertTrue("Root helper capability '$key' is not boolean", data.get(key) is Boolean)
    }
}
