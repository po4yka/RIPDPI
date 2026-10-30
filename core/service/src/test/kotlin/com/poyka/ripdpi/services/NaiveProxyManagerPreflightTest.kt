package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.classifyFailureClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class NaiveProxyManagerPreflightTest {
    private fun naiveConfig() =
        sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
            localSocksUsername = "local-user",
            localSocksPassword = "local-pass", // Fixture credential.
            // A pin selects the Rust helper, whose preflight these tests cover.
            naiveServerPinSha256 = "a".repeat(64),
        )

    @Test
    fun `helper without local auth support is rejected before launch`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            NaiveProxyProbe(schemaVersion = 1, helperVersion = "0.1.0", features = emptyList()),
                        ),
                )
            val failure =
                runCatching {
                    NaiveProxyManager(
                        processPort,
                        processPort,
                        unusedUpstreamLauncher(processPort),
                    ).start(naiveConfig())
                }.exceptionOrNull()

            assertTrue(failure is NaiveProxyCompatibilityException)
            assertTrue(failure?.message.orEmpty().contains("local listener authentication"))
            assertEquals(0, processPort.mainLaunches)
        }

    @Test
    fun `configured outbound bind IP is rejected before launch`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            NaiveProxyProbe(1, "0.1.0", listOf("ready-signal", "local-listener-auth")),
                        ),
                )

            val failure =
                runCatching {
                    NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort))
                        .start(naiveConfig().copy(outboundBindIp = "192.0.2.10"))
                }.exceptionOrNull()

            assertTrue(failure is NaiveProxyCompatibilityException)
            assertTrue(failure?.message.orEmpty().contains("outbound bind IP"))
            assertEquals("config", processPort.lastFailureClass)
            assertEquals(emptyList<String>(), processPort.events)
            assertEquals(0, processPort.mainLaunches)
        }

    @Test
    fun `a pinned profile with HTTP3 or concurrency is rejected before launch`() =
        runTest {
            for (config in listOf(naiveConfig().copy(naiveQuic = true), naiveConfig().copy(naiveConcurrency = 2))) {
                val processPort =
                    FakeNaiveProxyProcessPort(
                        probeResult =
                            NaiveProxyPreflightResult.Probed(
                                NaiveProxyProbe(1, "0.1.0", listOf("ready-signal", "local-listener-auth")),
                            ),
                    )

                val failure =
                    runCatching {
                        NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort)).start(config)
                    }.exceptionOrNull()

                assertTrue(failure is NaiveProxyCompatibilityException)
                assertEquals("config", processPort.lastFailureClass)
                assertEquals(0, processPort.mainLaunches)
            }
        }

    @Test
    fun `schema mismatch is rejected before relay subprocess starts`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            probe =
                                NaiveProxyProbe(
                                    schemaVersion = 2,
                                    helperVersion = "0.2.0",
                                    features = listOf("ready-signal", "local-listener-auth"),
                                ),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort))

            val error =
                runCatching {
                    manager.start(naiveConfig())
                }.exceptionOrNull()

            assertTrue(error is ServiceStartupRejectedException)
            assertTrue((error as ServiceStartupRejectedException).reason is FailureReason.RelayConfigRejected)
            assertTrue(error.message.orEmpty().contains("unsupported NaiveProxy probe schema_version 2"))
            assertEquals(listOf("probe"), processPort.events)
            assertEquals(0, processPort.mainLaunches)
            assertEquals("relay_compatibility", processPort.lastFailureClass)
            assertEquals(
                FailureClass.Compatibility,
                classifyFailureClass(
                    failureReason = error.reason,
                    proxyTelemetry = NativeRuntimeSnapshot(source = "proxy"),
                    relayTelemetry = NativeRuntimeSnapshot(source = "relay"),
                    warpTelemetry = NativeRuntimeSnapshot(source = "warp"),
                    tunnelTelemetry = NativeRuntimeSnapshot(source = "tunnel"),
                ),
            )
        }

    @Test
    fun `supported probe delegates to the authenticated relay launch spec`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            NaiveProxyProbe(
                                schemaVersion = 1,
                                helperVersion = "0.1.0",
                                features = listOf("ready-signal", "local-listener-auth"),
                            ),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort))

            manager.start(naiveConfig())

            assertEquals(listOf("probe", "main"), processPort.events)
            assertEquals(1, processPort.mainLaunches)
            assertEquals(processPort.lastExtractedBinary, processPort.lastStartedBinary)
            assertEquals(listOf("--version"), processPort.lastLaunchSpec?.versionArguments)
            assertNotNull(processPort.lastLaunchSpec?.commandArguments)
        }

    @Test
    fun `helper without probe support is rejected instead of falling back to schema zero`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Rejected(
                            "NaiveProxy helper does not support the required probe",
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort))

            val error =
                runCatching {
                    manager.start(naiveConfig())
                }.exceptionOrNull()

            assertTrue(error is NaiveProxyCompatibilityException)
            assertEquals(listOf("probe"), processPort.events)
            assertEquals(0, processPort.mainLaunches)
        }

    @Test
    fun `every manager start runs a fresh probe before launch`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            NaiveProxyProbe(
                                schemaVersion = 1,
                                helperVersion = "0.1.0",
                                features = listOf("local-listener-auth"),
                            ),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort, unusedUpstreamLauncher(processPort))
            val config = naiveConfig()

            manager.start(config)
            manager.start(config)

            assertEquals(listOf("probe", "main", "probe", "main"), processPort.events)
            assertEquals(2, processPort.mainLaunches)
        }

    @Test
    fun `default probe runner parses the helper capability line`() =
        runTest {
            val script =
                executableScript(
                    "printf '%s\\n' 'RIPDPI-PROBE " +
                        "{\"schema_version\":1,\"helper_version\":\"0.1.0\"," +
                        "\"features\":[\"ready-signal\"]}'",
                )

            val result = testProbeRunner().run(NaiveProxyBinaryRef(script.absolutePath))

            assertTrue(result is NaiveProxyPreflightResult.Probed)
            val probe = (result as NaiveProxyPreflightResult.Probed).probe
            assertEquals(1, probe.schemaVersion)
            assertEquals("0.1.0", probe.helperVersion)
            assertEquals(listOf("ready-signal"), probe.features)
        }

    @Test
    fun `default probe runner rejects an exit without a capability line`() =
        runTest {
            val script = executableScript("printf '%s\\n' 'RIPDPI-ERROR|naiveproxy|config|missing server'; exit 2")

            val result = testProbeRunner().run(NaiveProxyBinaryRef(script.absolutePath))

            assertEquals(
                NaiveProxyPreflightResult.Rejected("NaiveProxy helper does not support the required probe"),
                result,
            )
        }

    @Test
    fun `default probe runner rejects malformed successful output`() =
        runTest {
            val script = executableScript("printf '%s\\n' 'not-a-probe'")

            val result = testProbeRunner().run(NaiveProxyBinaryRef(script.absolutePath))

            assertEquals(
                NaiveProxyPreflightResult.Rejected("NaiveProxy pre-launch probe returned an invalid capability line"),
                result,
            )
        }

    @Test
    fun `timed out probe is forcibly terminated and reaped`() =
        runTest {
            val pidFile = File.createTempFile("naiveproxy-probe-pid-", ".txt").apply { delete() }
            val script =
                executableScript(
                    "trap '' TERM; printf '%s' \$\$ > '${pidFile.absolutePath}'; exec sleep 30",
                )

            val result = readyProbeRunner(pidFile, timeoutMillis = 2_000L).run(NaiveProxyBinaryRef(script.absolutePath))

            assertEquals(NaiveProxyPreflightResult.Rejected("NaiveProxy pre-launch probe timed out"), result)
            assertProcessExited(pidFile)
        }

    @Test
    fun `cancelling probe terminates and reaps its process`() =
        runTest {
            val pidFile = File.createTempFile("naiveproxy-probe-pid-", ".txt").apply { delete() }
            val script =
                executableScript(
                    "trap '' TERM; printf '%s' \$\$ > '${pidFile.absolutePath}'; exec sleep 30",
                )
            val ready = CompletableDeferred<Unit>()
            val probe = readyProbeRunner(pidFile) { ready.complete(Unit) }
            val job = async { probe.run(NaiveProxyBinaryRef(script.absolutePath)) }
            ready.await()

            job.cancelAndJoin()

            assertProcessExited(pidFile)
        }

    @Test
    fun `prelaunch refusal remains visible as relay compatibility telemetry`() =
        runTest {
            val subprocessManager =
                SubprocessSocksRelayManager(
                    context = RuntimeEnvironment.getApplication(),
                    protectPathProvider = ActiveProtectSocketPathProvider(),
                )
            val config = naiveConfig()
            val spec =
                SubprocessSocksRelayLaunchSpec(
                    binaryName = "ripdpi-naiveproxy",
                    commandArguments = emptyList(),
                    runtimeKind = RelayKindNaiveProxy,
                )

            subprocessManager.notePrelaunchFailure(
                config = config,
                spec = spec,
                failureClass = "relay_compatibility",
                message = "unsupported NaiveProxy probe schema_version 2",
            )
            val telemetry = subprocessManager.pollTelemetry()

            assertEquals("relay_compatibility", telemetry.lastFailureClass)
            assertEquals("unsupported NaiveProxy probe schema_version 2", telemetry.lastError)
        }

    private class FakeNaiveProxyProcessPort(
        private val probeResult: NaiveProxyPreflightResult,
    ) : NaiveProxyLaunchDelegate,
        NaiveProxyPreflightProbe {
        val events = mutableListOf<String>()
        var mainLaunches = 0
            private set
        var lastLaunchSpec: SubprocessSocksRelayLaunchSpec? = null
            private set
        var lastExtractedBinary: NaiveProxyBinaryRef? = null
            private set
        var lastStartedBinary: NaiveProxyBinaryRef? = null
            private set
        var lastFailureClass: String? = null
            private set

        override fun resolveBinary(binaryName: String): NaiveProxyBinaryRef =
            NaiveProxyBinaryRef("/native-lib/lib$binaryName.so").also { lastExtractedBinary = it }

        override suspend fun start(
            binary: NaiveProxyBinaryRef,
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
        ) {
            events += "main"
            mainLaunches += 1
            lastStartedBinary = binary
            lastLaunchSpec = spec
        }

        override suspend fun waitForExit(): Int = 0

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = "relay")

        override fun noteRestarting(reason: String) = Unit

        override fun notePrelaunchFailure(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
            failureClass: String,
            message: String,
        ) {
            lastFailureClass = failureClass
        }

        override suspend fun stop() = Unit

        override suspend fun run(binary: NaiveProxyBinaryRef): NaiveProxyPreflightResult {
            events += "probe"
            return probeResult
        }
    }

    private fun executableScript(command: String): File =
        File.createTempFile("naiveproxy-probe-", ".sh").apply {
            writeText("#!/bin/sh\n$command\n")
            check(setExecutable(true))
            deleteOnExit()
        }

    private fun testProbeRunner(timeoutMillis: Long = 5_000L): DefaultNaiveProxyPreflightProbe =
        DefaultNaiveProxyPreflightProbe(
            ioDispatcher = Dispatchers.IO,
            timeoutMillis = timeoutMillis,
        )

    /** Exercise timeout/cleanup only after the real child has installed its TERM trap. */
    private fun readyProbeRunner(
        pidFile: File,
        timeoutMillis: Long = 5_000L,
        onReady: () -> Unit = {},
    ): DefaultNaiveProxyPreflightProbe =
        DefaultNaiveProxyPreflightProbe(Dispatchers.IO, timeoutMillis) { binary ->
            val process = ProcessBuilder(binary.absolutePath, "--probe").redirectErrorStream(true).start()
            var handedOff = false
            try {
                awaitFixtureReady(process, pidFile)
                onReady()
                handedOff = true
                process
            } finally {
                if (!handedOff) terminateFixture(process)
            }
        }

    private fun awaitFixtureReady(
        process: Process,
        pidFile: File,
    ) {
        // Android compile stubs omit pid(); this real-process test runs on the host JDK.
        val expectedPid = Process::class.java.getMethod("pid").invoke(process) as Long
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!pidFile.exists() || pidFile.readText().toLongOrNull() != expectedPid) {
            check(process.isAlive) { "Probe fixture exited before readiness" }
            check(System.nanoTime() < deadline) { "Probe fixture did not become ready" }
            Thread.sleep(10L)
        }
        check(process.isAlive) { "Probe fixture exited before handoff" }
    }

    private fun terminateFixture(process: Process) {
        var interrupted = Thread.interrupted()
        try {
            process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (process.isAlive && System.nanoTime() < deadline) {
                try {
                    process.waitFor((deadline - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(!process.isAlive) { "Probe fixture could not be reaped" }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun assertProcessExited(pidFile: File) {
        assertTrue("Probe did not publish its pid", pidFile.exists())
        val pid = pidFile.readText().toLong()
        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
    }
}
