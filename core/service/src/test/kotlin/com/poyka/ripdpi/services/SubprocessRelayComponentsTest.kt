package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SubprocessRelayComponentsTest {
    @Test
    fun `output parser redacts structured errors and detects managed listener`() {
        val parser = SubprocessRelayOutputParser()
        val spec =
            launchSpec(
                managedClientBridge =
                    ManagedClientSocksBridgeSpec(
                        methodName = "webtunnel",
                        targetHost = "target.example",
                        targetPort = 443,
                        ptArguments = "secret-token",
                    ),
                redactedValues = listOf("secret-token"),
            )

        val listenerEvent = parser.parse("CMETHOD webtunnel socks5 127.0.0.1:43123", spec)
        val errorEvent = parser.parse("RIPDPI-ERROR|webtunnel|auth|token secret-token failed", spec)

        assertEquals(
            SubprocessRelayOutputEvent.ManagedClientListener(InetSocketAddress("127.0.0.1", 43123)),
            listenerEvent,
        )
        assertEquals(
            SubprocessRelayOutputEvent.Error(
                runtimeKind = "webtunnel",
                failureClass = "auth",
                message = "token <redacted> failed",
            ),
            errorEvent,
        )
    }

    @Test
    fun `output parser reports ready and plain error events`() {
        val parser = SubprocessRelayOutputParser()
        val spec = launchSpec()

        assertEquals(
            SubprocessRelayOutputEvent.Ready(runtimeKind = "webtunnel", version = "1.2.3"),
            parser.parse("RIPDPI-READY|webtunnel|1.2.3", spec),
        )
        assertEquals(
            SubprocessRelayOutputEvent.PlainError("PROXY-ERROR bind failed"),
            parser.parse("PROXY-ERROR bind failed", spec),
        )
        assertNull(parser.parse("[NOTICE] started", spec))
    }

    @Test
    fun `launch planner builds command and environment without starting process`() {
        val binary = File("/tmp/ripdpi-relay")
        val spec =
            launchSpec(
                commandArguments = listOf("--listen", "127.0.0.1:1080"),
                environment = mapOf("TOKEN" to "value"),
            )
        val builder = SubprocessRelayLaunchPlanner().buildMainProcess(binary, spec)

        assertEquals(listOf("/tmp/ripdpi-relay", "--listen", "127.0.0.1:1080"), builder.command())
        assertEquals("value", builder.environment()["TOKEN"])
        assertTrue(builder.redirectErrorStream())
    }

    @Test
    fun `launch planner advertises RIPDPI_PROTECT_PATH only when a protect path is active`() {
        val binary = File("/tmp/ripdpi-relay")
        val spec = launchSpec(commandArguments = listOf("--listen", "127.0.0.1:1080"))

        val active = SubprocessRelayLaunchPlanner().buildMainProcess(binary, spec, "/tmp/protect.sock")
        assertEquals("/tmp/protect.sock", active.environment()[ActiveProtectSocketPathProvider.ENV_VAR])

        val inactive = SubprocessRelayLaunchPlanner().buildMainProcess(binary, spec, null)
        assertNull(inactive.environment()[ActiveProtectSocketPathProvider.ENV_VAR])
    }

    @Test
    fun `inactive subprocess policy ignores a stale active protect path`() {
        val path =
            resolveSubprocessProtectPath(
                config = sampleResolvedRelayConfig().copy(socketProtection = RelaySocketProtection.Inactive),
                spec = launchSpec(protectionCapability = SubprocessSocketProtectionCapability.ProtectSocketPath),
                activeProtectPath = "/tmp/stale-protect.sock",
            )

        assertNull(path)
    }

    @Test
    fun `vpn subprocess policy requires an active protect path before launch`() {
        val error =
            runCatching {
                resolveSubprocessProtectPath(
                    config = sampleResolvedRelayConfig().copy(socketProtection = RelaySocketProtection.VpnRequired),
                    spec = launchSpec(protectionCapability = SubprocessSocketProtectionCapability.ProtectSocketPath),
                    activeProtectPath = null,
                )
            }.exceptionOrNull()

        assertTrue(error is ServiceStartupRejectedException)
        assertTrue((error as ServiceStartupRejectedException).reason is FailureReason.RelayConfigRejected)
    }

    @Test
    fun `vpn subprocess policy rejects unsupported helper even when protect path exists`() {
        val error =
            runCatching {
                resolveSubprocessProtectPath(
                    config = sampleResolvedRelayConfig().copy(socketProtection = RelaySocketProtection.VpnRequired),
                    spec = launchSpec(protectionCapability = SubprocessSocketProtectionCapability.Unsupported),
                    activeProtectPath = "/tmp/protect.sock",
                )
            }.exceptionOrNull()

        assertTrue(error is ServiceStartupRejectedException)
        assertTrue((error as ServiceStartupRejectedException).reason is FailureReason.RelayConfigRejected)
    }

    @Test
    fun `readiness probe waits until connector accepts socks handshake`() =
        runTest {
            var attempts = 0
            val probe =
                SubprocessRelayReadinessProbe(
                    connector =
                        SocksReadinessConnector { _, _ ->
                            attempts += 1
                            attempts >= 2
                        },
                    pollIntervalMs = 1L,
                    timeoutMs = 50L,
                )

            probe.waitUntilReady(
                config = sampleResolvedRelayConfig(),
                isRunning = { true },
                onFailure = { error("unexpected failure: $it") },
            )

            assertEquals(2, attempts)
        }

    @Test
    fun `readiness timeout follows coroutine monotonic time`() =
        runTest(timeout = 1.seconds) {
            var failure: String? = null
            val probe =
                SubprocessRelayReadinessProbe(
                    connector = SocksReadinessConnector { _, _ -> false },
                    pollIntervalMs = 10L,
                    timeoutMs = 50L,
                )

            val result =
                runCatching {
                    probe.waitUntilReady(
                        config = sampleResolvedRelayConfig(),
                        isRunning = { true },
                        onFailure = { failure = it },
                    )
                }

            assertTrue(result.isFailure)
            assertEquals("Subprocess relay readiness timed out", failure)
            assertEquals(50L, testScheduler.currentTime)
        }

    @Test
    fun `readiness probe fails when process exits before managed listener`() =
        runTest {
            val probe =
                SubprocessRelayReadinessProbe(
                    pollIntervalMs = 1L,
                    timeoutMs = 50L,
                )
            var failure: String? = null

            val result =
                runCatching {
                    probe.waitForManagedClientListener(
                        bridgeSpec =
                            ManagedClientSocksBridgeSpec(
                                methodName = "webtunnel",
                                targetHost = "target.example",
                                targetPort = 443,
                                ptArguments = "",
                            ),
                        currentListener = { null },
                        isRunning = { false },
                        onFailure = { failure = it },
                    )
                }

            assertTrue(result.isFailure)
            assertEquals("Managed PT exited before advertising a SOCKS listener", failure)
        }

    @Test
    fun `readiness probe returns managed listener advertised later`() =
        runTest {
            val probe =
                SubprocessRelayReadinessProbe(
                    pollIntervalMs = 1L,
                    timeoutMs = 50L,
                )
            var listener: InetSocketAddress? = null
            val expectedListener = InetSocketAddress("127.0.0.1", 43123)
            val bridgeSpec =
                ManagedClientSocksBridgeSpec(
                    methodName = "webtunnel",
                    targetHost = "target.example",
                    targetPort = 443,
                    ptArguments = "",
                )

            val waiter =
                launch {
                    assertEquals(
                        expectedListener,
                        probe.waitForManagedClientListener(
                            bridgeSpec = bridgeSpec,
                            currentListener = { listener },
                            isRunning = { true },
                            onFailure = { error("unexpected failure: $it") },
                        ),
                    )
                }
            runCurrent()
            listener = expectedListener
            waiter.join()
        }

    @Test
    fun `bridge orchestrator tracks and clears advertised managed listener`() {
        val orchestrator = SubprocessRelayBridgeOrchestrator()
        val listener = InetSocketAddress("127.0.0.1", 43123)

        orchestrator.noteManagedClientListener(listener)

        assertEquals(listener, orchestrator.currentManagedClientListener())

        orchestrator.reset()

        assertNull(orchestrator.currentManagedClientListener())
    }

    @Test
    fun `telemetry projector keeps naive proxy out of pt runtime fields`() {
        val telemetry =
            SubprocessRelayTelemetryProjector()
                .project(
                    SubprocessRelayTelemetryInputs(
                        config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy),
                        launchSpec = launchSpec(runtimeKind = RelayKindNaiveProxy),
                        running = true,
                        runtimeVersion = "naive 1.0",
                        lastError = null,
                        lastFailureClass = null,
                        runtimeStateOverride = null,
                    ),
                )

        assertEquals("relay", telemetry.source)
        assertEquals("running", telemetry.state)
        assertEquals("127.0.0.1:1080", telemetry.listenerAddress)
        assertNull(telemetry.ptRuntimeKind)
        assertNull(telemetry.ptRuntimeState)
    }

    @Test
    fun `telemetry projector marks managed runtime failed from last error`() {
        val telemetry =
            SubprocessRelayTelemetryProjector()
                .project(
                    SubprocessRelayTelemetryInputs(
                        config = sampleResolvedRelayConfig(kind = RelayKindWebTunnel),
                        launchSpec =
                            launchSpec(
                                runtimeKind = RelayKindWebTunnel,
                                upstreamAddress = "upstream.example:443",
                            ),
                        running = false,
                        runtimeVersion = "webtunnel 1.0",
                        lastError = "bind failed",
                        lastFailureClass = "bind",
                        runtimeStateOverride = null,
                    ),
                )

        assertEquals(false, telemetry.udpCapable)
        assertEquals("webtunnel", telemetry.ptRuntimeKind)
        assertEquals("failed", telemetry.ptRuntimeState)
        assertEquals("webtunnel 1.0", telemetry.ptRuntimeVersion)
        assertEquals("upstream.example:443", telemetry.upstreamAddress)
        assertEquals("bind", telemetry.lastFailureClass)
    }

    @Test
    fun `telemetry projector strips url credentials path and query`() {
        val credentialBearingUrl =
            "wss://user:sentinel-password@upstream.example/secret?token=sentinel-query"
        val telemetry =
            SubprocessRelayTelemetryProjector()
                .project(
                    SubprocessRelayTelemetryInputs(
                        config = sampleResolvedRelayConfig(kind = RelayKindWebTunnel),
                        launchSpec =
                            launchSpec(
                                runtimeKind = RelayKindWebTunnel,
                                upstreamAddress = credentialBearingUrl,
                            ),
                        running = true,
                        runtimeVersion = null,
                        lastError = null,
                        lastFailureClass = null,
                        runtimeStateOverride = null,
                    ),
                )

        assertEquals("upstream.example:443", telemetry.upstreamAddress)
        assertFalse(telemetry.upstreamAddress.orEmpty().contains("sentinel"))
    }

    private fun launchSpec(
        commandArguments: List<String> = emptyList(),
        runtimeKind: String = RelayKindWebTunnel,
        upstreamAddress: String? = null,
        environment: Map<String, String> = emptyMap(),
        managedClientBridge: ManagedClientSocksBridgeSpec? = null,
        redactedValues: List<String> = emptyList(),
        protectionCapability: SubprocessSocketProtectionCapability = SubprocessSocketProtectionCapability.Unsupported,
    ): SubprocessSocksRelayLaunchSpec =
        SubprocessSocksRelayLaunchSpec(
            binaryName = "webtunnel",
            commandArguments = commandArguments,
            runtimeKind = runtimeKind,
            upstreamAddress = upstreamAddress,
            environment = environment,
            managedClientBridge = managedClientBridge,
            redactedValues = redactedValues,
            protectionCapability = protectionCapability,
        )
}
