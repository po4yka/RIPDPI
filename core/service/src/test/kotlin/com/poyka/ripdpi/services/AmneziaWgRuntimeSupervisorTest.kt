package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiAmneziaWgConfig
import com.poyka.ripdpi.core.RipDpiAmneziaWgFactory
import com.poyka.ripdpi.core.RipDpiAmneziaWgObfuscationConfig
import com.poyka.ripdpi.core.RipDpiAmneziaWgRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.service.awg.AmneziaWgRuntimeConfigResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AmneziaWgRuntimeSupervisorTest {
    @Test
    fun explicitStopReportsExpectedAmneziaWgExitCause() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val resolver = RecordingAmneziaWgRuntimeConfigResolver()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = factory,
                    runtimeConfigResolver = resolver,
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(sampleRequest()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(listOf(SupervisorExitCause.ExpectedStop), exits)
        }

    @Test
    fun nonZeroExitReportsAmneziaWgCrashCause() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = factory,
                    runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(sampleRequest()) { exits += it }
            factory.lastRuntime.complete(29)
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf(SupervisorExitCause.Crash(29)), exits)
        }

    @Test
    fun startResolvesTheRequestIntoTheNativeConfigItStartsWith() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val resolver = RecordingAmneziaWgRuntimeConfigResolver()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = factory,
                    runtimeConfigResolver = resolver,
                )

            supervisor.start(sampleRequest()) {}
            advanceUntilIdle()

            // The supervisor resolved the app-reachable request into the engine
            // config and handed exactly that config to the native runtime.
            assertEquals("awg-test", resolver.lastRequest?.profileId)
            val started = factory.lastRuntime.lastConfig
            assertEquals("awg-test", started?.profileId)
            assertEquals("vpn.example.org", started?.endpointHost)
            assertEquals(51820, started?.endpointPort)
            assertEquals(7, started?.amnezia?.s3)
            assertEquals(9, started?.amnezia?.s4)
            assertEquals("deadbeef", started?.amnezia?.i1)

            supervisor.stop()
            advanceUntilIdle()
        }

    @Test
    fun pollTelemetryReturnsEngineErrorWhenAmneziaWgRuntimeThrows() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = factory,
                    runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
                )

            supervisor.start(sampleRequest()) {}
            factory.lastRuntime.telemetryFailure = IOException("amneziawg telemetry crash")

            val telemetry = supervisor.pollTelemetry()

            assertTrue(telemetry is RuntimeTelemetryOutcome.EngineError)
            assertEquals(
                "amneziawg telemetry crash",
                (telemetry as RuntimeTelemetryOutcome.EngineError).message,
            )
        }

    @Test
    fun pollTelemetryReturnsNoDataWhenAmneziaWgRuntimeIsMissing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = TestRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
                )

            assertEquals(RuntimeTelemetryOutcome.NoData, supervisor.pollTelemetry())
        }

    private fun sampleRequest(): AwgActivationRequest =
        AwgActivationRequest(
            profileId = "awg-test",
            privateKey = "privkey==",
            peerPublicKey = "peerpub==",
            presharedKey = "psk==",
            endpointHost = "vpn.example.org",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            obfuscation = AwgActivationObfuscation(jc = 4, s3 = 7, s4 = 9, i1 = "deadbeef"),
        )
}

internal class TestAmneziaWgRuntime : RipDpiAmneziaWgRuntime {
    private var exitCode = CompletableDeferred<Int>()
    private val ready = CompletableDeferred<Unit>()

    var startFailure: Throwable? = null
    var awaitReadyFailure: Exception? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var telemetry: NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "amneziawg",
            state = "running",
            health = "healthy",
        )
    var lastConfig: ResolvedRipDpiAmneziaWgConfig? = null
        private set
    var stopCount: Int = 0
        private set

    override suspend fun start(config: ResolvedRipDpiAmneziaWgConfig): Int {
        lastConfig = config
        startFailure?.let {
            ready.completeExceptionally(it)
            throw it
        }
        ready.complete(Unit)
        return exitCode.await()
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        awaitReadyFailure?.let { throw it }
        ready.await()
    }

    override suspend fun stop() {
        stopCount += 1
        stopFailure?.let { throw it }
        if (!exitCode.isCompleted) {
            exitCode.complete(0)
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        telemetryFailure?.let { throw it }
        return telemetry
    }

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}

internal class TestRipDpiAmneziaWgFactory(
    private val runtimeFactory: () -> TestAmneziaWgRuntime = { TestAmneziaWgRuntime() },
) : RipDpiAmneziaWgFactory {
    val runtimes = mutableListOf<TestAmneziaWgRuntime>()

    val lastRuntime: TestAmneziaWgRuntime
        get() = runtimes.last()

    override fun create(): RipDpiAmneziaWgRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class RecordingAmneziaWgRuntimeConfigResolver : AmneziaWgRuntimeConfigResolver {
    var lastRequest: AwgActivationRequest? = null
        private set

    override fun resolve(request: AwgActivationRequest): ResolvedRipDpiAmneziaWgConfig {
        lastRequest = request
        return ResolvedRipDpiAmneziaWgConfig(
            enabled = true,
            profileId = request.profileId,
            privateKey = request.privateKey,
            peerPublicKey = request.peerPublicKey,
            presharedKey = request.presharedKey,
            endpointHost = request.endpointHost,
            endpointPort = request.endpointPort,
            interfaceAddressV4 = request.interfaceAddressV4,
            interfaceAddressV6 = request.interfaceAddressV6,
            mtu = request.mtu,
            persistentKeepalive = request.persistentKeepalive,
            amnezia =
                RipDpiAmneziaWgObfuscationConfig(
                    jc = request.obfuscation.jc,
                    jmin = request.obfuscation.jmin,
                    jmax = request.obfuscation.jmax,
                    s1 = request.obfuscation.s1,
                    s2 = request.obfuscation.s2,
                    s3 = request.obfuscation.s3,
                    s4 = request.obfuscation.s4,
                    h1 = request.obfuscation.h1,
                    h2 = request.obfuscation.h2,
                    h3 = request.obfuscation.h3,
                    h4 = request.obfuscation.h4,
                    i1 = request.obfuscation.i1,
                    i2 = request.obfuscation.i2,
                    i3 = request.obfuscation.i3,
                    i4 = request.obfuscation.i4,
                    i5 = request.obfuscation.i5,
                ),
            localSocksHost = "127.0.0.1",
            localSocksPort = 10808,
        )
    }
}
