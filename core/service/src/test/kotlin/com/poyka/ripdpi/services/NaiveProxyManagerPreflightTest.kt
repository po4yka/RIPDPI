package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.classifyFailureClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runCurrent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class NaiveProxyManagerPreflightTest {
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
                                    features = listOf("ready-signal"),
                                ),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort)

            val error =
                runCatching {
                    manager.start(sampleResolvedRelayConfig(kind = RelayKindNaiveProxy))
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
    fun `supported probe delegates to the unchanged relay launch spec`() =
        runTest {
            val processPort =
                FakeNaiveProxyProcessPort(
                    probeResult =
                        NaiveProxyPreflightResult.Probed(
                            NaiveProxyProbe(
                                schemaVersion = 1,
                                helperVersion = "0.1.0",
                                features = listOf("ready-signal"),
                            ),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort)

            manager.start(sampleResolvedRelayConfig(kind = RelayKindNaiveProxy))

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
            val manager = NaiveProxyManager(processPort, processPort)

            val error =
                runCatching {
                    manager.start(sampleResolvedRelayConfig(kind = RelayKindNaiveProxy))
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
                            NaiveProxyProbe(schemaVersion = 1, helperVersion = "0.1.0", features = emptyList()),
                        ),
                )
            val manager = NaiveProxyManager(processPort, processPort)
            val config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)

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
                    "printf '%s' \$\$ > '${pidFile.absolutePath}'; trap '' TERM; exec sleep 30",
                )

            val result = testProbeRunner(timeoutMillis = 2_000L).run(NaiveProxyBinaryRef(script.absolutePath))

            assertEquals(NaiveProxyPreflightResult.Rejected("NaiveProxy pre-launch probe timed out"), result)
            assertProcessExited(pidFile)
        }

    @Test
    fun `cancelling probe terminates and reaps its process`() =
        runTest {
            val pidFile = File.createTempFile("naiveproxy-probe-pid-", ".txt").apply { delete() }
            val script =
                executableScript(
                    "printf '%s' \$\$ > '${pidFile.absolutePath}'; trap '' TERM; exec sleep 30",
                )
            val probe = testProbeRunner(timeoutMillis = 5_000L)
            val job = async { probe.run(NaiveProxyBinaryRef(script.absolutePath)) }
            runCurrent()
            runInterruptible(Dispatchers.IO) {
                repeat(200) {
                    if (pidFile.exists()) {
                        return@runInterruptible
                    }
                    Thread.sleep(10L)
                }
            }

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
            val config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)
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

        override fun extractBinary(binaryName: String): NaiveProxyBinaryRef =
            NaiveProxyBinaryRef("/extracted/$binaryName").also { lastExtractedBinary = it }

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

    private fun assertProcessExited(pidFile: File) {
        assertTrue("Probe did not publish its pid", pidFile.exists())
        val pid = pidFile.readText().toLong()
        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
    }
}
