package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudflarePublishProcessTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `helper environment scrub removes Cloudflare behavior overrides`() {
        val environment =
            mutableMapOf(
                "TUNNEL_TOKEN" to "inherited-secret",
                "TUNNEL_POST_QUANTUM" to "true",
                "NO_TLS_VERIFY" to "true",
                "STDIN_CONTROL" to "true",
                "HOME" to "/inherited/home",
                "PATH" to "/usr/bin",
            )

        environment.scrubCloudflareHelperEnvironment()

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun `origin launch keeps UUID and xHTTP path off argv and sends framed stdin config`() {
        val process = RecordingProcess()
        var command: List<String>? = null
        val supervisor =
            supervisor(
                process = process,
                outputReader = noOpOutputReader(),
                onStart = { command = it.command() },
            )
        val config =
            publishConfig()
                .copy(
                    xhttpPath = "/private-xhttp-path",
                    vlessUuid = "550e8400-e29b-41d4-a716-446655440000",
                )

        supervisor.launchOriginProcess(
            config = config,
            originSpec = CloudflarePublishConfigParser().parseLocalOriginSpec("http://127.0.0.1:43128"),
            stateDir = File("/private/cloudflare-state"),
            readySignal = CompletableDeferred(),
            onError = { _, _ -> },
        )

        assertEquals(listOf("/tmp/ripdpi-cloudflare-origin", "--config-stdin"), command)
        val argv = command.orEmpty().joinToString(" ")
        assertFalse(argv.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(argv.contains("private-xhttp-path"))
        assertFalse(argv.contains("private/cloudflare-state"))
        assertTrue(process.stdin.closed)

        DataInputStream(ByteArrayInputStream(process.stdin.toByteArray())).use { input ->
            val magic = ByteArray(16).also(input::readFully)
            assertEquals("RIPDPI-CF-ORIGIN", magic.decodeToString())
            assertEquals(1, input.readUnsignedByte())
            assertEquals("127.0.0.1:43128", input.readFrameField())
            assertEquals("/private-xhttp-path", input.readFrameField())
            assertEquals("550e8400-e29b-41d4-a716-446655440000", input.readFrameField())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `origin launch kills child when post-spawn output setup fails`() {
        val process = RecordingProcess()
        val supervisor =
            supervisor(
                process = process,
                outputReader = throwingOutputReader(),
            )

        val error =
            runCatching {
                supervisor.launchOriginProcess(
                    config = publishConfig(),
                    originSpec = CloudflarePublishConfigParser().parseLocalOriginSpec("http://127.0.0.1:43128"),
                    stateDir = File("/private/cloudflare-state"),
                    readySignal = CompletableDeferred(),
                    onError = { _, _ -> },
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue("partially initialized origin process was not terminated", process.destroyed)
        assertTrue(process.stdin.closed)
    }

    @Test
    fun `post-spawn failure retains unreaped child for supervisor cleanup retry`() {
        val process = RecordingProcess(waitForExit = false, ignoreTermination = true)
        val supervisor = supervisor(process = process, outputReader = throwingOutputReader())

        val error =
            runCatching {
                supervisor.launchOriginProcess(
                    config = publishConfig(),
                    originSpec = CloudflarePublishConfigParser().parseLocalOriginSpec("http://127.0.0.1:43128"),
                    stateDir = File("/private/cloudflare-state"),
                    readySignal = CompletableDeferred(),
                    onError = { _, _ -> },
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.suppressed?.isNotEmpty() == true)
        process.allowTermination()
        assertNull(runCatching { supervisor.stopOutstandingProcesses() }.exceptionOrNull())
    }

    @Test
    fun `cloudflared launch kills child when post-spawn output setup fails`() {
        val process = RecordingProcess()
        val stateDir = Files.createTempDirectory("ripdpi-cloudflared-rollback-").toFile()
        try {
            val supervisor =
                supervisor(
                    process = process,
                    outputReader = throwingOutputReader(),
                )

            val error =
                runCatching {
                    supervisor.launchCloudflaredProcess(
                        config = publishConfig(),
                        originSpec = CloudflarePublishConfigParser().parseLocalOriginSpec("http://127.0.0.1:43128"),
                        metricsAddress = "127.0.0.1:43129",
                        stateDir = stateDir,
                        lastErrorSink = { _, _ -> },
                        onRegisteredTunnelConnection = {},
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue("partially initialized cloudflared process was not terminated", process.destroyed)
        } finally {
            stateDir.deleteRecursively()
        }
    }

    @Test
    fun `version probe concurrently drains output and forcibly reaps a timed out child`() {
        val process = RecordingProcess(waitForExit = false)
        val probe =
            object : CloudflarePublishVersionProbe(timeoutMillis = 25L) {
                override fun startProcess(processBuilder: ProcessBuilder): Process = process
            }

        val version = probe.probe(File("/tmp/ripdpi-cloudflared"), listOf("--version"))

        assertNull(version)
        assertTrue("timed out version process was not forcibly terminated", process.forciblyDestroyed)
        assertTrue("timed out version process was not reaped", process.waitCalls >= 2)
    }

    @Test
    fun `version probe fails closed when a timed out child cannot be reaped`() {
        val process = RecordingProcess(waitForExit = false, ignoreTermination = true)
        val probe =
            object : CloudflarePublishVersionProbe(timeoutMillis = 25L) {
                override fun startProcess(processBuilder: ProcessBuilder): Process = process
            }

        val error = runCatching { probe.probe(File("/tmp/ripdpi-cloudflared"), listOf("--version")) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(process.forciblyDestroyed)
    }

    @Test
    fun `version probe deadline includes stdout held open after parent exit`() {
        val process = RecordingProcess(waitForExit = true, holdStdoutOpen = true)
        val probe =
            object : CloudflarePublishVersionProbe(timeoutMillis = 50L) {
                override fun startProcess(processBuilder: ProcessBuilder): Process = process
            }
        var version: String? = "not-run"

        val elapsedMillis =
            measureTimeMillis {
                version = probe.probe(File("/tmp/ripdpi-cloudflared"), listOf("--version"))
            }

        assertNull(version)
        assertTrue("stdout capture exceeded probe deadline: ${elapsedMillis}ms", elapsedMillis < 500)
    }

    @Test
    fun `helper stop fails closed when forced termination cannot reap child`() {
        val process = RecordingProcess(waitForExit = false, ignoreTermination = true)
        val supervisor = supervisor(process = process, outputReader = noOpOutputReader())

        val error =
            runCatching {
                supervisor.stop(ManagedCloudflareProcess(process, null, Thread { }))
            }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(process.forciblyDestroyed)
    }

    @Test
    fun `helper output redacts token UUID and state paths before error projection`() {
        val process =
            RecordingProcess(
                stdout =
                    "ERR token=fixture-token uuid=550e8400-e29b-41d4-a716-446655440000 " +
                        "path=/private/cloudflare-state\n",
            )
        val observed = mutableListOf<String>()

        val reader =
            CloudflarePublishProcessOutputReader().start(
                process = process,
                redactedValues =
                    listOf(
                        "fixture-token",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "/private/cloudflare-state",
                    ),
                onLine = observed::add,
            )
        reader.join()

        assertEquals(listOf("ERR token=<redacted> uuid=<redacted> path=<redacted>"), observed)
    }

    private fun publishConfig(): ResolvedRipDpiRelayConfig =
        sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel)
            .copy(
                cloudflarePublishLocalOriginUrl = "http://127.0.0.1:43128",
                cloudflareTunnelToken = "fixture-tunnel-token",
                cloudflareTunnelCredentialsJson = null,
                xhttpPath = "/xhttp",
                vlessUuid = "550e8400-e29b-41d4-a716-446655440000",
            )

    private fun supervisor(
        process: RecordingProcess,
        outputReader: CloudflarePublishProcessOutputReader,
        onStart: (ProcessBuilder) -> Unit = {},
    ): CloudflarePublishProcessSupervisor =
        object : CloudflarePublishProcessSupervisor(
            binaryExtractor =
                object : CloudflarePublishBinaryExtractor(context) {
                    override fun extract(
                        binaryName: String,
                        tunnelMode: String,
                    ): File = File("/tmp/$binaryName")
                },
            versionProbe =
                object : CloudflarePublishVersionProbe() {
                    override fun probe(
                        binary: File,
                        versionArguments: List<String>,
                    ): String? = null
                },
            launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
            outputReader = outputReader,
            protectPathProvider = ActiveProtectSocketPathProvider(),
        ) {
            override fun startProcess(processBuilder: ProcessBuilder): Process {
                onStart(processBuilder)
                return process
            }
        }

    private fun noOpOutputReader(): CloudflarePublishProcessOutputReader =
        object : CloudflarePublishProcessOutputReader() {
            override fun start(
                process: Process,
                redactedValues: List<String>,
                onLine: (String) -> Unit,
            ): Thread = Thread {}
        }

    private fun throwingOutputReader(): CloudflarePublishProcessOutputReader =
        object : CloudflarePublishProcessOutputReader() {
            override fun start(
                process: Process,
                redactedValues: List<String>,
                onLine: (String) -> Unit,
            ): Thread = error("output reader setup failed")
        }

    private fun DataInputStream.readFrameField(): String {
        val bytes = ByteArray(readUnsignedShort()).also(::readFully)
        return bytes.decodeToString()
    }

    private class RecordingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class RecordingProcess(
        private val waitForExit: Boolean = true,
        private var ignoreTermination: Boolean = false,
        private val holdStdoutOpen: Boolean = false,
        stdout: String = "",
    ) : Process() {
        val stdin = RecordingOutputStream()
        private val stdoutBytes = stdout.toByteArray()
        private var stdoutIndex = 0
        var destroyed = false
        var forciblyDestroyed = false
        var waitCalls = 0
        private var stdoutClosed = false
        private val stdoutStream =
            object : InputStream() {
                override fun read(): Int {
                    if (stdoutIndex < stdoutBytes.size) return stdoutBytes[stdoutIndex++].toInt() and 0xff
                    while (holdStdoutOpen && !stdoutClosed) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            return -1
                        }
                    }
                    return -1
                }

                override fun close() {
                    stdoutClosed = true
                }
            }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdoutStream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            waitCalls += 1
            return 0
        }

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            waitCalls += 1
            return waitForExit || (!ignoreTermination && (forciblyDestroyed || destroyed))
        }

        override fun exitValue(): Int =
            if (waitForExit || (!ignoreTermination && (forciblyDestroyed || destroyed))) {
                0
            } else {
                throw IllegalThreadStateException("running")
            }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            destroyed = true
            return this
        }

        fun allowTermination() {
            ignoreTermination = false
        }
    }
}
